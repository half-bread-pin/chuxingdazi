package com.visualzyzy.chuxingdazi.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Update
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TrafficLegCacheEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(trip: TripEntity)

    @Update
    suspend fun update(trip: TripEntity)

    @Delete
    suspend fun delete(trip: TripEntity)

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun getById(id: String): TripEntity?

    @Query("SELECT * FROM trip")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip")
    suspend fun getAll(): List<TripEntity>

    /** 清空所有数据（S13）。 */
    @Query("DELETE FROM trip")
    suspend fun deleteAll()
}

/** P7 交通腿结果缓存（用户点击计算，持久化以便重启后仍显示；备份不含此表）。 */
@Dao
interface TrafficLegDao {

    @Query("SELECT * FROM traffic_leg_cache")
    fun observeAll(): Flow<List<TrafficLegCacheEntity>>

    @Upsert
    suspend fun upsert(entity: TrafficLegCacheEntity)

    @Query("DELETE FROM traffic_leg_cache WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM traffic_leg_cache")
    suspend fun deleteAll()

    /** 删掉与某点位相关的全部缓存腿（第十六轮：点位删除后表无外键，失效腿随手清）。 */
    @Query("DELETE FROM traffic_leg_cache WHERE from_place_id = :placeId OR to_place_id = :placeId")
    suspend fun deleteForPlace(placeId: String)

    /** 导入后清理失效腿（第十六轮：from/to 任一端点位已不存在的腿）。 */
    @Query(
        "DELETE FROM traffic_leg_cache WHERE " +
            "from_place_id NOT IN (SELECT id FROM place) OR to_place_id NOT IN (SELECT id FROM place)",
    )
    suspend fun deleteStaleLegs()
}

@Dao
interface TripPlaceDao {

    /** IGNORE：复合主键天然挡住重复加入，第二次调用静默跳过（验收清单）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tripPlace: TripPlaceEntity)

    @Query("DELETE FROM trip_place WHERE trip_id = :tripId AND place_id = :placeId")
    suspend fun remove(tripId: String, placeId: String)

    /** 备份导出（§5.5 tripPlaces，全量含真实 added_at——待安排池按它排）。 */
    @Query("SELECT * FROM trip_place")
    suspend fun allForBackup(): List<TripPlaceEntity>

    @Query(
        "SELECT p.* FROM trip_place tp JOIN place p ON p.id = tp.place_id " +
            "WHERE tp.trip_id = :tripId ORDER BY tp.added_at",
    )
    suspend fun placesForTrip(tripId: String): List<PlaceEntity>

    /**
     * 待安排池（§5.3 文档钦点 SQL）：属于该行程、但在该行程里没有任何 schedule 的点位。
     * 排了两次的点位也不在池子里——再排第三段从详情页「＋ 再安排一段」进（§6 S14）。
     */
    @Query(
        "SELECT p.* FROM trip_place tp JOIN place p ON p.id = tp.place_id " +
            "WHERE tp.trip_id = :tripId " +
            "AND NOT EXISTS (SELECT 1 FROM schedule s WHERE s.trip_id = :tripId AND s.place_id = tp.place_id) " +
            "ORDER BY tp.added_at",
    )
    suspend fun unscheduledPlaces(tripId: String): List<PlaceEntity>

    @Query("SELECT COUNT(DISTINCT trip_id) FROM trip_place WHERE place_id = :placeId")
    suspend fun countTripsForPlace(placeId: String): Int

    @Query("SELECT COUNT(*) FROM trip_place WHERE trip_id = :tripId")
    suspend fun countInTrip(tripId: String): Int

    /** 各行程点位数（S23 卡片角标，响应式）。 */
    @Query("SELECT trip_id AS trip_id, COUNT(*) AS cnt FROM trip_place GROUP BY trip_id")
    fun observeCountByTrip(): Flow<List<TripCountRow>>

    /** 清空所有数据（S13）。 */
    @Query("DELETE FROM trip_place")
    suspend fun deleteAll()

    @Query("DELETE FROM trip_place WHERE place_id = :placeId")
    suspend fun deleteAllForPlace(placeId: String)
}

