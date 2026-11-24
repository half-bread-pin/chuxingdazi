package com.visualzyzy.chuxingdazi.data.repo

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceDao
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.data.tencent.GeoCoderService
import com.visualzyzy.chuxingdazi.data.tencent.TencentSdk
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 区域自愈（第七轮 2026-11-06；2026-11-17 腾讯版：逆地理经 GeoCoderService = 腾讯 geo2address）：
 * place.province/city 只信腾讯逆地理；保存时 UI 手头有就一并写入，没有就留空，
 * 由本 healer 在后台分批逆地理回填（旧数据 / 搜索链路省略 province 的点位都会被补上）。
 *
 * - 触发 = `kick()`：三个地图屏（S03/S06/S05b）地图就绪时 + 每次新增点位后调用；
 *   进程内串行（Mutex），重复 kick 不会叠加任务。
 * - 第八轮（2026-11-07）两道闸门（用户要求）：
 *   1. **all_points_healthy 标志**（DataStore，非 DB——纯性能优化不陪跑 schema 版本）：
 *      上轮扫描确认全部点位区域齐备时置 true；kick() 扫库前先读它，true = 直接跳过，
 *      不再发任何查询。凡添加点位 / 导入数据（[markDirty] 的全部调用点）都置回 false。
 *   2. **2 分钟限速**：不管 kick 被触发多少次，两次实际扫描之间至少隔 2 分钟
 *      （SystemClock.elapsedRealtime 单调计时，不受系统时间回拨影响）。
 * - 不影响体验：纯后台协程，不阻塞任何 UI；请求间隔 600ms 限速（第八轮 300→600）；
 *   失败（无 AK/断网/占位 AK 检索不可用）→ 本轮中止，留待下次 kick 重试
 *   （P65 先例：失败保留旧值，不做破坏性动作；此时标志保持 false）。
 * - 坐标仍是事实源：逆地理只产出 province/city 快照，不动 lat/lng。
 */
