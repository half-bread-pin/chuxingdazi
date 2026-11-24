package com.visualzyzy.chuxingdazi.data.db

import com.visualzyzy.chuxingdazi.data.db.dao.PlaceDao
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripPlaceDao
import com.visualzyzy.chuxingdazi.data.repo.TripRepositoryImpl
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * §5.3 删除语义 + §13 验收清单（数据库条目）：
 * - trip_place 复合主键挡重复加入
 * - 待安排池 NOT EXISTS
 * - 移出行程必须同事务删 trip_place + schedule
 * - 删行程 / 删点位的级联行为
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TripRelationTest {

    private lateinit var db: ChuxingdaziDatabase
    private lateinit var tripRepository: TripRepositoryImpl
    private lateinit var tripPlaceDao: TripPlaceDao
    private lateinit var scheduleDao: ScheduleDao
    private lateinit var placeDao: PlaceDao
    private lateinit var tripDao: TripDao

    @Before
    fun setUp() {
        db = TestDb.create()
        tripRepository = TripRepositoryImpl(db, db.tripDao(), db.tripPlaceDao(), db.scheduleDao())
        tripPlaceDao = db.tripPlaceDao()
        scheduleDao = db.scheduleDao()
        placeDao = db.placeDao()
        tripDao = db.tripDao()
    }

    @After
    fun tearDown() {
        TestDb.closeDb(db)
    }

    private suspend fun seedTripWithTwoPlaces(): Pair<String, List<String>> {
        placeDao.insert(TestDb.place("p1", "西湖"))
        placeDao.insert(TestDb.place("p2", "灵隐寺"))
        val trip = tripRepository.create("杭州一日", "2026-10-31", "2026-10-31", listOf("p1", "p2"))
        return trip.id to listOf("p1", "p2")
    }

    @Test
    fun `复合主键挡住同一点位重复加入同一行程`() = runTest {
        placeDao.insert(TestDb.place("p1", "西湖"))

        val tripId = tripRepository.create("第二趟", null, null, listOf("p1")).id
        tripRepository.addPlaces(tripId, listOf("p1"))
        tripRepository.addPlaces(tripId, listOf("p1"))

        assertEquals(1, tripPlaceDao.placesForTrip(tripId).size)
    }

    @Test
    fun `待安排池只含没有安排的点位`() = runTest {
        val (tripId, places) = seedTripWithTwoPlaces()
        assertEquals(places, tripRepository.unscheduledPlaces(tripId).map { it.id }.sorted())

        // 排了 p1 的第一段后，p1 出池
        tripRepository.addSchedule(TestDb.schedule("s1", tripId, "p1", "2026-10-31", 570, 600))
        assertEquals(listOf("p2"), tripRepository.unscheduledPlaces(tripId).map { it.id })

        // 同一点位排第二段，池子不变（§6 S14：已安排的不再出现在池里）
        tripRepository.addSchedule(TestDb.schedule("s2", tripId, "p1", "2026-10-31", 780, 840))
        assertEquals(listOf("p2"), tripRepository.unscheduledPlaces(tripId).map { it.id })

        // 删掉 p1 的全部安排后回到池里（§13：删最后一段后重新出现）
        tripRepository.deleteSchedule("s1")
        tripRepository.deleteSchedule("s2")
        assertEquals(places, tripRepository.unscheduledPlaces(tripId).map { it.id }.sorted())
    }

    @Test
    fun `未加入行程的点位不能安排时间`() = runTest {
        placeDao.insert(TestDb.place("p_out", "库外点位"))
        val tripId = tripRepository.create("一日", null, null).id
        val result = runCatching {
            tripRepository.addSchedule(TestDb.schedule("s1", tripId, "p_out", "2026-10-31", 570, 600))
        }
        assertTrue(result.exceptionOrNull() is com.visualzyzy.chuxingdazi.data.repo.InvalidScheduleException)
    }

    @Test
    fun `安排的 endMin 不得早于 startMin 且不超 30 天`() = runTest {
        placeDao.insert(TestDb.place("p1", "西湖"))
        val tripId = tripRepository.create("一日", null, null, listOf("p1")).id

        assertTrue(
            runCatching {
                tripRepository.addSchedule(TestDb.schedule("s1", tripId, "p1", "2026-10-31", 600, 590))
            }.exceptionOrNull() is com.visualzyzy.chuxingdazi.data.repo.InvalidScheduleException,
        )
        // 决策 #66：0 分钟安排（开始=结束）合法、落库
        assertNotNull(tripRepository.addSchedule(TestDb.schedule("s0", tripId, "p1", "2026-10-31", 600, 600)))
        assertTrue(
            runCatching {
                tripRepository.addSchedule(TestDb.schedule("s1", tripId, "p1", "2026-10-31", 600, 600 + 43201))
            }.exceptionOrNull() is com.visualzyzy.chuxingdazi.data.repo.InvalidScheduleException,
        )
        // 跨 30 天（43200 分钟）合法
        assertNotNull(tripRepository.addSchedule(TestDb.schedule("s2", tripId, "p1", "2026-10-31", 600, 600 + 43200)))
    }

    @Test
    fun `移出行程同事务删掉该行程下该点位的全部安排，其他行程不受影响`() = runTest {
        placeDao.insert(TestDb.place("p1", "杭州东站"))
        val trip1 = tripRepository.create("行一", null, null, listOf("p1")).id
        val trip2 = tripRepository.create("行二", null, null, listOf("p1")).id
        scheduleDao.insert(TestDb.schedule("s1", trip1, "p1", "2026-10-31", 570, 600))
        scheduleDao.insert(TestDb.schedule("s2", trip1, "p1", "2026-10-31", 780, 840))
        scheduleDao.insert(TestDb.schedule("s3", trip2, "p1", "2026-11-05", 570, 600))

        tripRepository.removePlace(trip1, "p1")

        // 行一：关系与安排全删
        assertNull(tripRepository.schedulesForTrip(trip1).firstOrNull())
        assertTrue(tripRepository.unscheduledPlaces(trip1).isEmpty())
        // 行二：不受影响
        assertEquals(listOf("s3"), tripRepository.schedulesForTrip(trip2).map { it.schedule.id })
        // 点位本身还在库里
        assertNotNull(placeDao.getById("p1"))
    }

    @Test
    fun `删行程只删关系与安排，点位保留且行程变空也保留`() = runTest {
        val (tripId, _) = seedTripWithTwoPlaces()
        tripRepository.addSchedule(TestDb.schedule("s1", tripId, "p1", "2026-10-31", 570, 600))

        tripRepository.delete(tripId)

        assertNull(tripDao.getById(tripId))
        assertTrue(tripPlaceDao.placesForTrip(tripId).isEmpty())
        assertEquals(0, scheduleDao.countForPlace("p1"))
        assertNotNull(placeDao.getById("p1"))
        assertNotNull(placeDao.getById("p2"))
    }

    @Test
    fun `删点位级联带走所有行程里的关系与安排，行程保留`() = runTest {
        placeDao.insert(TestDb.place("p1", "杭州东站"))
        placeDao.insert(TestDb.place("p2", "西湖"))
        val trip1 = tripRepository.create("行一", null, null, listOf("p1", "p2")).id
        val trip2 = tripRepository.create("行二", null, null, listOf("p1")).id
        scheduleDao.insert(TestDb.schedule("s1", trip1, "p1", "2026-10-31", 570, 600))
        scheduleDao.insert(TestDb.schedule("s2", trip2, "p1", "2026-11-05", 570, 600))
        scheduleDao.insert(TestDb.schedule("s3", trip1, "p2", "2026-10-31", 780, 840))

        val impact = tripRepository.run {
            com.visualzyzy.chuxingdazi.data.repo.PlaceRepositoryImpl(
                db.placeDao(), db.placeListDao(), db.tripPlaceDao(), db.scheduleDao(), db.trafficLegDao(),
            ).impactOfDelete("p1")
        }
        assertEquals(2, impact.tripCount)
        assertEquals(2, impact.scheduleCount)

        placeDao.deleteById("p1")

        assertNull(placeDao.getById("p1"))
        assertTrue(tripRepository.schedulesForTrip(trip1).map { it.schedule.id }.containsAll(listOf("s3")))
        assertTrue(tripRepository.schedulesForTrip(trip2).isEmpty())
        assertNotNull(tripDao.getById(trip1)) // 行程不删，即使变空
        assertNotNull(placeDao.getById("p2"))
    }
}
