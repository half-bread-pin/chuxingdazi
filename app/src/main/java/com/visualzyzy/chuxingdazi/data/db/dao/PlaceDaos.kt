package com.visualzyzy.chuxingdazi.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {

    /** 默认 ABORT：UNIQUE(name, lat, lng) 兜底会抛异常，由仓库转译为重复点位（§14.1 #34）。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(place: PlaceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(places: List<PlaceEntity>)

    @Update
    suspend fun update(place: PlaceEntity)

    @Delete
    suspend fun delete(place: PlaceEntity)

    @Query("DELETE FROM place WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空所有数据（S13）。 */
    @Query("DELETE FROM place")
    suspend fun deleteAll()

    @Query("SELECT * FROM place WHERE id = :id")
    suspend fun getById(id: String): PlaceEntity?

    /** S08 编辑页：点位变化实时回流（S06 调整模式保存后自动刷新）。 */
    @Query("SELECT * FROM place WHERE id = :id")
    fun observeById(id: String): Flow<PlaceEntity?>

    @Query("SELECT * FROM place ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM place ORDER BY created_at DESC")
    suspend fun getAll(): List<PlaceEntity>

    @Query("SELECT * FROM place WHERE category_id = :categoryId ORDER BY created_at DESC")
    suspend fun getByCategory(categoryId: String): List<PlaceEntity>

    /** POI uid 查重（S05：命中提示「已在库」，非唯一索引）。 */
    @Query("SELECT * FROM place WHERE poi_uid = :poiUid LIMIT 1")
    suspend fun findByPoiUid(poiUid: String): PlaceEntity?

    /** 同名点位（应用层再按距离 <5m 硬拦；SQL 算不了球面距离，Kotlin 侧过滤）。 */
    @Query("SELECT * FROM place WHERE name = :name")
    suspend fun findSameName(name: String): List<PlaceEntity>

    /** 第十六轮：visited=true 时记 visited_at，取消到访清空（updated_at 照常刷新——这是用户动作）。 */
    @Query("UPDATE place SET visited = :visited, visited_at = :visitedAt, updated_at = :now WHERE id = :id")
    suspend fun setVisited(id: String, visited: Boolean, visitedAt: Long?, now: Long)

    @Query("SELECT COUNT(*) FROM place WHERE category_id = :categoryId")
    suspend fun countByCategory(categoryId: String): Int

    /** 删自定义分类时，把名下点位整体迁到 other（§5.2 注释；事务由仓库包）。 */
    @Query("UPDATE place SET category_id = :toCategoryId, updated_at = :now WHERE category_id = :fromCategoryId")
    suspend fun moveCategory(fromCategoryId: String, toCategoryId: String, now: Long)

    /** S04 分类胶囊 / S28 分类筛选的数量角标。 */
    @Query("SELECT COUNT(*) FROM place")
    suspend fun countAll(): Int

    // ---- 区域记录（第七轮 2026-11-06）----
    // 「省份表 / 省份-城市记录表」= 下面的 GROUP BY 聚合（走 idx_place_region 覆盖索引，索引扫描不回表）；
    // 「省份-点位 / 城市-点位记录表」= place 行本体（province/city 列），按区域查点位走同一索引。

    /** S04 按照地区筛选：已有点位的省份清单（省份表），带每省点位数。 */
    @Query(
        "SELECT province AS name, COUNT(*) AS placeCount FROM place " +
            "WHERE province IS NOT NULL AND province != '' " +
            "GROUP BY province ORDER BY placeCount DESC, name",
    )
    fun observeProvinceCounts(): Flow<List<RegionCountRow>>

    /** S04 按照地区筛选：每个省份下的已用城市（省份-城市记录表），带数量。 */
    @Query(
        "SELECT province, city, COUNT(*) AS placeCount FROM place " +
            "WHERE province IS NOT NULL AND province != '' AND city IS NOT NULL AND city != '' " +
            "GROUP BY province, city ORDER BY province, placeCount DESC, city",
    )
    fun observeProvinceCityCounts(): Flow<List<RegionCityCountRow>>

    /** 自愈（第七轮）：region 还没回填的点位，分批取。 */
    @Query(
        "SELECT * FROM place WHERE province IS NULL OR province = '' OR city IS NULL OR city = '' " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun getPlacesMissingRegion(limit: Int): List<PlaceEntity>

    @Query("SELECT COUNT(*) FROM place WHERE province IS NULL OR province = '' OR city IS NULL OR city = ''")
    suspend fun countMissingRegion(): Int

    /** S04「地区未知」胶囊（第十六轮）：region 信息不全的点位数，实时流。 */
    @Query("SELECT COUNT(*) FROM place WHERE province IS NULL OR province = '' OR city IS NULL OR city = ''")
    fun observeUnknownRegionCount(): Flow<Int>

    /**
     * 自愈回填一行的 province/city（第十六轮：不再刷新 updated_at——后台自愈不该把
     * 「最近编辑」类排序/展示打乱； province/city 不是用户编辑的内容）。
     */
    @Query("UPDATE place SET province = :province, city = :city WHERE id = :id")
    suspend fun setRegion(id: String, province: String?, city: String?)
}

/** 「省份表」聚合行（第七轮）：name=省份名，placeCount=该省已有点位数。 */
data class RegionCountRow(val name: String, val placeCount: Int)

/** 「省份-城市记录表」聚合行（第七轮）。 */
data class RegionCityCountRow(
    val province: String,
    val city: String,
    val placeCount: Int,
)

/** 点位在清单里的行（S28 列表用）。 */
data class PlaceInListRow(
    @androidx.room.Embedded val place: PlaceEntity,
    @androidx.room.ColumnInfo(name = "added_at_in_list") val addedAtInList: Long,
)

@Dao
interface PlaceListDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(placeList: PlaceListEntity)

    @Query("DELETE FROM place_list WHERE place_id = :placeId AND list_id = :listId")
    suspend fun remove(placeId: String, listId: String)

    @Query(
        "SELECT p.*, pl.added_at AS added_at_in_list FROM place_list pl " +
            "JOIN place p ON p.id = pl.place_id WHERE pl.list_id = :listId " +
            "ORDER BY pl.added_at DESC",
    )
    suspend fun placesInList(listId: String): List<PlaceInListRow>

    /** S28 清单详情（第七轮）：清单内点位的实时流（JOIN 同时观察 place/place_list 两表）。 */
    @Query(
        "SELECT p.*, pl.added_at AS added_at_in_list FROM place_list pl " +
            "JOIN place p ON p.id = pl.place_id WHERE pl.list_id = :listId " +
            "ORDER BY pl.added_at DESC",
    )
    fun observePlacesInList(listId: String): Flow<List<PlaceInListRow>>

    /** S28 添加浮层：某清单的成员 id 集合（排除已加入用）。 */
    @Query("SELECT place_id FROM place_list WHERE list_id = :listId")
    fun observePlaceIdsInList(listId: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM place_list WHERE list_id = :listId")
    suspend fun countInList(listId: String): Int

    @Query("DELETE FROM place_list WHERE list_id = :listId")
    suspend fun deleteAllForList(listId: String)

    /** 备份导出：全部点位↔清单关联。 */
    @Query("SELECT place_id AS place_id, list_id AS list_id, added_at AS added_at FROM place_list")
    suspend fun allForBackup(): List<com.visualzyzy.chuxingdazi.data.db.dao.PlaceListBackupRow>

    /** 清空所有数据（S13）。 */
    @Query("DELETE FROM place_list")
    suspend fun deleteAll()

    @Query("SELECT list.* FROM place_list pl JOIN list ON list.id = pl.list_id WHERE pl.place_id = :placeId ORDER BY pl.added_at DESC")
    suspend fun listsForPlace(placeId: String): List<ListEntity>
}

/** 备份导出用的行（§5.5 placeLists）。 */
data class PlaceListBackupRow(
    @androidx.room.ColumnInfo(name = "place_id") val placeId: String,
    @androidx.room.ColumnInfo(name = "list_id") val listId: String,
    @androidx.room.ColumnInfo(name = "added_at") val addedAt: Long,
)
