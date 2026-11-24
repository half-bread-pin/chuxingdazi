package com.visualzyzy.chuxingdazi.data.db

import com.visualzyzy.chuxingdazi.data.repo.DuplicatePlaceException
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepositoryImpl
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
 * §14.1 #34 点位查重两层机制：
 * - 应用层「同名 + 距离 <5m」硬拦（主力）
 * - 数据库 UNIQUE(name, lat, lng) 兜底（同名 + 坐标逐位相同 / 并发插入）
 * 连锁店同名但位置不同（>5m）不受影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaceUniquenessTest {

    private lateinit var db: ChuxingdaziDatabase
    private lateinit var placeRepository: PlaceRepositoryImpl

    @Before
    fun setUp() {
        db = TestDb.create()
        placeRepository = PlaceRepositoryImpl(db.placeDao(), db.placeListDao(), db.tripPlaceDao(), db.scheduleDao(), db.trafficLegDao())
    }

    @After
    fun tearDown() {
        TestDb.closeDb(db)
    }

    @Test
    fun `同名且坐标逐位相同的点位被 UNIQUE 挡住`() = runTest {
        placeRepository.add(TestDb.place("p1", "楼外楼", lat = 30.256100, lng = 120.142800))
        val result = runCatching {
            placeRepository.add(TestDb.place("p2", "楼外楼", lat = 30.256100, lng = 120.142800))
        }
        assertTrue(result.exceptionOrNull() is DuplicatePlaceException)
    }

    @Test
    fun `同名不同坐标（连锁店）不受影响`() = runTest {
        placeRepository.add(TestDb.place("p1", "连锁咖啡", lat = 30.256100, lng = 120.142800))
        placeRepository.add(TestDb.place("p2", "连锁咖啡", lat = 30.300000, lng = 120.300000))
        assertEquals(2, placeRepository.getAll().size)
    }

    @Test
    fun `同名且距离小于 5 米的应用层硬拦命中`() = runTest {
        // 约 3 米的偏移（1 米纬度 ≈ 1/111320 度）
        val threeMeters = 3.0 / 111320.0
        placeRepository.add(TestDb.place("p1", "楼外楼", lat = 30.256100, lng = 120.142800))
        val duplicate = placeRepository.findNearbyDuplicate("楼外楼", 30.256100 + threeMeters, 120.142800)
        assertNotNull(duplicate)
        assertEquals("p1", duplicate?.id)
    }

    @Test
    fun `同名但距离超过 5 米不拦`() = runTest {
        val fiftyMeters = 50.0 / 111320.0
        placeRepository.add(TestDb.place("p1", "楼外楼", lat = 30.256100, lng = 120.142800))
        val duplicate = placeRepository.findNearbyDuplicate("楼外楼", 30.256100 + fiftyMeters, 120.142800)
        assertNull(duplicate)
    }

    @Test
    fun `poi_uid 查重命中已有点位`() = runTest {
        val withUid = TestDb.place("p1", "百度 POI 店").copy(poiUid = "poi-123")
        placeRepository.add(withUid)
        assertNotNull(placeRepository.findByPoiUid("poi-123"))
        assertNull(placeRepository.findByPoiUid("poi-999"))
    }
}
