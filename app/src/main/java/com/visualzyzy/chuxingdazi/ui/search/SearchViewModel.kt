package com.visualzyzy.chuxingdazi.ui.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.tencent.SuggestionItem
import com.visualzyzy.chuxingdazi.data.tencent.SuggestionSearcher
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.ListRepository
import com.visualzyzy.chuxingdazi.data.repo.NameConflictException
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** S05 结果行：联想条目 + 查重结论（§14.1 #34：poi_uid 命中 或 同名<5m → 已在库硬拦）。 */
data class SearchResultRow(
    val item: SuggestionItem,
    val inLibrary: Boolean,
    /** 第十六轮软提示：坐标 <5m 内已有名称不同的点位（行内提示）。 */
    val nearMiss: Boolean = false,
) {
    /** 第十六轮：无坐标的行是搜索联想词（如「附近的烤鸭店」），不可入库、不可打开详情。 */
    val hasCoords: Boolean get() = item.lat != null && item.lng != null
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val listRepository: ListRepository,
    private val categoryRepository: CategoryRepository,
    private val regionHealer: com.visualzyzy.chuxingdazi.data.repo.RegionHealer,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val searcher = SuggestionSearcher(appContext)

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allLists: StateFlow<List<ListEntity>> =
        listRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 第十三轮：S04「按清单筛选」——所选清单的点位 id 集（S05b 面板消费）。 */
    suspend fun placeIdsInList(listId: String): Set<String> =
        listRepository.observePlaceIdsInList(listId).first()

    /** 2026-11-24 多选：所选清单点位 id 并集（S05b 页内筛选副本消费）。 */
    suspend fun placeIdsInLists(listIds: Set<String>): Set<String> =
        listRepository.placeIdsInLists(listIds)

    // 第六轮（2026-11-06）：S05b 详情页地图也要显示已有点位标记（按 S04 筛选设置，与主界面同一份数据）
    val places: StateFlow<List<PlaceEntity>> =
        placeRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 第七轮（2026-11-06）：S04「按照地区筛选」数据源（省份表 / 省份-城市记录表，DB 聚合实时流）
    val provinceCounts: StateFlow<List<com.visualzyzy.chuxingdazi.data.db.dao.RegionCountRow>> =
        placeRepository.observeProvinceCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val provinceCityCounts: StateFlow<List<com.visualzyzy.chuxingdazi.data.db.dao.RegionCityCountRow>> =
        placeRepository.observeProvinceCityCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 第十六轮：S04「地区未知」胶囊数量（region 信息不全的点位）。 */
    val unknownRegionCount: StateFlow<Int> =
        placeRepository.observeUnknownRegionCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentSearches: StateFlow<List<String>> =
        Prefs.recentSearches(appContext).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var query by androidx.compose.runtime.mutableStateOf("")
        private set

    private val _rows = MutableStateFlow<List<SearchResultRow>?>(null)
    val rows: StateFlow<List<SearchResultRow>?> = _rows.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _requestRejected = MutableStateFlow(false)
    val requestRejected: StateFlow<Boolean> = _requestRejected.asStateFlow()
    private val _city = MutableStateFlow<String?>(null)
    val city: StateFlow<String?> = _city.asStateFlow()
    // 2026-11-19：多选/批量添加已按用户定调移除（勾选集合/批量入库随之删除）

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            _city.value = Prefs.lastCity(appContext).first()
        }
    }

    /** 输入 ≥1 字符后 300ms 防抖触发联想（§7.2）；同时记录最近搜索与城市。 */
    fun onQueryChange(value: String) {
        query = value
        searchJob?.cancel()
        if (value.trim().isBlank()) {
            _rows.value = null
            _searching.value = false
            _requestRejected.value = false
            return
        }
        _searching.value = true
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val keyword = value.trim()
            Prefs.addRecentSearch(appContext, keyword)
            val usedCity = _city.value
            searcher.search(
                keyword,
                usedCity,
            ) { items ->
                if (items == null) {
                    _requestRejected.value = true // 请求被拒：S05 显示重试按钮
                } else {
                    viewModelScope.launch {
                        _rows.value = items.map { item ->
                            val inLibrary = isInLibrary(item)
                            val nearMiss = !inLibrary &&
                                item.lat != null && item.lng != null &&
                                placeRepository.findVeryClosePlace(item.lat, item.lng) != null
                            SearchResultRow(item, inLibrary, nearMiss)
                        }
                        _requestRejected.value = false
                    }
                }
                _searching.value = false
            }
        }
    }

    fun retry() = onQueryChange(query)

    /** P67（2026-11-04）第四轮：清空全部最近搜索（搜索页小垃圾桶「确认」）。 */
    fun clearRecentSearches() {
        viewModelScope.launch { Prefs.clearRecentSearches(appContext) }
    }

    /** P67（2026-11-04）第四轮：详情页 X 键回搜索落地页时清空输入与结果状态。 */
    fun clearQuery() {
        query = ""
        _rows.value = null
        _searching.value = false
        _requestRejected.value = false
    }

    /**
     * P67（2026-11-04）第四轮：详情页「为该位置添加标记」单条入库（逻辑同 batchAdd 单行版）。
     * 第六轮（2026-11-06）：支持自定义名称与备注（添加浮层新增的文本框；留空回退 POI 名）。
     * @return onDone(actualAdded, coords) —— added=0 表示重复点位
     */
    fun addSingle(
        item: SuggestionItem,
        categoryId: String,
        listIds: Set<String>,
        customName: String = "",
        note: String = "",
        // 第十一轮（2026-11-11）：S05b 添加卡片与 S06 同构——支持自定义图标 + 分类模板字段
        iconKey: String? = null,
        fieldValuesJson: String = "{}",
        customSchemaJson: String = "[]", // 字段模板升级 §3.1：点位自定义字段定义随添加落库
        onDone: (added: Int, coords: DoubleArray) -> Unit,
    ) {
        viewModelScope.launch {
            val lat = item.lat
            val lng = item.lng
            if (lat == null || lng == null) {
                onDone(0, DoubleArray(0))
                return@launch
            }
            val now = System.currentTimeMillis()
            val place = PlaceEntity(
                id = UUID.randomUUID().toString(),
                name = customName.trim().ifBlank { item.name.ifBlank { "未命名地点" } },
                address = item.address ?: listOfNotNull(item.district, item.city).joinToString("").ifBlank { null },
                lat = lat,
                lng = lng,
                categoryId = categoryId,
                iconKey = iconKey?.takeIf { it.isNotBlank() },
                color = null,
                note = note.trim().takeIf { it.isNotBlank() },
                fieldValues = fieldValuesJson,
                customSchema = customSchemaJson,
                source = PlaceEntity.SOURCE_SEARCH,
                poiUid = item.uid?.takeIf { it.isNotBlank() },
                // 第八轮：S05b 点击点位入库带逆地理省份（联想结果没有 → null，由 RegionHealer 补齐）
                province = item.province?.takeIf { it.isNotBlank() },
                city = item.city?.takeIf { it.isNotBlank() }, // city 是百度 SDK 返回的，直接落库
                visited = false,
                createdAt = now,
                updatedAt = now,
            )
            var added = 0
            val coords = DoubleArray(2)
            try {
                placeRepository.add(place)
                regionHealer.markDirty() // 第八轮：有新点位 → healthy 标志置 false（下一轮扫描复核）
                regionHealer.kick()
                listIds.forEach { listId -> listRepository.addPlace(place.id, listId) }
                coords[0] = lat
                coords[1] = lng
                added = 1
            } catch (e: com.visualzyzy.chuxingdazi.data.repo.DuplicatePlaceException) {
                added = 0
            }
            // 第十六轮：联想结果一般不带省 → 当场逆地理补齐（地区筛选立即可命中）；
            // 并行执行不拖慢返回地图
            if (added > 0 && place.province.isNullOrBlank()) {
                launch { regionHealer.healPlacesNow(listOf(place)) }
            }
            onDone(added, coords)
        }
    }

    /** 查重（§14.1 #34：poi_uid 命中 或 同名<5m → 已在库）。S05b 详情页也用（P67）。 */
    suspend fun isInLibrary(item: SuggestionItem): Boolean {
        if (!item.uid.isNullOrBlank()) {
            if (placeRepository.findByPoiUid(item.uid) != null) return true
        }
        val lat = item.lat ?: return false
        val lng = item.lng ?: return false
        return placeRepository.findNearbyDuplicate(item.name, lat, lng) != null
    }

    fun saveCity(cityName: String) {
        viewModelScope.launch {
            _city.value = cityName
            Prefs.saveLastCity(appContext, cityName)
        }
    }

    /** @return null = 名称冲突 */
    suspend fun createList(name: String): ListEntity? = try {
        listRepository.create(name, null)
    } catch (e: NameConflictException) {
        null
    }

    /** 第十一轮：S05b 添加浮层内「＋新建分类」（与 S06/PickPlaceViewModel 同语义）。@return null = 名称冲突 */
    suspend fun createCategory(name: String, iconKey: String, color: String): CategoryEntity? = try {
        categoryRepository.create(name, iconKey, color)
    } catch (e: NameConflictException) {
        null
    }
}
