package com.visualzyzy.chuxingdazi.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 分类专属字段定义（§5.4）。表单/详情页完全由 category.field_schema 数据驱动渲染——
 * 加分类或加字段不需要改 UI 代码。
 */
@Serializable
data class FieldDef(
    val key: String, // 存 field_values 的键
    val label: String,
    val type: FieldType,
    val placeholder: String? = null,
    val required: Boolean = false, // 内置 7 个分类的默认模板全部 false（§14.1 #17）
)

@Serializable
enum class FieldType {
    @SerialName("TEXT") TEXT,
    @SerialName("NUMBER") NUMBER,
    @SerialName("MONEY") MONEY,
    @SerialName("TIME_RANGE") TIME_RANGE,
    @SerialName("DATETIME") DATETIME,
    @SerialName("TEL") TEL,
    @SerialName("URL") URL,
    @SerialName("LONG_TEXT") LONG_TEXT,
    @SerialName("BUSINESS_HOURS") BUSINESS_HOURS, // 字段模板升级 §2.2（2026-11-21）：周分组多时段
}

/**
 * 值编码（§5.4 表）：TEXT/LONG_TEXT/TEL/URL 存字符串；NUMBER/MONEY 存 JSON 数字；
 * DATETIME 存 Unix 毫秒；TIME_RANGE 存 "HH:mm-HH:mm"。没填的字段**不写入** map。
 */
object FieldValuesCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(values: Map<String, JsonElement>): String = json.encodeToString<Map<String, JsonElement>>(values)

    /** 容错解码：配置错误（key 重复等）不崩溃，返回能解析的部分（§6 S08 边界）。 */
    fun decode(raw: String): Map<String, JsonElement> =
        runCatching { json.decodeFromString<Map<String, JsonElement>>(raw) }.getOrDefault(emptyMap())

    /**
     * 把文本输入按字段类型转为 JSON（§5.4 值编码）。
     * 未填 / 解析失败的字段不写入（模板字段一律不校验，§14.1 #17）。
     */
    fun fromTextInputs(schema: List<FieldDef>, inputs: Map<String, String>): String {
        val values = LinkedHashMap<String, JsonElement>()
        schema.forEach { def ->
            val text = inputs[def.key]?.trim().orEmpty()
            if (text.isBlank()) return@forEach
            val element: JsonElement? = when (def.type) {
                FieldType.TEXT, FieldType.LONG_TEXT, FieldType.TEL, FieldType.URL, FieldType.TIME_RANGE ->
                    JsonPrimitive(text)
                FieldType.NUMBER -> text.toDoubleOrNull()?.let { JsonPrimitive(it) }
                FieldType.MONEY -> text.toDoubleOrNull()?.let { JsonPrimitive("%.2f".format(it).toDouble()) }
                FieldType.DATETIME -> parseDatetimeText(text)?.let { JsonPrimitive(it) }
                // BUSINESS_HOURS：编辑器产出归一化 JSON 数组文本（§2.2），原样解析入库；
                // 空数组 = 没填，不落库
                FieldType.BUSINESS_HOURS -> runCatching {
                    Json.parseToJsonElement(text)
                }.getOrNull()?.takeIf {
                    it is JsonArray && it.isNotEmpty()
                }
            }
            element?.let { values[def.key] = it }
        }
        return encode(values)
    }

    /**
     * DATETIME 文本 → 毫秒：优先按展示态 "yyyy-MM-dd HH:mm" 严格解析；
     * 纯数字按毫秒数原样接受——编辑页对旧值「只看不改」时往返不丢值
     * （fieldInputs 装载的是编码原文，此前旧值会被静默丢弃，字段模板升级 2026-11-21 修复）。
     */
    private fun parseDatetimeText(text: String): Long? {
        runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).apply {
                isLenient = false
            }.parse(text)?.time
        }.getOrNull()?.let { return it }
        return text.toLongOrNull()
    }
}

object FieldSchemaCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(defs: List<FieldDef>): String = json.encodeToString<List<FieldDef>>(defs)

    fun decode(raw: String): List<FieldDef> =
        runCatching { json.decodeFromString<List<FieldDef>>(raw) }.getOrDefault(emptyList())
            .distinctBy { it.key } // key 重复按一条算，渲染端去重并记日志（§6 S08 边界）

    /**
     * 点位自定义字段的 key（字段模板升级 §3.1，2026-11-21）：`p_` + 8 位随机 hex（如 p_a3f81c22）。
     * 展示名存 label；内部 key 随机唯一、生成后不变，改名只动 label、已填的值不丢，且永不与
     * 分类模板的人类可读 key（startAt/openHours）撞名。
     */
    fun newCustomFieldKey(): String {
        val hex = "0123456789abcdef"
        return "p_" + buildString { repeat(8) { append(hex.random()) } }
    }
}
