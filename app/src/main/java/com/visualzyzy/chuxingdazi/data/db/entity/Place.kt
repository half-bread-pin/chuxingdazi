package com.visualzyzy.chuxingdazi.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 点位（§5.2 place 表）。坐标一律 BD09LL，Double 保留 6 位小数。
 *
 * 注意：category_id 外键**不写 onDelete**（保持 RESTRICT）——删自定义分类时先在事务里
 * 把点位改到 other 再删分类，防止漏写退回落点时连带删掉点位（§5.2 注释）。
 * UNIQUE(name, lat, lng) 是同名 + 坐标逐位相同的兜底；"近似同坐标"靠应用层 <5m 硬拦（§14.1 #34）。
 */
@Entity(
    tableName = "place",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
        ),
    ],
    indices = [
        Index(value = ["category_id"], name = "idx_place_category"),
        Index(value = ["poi_uid"], name = "idx_place_poi"), // 非唯一，加点位时查重
        Index(value = ["name", "lat", "lng"], unique = true),
        // 区域记录（第七轮 2026-11-06）：省份-点位 / 城市-点位 两张记录表不物化，
        // 就以 place 行 + 这两个覆盖索引表达（省份表/省份-城市表用 GROUP BY 走索引聚合，见 PlaceDao）
        Index(value = ["province", "city"], name = "idx_place_region"),
        Index(value = ["city"], name = "idx_place_city"),
    ],
)
data class PlaceEntity(
    @PrimaryKey
    val id: String,
    val name: String, // 非空：留空回退逆地理简称，再失败存坐标文本
    val address: String?,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "category_id")
    val categoryId: String,
    @ColumnInfo(name = "icon_key")
    val iconKey: String?, // 覆盖分类图标；v1 不开放入口，预留
    val color: String?, // 覆盖分类颜色；v1 不开放入口，预留
    val note: String?, // 备注（多行）
    @ColumnInfo(name = "field_values")
    val fieldValues: String = "{}", // JSON: {fieldKey: value}，值格式由 field_schema 的 type 决定（§5.4）
    // 点位自定义字段定义（字段模板升级 §3.1，2026-11-21 DB v9）：JSON: FieldDef[]，复用分类
    // 同款 FieldDef 序列化；key 由 App 生成 p_+8hex。值仍写进 field_values，不新增字段值表。
    @ColumnInfo(name = "custom_schema", defaultValue = "[]")
    val customSchema: String = "[]",
    val source: String = "SEARCH", // SEARCH/MAP_PICK/MANUAL/SHARE
    @ColumnInfo(name = "poi_uid")
    val poiUid: String?, // 百度 POI uid，可空
    val province: String? = null, // 省份（第七轮 2026-11-06）：只信百度逆地理回填，用户不可改；可空=还没自愈到
    val city: String? = null, // 城市（同上；与 province 同为「区域筛选/区域展示」的事实源列）
    val visited: Boolean = false,
    // 第十六轮（2026-11-14）：到访时间（优化建议：时间轴绿勾无法回答「哪次去的」，至少记 visited_at）；
    // 取消到访置回 null。备份 v5 起随点位导出。
    @ColumnInfo(name = "visited_at")
    val visitedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val SOURCE_SEARCH = "SEARCH"
        const val SOURCE_MAP_PICK = "MAP_PICK"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_SHARE = "SHARE"
    }
}

/**
 * 点位 ↔ 清单多对多（§5.2 place_list 表）。删任一侧 → 关系级联删，主体保留。
 * added_at 支持 S28「最近添加」排序。
 */
@Entity(
    tableName = "place_list",
    primaryKeys = ["place_id", "list_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["place_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["list_id"], name = "idx_place_list_list")], // PK 前列是 place_id，按清单查需自建索引
)
data class PlaceListEntity(
    @ColumnInfo(name = "place_id")
    val placeId: String,
    @ColumnInfo(name = "list_id")
    val listId: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)
