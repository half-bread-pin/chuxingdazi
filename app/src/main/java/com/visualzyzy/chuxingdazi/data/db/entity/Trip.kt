package com.visualzyzy.chuxingdazi.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 行程（§5.2 trip 表）。有日期的出行计划，独立实体，不依附清单。
 * start_date/end_date 为 yyyy-MM-dd 文本，可空 = 未定日期；应用层保证 end >= start。
 */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "start_date")
    val startDate: String?,
    @ColumnInfo(name = "end_date")
    val endDate: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * 行程 ↔ 点位（§5.2 trip_place 表）。行程自己的点位集合，与清单无关。
 * 复合主键天然挡住「同一点位重复加入同一行程」；删任一侧 → 级联删。
 */
@Entity(
    tableName = "trip_place",
    primaryKeys = ["trip_id", "place_id"],
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["place_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["place_id"], name = "idx_trip_place_place")], // 反查"这个点位在哪些行程里"
)
data class TripPlaceEntity(
    @ColumnInfo(name = "trip_id")
    val tripId: String,
    @ColumnInfo(name = "place_id")
    val placeId: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long, // 待安排池按它排
)

/**
 * 行程安排/时间轴上的一段（§5.2 schedule 表 + §5.3）。
 *
 * 关键约定：
 * - 一个点位在同一行程里可有多段（出发/返程同一车站是常态），彼此独立、无耦合；
 * - plan_date 为起始日 yyyy-MM-dd；start_min 为当天 00:00 起分钟数（0–1439）；
 * - **end_min 允许 ≥1440 表示跨天**（22:00–次日 01:30 存 1320/1530），必须 > start_min，≤ 30 天；
 * - schedule 的 (trip_id, place_id) 必须已存在于 trip_place —— 应用层保证（写入前校验）；
 * - plan_date 允许落在行程日期区间之外，只提示不拦。
 */
@Entity(
    tableName = "schedule",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["place_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trip_id", "plan_date", "start_min"], name = "idx_schedule_trip"),
        Index(value = ["place_id"], name = "idx_schedule_place"),
    ],
)
data class ScheduleEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "trip_id")
    val tripId: String,
    @ColumnInfo(name = "place_id")
    val placeId: String,
    @ColumnInfo(name = "plan_date")
    val planDate: String, // 起始日 yyyy-MM-dd
    @ColumnInfo(name = "start_min")
    val startMin: Int, // 0–1439
    @ColumnInfo(name = "end_min")
    val endMin: Int, // (start_min, start_min + 43200]，≥1440 即跨天
    @ColumnInfo(name = "sort_hint")
    val sortHint: Int = 0, // 同一 start_min 的先后；v1 无 UI 写入、恒为 0，靠 created_at 兜底
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    /** 跨了几个自然日（§5.3：end_min-1 参与计算，避免 0 分钟尾巴算进后一天）。 */
    val spannedDays: Int
        get() = (endMin - 1) / 1440
}

/**
 * P7 交通腿结果缓存（时间轴上用户主动点击「计算交通耗时」的结果）：
 * - 不属业务数据：备份导出/导入不含本表，清空数据时随之清空；
 * - id = "fromPlaceId_toPlaceId_MODE"，同对同方式覆盖更新；MANUAL = 用户手填（distanceM=0）；
 * - 结果按「当前时刻/当前路况」查询（SDK 公交不支持传出发时间，UI 已如实注明）。
 */
@Entity(tableName = "traffic_leg_cache")
data class TrafficLegCacheEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "from_place_id")
    val fromPlaceId: String,
    @ColumnInfo(name = "to_place_id")
    val toPlaceId: String,
    @ColumnInfo(name = "mode")
    val mode: String, // TravelMode 名称或 MANUAL
    @ColumnInfo(name = "minutes")
    val minutes: Int,
    @ColumnInfo(name = "distance_m")
    val distanceM: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
