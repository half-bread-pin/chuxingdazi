package com.visualzyzy.chuxingdazi.data.repo

import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleDao
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithPlaceRow
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithTripRow
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import javax.inject.Inject
import javax.inject.Singleton

/** 时间轴/安排的读侧仓库（M8 时间轴与 S15 安排面板）。 */
@Singleton
class ScheduleRepository @Inject constructor(
    private val scheduleDao: ScheduleDao,
) {
    fun countByTrip() = scheduleDao.countByTripFlow()

    suspend fun forTrip(tripId: String): List<ScheduleWithPlaceRow> = scheduleDao.forTrip(tripId)

    suspend fun forDay(tripId: String, day: String): List<ScheduleWithPlaceRow> = scheduleDao.forDay(tripId, day)

    /** S07「已安排」区块（2026-11-24 实装）：带行程名。 */
    suspend fun forPlace(placeId: String): List<ScheduleWithTripRow> = scheduleDao.forPlaceWithTrip(placeId)

    suspend fun byId(id: String): ScheduleEntity? = scheduleDao.getById(id)

    suspend fun insert(schedule: ScheduleEntity) = scheduleDao.insert(schedule)

    suspend fun update(schedule: ScheduleEntity) = scheduleDao.update(schedule)

    suspend fun deleteById(id: String) = scheduleDao.deleteById(id)

    suspend fun deleteForTripPlace(tripId: String, placeId: String) =
        scheduleDao.deleteForTripPlace(tripId, placeId)

    suspend fun countForPlace(placeId: String) = scheduleDao.countForPlace(placeId)
}
