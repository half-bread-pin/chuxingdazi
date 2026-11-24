package com.visualzyzy.chuxingdazi.data.tencent

import android.content.Context
import com.tencent.lbssearch.TencentSearch
import com.tencent.lbssearch.httpresponse.BaseObject
import com.tencent.lbssearch.httpresponse.HttpResponseListener
// ⚠️ 包段 `object` 是 Kotlin 关键字，import 必须反引号转义（com.tencent.lbssearch.object.*）
import com.tencent.lbssearch.`object`.param.Address2GeoParam
import com.tencent.lbssearch.`object`.param.Geo2AddressParam
import com.tencent.lbssearch.`object`.result.Geo2AddressResultObject
import com.tencent.lbssearch.`object`.result.Address2GeoResultObject
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 腾讯检索服务薄封装（lbssearch geo2address / address2geo；2026-11-17 腾讯版重写）。
 *
 * - 单例：内部持一个 TencentSearch 实例（key 变更时重建）；S03 pointed_information /
 *   S06 选点 / RegionHealer 自愈 / 城市联动共用，避免每请求新建。
 * - key = S02 用户填写，经 [TencentSdk.currentKey] 读取；空 key 直接返回失败
 *   （检索接口无 key 必然报错，省一次网络请求）。
 */
object GeoCoderService {

    private var search: TencentSearch? = null
    private var searchKey: String? = null

    private fun searcher(context: Context): TencentSearch? {
        val key = TencentSdk.currentKey
        if (key.isNullOrBlank()) return null
        val existing = search
        if (existing != null && searchKey == key) return existing
        runCatching { existing?.cancelAll() }
        // 决策 #114：3 参构造器，key 必须落 apiKey 位（2 参构造器 key 进 secretKey 位不进请求）
        val fresh = runCatching { TencentSearch(context.applicationContext, key, null) }.getOrNull()
        return fresh.also {
            search = it
            searchKey = key
        }
    }

    /**
     * 逆地理编码结果（S06/S03 pointed_information：semantic=主地址语义化描述，
     * address=完整地址，poiName=附近 POI 名）。
     * province/city 来自腾讯 address_component（只信 SDK，App 不自建区划库），
     * 保存点位时一并落库；取不到为空串。
     */
    data class ReverseGeocodeInfo(
        val address: String,
        val semantic: String,
        val poiName: String?,
        val province: String = "",
        val city: String = "",
        val district: String = "",
    ) {
        /** 名称兜底链：附近 POI 名 → 语义化描述 → 完整地址（§6 S06 名称留空回退）。 */
        val nameCandidate: String
            get() = poiName?.takeIf { it.isNotBlank() }
                ?: semantic.takeIf { it.isNotBlank() }
                ?: address

        val provinceOrNull: String? get() = province.takeIf { it.isNotBlank() }
        val cityOrNull: String? get() = city.takeIf { it.isNotBlank() }
    }

    /**
     * 挂起式逆地理。失败（无 key / 请求错误 / 无结果）返回 null。
     * formatted_addresses.recommend = 腾讯的「推荐地址」（语义化，如「北山街 · 靠近断桥残雪」同位）；
     * pois 首个 = 附近 POI（名称兜底链首位）。
     */
    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): ReverseGeocodeInfo? {
        val searcher = searcher(context) ?: return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                searcher.geo2address(
                    Geo2AddressParam(LatLng(lat, lng)).getPoi(true),
                    object : HttpResponseListener<BaseObject> {
                        override fun onSuccess(statusCode: Int, obj: BaseObject?) {
                            if (cont.isActive) {
                                val result = (obj as? Geo2AddressResultObject)?.result
                                if (result == null) {
                                    cont.resume(null)
                                    return
                                }
                                val component = result.address_component
                                cont.resume(
                                    ReverseGeocodeInfo(
                                        address = result.address.orEmpty(),
                                        semantic = result.formatted_addresses?.recommend.orEmpty(),
                                        poiName = result.pois?.firstOrNull()?.title
                                            ?: result.address_reference?.famous_area?.title?.takeIf { it.isNotBlank() },
                                        province = component?.province.orEmpty(),
                                        city = component?.city.orEmpty(),
                                        district = component?.district.orEmpty(),
                                    ),
                                )
                            }
                        }

                        override fun onFailure(statusCode: Int, msg: String?, throwable: Throwable?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { searcher.cancelAll() } }
        }
    }

    /** 城市名 → 城市中心坐标（P10 选中某市后地图切换；失败回 null 由调用方提示）。 */
    suspend fun geocode(context: Context, name: String): LatLng? {
        val searcher = searcher(context) ?: return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                searcher.address2geo(
                    Address2GeoParam(name),
                    object : HttpResponseListener<BaseObject> {
                        override fun onSuccess(statusCode: Int, obj: BaseObject?) {
                            if (cont.isActive) {
                                val result = obj as? Address2GeoResultObject
                                cont.resume(result?.result?.latLng)
                            }
                        }

                        override fun onFailure(statusCode: Int, msg: String?, throwable: Throwable?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { searcher.cancelAll() } }
        }
    }
}
