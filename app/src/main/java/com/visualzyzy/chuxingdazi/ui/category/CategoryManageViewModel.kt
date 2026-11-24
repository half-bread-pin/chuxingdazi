package com.visualzyzy.chuxingdazi.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CategoryManageViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val placeRepository: PlaceRepository,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val places: StateFlow<List<PlaceEntity>> =
        placeRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 编辑中的分类 id（S09 底部编辑面板）。 */
    private val _editingId = MutableStateFlow<String?>(null)
    val editingId: StateFlow<String?> = _editingId.asStateFlow()

    /**
     * R15/R16（第七轮 2026-11-06）：「＋ 新建自定义分类」的**未落库草稿**。
     * 打开面板只设草稿；必须点「保存」才真正 create（落库）；点 X / 返回 = 丢弃草稿。
     * （旧实现 createNewCustom() 立即建库行，用户不确认也会留下「新分类」——已废弃。）
     */
    private val _draft = MutableStateFlow<CategoryEntity?>(null)
    val draft: StateFlow<CategoryEntity?> = _draft.asStateFlow()

    fun openEditor(id: String?) {
        _editingId.value = id
        if (id != null) _draft.value = null // 打开已有分类时丢弃未保存草稿
    }

    fun startCreating() {
        _editingId.value = null
        _draft.value = CategoryEntity(
            id = DRAFT_ID,
            name = "",
            iconKey = "other_point",
            color = "#94A3B8",
            sortIndex = 0,
            isBuiltin = false,
            fieldSchema = "[]",
            createdAt = 0L,
            updatedAt = 0L,
        )
    }

    /** S18（草稿模式）回传的颜色写回草稿，不落库。 */
    fun updateDraftColor(hex: String) {
        _draft.value = _draft.value?.copy(color = hex)
    }

    fun cancelCreating() {
        _draft.value = null
    }

    /**
     * 保存草稿 = 真正创建。重名 → onNameConflict（不创建）；成功 → onDone（关闭面板）。
     * schemaJson：模板字段（字段模板升级 §3.4，新建时已加的字段随创建落库）。
     */
    fun saveDraft(
        name: String,
        iconKey: String,
        schemaJson: String,
        onNameConflict: () -> Unit,
        onDone: () -> Unit,
    ) {
        val draftCat = _draft.value ?: return
        viewModelScope.launch {
            if (categoryRepository.isNameTaken(name)) {
                onNameConflict()
            } else {
                val created = categoryRepository.create(name, iconKey, draftCat.color)
                categoryRepository.updateFieldSchema(
                    created.id,
                    com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.decode(schemaJson),
                )
                _draft.value = null
                onDone()
            }
        }
    }

    suspend fun getCategory(id: String): CategoryEntity? = categoryRepository.getById(id)

    suspend fun isNameTaken(name: String, excludeId: String?): Boolean =
        categoryRepository.isNameTaken(name, excludeId)

    fun saveAppearance(id: String, name: String, iconKey: String, color: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val current = categoryRepository.getById(id) ?: return@launch
            categoryRepository.updateAppearance(id, iconKey, color)
            categoryRepository.rename(id, name)
            onDone()
        }
    }

    /** 模板字段增删落库（字段模板升级 §3.4）：repo 单事务更新 schema + 清被删字段的孤儿值。 */
    fun saveTemplateFields(id: String, schemaJson: String, onDone: () -> Unit) {
        viewModelScope.launch {
            categoryRepository.updateTemplateSchema(
                id,
                com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.decode(schemaJson),
            )
            onDone()
        }
    }

    /** 删自定义分类（仓库事务：点位回落 other + 清理孤儿值）。 */
    fun deleteCustom(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            categoryRepository.deleteCustom(id)
            onDone()
        }
    }

    companion object {
        /** 草稿占位 id（不落库，仅用于面板区分「新建」与「编辑已有」。 */
        const val DRAFT_ID = "__draft__"
    }
}
