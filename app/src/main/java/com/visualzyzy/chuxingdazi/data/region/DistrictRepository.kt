package com.visualzyzy.chuxingdazi.data.region

import android.content.Context
import com.tencent.lbssearch.TencentSearch
import com.tencent.lbssearch.httpresponse.BaseObject
import com.tencent.lbssearch.httpresponse.HttpResponseListener
import com.tencent.lbssearch.`object`.param.DistrictChildrenParam
import com.tencent.lbssearch.`object`.result.DistrictResultObject
import com.visualzyzy.chuxingdazi.data.db.dao.DistrictDao
import com.visualzyzy.chuxingdazi.data.db.entity.DistrictEntity
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.data.tencent.TencentSdk
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 省-市两级行政区划数据（2026-11-17 腾讯版新需求）：
 *
 * - 数据源 = 腾讯检索 `getDistrictChildren`（不设 id = 顶级省列表；设省 id = 该省辖市），
 *   只保存省级-市级两级；**更新频率 2 个月一次，只在每次应用启动时检查是否到期**
 *   （到期才后台刷新；手动更新走设置页按钮）。
 * - 每次更新完成后默认从已保存的 district 表读取（CityPickerDialog 走 Room 实时流）。
 * - 更新过程整表替换（单事务），读取端不会看到半套数据。
 */
@Singleton
class DistrictRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val districtDao: DistrictDao,
) {

    /** 更新进度（设置页「更新中…」弹窗消费；null = 空闲）。 */
    data class Progress(
        /** 已完成的省/自治区/直辖市名称（弹窗第一行「已更新xx市、xx省、xx省」）。 */
        val doneNames: List<String>,
        /** 正在更新的省名（弹窗第二行「正在更新xx省」）。 */
        val currentName: String,
    )

    val progress = kotlinx.coroutines.flow.MutableStateFlow<Progress?>(null)
    val lastError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** 更新完成标记（设置页「更新完成」弹窗消费；成功后置 true，UI 消费后调 acknowledge）。 */
    val justFinished = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val mutex = Mutex()
    private var updating = false

    /** 省列表实时流（CityPickerDialog 第一级）。 */
    fun observeProvinces() = districtDao.observeProvinces()

    /** 某省的市级列表（CityPickerDialog 第二级）。 */
    suspend fun citiesOf(provinceAdcode: Int): List<DistrictEntity> = districtDao.citiesOf(provinceAdcode)

    /** 库里是否有数据（无数据时 CityPickerDialog 显示引导文案）。 */
    suspend fun isEmpty(): Boolean = districtDao.count() == 0

    /**
     * 应用启动时调用：距上次更新 ≥ 2 个月才后台刷新（不弹任何 UI；失败静默留待下次）。
     */
    suspend fun refreshIfDue() {
        val last = Prefs.districtLastUpdated(appContext).first()
        if (System.currentTimeMillis() - last >= UPDATE_INTERVAL_MS) {
            runCatching { refresh() }
                .onFailure { android.util.Log.w(TAG, "auto refresh failed: ${it.message}") }
        }
    }

    /**
     * 手动刷新（设置页按钮）。进行中重复调用返回 false；成功返回 true 并置 justFinished。
     * 失败原因写 [lastError]（设置页弹「更新失败」）。
     */
    suspend fun refresh(): Boolean = mutex.withLock {
        if (updating) return false
        if (TencentSdk.currentKey.isNullOrBlank()) {
            lastError.value = "尚未配置地图密钥"
            return false
        }
        updating = true
        lastError.value = null
        justFinished.value = false
        try {
            val rows = mutableListOf<DistrictEntity>()
            // 1. 顶级省列表（不设 id）
            val provinces = fetchChildren(null)
                ?: throw IllegalStateException("获取省级行政区失败")
            provinces.forEachIndexed { pIdx, province ->
                progress.value = Progress(
                    doneNames = provinces.subList(0, pIdx).map { it.fullname },
                    currentName = province.fullname,
                )
                // 2. 该省的市级列表
                val cities = fetchChildren(province.id).orEmpty()
                rows.add(DistrictEntity(province.id, province.fullname, null, pIdx))
                cities.forEachIndexed { cIdx, city ->
                    if (city.id != province.id) { // 直辖市子级与省同名同 id，跳过防 PK 冲突
                        rows.add(DistrictEntity(city.id, city.fullname, province.id, cIdx))
                    }
                }
            }
            if (rows.isEmpty()) throw IllegalStateException("未获取到任何行政区划数据")
            districtDao.replaceAll(rows)
            Prefs.setDistrictLastUpdated(appContext, System.currentTimeMillis())
            progress.value = null
            justFinished.value = true
            android.util.Log.i(TAG, "refresh done: ${rows.size} rows")
            true
        } catch (e: Exception) {
            progress.value = null
            lastError.value = e.message ?: "网络异常"
            android.util.Log.w(TAG, "refresh failed: ${e.message}")
            false
        } finally {
            updating = false
        }
    }

    /** 设置页「更新完成」弹窗展示后清除标记。 */
    fun acknowledgeFinished() {
        justFinished.value = false
    }

    /** 一次 getDistrictChildren 请求；失败/空结果返回 null。
     *  决策 #115：个人开发者 key 的 WebService 并发额度 = 5 次/秒（官方配额页
     *  lbs.qq.com/dev/console/quotaImprove），34 个省级子列表请求零间隔连发会触发限流
     *  （2026-11-18 实测前 4 省成功、其后全部被拒被 orEmpty 静默跳过）——
     *  每次请求前强制间隔 400ms ≈ 2.5 QPS，留安全余量。 */
    private suspend fun fetchChildren(parentId: Int?): List<DistrictResultObject.DistrictResult>? {
        delay(REQUEST_INTERVAL_MS)
        val key = TencentSdk.currentKey ?: return null
        // 决策 #114：3 参构造器，key 必须落 apiKey 位（2 参构造器 key 进 secretKey 位不进请求）
        val searcher = TencentSearch(appContext, key, null)
        return suspendCancellableCoroutine { cont ->
            val launched = runCatching {
                val param = if (parentId == null) DistrictChildrenParam() else DistrictChildrenParam().id(parentId)
                searcher.getDistrictChildren(
                    param,
                    object : HttpResponseListener<BaseObject> {
                        override fun onSuccess(statusCode: Int, obj: BaseObject?) {
                            val list = (obj as? DistrictResultObject)?.result?.firstOrNull().orEmpty()
                            if (cont.isActive) {
                                cont.resume(if (list.isEmpty()) null else list)
                            }
                        }

                        override fun onFailure(statusCode: Int, msg: String?, throwable: Throwable?) {
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }
                    },
                )
            }.isSuccess
            if (!launched && cont.isActive) cont.resume(null)
            cont.invokeOnCancellation { runCatching { searcher.cancelAll() } }
        }
    }

    private companion object {
        const val TAG = "DistrictRepo"
        /** 更新间隔：2 个月（约 60 天；只按启动检查口径比对）。 */
        const val UPDATE_INTERVAL_MS = 60L * 24 * 60 * 60 * 1000
        /** 相邻 getDistrictChildren 请求间隔（决策 #115：官方并发额度 5 次/秒，取 2.5 QPS 余量）。 */
        const val REQUEST_INTERVAL_MS = 400L
    }
}
