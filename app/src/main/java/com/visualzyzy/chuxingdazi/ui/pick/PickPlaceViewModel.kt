package com.visualzyzy.chuxingdazi.ui.pick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.tencent.MapController
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** S06 地图选点添加（§6 S06）。editPlaceId 非空 = S08「调整」模式（只更新坐标/地址）。 */
@HiltViewModel
class PickPlaceViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val categoryRepository: CategoryRepository,
    private val listRepository: com.visualzyzy.chuxingdazi.data.repo.ListRepository,
    private val regionHealer: com.visualzyzy.chuxingdazi.data.repo.RegionHealer,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 第六轮（2026-11-06）：S06 地图上也要能看到已有点位标记（显示规则由 S04 筛选设置决定）
    val places: StateFlow<List<PlaceEntity>> =
        placeRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun getPlace(placeId: String): PlaceEntity? = placeRepository.getById(placeId)

    /** 第十六轮：新坐标 5 米内已有点位（调整模式排除自身）——保存前软确认用。 */
    suspend fun nearDuplicateOf(lat: Double, lng: Double, excludePlaceId: String? = null): PlaceEntity? =
        placeRepository.findVeryClosePlace(lat, lng, excludePlaceId)

    /** 第十三轮：S04「按清单筛选」——所选清单的点位 id 集（S06 地图标记渲染过滤用）。 */
    suspend fun placeIdsInList(listId: String): Set<String> =
        listRepository.observePlaceIdsInList(listId).first()

    /** 2026-11-24 多选：所选清单点位 id 并集（S06 地图标记渲染过滤用）。 */
    suspend fun placeIdsInLists(listIds: Set<String>): Set<String> =
        listRepository.placeIdsInLists(listIds)

    /** 第七轮：S06 地图就绪 → 触发区域自愈（与 S03/S05b 同节奏）。 */
    fun kickRegionHeal() {
        regionHealer.kick()
    }

    /** 新建入库（§6 S06：名称留空回退逆地理简短名称；模板字段值由调用方编码好传入）。
     *  第七轮：province/city 随保存一并落库（只信百度逆地理；空则留空，由 RegionHealer 自愈补齐）。 */
    fun saveNew(
        lat: Double,
        lng: Double,
        name: String,
        address: String?,
        nameCandidate: String,
        categoryId: String,
        note: String?,
        fieldValuesJson: String,
        customSchemaJson: String = "[]", // 字段模板升级 §3.1：点位自定义字段定义随保存落库
        province: String? = null,
        city: String? = null,
        iconKey: String? = null, // 第十一轮：点位自定义图标（null = 跟随分类图标）
        onSaved: (PlaceEntity) -> Unit,
        onDuplicate: () -> Unit,
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val place = PlaceEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { nameCandidate.take(16).ifBlank { "已标注地点" } },
                address = address?.takeIf { it.isNotBlank() },
                lat = lat,
                lng = lng,
                categoryId = categoryId,
                iconKey = iconKey?.takeIf { it.isNotBlank() },
                color = null,
                note = note?.takeIf { it.isNotBlank() },
                fieldValues = fieldValuesJson,
                customSchema = customSchemaJson,
                source = PlaceEntity.SOURCE_MAP_PICK,
                poiUid = null,
                province = province,
                city = city,
                visited = false,
                createdAt = now,
                updatedAt = now,
            )
            try {
                placeRepository.add(place)
                regionHealer.markDirty() // 第八轮：有新点位 → healthy 标志置 false
                regionHealer.kick() // 手头没拿到 region 时后台补齐（有也不碍事，查询会落空）
                onSaved(place)
            } catch (e: com.visualzyzy.chuxingdazi.data.repo.DuplicatePlaceException) {
                onDuplicate()
            }
        }
    }

    /** S06 内新建自定义分类（§6 用户反馈 2026-10-31：分类行末「＋」）。@return null = 名称冲突 */
    suspend fun createCategory(name: String, iconKey: String, color: String): CategoryEntity? =
        try {
            categoryRepository.create(name, iconKey, color)
        } catch (e: com.visualzyzy.chuxingdazi.data.repo.NameConflictException) {
            null
        }

    /** 调整模式：只更新坐标与地址（其余字段归 S08 管）。第七轮：坐标变了区域可能也变，一并刷新。 */
    fun updateCoords(
        placeId: String,
        lat: Double,
        lng: Double,
        address: String?,
        province: String? = null,
        city: String? = null,
    ) {
        viewModelScope.launch {
            val current = placeRepository.getById(placeId) ?: return@launch
            placeRepository.update(
                current.copy(
                    lat = lat,
                    lng = lng,
                    address = address?.takeIf { it.isNotBlank() } ?: current.address,
                    province = province ?: current.province,
                    city = city ?: current.city,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            regionHealer.markDirty() // 第八轮：点位数据变了 → healthy 标志置 false（区域可能缺失）
            regionHealer.kick()
        }
    }
}
