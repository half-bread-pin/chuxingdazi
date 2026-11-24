package com.visualzyzy.chuxingdazi.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分类（设计文档 §5.2 category 表）。
 * name 全局唯一（内置 + 自定义统一）；field_schema 为 JSON 字符串（FieldDef[]，§5.4）。
 */
@Entity(
    tableName = "category",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String, // 内置: "sight"/"food"/… 自定义: uuid
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "icon_key")
    val iconKey: String,
    val color: String, // #RRGGBB
    @ColumnInfo(name = "sort_index")
    val sortIndex: Int = 0, // v1 无排序 UI，靠 created_at 排；预留
    @ColumnInfo(name = "is_builtin")
    val isBuiltin: Boolean = false,
    @ColumnInfo(name = "field_schema")
    val fieldSchema: String = "[]",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * 清单（§5.2 list 表）。点位的自由集合，与出行无关；name 唯一。
 */
@Entity(
    tableName = "list",
    indices = [Index(value = ["name"], unique = true)],
)
data class ListEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val color: String?, // 可空 = 默认灰（S27 圆点 / S28 标题色点）
    @ColumnInfo(name = "sort_index")
    val sortIndex: Int = 0, // v1 恒为 0，靠 created_at 排；预留
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
