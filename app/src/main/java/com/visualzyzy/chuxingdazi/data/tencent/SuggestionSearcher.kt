package com.visualzyzy.chuxingdazi.data.tencent

import android.content.Context
import com.tencent.lbssearch.TencentSearch
import com.tencent.lbssearch.httpresponse.BaseObject
import com.tencent.lbssearch.httpresponse.HttpResponseListener
// ⚠️ 包段 `object` 是 Kotlin 关键字，import 必须反引号转义
import com.tencent.lbssearch.`object`.param.SuggestionParam
import com.tencent.lbssearch.`object`.result.SuggestionResultObject

/** 联想搜索结果行（S05）。lat/lng 可空——个别联想结果不带坐标。 */
data class SuggestionItem(
    val uid: String?,
    val name: String,
    val address: String?,
    val city: String?,
    val district: String?,
    val tag: String?,
    val lat: Double?,
    val lng: Double?,
    // 第八轮（2026-11-07）：S05b 点击点位入库时手头有逆地理省份就直接带上（联想结果本身没有，默认 null）
    val province: String? = null,
)

/**
 * 腾讯联想搜索薄封装（§7.2：输入 300ms 防抖由 ViewModel 负责，这里只发请求收结果）。
 * 单实例复用（TencentSearch 变更 key 时才重建）。
 *
 * 第二十一轮（2026-11-14）经验沿用：外部数据不直接当列表 key——uid 空串归一为 null
 * （腾讯 suggestion 的地区联想行同样可能出现空/重复 id）。
 */
class SuggestionSearcher(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @return null = 请求被拒（网络/服务异常/无 key，S05 显示重试按钮）。
     * 注意：city 为空时兜底传「全国」（§6 S05 边界：定位失败无城市 → 全国检索）。
     */
    fun search(keyword: String, city: String?, onResult: (List<SuggestionItem>?) -> Unit) {
        // 决策 #114：必须用 3 参构造器——2 参 TencentSearch(context,key) 会把 key 传进
        // secretKey 位（mApiKey=null），请求 key 回退 manifest（现已彻底移除）被服务端拒 311。
        val searcher = TencentSearch(appContext, TencentSdk.currentKey ?: "", null)
        val param = SuggestionParam(keyword, city?.takeIf { it.isNotBlank() } ?: "全国")
            .getSubPois(false)
            .pageIndex(1)
            .pageSize(20)
        runCatching {
            searcher.suggestion(
                param,
                object : HttpResponseListener<BaseObject> {
                    override fun onSuccess(statusCode: Int, obj: BaseObject?) {
                        val result = obj as? SuggestionResultObject
                        val items = result?.data?.map { info ->
                            SuggestionItem(
                                // 外部数据不信任：id 空串归一 null（撞 key 崩溃防护，决策 #90）
                                uid = info.id?.takeIf { it.isNotBlank() },
                                name = info.title.orEmpty(),
                                address = info.address?.takeIf { it.isNotBlank() },
                                city = info.city?.takeIf { it.isNotBlank() },
                                district = info.district?.takeIf { it.isNotBlank() },
                                tag = info.category?.takeIf { it.isNotBlank() },
                                lat = info.latLng?.latitude,
                                lng = info.latLng?.longitude,
                                province = info.province?.takeIf { it.isNotBlank() },
                            )
                        } ?: emptyList()
                        onResult(items)
                    }

                    override fun onFailure(statusCode: Int, msg: String?, throwable: Throwable?) {
                        onResult(null)
                    }
                },
            )
        }.onFailure { onResult(null) }
    }
}
