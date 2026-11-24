package com.visualzyzy.chuxingdazi.di

import android.content.Context
import androidx.room.Room
import com.visualzyzy.chuxingdazi.data.db.ChuxingdaziDatabase
import com.visualzyzy.chuxingdazi.data.db.dao.CategoryDao
import com.visualzyzy.chuxingdazi.data.db.dao.DistrictDao
import com.visualzyzy.chuxingdazi.data.db.dao.ListDao
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceDao
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceListDao
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleDao
import com.visualzyzy.chuxingdazi.data.db.dao.TrafficLegDao
import com.visualzyzy.chuxingdazi.data.repo.ScheduleRepository
import com.visualzyzy.chuxingdazi.data.db.dao.TripDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripPlaceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 应用级协程作用域（种子写入等与 UI 生命周期无关的后台任务）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ChuxingdaziDatabase =
        Room.databaseBuilder(context, ChuxingdaziDatabase::class.java, ChuxingdaziDatabase.NAME)
            .addMigrations(
                ChuxingdaziDatabase.MIGRATION_1_2,
                ChuxingdaziDatabase.MIGRATION_2_3,
                ChuxingdaziDatabase.MIGRATION_3_4,
                ChuxingdaziDatabase.MIGRATION_4_5,
                ChuxingdaziDatabase.MIGRATION_5_6,
                ChuxingdaziDatabase.MIGRATION_6_7,
                ChuxingdaziDatabase.MIGRATION_7_8,
                ChuxingdaziDatabase.MIGRATION_8_9,
            )
            .build()

    @Provides
    fun provideCategoryDao(db: ChuxingdaziDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideDistrictDao(db: ChuxingdaziDatabase): DistrictDao = db.districtDao()

    @Provides
    fun provideListDao(db: ChuxingdaziDatabase): ListDao = db.listDao()

    @Provides
    fun providePlaceDao(db: ChuxingdaziDatabase): PlaceDao = db.placeDao()

    @Provides
    fun providePlaceListDao(db: ChuxingdaziDatabase): PlaceListDao = db.placeListDao()

    @Provides
    fun provideTripDao(db: ChuxingdaziDatabase): TripDao = db.tripDao()

    @Provides
    fun provideTripPlaceDao(db: ChuxingdaziDatabase): TripPlaceDao = db.tripPlaceDao()

    @Provides
    fun provideScheduleDao(db: ChuxingdaziDatabase): ScheduleDao = db.scheduleDao()

    @Provides
    fun provideTrafficLegDao(db: ChuxingdaziDatabase): TrafficLegDao = db.trafficLegDao()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
