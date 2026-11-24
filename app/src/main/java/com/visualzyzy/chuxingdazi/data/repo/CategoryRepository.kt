package com.visualzyzy.chuxingdazi.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.visualzyzy.chuxingdazi.data.db.ChuxingdaziDatabase
import com.visualzyzy.chuxingdazi.data.db.dao.CategoryDao
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceDao
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.model.FieldDef
import com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec
import com.visualzyzy.chuxingdazi.data.model.FieldValuesCodec
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** UNIQUE(name) 冲突（新建/改名，含内置分类名，§14.1 #33）。 */
class NameConflictException(message: String) : Exception(message)

interface CategoryRepository {
    fun observeAll(): Flow<List<CategoryEntity>>
    suspend fun getAll(): List<CategoryEntity>
    suspend fun getById(id: String): CategoryEntity?

    /** 名称实时查重（S09 编辑面板；excludeId = 编辑自身时排除）。 */
    suspend fun isNameTaken(name: String, excludeId: String? = null): Boolean

    /** 首启种子：幂等，已存在的不覆盖（§5.4）。 */
    suspend fun seedBuiltins()

    /** @throws NameConflictException 名称与已有分类（含内置）重复 */
    suspend fun create(name: String, iconKey: String, color: String): CategoryEntity

    /** @throws NameConflictException 新名称与其它分类重复（编辑自身除外） */
    suspend fun rename(id: String, newName: String)

    suspend fun updateAppearance(id: String, iconKey: String, color: String)
    suspend fun updateFieldSchema(id: String, schema: List<FieldDef>)

    /**
     * 更新分类模板字段（字段模板升级 §3.4，2026-11-21）：单事务内更新 field_schema，
     * 并清理该分类**所有点位** field_values 里「被删字段」的值（孤儿值清理，与
     * deleteCustom 的清理同款语义）。新增字段不动已有值。
     */
    suspend fun updateTemplateSchema(id: String, schema: List<FieldDef>)

    /**
     * 删自定义分类（§5.2 / §5.3 删除语义）：单事务内把名下点位迁到 other
     * 并**同时清理这些点位 field_values 里属于该分类模板的 key**，然后删分类。
     * @throws IllegalStateException 内置分类不可删除
     */
    suspend fun deleteCustom(id: String)
}

@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val db: ChuxingdaziDatabase,
    private val categoryDao: CategoryDao,
    private val placeDao: PlaceDao,
) : CategoryRepository {

    override fun observeAll(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    override suspend fun getAll(): List<CategoryEntity> = categoryDao.getAll()

    override suspend fun getById(id: String): CategoryEntity? = categoryDao.getById(id)

    override suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val found = categoryDao.findByName(name.trim()) ?: return false
        return found.id != excludeId
    }

    override suspend fun seedBuiltins() {
        categoryDao.insertIgnore(BuiltinCategories.entities(System.currentTimeMillis()))
    }

    override suspend fun create(name: String, iconKey: String, color: String): CategoryEntity {
        val now = System.currentTimeMillis()
        val entity = CategoryEntity(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            iconKey = iconKey,
            color = color,
            sortIndex = 0,
            isBuiltin = false,
            fieldSchema = "[]",
            createdAt = now,
            updatedAt = now,
        )
        return try {
            categoryDao.insert(entity)
            entity
        } catch (e: SQLiteConstraintException) {
            throw NameConflictException("已有同名分类：${entity.name}")
        }
    }

    override suspend fun rename(id: String, newName: String) {
        val current = categoryDao.getById(id) ?: return
        val conflict = categoryDao.findByName(newName.trim())
        if (conflict != null && conflict.id != id) {
            throw NameConflictException("已有同名分类：${conflict.name}")
        }
        categoryDao.update(current.copy(name = newName.trim(), updatedAt = System.currentTimeMillis()))
    }

    override suspend fun updateAppearance(id: String, iconKey: String, color: String) {
        val current = categoryDao.getById(id) ?: return
        categoryDao.update(current.copy(iconKey = iconKey, color = color, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun updateFieldSchema(id: String, schema: List<FieldDef>) {
        val current = categoryDao.getById(id) ?: return
        categoryDao.update(current.copy(fieldSchema = FieldSchemaCodec.encode(schema), updatedAt = System.currentTimeMillis()))
    }

    override suspend fun updateTemplateSchema(id: String, schema: List<FieldDef>) {
        db.withTransaction {
            val fresh = categoryDao.getById(id) ?: return@withTransaction
            val now = System.currentTimeMillis()
            categoryDao.update(
                fresh.copy(fieldSchema = FieldSchemaCodec.encode(schema), updatedAt = now),
            )
            // 孤儿值清理：旧 schema 有、新 schema 没有的 key，从该分类全部点位 field_values 移除
            val oldKeys = FieldSchemaCodec.decode(fresh.fieldSchema).map { it.key }.toSet()
            val newKeys = schema.map { it.key }.toSet()
            val removedKeys = oldKeys - newKeys
            if (removedKeys.isEmpty()) return@withTransaction
            for (place in placeDao.getByCategory(id)) {
                val values = FieldValuesCodec.decode(place.fieldValues).toMutableMap()
                val before = values.size
                removedKeys.forEach(values::remove)
                if (values.size != before) {
                    placeDao.update(place.copy(fieldValues = FieldValuesCodec.encode(values), updatedAt = now))
                }
            }
        }
    }

    override suspend fun deleteCustom(id: String) {
        // 校验放在事务外：事务体内抛异常会让 Room 的回滚路径把异常抛到全局协程处理器
        val category = categoryDao.getById(id) ?: return
        check(!category.isBuiltin) { "内置分类不可删除（§5.4）" }

        db.withTransaction {
            val fresh = categoryDao.getById(id) ?: return@withTransaction
            if (fresh.isBuiltin) return@withTransaction // 双保险，不在事务体内抛

            val now = System.currentTimeMillis()
            val schemaKeys = FieldSchemaCodec.decode(fresh.fieldSchema).map { it.key }.toSet()
            // 迁移点位到 other 的同时清理该分类模板的孤儿值（§5.3 删除语义表）
            for (place in placeDao.getByCategory(id)) {
                val cleaned = if (schemaKeys.isEmpty()) {
                    place.fieldValues
                } else {
                    val values = FieldValuesCodec.decode(place.fieldValues).toMutableMap()
                    schemaKeys.forEach(values::remove)
                    FieldValuesCodec.encode(values)
                }
                placeDao.update(
                    place.copy(
                        categoryId = BuiltinIds.OTHER,
                        fieldValues = cleaned,
                        updatedAt = now,
                    ),
                )
            }
            categoryDao.delete(fresh)
        }
    }
}
