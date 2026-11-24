package com.visualzyzy.chuxingdazi.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 省级-市级行政区划表（2026-11-17 腾讯版新需求）：
 * 数据不再内置静态表（ChinaRegions 废除），改由腾讯 getDistrictChildren 接口获取，
 * 本地持久化，2 个月更新一次（只在应用启动时检查到期；设置页可手动更新）。
 *
 * - 顶级（parent_id = NULL）= 省级（省/自治区/直辖市/特别行政区）
 * - 子级（parent_id = 省 adcode）= 市级（地级市/直辖市的区）
 * - 直辖市：接口返回的市级条目与省同名（fullname），CityPickerDialog 原样展示
 *   （「北京市-北京市」显示问题沿用原静态表同款口径，见待办册遗留 #9）。
 */
@Entity(tableName = "district")
data class DistrictEntity(
    /** 腾讯行政区划 id（int，如河北省=130000）。 */
    @PrimaryKey val adcode: Int,
    /** 全名（fullname，如「河北省」「石家庄市」）。 */
    val name: String,
    /** 父级 adcode；顶级省 = null。 */
    val parentId: Int?,
    /** 接口返回顺序（省级 0..N；市级在省内 0..N），用于列表排序。 */
    val sortIndex: Int,
)
