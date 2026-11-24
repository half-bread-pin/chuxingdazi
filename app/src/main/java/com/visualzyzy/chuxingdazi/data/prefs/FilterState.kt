package com.visualzyzy.chuxingdazi.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * S04 筛选面板状态（§6 S04）。所有开关与分类显隐写入 DataStore，下次进入保持。
 *
 * - showMarkers: 显示标记总开关（P22，2026-11-02；关 = 地图上不画任何点位/聚合图标）
 * - markerStyle: ICON 图标标记 / DOT 圆点 / NAME_ONLY 仅名称（三分段）
 * - showLabels: 显示名称标签（默认开；NAME_ONLY 时强制开且不可改，P21）
 * - clusterMode: AUTO 自动（点位 > 60 时聚合）/ ON / OFF（§14.1 #9）
 * - unvisitedOnly: 只看未到访（默认关）
 * - markerSizePercent: 标记大小滑块（默认 60；拖动松手才生效，P23）
 * - hiddenCategoryIds: 隐藏的分类 id 集合（逗号分隔存储）
 * - regionProvince/regionCity: 按照地区筛选（第七轮 2026-11-06）；null = 全国。
 *   regionProvince 非空 = 只看该省；regionCity 再非空 = 只看该省该市。只信百度逆地理回填的数据。
 * - listFilterEnabled/listIds: 按清单筛选（第十三轮 2026-11-12；2026-11-24 单选改多选）。
 *   开关关闭 = 不启用该维度；开启且 listIds 非空 = 只看所选清单点位并集（任一命中即显示，
 *   同一点位可同时在多个清单）；空集 =「全部」。存储沿用旧键（逗号分隔多 id，
 *   旧单 id 存值自动兼容，零迁移）。
 */
data class FilterState(
    val showMarkers: Boolean = true,
    val markerStyle: String = "ICON",
    val showLabels: Boolean = true,
    val clusterMode: String = "AUTO",
    val unvisitedOnly: Boolean = false,
    val markerSizePercent: Int = 60,
    val hiddenCategoryIds: Set<String> = emptySet(),
    val regionProvince: String? = null,
    val regionCity: String? = null,
    val listFilterEnabled: Boolean = false,
    val listIds: Set<String> = emptySet(),
) {
    /**
     * 区域筛选是否命中（第七轮）：全国（null）恒真；选中省/市时，未知区域（还没自愈到）
     * 不命中。第十六轮：选中 [REGION_UNKNOWN]（「地区未知」胶囊）时只显示 region 信息
     * 不全的点位——避免自愈完成前这些点像被删掉。
     */
    fun matchesRegion(province: String?, city: String?): Boolean {
        val p = regionProvince ?: return true
        if (p == REGION_UNKNOWN) return province.isNullOrBlank() || city.isNullOrBlank()
        if (province.isNullOrBlank()) return false
        if (province != p) return false
        val c = regionCity ?: return true
        return city == c
    }

    /**
     * 清单筛选是否命中（第十三轮；2026-11-24 多选）：开关关闭或未选清单（listIds 空=「全部」）
     * → 恒真；开启且选中清单时，listPlaceIds 传**所选清单点位 id 的并集**（加载完成前为空集
     * → 短暂全隐），命中 = 点位在并集里（同一点位可在多个清单，任一命中即显示）。
     */
    fun matchesList(placeId: String, listPlaceIds: Set<String>?): Boolean {
        if (!listFilterEnabled || listIds.isEmpty()) return true
        return listPlaceIds?.contains(placeId) ?: false
    }

    companion object {
        /** 「地区未知」胶囊的哨兵值（第十六轮）：存进 DataStore 的 regionProvince 键位。 */
        const val REGION_UNKNOWN = "__unknown__"
        const val STYLE_ICON = "ICON"
        const val STYLE_DOT = "DOT"
        const val STYLE_NAME_ONLY = "NAME_ONLY"
        const val CLUSTER_AUTO = "AUTO"
        const val CLUSTER_ON = "ON"
        const val CLUSTER_OFF = "OFF"
    }
}

object FilterPrefs {

