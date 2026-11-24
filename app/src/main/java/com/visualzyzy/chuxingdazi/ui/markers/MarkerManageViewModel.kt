package com.visualzyzy.chuxingdazi.ui.markers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.DeleteImpact
import com.visualzyzy.chuxingdazi.data.repo.DuplicatePlaceException
import com.visualzyzy.chuxingdazi.data.repo.ListRepository
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * P76（2026-11-05）S29 标记管理（§6 S13 设置 → 点位显示）：管理已入库的标记。
 * 只读列表 + 改名 / 换分类 / 清单归属 / 到访状态 / 删除——**不含新增标记**。
 * 数据联动：删标记走 FK 级联（place_list / trip_place / schedule 一并删除），
 * 清单/分类/行程侧的数量与列表实时回流（各页都 observe 同一张表）。
 */
@HiltViewModel
class MarkerManageViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val listRepository: ListRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val places: StateFlow<List<PlaceEntity>> =
        placeRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lists: StateFlow<List<ListEntity>> =
        listRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** 该点位当前所属清单（面板打开时读取一次，勾选变化后增量维护）。 */
    suspend fun listsOfPlace(placeId: String): List<ListEntity> = placeRepository.listsOfPlace(placeId)

    /** 点位删除影响（S07 同源）：行程数 + 安排段数（清单侧走级联，删除前一并提示）。 */
    suspend fun impactOfDelete(placeId: String): DeleteImpact = placeRepository.impactOfDelete(placeId)

    /** 改清单归属：与目标集合对齐（新增缺失、移除多余），保持其他关联不动。 */
    fun setPlaceLists(placeId: String, currentIds: Set<String>, targetIds: Set<String>) {
        viewModelScope.launch {
            runCatching {
                (targetIds - currentIds).forEach { listRepository.addPlace(placeId, it) }
                (currentIds - targetIds).forEach { listRepository.removePlace(placeId, it) }
            }.onFailure { _message.value = "清单归属更新失败：${it.message}" }
        }
    }

    /** 改名。同点异物重名会被 UNIQUE(name,lat,lng) 兜底拦下（转译为提示）。 */
    fun rename(placeId: String, newName: String) {
        viewModelScope.launch {
            runCatching {
                val place = placeRepository.getById(placeId) ?: return@launch
                placeRepository.update(
                    place.copy(name = newName.trim(), updatedAt = System.currentTimeMillis()),
                )
            }.onFailure { e ->
                _message.value = if (e is DuplicatePlaceException) {
                    "已存在同名且坐标相同的点位，换个名字"
                } else {
                    "改名失败：${e.message}"
                }
            }
        }
    }

    /** 换分类：仅改 category_id（点位回到该分类的图标/颜色渲染），清单与行程安排不动。 */
    fun changeCategory(placeId: String, categoryId: String) {
        viewModelScope.launch {
            runCatching {
                val place = placeRepository.getById(placeId) ?: return@launch
                placeRepository.update(
                    place.copy(categoryId = categoryId, updatedAt = System.currentTimeMillis()),
                )
            }.onFailure { _message.value = "换分类失败：${it.message}" }
        }
    }

    /**
     * 第十一轮（2026-11-11）：设置/清除点位自定义图标。
     * iconKey null = 清除自定义、回「跟随分类」；地图标记按 place.iconKey 优先渲染
     *（MapController.addPlaceMarker 既有覆盖逻辑），places 流刷新后即时生效。
     */
    fun setIcon(placeId: String, iconKey: String?) {
        viewModelScope.launch {
            runCatching {
                val place = placeRepository.getById(placeId) ?: return@launch
                placeRepository.update(
                    place.copy(iconKey = iconKey?.takeIf { it.isNotBlank() }, updatedAt = System.currentTimeMillis()),
                )
            }.onFailure { _message.value = "图标更新失败：${it.message}" }
        }
    }

    fun setVisited(placeId: String, visited: Boolean) {
        viewModelScope.launch { runCatching { placeRepository.setVisited(placeId, visited) } }
    }

    /**
     * 删除标记：place 表删行 → place_list / trip_place / schedule 经 FK CASCADE 级联删除
     * （清单失去该点位、行程安排同步消失，行程本体保留）。
     */
    fun deletePlace(placeId: String) {
        viewModelScope.launch { runCatching { placeRepository.delete(placeId) } }
    }
}
