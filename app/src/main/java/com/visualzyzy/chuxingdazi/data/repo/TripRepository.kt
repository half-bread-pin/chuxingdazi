package com.visualzyzy.chuxingdazi.data.repo

import androidx.room.withTransaction
import com.visualzyzy.chuxingdazi.data.db.ChuxingdaziDatabase
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripPlaceDao
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** schedule 写入校验失败（§5.3 字段约定 + 应用层不变式）。 */
class InvalidScheduleException(message: String) : Exception(message)

interface TripRepository {
    fun observeAll(): Flow<List<TripEntity>>
    suspend fun getById(id: String): TripEntity?

    /** 创建行程：写 trip + 若干 trip_place，然后直接进时间轴（§7.6）。 */
    suspend fun create(name: String, startDate: String?, endDate: String?, initialPlaceIds: List<String> = emptyList()): TripEntity
    suspend fun rename(id: String, newName: String)

    /**
     * 第十六轮（2026-11-14）：未定日期行程的「选定这几天」——用户显式选定起止日期（覆盖语义）。
     * 第三十二轮（2026-11-15，用户反馈 222.jpg）：改日期后，区间外的安排**移回待安排**
     * ——行程区间收窄后，凡整段（含跨天尾）不落在新区间内的 schedule 一律删除
     * （点位仍在行程里，待安排池按「无 schedule」口径自然回池）；@return 删除的段数。
     */
    suspend fun updateDates(id: String, startDate: String, endDate: String): Int

    /** 删行程：trip_place 与该行程全部 schedule 级联删，点位本身不动。 */
    suspend fun delete(tripId: String)

    suspend fun addPlaces(tripId: String, placeIds: List<String>)

    /**
     * 把点位移出行程（§5.3）：**必须同事务**删 trip_place 那行 + 该行程下这个点位的全部 schedule，
     * 否则出现中间态破坏「有安排必在行程里」的不变式。
     */
    suspend fun removePlace(tripId: String, placeId: String)

    suspend fun placesForTrip(tripId: String): List<com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity>

    /** 待安排池（§5.3 钦点查询）。 */
    suspend fun unscheduledPlaces(tripId: String): List<com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity>

    /** S23 行程卡：各行程的点位计数。 */
    suspend fun placeCountByTrip(): Map<String, Int>

    /** S23 行程卡：各行程点位计数（响应式流）。 */
    fun observePlaceCountByTrip(): Flow<List<com.visualzyzy.chuxingdazi.data.db.dao.TripCountRow>>

    suspend fun schedulesForTrip(tripId: String): List<com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithPlaceRow>

    /** 按天取安排（跨天段绝对分钟区间重叠，§5.3 核心查询 1）。 */
    suspend fun schedulesForDay(tripId: String, day: String): List<com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithPlaceRow>

    /** S07「已安排」区块（2026-11-24 实装）：带行程名。 */
    suspend fun schedulesForPlace(placeId: String): List<com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithTripRow>

    /**
     * 新建一段安排，写入前做应用层校验（§5.3）：
     * - (trip_id, place_id) 必须已存在于 trip_place；
     * - startMin ∈ [0,1439]；endMin ∈ (startMin, startMin + 43200]（必须 > start，≤ 30 天）；
     * - plan_date 允许落在行程日期区间之外（只提示不拦，由 UI 层处理）。
     * @throws InvalidScheduleException
     */
    suspend fun addSchedule(schedule: ScheduleEntity): ScheduleEntity

    suspend fun updateSchedule(schedule: ScheduleEntity)
    suspend fun deleteSchedule(id: String)
}