    private val KEY_SHOW_MARKERS = booleanPreferencesKey("filter_show_markers")
    private val KEY_STYLE = stringPreferencesKey("filter_marker_style")
    private val KEY_LABELS = booleanPreferencesKey("filter_show_labels")
    private val KEY_CLUSTER = stringPreferencesKey("filter_cluster_mode")
    private val KEY_UNVISITED = booleanPreferencesKey("filter_unvisited_only")
    private val KEY_SIZE = intPreferencesKey("filter_marker_size")
    private val KEY_HIDDEN_CATS = stringPreferencesKey("filter_hidden_cats")
    private val KEY_REGION_PROVINCE = stringPreferencesKey("filter_region_province")
    private val KEY_REGION_CITY = stringPreferencesKey("filter_region_city")
    private val KEY_LIST_ENABLED = booleanPreferencesKey("filter_list_enabled")
    private val KEY_LIST_ID = stringPreferencesKey("filter_list_id")

    fun filterState(context: Context): Flow<FilterState> =
        context.dataStore.data.map { p ->
            FilterState(
                showMarkers = p[KEY_SHOW_MARKERS] ?: true,
                markerStyle = p[KEY_STYLE] ?: FilterState.STYLE_ICON,
                showLabels = p[KEY_LABELS] ?: true,
                clusterMode = p[KEY_CLUSTER] ?: FilterState.CLUSTER_AUTO,
                unvisitedOnly = p[KEY_UNVISITED] ?: false,
                markerSizePercent = p[KEY_SIZE] ?: 60,
                hiddenCategoryIds = (p[KEY_HIDDEN_CATS] ?: "").split(',').filter { it.isNotBlank() }.toSet(),
                regionProvince = p[KEY_REGION_PROVINCE]?.takeIf { it.isNotBlank() },
                regionCity = p[KEY_REGION_CITY]?.takeIf { it.isNotBlank() },
                listFilterEnabled = p[KEY_LIST_ENABLED] ?: false,
                listIds = (p[KEY_LIST_ID] ?: "").split(',').filter { it.isNotBlank() }.toSet(),
            )
        }

    suspend fun saveShowMarkers(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_MARKERS] = value }
    }

    suspend fun saveStyle(context: Context, style: String) {
        context.dataStore.edit { it[KEY_STYLE] = style }
    }

    suspend fun saveShowLabels(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_LABELS] = value }
    }

    suspend fun saveClusterMode(context: Context, mode: String) {
        context.dataStore.edit { it[KEY_CLUSTER] = mode }
    }

    suspend fun saveUnvisitedOnly(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_UNVISITED] = value }
    }

    suspend fun saveMarkerSize(context: Context, percent: Int) {
        context.dataStore.edit { it[KEY_SIZE] = percent.coerceIn(30, 100) }
    }

    /** 切换某分类的隐藏状态（S04 分类胶囊点击）。 */
    suspend fun toggleHiddenCategory(context: Context, categoryId: String) {
        context.dataStore.edit { p ->
            val current = (p[KEY_HIDDEN_CATS] ?: "").split(',').filter { it.isNotBlank() }.toMutableSet()
            if (categoryId in current) current.remove(categoryId) else current.add(categoryId)
            p[KEY_HIDDEN_CATS] = current.joinToString(",")
        }
    }

    suspend fun clearHiddenCategories(context: Context) {
        context.dataStore.edit { it[KEY_HIDDEN_CATS] = "" }
    }

    suspend fun hideAllCategories(context: Context, allIds: List<String>) {
        context.dataStore.edit { it[KEY_HIDDEN_CATS] = allIds.joinToString(",") }
    }

    /** 按照地区筛选（第七轮）：province=null 表示全国；province 非空 city=null 表示全省。 */
    suspend fun saveRegion(context: Context, province: String?, city: String?) {
        context.dataStore.edit {
            it[KEY_REGION_PROVINCE] = province.orEmpty()
            it[KEY_REGION_CITY] = if (province == null) "" else city.orEmpty()
        }
    }

    /** 按清单筛选（第十三轮；2026-11-24 多选）：恒存选中集合（逗号分隔），enabled=false
     *  时集合也照存——用户关掉开关再重开时恢复原选区（对齐旧行为「listId 保留以便重开」）。 */
    suspend fun saveListFilter(context: Context, enabled: Boolean, listIds: Set<String>) {
        context.dataStore.edit {
            it[KEY_LIST_ENABLED] = enabled
            it[KEY_LIST_ID] = listIds.joinToString(",")
        }
    }

    /** 供非 suspend 场景读取（如 S03 首帧前的默认值）。 */
    suspend fun loadOnce(context: Context): FilterState = filterState(context).first()
}
