package com.visualzyzy.chuxingdazi.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripEntity
import com.visualzyzy.chuxingdazi.data.repo.BuiltinCategories
import kotlinx.coroutines.runBlocking

/** 测试用内存库 + 数据构造器。创建即种内置分类（等价生产 App 启动的 seed）。 */
object TestDb {

    // 配合测试类的 @SQLiteMode(NATIVE)：真实 sqlite 驱动无线程绑定限制。
    // 查询与事务共用单线程执行器，行为最接近生产且避免并发交错。
    // 执行器是 object 级共享的：跨测试复用同一条线程，close 也必须发生在该线程上，
    // 否则残留的 InvalidationTracker 刷新任务会在下一个测试里抛连接指针异常。
    private val dbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun create(): ChuxingdaziDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ChuxingdaziDatabase::class.java,
        )
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .setQueryExecutor(dbExecutor)
            .setTransactionExecutor(dbExecutor)
            .allowMainThreadQueries()
            .build()
            .also { db ->
                runBlocking {
                    db.categoryDao().insertIgnore(BuiltinCategories.entities(0L))
                }
            }

    /** tearDown 统一入口：先排空执行器队列（InvalidationTracker 的刷新任务在库还开着时跑完），再关闭。 */
    fun closeDb(db: ChuxingdaziDatabase) {
        dbExecutor.submit { }.get() // 排空：避免已关闭库的残留任务在下一个测试抛未捕获异常
        db.close()
    }

    fun place(
        id: String,
        name: String,
        categoryId: String = "other",
        lat: Double = 30.256100,
        lng: Double = 120.142800,
        fieldValues: String = "{}",
    ): PlaceEntity = PlaceEntity(
        id = id,
        name = name,
        address = null,
        lat = lat,
        lng = lng,
        categoryId = categoryId,
        iconKey = null,
        color = null,
        note = null,
        fieldValues = fieldValues,
        source = PlaceEntity.SOURCE_MANUAL,
        poiUid = null,
        visited = false,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    fun trip(id: String, name: String, startDate: String? = null, endDate: String? = null): TripEntity =
        TripEntity(id, name, startDate, endDate, System.currentTimeMillis(), System.currentTimeMillis())

    fun schedule(
        id: String,
        tripId: String,
        placeId: String,
        planDate: String,
        startMin: Int,
        endMin: Int,
    ): ScheduleEntity = ScheduleEntity(
        id = id,
        tripId = tripId,
        placeId = placeId,
        planDate = planDate,
        startMin = startMin,
        endMin = endMin,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )
}