@Singleton
class RegionHealer @Inject constructor(
    private val placeDao: PlaceDao,
    @ApplicationContext private val appContext: Context,
    @com.visualzyzy.chuxingdazi.di.ApplicationScope private val scope: CoroutineScope,
) {

    private val mutex = Mutex()
    private var job: Job? = null

    /** 上次实际开始扫描的时刻（elapsedRealtime；0 = 本次进程还没扫过）。 */
    @Volatile private var lastScanStartedAtMs = 0L

    /**
     * 本轮扫描期间有新点位数据写入（markDirty 被调用）。
     * 扫描收尾据此决定要不要把标志置 true——若扫描的最后一条查询之后又来了新点位，
     * 置 true 会把它的自愈机会挡掉，所以保持 false 留给下次扫描复核。
     */
    @Volatile private var dirtiedDuringScan = false

    /** 幂等触发一轮自愈（已有任务在跑则忽略；healthy 标志 / 2 分钟限速先挡）。 */
    fun kick() {
        if (!TencentSdk.isInitialized) return
        if (job?.isActive == true) return
        job = scope.launch {
            // 闸门 1：上轮扫描确认全库健康 → 一个查询都不发（第八轮）
            if (Prefs.allPointsHealthy(appContext).first()) {
                Log.d(TAG, "kick skipped: all_points_healthy=true")
                return@launch
            }
            // 闸门 2：2 分钟内最多执行一次（第八轮）
            val elapsedMs = SystemClock.elapsedRealtime() - lastScanStartedAtMs
            if (lastScanStartedAtMs > 0 && elapsedMs < MIN_SCAN_INTERVAL_MS) {
                Log.d(
                    TAG,
                    "kick skipped: throttled, next eligible in ${((MIN_SCAN_INTERVAL_MS - elapsedMs) / 1000)}s",
                )
                return@launch
            }
            runOnce()
        }
    }

    /**
     * 点位数据有变（添加点位 / 导入数据 / 坐标调整）→ 标志置回 false。
     * 全部调用点都在协程内，做成 suspend 以拿到 DataStore 写完成的顺序保证：
     * 之后的 kick() 读标志必然读到 false。
     */
    suspend fun markDirty() {
        dirtiedDuringScan = true
        Prefs.setAllPointsHealthy(appContext, false)
    }

    /**
     * 第十六轮（2026-11-14）：新增点位**当场**补齐省市区（优化建议：批量搜索入库不再把
     * province 留空等自愈——保存路径能拿到逆地理就当场写入，地区筛选立即可命中）。
     * 逐条逆地理、间隔 [REQUEST_INTERVAL_MS]；单条失败只跳过（留给后续 kick 扫描兜底）。
     * 不走 markDirty/闸门：调用点刚插入完数据，healthy 标志已由调用方置 false。
     */
    suspend fun healPlacesNow(places: List<com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity>) {
        if (!TencentSdk.isInitialized) return
        for (place in places) {
            if (place.province?.isNotBlank() == true && place.city?.isNotBlank() == true) continue
            val region = reverseGeocodeRegion(place.lat, place.lng) ?: continue
            placeDao.setRegion(place.id, region.first.takeIf { it.isNotBlank() }, region.second.takeIf { it.isNotBlank() })
            delay(REQUEST_INTERVAL_MS)
        }
    }

    private suspend fun runOnce() {
        mutex.withLock {
            if (!TencentSdk.isInitialized) return
            lastScanStartedAtMs = SystemClock.elapsedRealtime()
            dirtiedDuringScan = false
            // 占位 AK 下 GeoCoder 静默失败（P65），连续失败直接收工，避免空转烧配额
            var consecutiveFailures = 0
            var healed = 0
            var aborted = false
            // 本轮已尝试过的点位不再重试——某些坐标逆地理永远给不出区域（如海上坐标返回
            // NO_ERROR 但 province/city 全空），若不记录会把同一批无限重取、烧配额
            val attempted = mutableSetOf<String>()
            loop@ while (true) {
                if (!TencentSdk.isInitialized) {
                    aborted = true
                    break
                }
                val batch = placeDao.getPlacesMissingRegion(BATCH_SIZE)
                    .filter { attempted.add(it.id) } // add 返回 false = 本轮已试过，跳过
                if (batch.isEmpty()) break
                for (place in batch) {
                    val region = reverseGeocodeRegion(place.lat, place.lng)
                    if (region == null) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            Log.w(TAG, "region heal aborted after $consecutiveFailures failures")
                            aborted = true
                            break@loop
                        }
                    } else {
                        consecutiveFailures = 0
                        healed++
                        placeDao.setRegion(
                            id = place.id,
                            province = region.first.takeIf { it.isNotBlank() },
                            city = region.second.takeIf { it.isNotBlank() },
                        )
                    }
                    delay(REQUEST_INTERVAL_MS)
                }
            }
            // 干净跑完（没中途失败）且扫描期间没有新数据写入 → 认定全库健康，置标志；
            // 中途失败（网络/P65）或扫描尾段又来了新点位 → 保持 false，留给下次扫描复核
            if (!aborted && !dirtiedDuringScan) {
                Prefs.setAllPointsHealthy(appContext, true)
                Log.d(TAG, "scan done: healed=$healed, all points healthy → flag=true")
            } else {
                Log.d(TAG, "scan done: healed=$healed, flag stays false (aborted=$aborted dirty=$dirtiedDuringScan)")
            }
        }
    }

    /** 挂起式逆地理：返回 (province, city)；失败（请求错误 / 无结果 / 区域全空）返回 null。 */
    private suspend fun reverseGeocodeRegion(lat: Double, lng: Double): Pair<String, String>? {
        val info = GeoCoderService.reverseGeocode(appContext, lat, lng) ?: return null
        val province = info.province
        val city = info.city
        return if (province.isBlank() && city.isBlank()) null else province to city
    }

    private companion object {
        const val TAG = "RegionHealer"
        const val BATCH_SIZE = 20
        const val REQUEST_INTERVAL_MS = 600L // 第八轮：300 → 600ms
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val MIN_SCAN_INTERVAL_MS = 2 * 60 * 1000L // 第八轮：两次扫描至少隔 2 分钟
    }
}
