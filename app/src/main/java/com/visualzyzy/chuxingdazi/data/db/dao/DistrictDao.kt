package com.visualzyzy.chuxingdazi.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.visualzyzy.chuxingdazi.data.db.entity.DistrictEntity
import kotlinx.coroutines.flow.Flow

/** 省级-市级行政区划表（2026-11-17 腾讯版新需求；数据源 = 腾讯 getDistrictChildren）。 */
@Dao
interface DistrictDao {

    /** 顶级省列表（按接口顺序）。 */
    @Query("SELECT * FROM district WHERE parentId IS NULL ORDER BY sortIndex")
    fun observeProvinces(): Flow<List<DistrictEntity>>

    /** 某省的市级列表（按接口顺序）。 */
    @Query("SELECT * FROM district WHERE parentId = :provinceAdcode ORDER BY sortIndex")
    suspend fun citiesOf(provinceAdcode: Int): List<DistrictEntity>

    @Query("SELECT COUNT(*) FROM district")
    suspend fun count(): Int

    @Query("DELETE FROM district")
    suspend fun clear()

    /** 全量替换（更新流程收尾调用；单事务保证读取端不出现半套数据）。 */
    @Transaction
    suspend fun replaceAll(rows: List<DistrictEntity>) {
        clear()
        insertAll(rows)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DistrictEntity>)
}
