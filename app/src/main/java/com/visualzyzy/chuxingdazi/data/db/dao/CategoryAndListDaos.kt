package com.visualzyzy.chuxingdazi.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    // P12（2026-11-02）：预设分类排最左（按 sort_index），用户新建按创建时间排在后面。
    // 旧版 ORDER BY sort_index, created_at 会让用户分类（sort_index=0）插进内置分类中间。
    @Query(
        "SELECT * FROM category ORDER BY is_builtin DESC, " +
            "CASE WHEN is_builtin = 1 THEN sort_index ELSE created_at END",
    )
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query(
        "SELECT * FROM category ORDER BY is_builtin DESC, " +
            "CASE WHEN is_builtin = 1 THEN sort_index ELSE created_at END",
    )
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    /** 名称查重（新建/改名实时校验，含内置分类名，§14.1 #33）。 */
    @Query("SELECT * FROM category WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    /** 种子数据用 IGNORE：已存在（含用户改过的内置分类）不覆盖，保证幂等（§5.4）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(categories: List<CategoryEntity>): List<Long>

    /** 新建/更新走 ABORT / @Update：UNIQUE(name) 冲突时抛 SQLite 异常，由仓库转译。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)
}

@Dao
interface ListDao {

    // 第十六轮：sort_index 生效（S27 可排序）；同序号内仍按创建时间倒序（旧行为不变）
    @Query("SELECT * FROM list ORDER BY sort_index, created_at DESC")
    fun observeAll(): Flow<List<ListEntity>>

    @Query("SELECT * FROM list ORDER BY sort_index, created_at DESC")
    suspend fun getAll(): List<ListEntity>

    /** 第十六轮：新建清单排到最前（sort_index = 当前最小值 − 1，保持「新建在上」的原有直觉）。 */
    @Query("SELECT MIN(sort_index) FROM list")
    suspend fun getMinSortIndex(): Int?

    @Query("SELECT * FROM list WHERE id = :id")
    suspend fun getById(id: String): ListEntity?

    @Query("SELECT * FROM list WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): ListEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(list: ListEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(list: ListEntity)

    @Update
    suspend fun update(list: ListEntity)

    @Delete
    suspend fun delete(list: ListEntity)

    /** 清空所有数据（S13）。 */
    @Query("DELETE FROM list")
    suspend fun deleteAll()
}
