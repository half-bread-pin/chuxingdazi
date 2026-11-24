package com.visualzyzy.chuxingdazi.data.db

import androidx.test.core.app.ApplicationProvider
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceDao
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripPlaceDao
import com.visualzyzy.chuxingdazi.data.repo.BuiltinIds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * §5.3 时间轴数据约定 + §13 验收清单（数据库条目）：
 * - 跨天段（10/31 20:30 → 11/02 08:30）在 11/01、11/02 的按天列表里都能查到
 * - 区间左闭右开：end_min 恰为 1440 整数倍时只属于开始日
 * - 同一点位多段彼此独立
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScheduleQueryTest {

    private lateinit var db: ChuxingdaziDatabase
    private lateinit var scheduleDao: ScheduleDao
    private lateinit var tripPlaceDao: TripPlaceDao
    private lateinit var tripDao: TripDao
    private lateinit var placeDao: PlaceDao

    @Before
    fun setUp() {
        db = TestDb.create()
        scheduleDao = db.scheduleDao()
        tripPlaceDao = db.tripPlaceDao()
        tripDao = db.tripDao()
        placeDao = db.placeDao()
    }

    @After
    fun tearDown() {
        TestDb.closeDb(db)
    }

    @Test
    fun `跨三天的安排在中间天和结束天都能查到`() = runTest {
        tripDao.insert(TestDb.trip("t1", "露营"))
        placeDao.insert(TestDb.place("p1", "营地", BuiltinIds.OTHER))
        tripPlaceDao.insertIgnore(
            com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity("t1", "p1", 0),
        )
        // 10/31 20:30 → 11/02 08:30（跨 3 个自然日，§5.3 示例）
        scheduleDao.insert(TestDb.schedule("s1", "t1", "p1", "2026-10-31", 1230, 3390))

        assertEquals(1, scheduleDao.forDay("t1", "2026-10-31").size)
        assertEquals(1, scheduleDao.forDay("t1", "2026-11-01").size)
        assertEquals(1, scheduleDao.forDay("t1", "2026-11-02").size)
        assertEquals(0, scheduleDao.forDay("t1", "2026-10-30").size)
        assertEquals(0, scheduleDao.forDay("t1", "2026-11-03").size)
    }

    @Test
    fun `end_min 恰为 1440 时只属于开始日（左闭右开）`() = runTest {
        tripDao.insert(TestDb.trip("t1", "夜宵"))
        placeDao.insert(TestDb.place("p1", "大排档"))
        tripPlaceDao.insertIgnore(
            com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity("t1", "p1", 0),
        )
        // 10/31 22:00 – 24:00（end=1440，spannedDays=(1440-1)/1440=0）
        scheduleDao.insert(TestDb.schedule("s1", "t1", "p1", "2026-10-31", 1320, 1440))

        assertEquals(1, scheduleDao.forDay("t1", "2026-10-31").size)
        assertEquals(0, scheduleDao.forDay("t1", "2026-11-01").size)
    }

    @Test
    fun `同一点位的两段安排彼此独立且都返回`() = runTest {
        tripDao.insert(TestDb.trip("t1", "杭州两日"))
        placeDao.insert(TestDb.place("p1", "杭州东站"))
        tripPlaceDao.insertIgnore(
            com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity("t1", "p1", 0),
        )
        scheduleDao.insert(TestDb.schedule("s1", "t1", "p1", "2026-10-31", 570, 600))
        scheduleDao.insert(TestDb.schedule("s2", "t1", "p1", "2026-11-01", 1230, 1290))

        val all = scheduleDao.forTrip("t1")
        assertEquals(2, all.size)
        assertEquals(listOf("s1", "s2"), all.map { it.schedule.id })
        // 都关联到点位名
        assertTrue(all.all { it.placeName == "杭州东站" })
    }

    @Test
    fun `整轴按 plan_date 与 start_min 排序`() = runTest {
        tripDao.insert(TestDb.trip("t1", "两日"))
        placeDao.insert(TestDb.place("p1", "A"))
        placeDao.insert(TestDb.place("p2", "B"))
        tripPlaceDao.insertIgnore(
            com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity("t1", "p1", 0),
        )
        tripPlaceDao.insertIgnore(
            com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity("t1", "p2", 0),
        )
        scheduleDao.insert(TestDb.schedule("s2", "t1", "p2", "2026-10-31", 900, 960))
        scheduleDao.insert(TestDb.schedule("s1", "t1", "p1", "2026-10-31", 570, 600))
        scheduleDao.insert(TestDb.schedule("s3", "t1", "p1", "2026-11-01", 570, 600))

        assertEquals(
            listOf("s1", "s2", "s3"),
            scheduleDao.forTrip("t1").map { it.schedule.id },
        )
    }

    @Test
    fun `forPlaceWithTrip 返回该点位在全部行程里的时段（带行程名）`() = runTest {
        tripDao.insert(TestDb.trip("t1", "行一"))
        tripDao.insert(TestDb.trip("t2", "行二"))
        placeDao.insert(TestDb.place("p1", "车站"))
        tripPlaceDao.insertIgnore(
            com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity("t1", "p1", 0),
        )
        tripPlaceDao.insertIgnore(
            com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity("t2", "p1", 0),
        )
        scheduleDao.insert(TestDb.schedule("s1", "t1", "p1", "2026-10-31", 570, 600))
        scheduleDao.insert(TestDb.schedule("s2", "t2", "p1", "2026-11-05", 570, 600))

        val rows = scheduleDao.forPlaceWithTrip("p1")
        assertEquals(2, rows.size)
        // 2026-11-24：JOIN trip 带出行程名（S07 已安排行第二行文案）
        assertEquals(
            setOf("行一" to "s1", "行二" to "s2"),
            rows.map { it.tripName to it.schedule.id }.toSet(),
        )
        assertEquals(2, scheduleDao.countForPlace("p1"))
    }
}
