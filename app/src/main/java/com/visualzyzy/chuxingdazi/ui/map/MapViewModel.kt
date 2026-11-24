package com.visualzyzy.chuxingdazi.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import com.visualzyzy.chuxingdazi.data.repo.DeleteImpact
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** S03 主地图的数据层接入（M3）。 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    categoryRepository: CategoryRepository,
    private val listRepository: com.visualzyzy.chuxingdazi.data.repo.ListRepository,
    private val tripRepository: com.visualzyzy.chuxingdazi.data.repo.TripRepository,
    private val regionHealer: com.visualzyzy.chuxingdazi.data.repo.RegionHealer,
    /** 省-市行政区划数据（2026-11-17 腾讯版：腾讯接口获取 + Room 持久化，CityPickerDialog 消费）。 */
    val districtRepository: com.visualzyzy.chuxingdazi.data.region.DistrictRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** 相机静止时持久化最近位置（S06/重启/返回 S03 时恢复，避免跳回默认中心）。 */
    fun saveCamera(lat: Double, lng: Double, zoom: Float) {
        viewModelScope.launch { Prefs.saveCamera(appContext, lat, lng, zoom) }
    }

    // 第二十五轮（2026-11-14）：用户在本会话点过主界面「定位」按钮（即授权并启动过定位）。
    // ViewModel 绑定 S03 的 backStackEntry，从子页面（S10/S13/S05/S06…）返回 S03 时仍存活——
    // 用来区分「冷启动」（不启动定位，§7.5：权限框只在用户点按钮时弹）与「返回主界面重建
    // MapView」（需自动重启定位恢复蓝点，见 MapScreen onMapCreated）。
    var locateEverStarted = false
        private set

    fun markLocateStarted() { locateEverStarted = true }

    val places: StateFlow<List<PlaceEntity>> =
        placeRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    // 第十三轮（2026-11-12）：S04「按清单筛选」数据源——全部清单（面板方框）+ 所选清单的点位 id 集
    val allLists: StateFlow<List<ListEntity>> =
        listRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun placeIdsInList(listId: String): Set<String> =
        listRepository.observePlaceIdsInList(listId).first()

    /** 2026-11-24 多选：所选清单点位 id 并集（S04 清单筛选）。 */
    suspend fun placeIdsInLists(listIds: Set<String>): Set<String> =
        listRepository.placeIdsInLists(listIds)

    /** S07「已安排」区块（2026-11-24 实装）：该点位在各行程里的时段（带行程名）。 */
    suspend fun schedulesForPlace(placeId: String): List<com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithTripRow> =
        tripRepository.schedulesForPlace(placeId)

    /** 地图就绪后触发一轮区域自愈（补齐旧数据/搜索链路缺失的 province/city，纯后台不影响体验）。 */
    fun kickRegionHeal() {
        regionHealer.kick()
    }

    /** 长按地图 = 进入 S06 选点（M4 起）；M3 的 quickAddPlace 已无调用方，第十六轮删除。 */
    fun toggleVisited(place: PlaceEntity) {
        viewModelScope.launch { placeRepository.setVisited(place.id, !place.visited) }
    }

    fun deletePlace(placeId: String) {
        viewModelScope.launch { placeRepository.delete(placeId) }
    }

    suspend fun listsOfPlace(placeId: String): List<ListEntity> = placeRepository.listsOfPlace(placeId)

    suspend fun impactOfDelete(placeId: String): DeleteImpact = placeRepository.impactOfDelete(placeId)
}
