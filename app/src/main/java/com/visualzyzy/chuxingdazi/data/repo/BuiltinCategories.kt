package com.visualzyzy.chuxingdazi.data.repo

import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.model.FieldDef
import com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec
import com.visualzyzy.chuxingdazi.data.model.FieldType

/** 内置分类 id（§5.4），place 迁移回落点 other 也用它。 */
object BuiltinIds {
    const val SIGHT = "sight"
    const val FOOD = "food"
    const val STAY = "stay"
    const val TRANSIT = "transit"
    const val SHOP = "shop"
    const val EVENT = "event"
    const val OTHER = "other"

    val all = listOf(SIGHT, FOOD, STAY, TRANSIT, SHOP, EVENT, OTHER)
}

/**
 * 内置 7 分类 + 默认字段模板（§6 分类色板 + §5.4 模板表）。
 * 模板字段一律 required = false（§14.1 #17）；other 无字段。
 */
object BuiltinCategories {

    private fun defs(vararg pairs: Pair<String, Pair<FieldType, String>>): List<FieldDef> =
        pairs.map { (key, spec) ->
            FieldDef(key = key, label = spec.second, type = spec.first, required = false)
        }

    val specs: List<Triple<String, Pair<String, String>, String>> = listOf(
        // Triple(id, 名称 to 图标key, 颜色)
        // 第十一轮（2026-11-11）图标库全量替换：内置分类默认图标改用新库 key（用户指定映射）
        Triple(BuiltinIds.SIGHT, "景点" to "travel_mountain", "#2E9E6B"),
        Triple(BuiltinIds.FOOD, "美食" to "food_utensils", "#F97316"),
        Triple(BuiltinIds.STAY, "住宿" to "place_bed", "#8B5CF6"),
        Triple(BuiltinIds.TRANSIT, "交通" to "transport_train", "#3B82F6"),
        Triple(BuiltinIds.SHOP, "购物" to "place_shopping", "#EC4899"),
        Triple(BuiltinIds.EVENT, "活动" to "event_ticket", "#E5484D"),
        Triple(BuiltinIds.OTHER, "其他" to "other_point", "#64748B"),
    )

    private val schemas: Map<String, List<FieldDef>> = mapOf(
        BuiltinIds.SIGHT to defs(
            "ticket_price" to (FieldType.MONEY to "门票价格"),
            // 字段模板升级（2026-11-21）：开放时间 TEXT → BUSINESS_HOURS（新装库 seed 用 v2 模板；
            // 存量数据走 backup_field_v2_convert.py 转换，App 端不做存量迁移）
            "open_time" to (FieldType.BUSINESS_HOURS to "开放时间"),
        ),
        BuiltinIds.FOOD to defs(
            "avg_price" to (FieldType.MONEY to "人均价格"),
            "open_time" to (FieldType.BUSINESS_HOURS to "营业时间"),
        ),
        BuiltinIds.STAY to defs(
            "checkin_at" to (FieldType.DATETIME to "入住时间"),
            "checkout_at" to (FieldType.DATETIME to "退房时间"),
            "price" to (FieldType.MONEY to "房价"),
        ),
        BuiltinIds.TRANSIT to defs(
            "trip_no" to (FieldType.TEXT to "班次/车次"),
        ),
        BuiltinIds.SHOP to defs(
            "open_time" to (FieldType.BUSINESS_HOURS to "营业时间"),
        ),
        BuiltinIds.EVENT to defs(
            "start_at" to (FieldType.DATETIME to "开始时间"),
            "end_at" to (FieldType.DATETIME to "结束时间"),
        ),
        BuiltinIds.OTHER to emptyList(),
    )

    fun entities(now: Long): List<CategoryEntity> = specs.mapIndexed { index, (id, nameIcon, color) ->
        CategoryEntity(
            id = id,
            name = nameIcon.first,
            iconKey = nameIcon.second,
            color = color,
            sortIndex = index,
            isBuiltin = true,
            fieldSchema = FieldSchemaCodec.encode(schemas.getValue(id)),
            createdAt = now + index, // 同毫秒也保证稳定排序（v1 无排序 UI，靠 created_at 排）
            updatedAt = now + index,
        )
    }
}
