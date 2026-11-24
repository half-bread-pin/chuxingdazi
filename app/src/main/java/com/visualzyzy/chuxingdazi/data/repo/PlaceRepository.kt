package com.visualzyzy.chuxingdazi.data.repo

import android.database.sqlite.SQLiteConstraintException
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceDao
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripPlaceDao
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** UNIQUE(name, lat, lng) 兜底命中：同名 + 坐标逐位相同（同一 POI 重复 / 并发插入）。 */
class DuplicatePlaceException(message: String) : Exception(message)

/** S07 删除确认文案需要的两个数字（§6 S07：N 个行程里还有 M 段安排）。 */
data class DeleteImpact(val tripCount: Int, val scheduleCount: Int)

interface PlaceRepository {
    fun observeAll(): Flow<List<PlaceEntity>>
    fun observeById(id: String): Flow<PlaceEntity?>
    suspend fun getById(id: String): PlaceEntity?
    suspend fun getAll(): List<PlaceEntity>
    suspend fun getByCategory(categoryId: String): List<PlaceEntity>

    suspend fun add(place: PlaceEntity): PlaceEntity

    /** 应用层硬拦（主力，§14.1 #34）：同名 + 距离 <5m 视为同一点位。 */
    suspend fun findNearbyDuplicate(name: String, lat: Double, lng: Double): PlaceEntity?

    /**
     * 第十六轮（软提示）：5 米内的**任意**已有点位（不管名称）——搜索「坐标很近、名称不同」
     * 的软提示与 S06/S08 保存坐标前的确认共用。excludePlaceId 用于调整坐标（排除自身）。
     */
    suspend fun findVeryClosePlace(lat: Double, lng: Double, excludePlaceId: String? = null): PlaceEntity?

    suspend fun update(place: PlaceEntity)
    suspend fun setVisited(id: String, visited: Boolean)
    suspend fun delete(placeId: String)
    suspend fun impactOfDelete(placeId: String): DeleteImpact
    suspend fun findByPoiUid(poiUid: String): PlaceEntity?

    /** S07 标签行：该点位所属的清单。 */
    suspend fun listsOfPlace(placeId: String): List<ListEntity>

    // ---- 区域记录（第七轮 2026-11-06）----

    /** 「省份表」：已有点位的省份聚合（S04 按照地区筛选）。 */
    fun observeProvinceCounts(): Flow<List<com.visualzyzy.chuxingdazi.data.db.dao.RegionCountRow>>

    /** 「省份-城市记录表」：每省已用的城市聚合（S04 按照地区筛选）。 */
    fun observeProvinceCityCounts(): Flow<List<com.visualzyzy.chuxingdazi.data.db.dao.RegionCityCountRow>>

    /** 「地区未知」点位数（第十六轮：S04 地区胶囊新增项，自愈完成前这些点也能被筛出）。 */
    fun observeUnknownRegionCount(): Flow<Int>
}

@Singleton
class PlaceRepositoryImpl @Inject constructor(
    private val placeDao: PlaceDao,
    private val placeListDao: com.visualzyzy.chuxingdazi.data.db.dao.PlaceListDao,
    private val tripPlaceDao: TripPlaceDao,
    private val scheduleDao: ScheduleDao,
    private val trafficLegDao: com.visualzyzy.chuxingdazi.data.db.dao.TrafficLegDao,
) : PlaceRepository {

    override fun observeAll(): Flow<List<PlaceEntity>> = placeDao.observeAll()

    override fun observeById(id: String): Flow<PlaceEntity?> = placeDao.observeById(id)

    override suspend fun getById(id: String): PlaceEntity? = placeDao.getById(id)

    override suspend fun getAll(): List<PlaceEntity> = placeDao.getAll()

    override suspend fun getByCategory(categoryId: String): List<PlaceEntity> = placeDao.getByCategory(categoryId)

    override suspend fun add(place: PlaceEntity): PlaceEntity = try {
        placeDao.insert(place)
        place
    } catch (e: SQLiteConstraintException) {
        throw DuplicatePlaceException("已存在同名且坐标相同的点位：${place.name}")
    }

    override suspend fun findNearbyDuplicate(name: String, lat: Double, lng: Double): PlaceEntity? =
        placeDao.findSameName(name).firstOrNull { distanceMeters(it.lat, it.lng, lat, lng) < 5.0 }

    override suspend fun findVeryClosePlace(lat: Double, lng: Double, excludePlaceId: String?): PlaceEntity? =
        placeDao.getAll().minByOrNull { distanceMeters(it.lat, it.lng, lat, lng) }
            ?.takeIf { distanceMeters(it.lat, it.lng, lat, lng) < 5.0 && it.id != excludePlaceId }

    override suspend fun update(place: PlaceEntity) = placeDao.update(place)

    override suspend fun setVisited(id: String, visited: Boolean) {
        val now = System.currentTimeMillis()
        // 第十六轮：到访动作记 visited_at；取消到访清空
        placeDao.setVisited(id, visited, if (visited) now else null, now)
    }

    /**
     * 删点位（§5.3 删除语义）：级联带走它在**所有行程**里的 trip_place 与 schedule；
     * 行程本身不动，即使因此变空也保留。traffic_leg_cache 无外键（P7），失效腿随手清
     * （第十六轮）。
     */
    override suspend fun delete(placeId: String) {
        placeDao.deleteById(placeId)
        trafficLegDao.deleteForPlace(placeId)
    }

    override suspend fun impactOfDelete(placeId: String): DeleteImpact =
        DeleteImpact(
            tripCount = tripPlaceDao.countTripsForPlace(placeId),
            scheduleCount = scheduleDao.countForPlace(placeId),
        )

    override suspend fun findByPoiUid(poiUid: String): PlaceEntity? = placeDao.findByPoiUid(poiUid)

    override suspend fun listsOfPlace(placeId: String): List<ListEntity> = placeListDao.listsForPlace(placeId)

    override fun observeProvinceCounts(): Flow<List<com.visualzyzy.chuxingdazi.data.db.dao.RegionCountRow>> =
        placeDao.observeProvinceCounts()

    override fun observeProvinceCityCounts(): Flow<List<com.visualzyzy.chuxingdazi.data.db.dao.RegionCityCountRow>> =
        placeDao.observeProvinceCityCounts()

    override fun observeUnknownRegionCount(): Flow<Int> = placeDao.observeUnknownRegionCount()

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
