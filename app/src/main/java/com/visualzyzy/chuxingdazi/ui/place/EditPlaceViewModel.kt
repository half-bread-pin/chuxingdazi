package com.visualzyzy.chuxingdazi.ui.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.ListRepository
import com.visualzyzy.chuxingdazi.data.repo.NameConflictException
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** S08 编辑点位（§6 S08）。 */
@HiltViewModel
class EditPlaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val placeRepository: PlaceRepository,
    private val categoryRepository: CategoryRepository,
    private val listRepository: ListRepository,
) : ViewModel() {

    val placeId: String = savedStateHandle.get<String>("placeId").orEmpty()

    val place: StateFlow<PlaceEntity?> =
        placeRepository.observeById(placeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allLists: StateFlow<List<ListEntity>> =
        listRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun memberListIds(): Set<String> = placeRepository.listsOfPlace(placeId).map { it.id }.toSet()

    /** S08 删除确认的影响文案：所属清单名列表（2026-11-21 承接原 S29 面板能力）。 */
    suspend fun memberLists(): List<ListEntity> = placeRepository.listsOfPlace(placeId)

    /** @return null = 名称冲突 */
    suspend fun createList(name: String): ListEntity? = try {
        listRepository.create(name, null)
    } catch (e: NameConflictException) {
        null
    }

    /** 新建分类（2026-11-19：S08 分类横排换 CategorySelectorRow 后「＋新建」入口，与 S06 同语义）。@return null = 名称冲突 */
    suspend fun createCategory(name: String, iconKey: String, color: String): CategoryEntity? = try {
        categoryRepository.create(name, iconKey, color)
    } catch (e: NameConflictException) {
        null
    }

    /** 保存点位本体 + 同步清单关系（§6 S08：名称留空回退既有名称；模板字段不校验）。
     *  customSchemaJson：点位自定义字段定义（字段模板升级 §3.1，2026-11-21），值已在 fieldValuesJson 里。
     *  iconKey/visited：S08 2026-11-21 补齐（承接原 S29 面板能力）——随「保存」统一落库；
     *  首次勾选已到访记 visited_at=now，取消到访置 null（与 repo.setVisited 同语义）。 */
    fun save(
        name: String,
        categoryId: String,
        note: String?,
        fieldValuesJson: String,
        customSchemaJson: String = "[]",
        iconKey: String?,
        visited: Boolean,
        selectedListIds: Set<String>,
        originalListIds: Set<String>,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            val current = placeRepository.getById(placeId) ?: return@launch
            val now = System.currentTimeMillis()
            placeRepository.update(
                current.copy(
                    name = name.trim().ifBlank { current.name },
                    categoryId = categoryId,
                    note = note?.takeIf { it.isNotBlank() },
                    fieldValues = fieldValuesJson,
                    customSchema = customSchemaJson,
                    iconKey = iconKey?.takeIf { it.isNotBlank() },
                    visited = visited,
                    visitedAt = if (visited) current.visitedAt ?: now else null,
                    updatedAt = now,
                ),
            )
            // 清单关系同步：新增的加、取消的删（§5.2 place_list）
            (selectedListIds - originalListIds).forEach { listId ->
                listRepository.addPlace(placeId, listId)
            }
            (originalListIds - selectedListIds).forEach { listId ->
                listRepository.removePlace(placeId, listId)
            }
            onSaved()
        }
    }

    /** 删除点位（字段模板升级 2026-11-21：S08 补齐，承接原 S29 面板删除能力）。
     *  级联带走它在所有行程里的 trip_place 与 schedule（repo 事务语义）。 */
    fun deletePlace(onDone: () -> Unit) {
        viewModelScope.launch {
            placeRepository.delete(placeId)
            onDone()
        }
    }

    /** 删除影响统计（清单数/行程安排数，S29 面板同款语义）。 */
    suspend fun impactOfDelete(): com.visualzyzy.chuxingdazi.data.repo.DeleteImpact =
        placeRepository.impactOfDelete(placeId)
}