/** 时间轴/按天查询的行：安排 + 点位名 + 分类（渲染色块需要）。 */
data class ScheduleWithPlaceRow(
    @Embedded val schedule: ScheduleEntity,
    @androidx.room.ColumnInfo(name = "place_name") val placeName: String,
    @androidx.room.ColumnInfo(name = "category_id") val categoryId: String,
)

/** S07「已安排」区块行（2026-11-24 实装）：带行程名。
 *  不并入 [ScheduleWithPlaceRow]——它被 forTrip/forDay 两个查询共用，加字段会连累编译。 */
data class ScheduleWithTripRow(
    @Embedded val schedule: ScheduleEntity,
    @androidx.room.ColumnInfo(name = "trip_name") val tripName: String,
)

/** S23 行程卡计数角标行。 */
data class TripCountRow(
    @androidx.room.ColumnInfo(name = "trip_id") val tripId: String,
    @androidx.room.ColumnInfo(name = "cnt") val cnt: Int,
)

@Dao
interface ScheduleDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(schedule: ScheduleEntity)

    @Update
    suspend fun update(schedule: ScheduleEntity)

    @Query("DELETE FROM schedule WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 备份导出（§5.5 schedules）。 */
    @Query("SELECT * FROM schedule")
    suspend fun allForBackup(): List<ScheduleEntity>

    /** 清空所有数据（S13）。 */
    @Query("DELETE FROM schedule")
    suspend fun deleteAll()

    @Query("SELECT * FROM schedule WHERE id = :id")
    suspend fun getById(id: String): ScheduleEntity?

    /** 时间轴整轴：按绝对时间排序（§5.3 核心查询 2）。 */
    @Query(
        "SELECT s.*, p.name AS place_name, p.category_id AS category_id " +
            "FROM schedule s JOIN place p ON p.id = s.place_id " +
            "WHERE s.trip_id = :tripId ORDER BY s.plan_date, s.start_min, s.sort_hint, s.created_at",
    )
    suspend fun forTrip(tripId: String): List<ScheduleWithPlaceRow>

    /**
     * 按天取安排（§5.3 核心查询 1，文档钦点 SQL）：
     * 跨天段用绝对分钟区间重叠判断——end_min-1 参与跨天天数计算，
     * 不能只 plan_date = :day，否则跨天段在后续天里查不到。
     */
    @Query(
        "SELECT s.*, p.name AS place_name, p.category_id AS category_id " +
            "FROM schedule s JOIN place p ON p.id = s.place_id " +
            "WHERE s.trip_id = :tripId " +
            "AND s.plan_date <= :day " +
            "AND date(s.plan_date, '+' || ((s.end_min - 1) / 1440) || ' days') >= :day " +
            "ORDER BY s.start_min, s.sort_hint, s.created_at",
    )
    suspend fun forDay(tripId: String, day: String): List<ScheduleWithPlaceRow>

    /** S07「已安排」区块（2026-11-24 实装）：该点位在各行程里的全部时段，带行程名。
     *  旧 forPlace（JOIN place 取点位名）无 UI 调用者，直接替换。 */
    @Query(
        "SELECT s.*, t.name AS trip_name " +
            "FROM schedule s JOIN trip t ON t.id = s.trip_id " +
            "WHERE s.place_id = :placeId ORDER BY s.plan_date, s.start_min",
    )
    suspend fun forPlaceWithTrip(placeId: String): List<ScheduleWithTripRow>

    @Query("SELECT COUNT(*) FROM schedule WHERE place_id = :placeId")
    suspend fun countForPlace(placeId: String): Int

    /** S23 行程卡计数角标。 */
    @Query("SELECT trip_id, COUNT(*) AS cnt FROM schedule GROUP BY trip_id")
    fun countByTripFlow(): kotlinx.coroutines.flow.Flow<List<TripCountRow>>


    /** 「移出行程」事务的一半：删该行程下该点位的全部安排（§5.3：必须与删 trip_place 同事务）。 */
    @Query("DELETE FROM schedule WHERE trip_id = :tripId AND place_id = :placeId")
    suspend fun deleteForTripPlace(tripId: String, placeId: String)
}