@Singleton
class TripRepositoryImpl @Inject constructor(
    private val db: ChuxingdaziDatabase,
    private val tripDao: TripDao,
    private val tripPlaceDao: TripPlaceDao,
    private val scheduleDao: ScheduleDao,
) : TripRepository {

    override fun observeAll(): Flow<List<TripEntity>> = tripDao.observeAll()

    override suspend fun getById(id: String): TripEntity? = tripDao.getById(id)

    override suspend fun create(
        name: String,
        startDate: String?,
        endDate: String?,
        initialPlaceIds: List<String>,
    ): TripEntity {
        require(endDate == null || startDate == null || endDate >= startDate) { "end_date 必须 >= start_date" }
        val now = System.currentTimeMillis()
        val trip = TripEntity(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            startDate = startDate,
            endDate = endDate,
            createdAt = now,
            updatedAt = now,
        )
        db.withTransaction {
            tripDao.insert(trip)
            initialPlaceIds.forEach { placeId ->
                tripPlaceDao.insertIgnore(TripPlaceEntity(trip.id, placeId, System.currentTimeMillis()))
            }
        }
        return trip
    }

    override suspend fun rename(id: String, newName: String) {
        val current = tripDao.getById(id) ?: return
        tripDao.update(current.copy(name = newName.trim(), updatedAt = System.currentTimeMillis()))
    }

    override suspend fun updateDates(id: String, startDate: String, endDate: String): Int {
        val current = tripDao.getById(id) ?: return 0
        // 第三十二轮（2026-11-15）：区间外安排随改日期一并移回待安排——
        // 与「移出时间轴」同语义（只删 schedule 行，点位保留在行程里），同事务保证原子性。
        var removed = 0
        db.withTransaction {
            tripDao.update(current.copy(startDate = startDate, endDate = endDate, updatedAt = System.currentTimeMillis()))
            scheduleDao.forTrip(id).forEach { row ->
                val sch = row.schedule
                val planDate = runCatching { java.time.LocalDate.parse(sch.planDate) }.getOrNull()
                val newStart = runCatching { java.time.LocalDate.parse(startDate) }.getOrNull()
                val newEnd = runCatching { java.time.LocalDate.parse(endDate) }.getOrNull()
                if (planDate == null || newStart == null || newEnd == null) return@forEach // 解析失败不误删
                // 跨天段按整段算：结束日 = plan_date + (end_min-1)/1440 天（§5.3 spannedDays 口径）
                val endDay = planDate.plusDays(sch.spannedDays.toLong())
                val inRange = !planDate.isBefore(newStart) && !endDay.isAfter(newEnd)
                if (!inRange) {
                    scheduleDao.deleteById(sch.id)
                    removed++
                }
            }
        }
        return removed
    }

    override suspend fun delete(tripId: String) {
        tripDao.getById(tripId)?.let { tripDao.delete(it) }
    }

    override suspend fun addPlaces(tripId: String, placeIds: List<String>) {
        db.withTransaction {
            placeIds.forEach { placeId ->
                tripPlaceDao.insertIgnore(TripPlaceEntity(tripId, placeId, System.currentTimeMillis()))
            }
        }
    }

    override suspend fun removePlace(tripId: String, placeId: String) {
        db.withTransaction {
            tripPlaceDao.remove(tripId, placeId)
            scheduleDao.deleteForTripPlace(tripId, placeId)
        }
    }

    override suspend fun placeCountByTrip(): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        tripDao.getAll().forEach { trip ->
            map[trip.id] = tripPlaceDao.countInTrip(trip.id)
        }
        return map
    }

    override fun observePlaceCountByTrip() = tripPlaceDao.observeCountByTrip()

    override suspend fun placesForTrip(tripId: String) = tripPlaceDao.placesForTrip(tripId)

    override suspend fun unscheduledPlaces(tripId: String) = tripPlaceDao.unscheduledPlaces(tripId)

    override suspend fun schedulesForTrip(tripId: String) = scheduleDao.forTrip(tripId)

    override suspend fun schedulesForDay(tripId: String, day: String) = scheduleDao.forDay(tripId, day)

    override suspend fun schedulesForPlace(placeId: String) = scheduleDao.forPlaceWithTrip(placeId)

    override suspend fun addSchedule(schedule: ScheduleEntity): ScheduleEntity {
        if (schedule.startMin !in 0..1439) {
            throw InvalidScheduleException("startMin 必须在 0–1439：${schedule.startMin}")
        }
        if (schedule.endMin < schedule.startMin || schedule.endMin > schedule.startMin + 43200) {
            // 决策 #66：0 分钟安排（endMin == startMin）合法、落库按最短块渲染
            throw InvalidScheduleException("endMin 必须在 [startMin, startMin + 43200]：${schedule.endMin}")
        }
        val member = tripPlaceDao.placesForTrip(schedule.tripId).any { it.id == schedule.placeId }
        if (!member) {
            throw InvalidScheduleException("点位必须先加入行程（trip_place）才能安排时间")
        }
        db.withTransaction { scheduleDao.insert(schedule) }
        return schedule
    }

    override suspend fun updateSchedule(schedule: ScheduleEntity) {
        // 决策 #66：0 分钟安排（endMin == startMin）合法
        require(schedule.endMin >= schedule.startMin && schedule.endMin <= schedule.startMin + 43200) {
            "endMin 必须在 [startMin, startMin + 43200]"
        }
        db.withTransaction {
            scheduleDao.update(schedule)
        }
    }

    override suspend fun deleteSchedule(id: String) {
        scheduleDao.deleteById(id)
    }
}
