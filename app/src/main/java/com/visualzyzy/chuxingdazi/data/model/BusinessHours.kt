package com.visualzyzy.chuxingdazi.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * BUSINESS_HOURS 值编码与归一化（字段模板升级 §2.2，2026-11-21）。
 *
 * 编码 = JSON 数组（field_values 里存真正的数组，不是字符串）：
 * ```
 * [ { "days": [1,2,3,4,5], "slots": ["09:00-18:00"] },
 *   { "days": [6],          "slots": ["10:00-14:00","16:00-22:00"] } ]
 * ```
 * - days ∈ 0(周日)..6(周六)；slot = "HH:mm-HH:mm"，起<止，**不允许跨午夜**（§2.2，防解析歧义）
 * - 写入前强制归一化：days 升序去重、相同 days 组合并、非法槽丢弃、空时段组丢弃、
 *   排序键 = days 最小值 → slots[0]
 * - 读侧容错：旧格式字符串 "HH:mm-HH:mm" → 全年适用单组；非数组脏值 → 空表（渲染回退原文）
 */
data class HoursGroup(
    val days: List<Int>,   // 升序去重，元素 ∈ 0..6
    val slots: List<String>, // 归一化槽 "HH:mm-HH:mm"
)

@Serializable
private data class HoursGroupDto(
    val days: List<Int> = emptyList(),
    val slots: List<String> = emptyList(),
)

object BusinessHoursCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /** 周几名（索引即 days 编码：0=周日..6=周六）。 */
    private val DAY_NAMES = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    private val ALL_DAYS = (0..6).toList()

    /**
     * 读侧解码（§2.2 容错）：
     * - JSON 数组 → 组（非法组整组丢弃）
     * - 旧格式单段字符串 "HH:mm-HH:mm" → 全年适用单组（存量 TIME_RANGE 值天然可读）
     * - 其他脏值 → 空表（调用方回退原样展示）
     */
    fun decode(raw: String): List<HoursGroup> {
        var trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        // JSON 字符串字面量（旧值经 toString 带引号）→ 剥引号
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length - 1)
        }
        if (!trimmed.startsWith("[")) {
            parseSlot(trimmed)?.let { return listOf(HoursGroup(ALL_DAYS, listOf(it))) }
            return emptyList()
        }
        return runCatching {
            json.decodeFromString<List<HoursGroupDto>>(trimmed)
        }.getOrDefault(emptyList()).mapNotNull { dto ->
            val days = dto.days.filter { it in 0..6 }.distinct().sorted()
            val slots = dto.slots.mapNotNull { parseSlot(it) }
            if (days.isEmpty() || slots.isEmpty()) null else HoursGroup(days, slots)
        }
    }

    /**
     * 写侧归一化 + 序列化（§2.2 编码规则）：返回 (归一化 JSON 文本, 丢弃的非法组数)。
     * days 升序去重、槽起<止过滤、相同 days 组合并、空组丢弃、按 days 最小值 → slots[0] 排序。
     */
    fun encodeNormalized(groups: List<HoursGroup>): Pair<String, Int> {
        var dropped = 0
        val cleaned = groups.map { g ->
            val days = g.days.filter { it in 0..6 }.distinct().sorted()
            val slots = g.slots.mapNotNull { parseSlot(it) }
            if (days.isEmpty() || slots.isEmpty()) {
                dropped++
                null
            } else {
                HoursGroup(days, slots)
            }
        }.filterNotNull()

        // 相同 days 的组先合并（槽集合去重）
        val byDays = LinkedHashMap<List<Int>, LinkedHashSet<String>>()
        cleaned.forEach { g -> byDays.getOrPut(g.days) { LinkedHashSet() }.addAll(g.slots) }

        // 相同 slots 的组再按 days 求并集（此时组间 days 必不相交；并集覆盖全 7 天
        // 即「全 7 天同 slots 的多组合并」规则——工作日组+周末组同时段 → 单个每天组）
        val bySlots = LinkedHashMap<List<String>, MutableSet<Int>>()
        byDays.forEach { (days, slots) -> bySlots.getOrPut(slots.toList()) { LinkedHashSet() }.addAll(days) }

        val merged = bySlots.map { (slots, days) -> HoursGroup(days.sorted(), slots) }
            .sortedWith(compareBy({ it.days.first() }, { it.slots.firstOrNull().orEmpty() }))
        return json.encodeToString<List<HoursGroupDto>>(merged.map { HoursGroupDto(it.days, it.slots) }) to dropped
    }

    /** 摘要文本（详情页/字段格展示）：`周一至周五 09:00-18:00 · 周六 10:00-14:00、16:00-22:00`。 */
    fun summarize(groups: List<HoursGroup>): String? {
        if (groups.isEmpty()) return null
        return groups.joinToString(" · ") { g ->
            "${daysLabel(g.days)} ${g.slots.joinToString("、")}"
        }
    }

    /** 「现在营业中」判定（§4.3 徽标）：纯函数。dayIdx 用本编码约定 0(周日)..6(周六)。 */
    fun isOperatingNow(groups: List<HoursGroup>, dayIdx: Int, minuteOfDay: Int): Boolean =
        groups.any { g -> dayIdx in g.days && g.slots.any { slotCovers(it, minuteOfDay) } }

    /** 保存前校验：同一天跨组时段重叠 → 返回首个冲突周几名（如「周一」）；无冲突返回 null。 */
    fun findOverlapConflict(groups: List<HoursGroup>): String? {
        for (day in 0..6) {
            val slots = groups.filter { day in it.days }.flatMap { it.slots }
            for (i in slots.indices) {
                for (j in i + 1 until slots.size) {
                    if (slotsOverlap(slots[i], slots[j])) return DAY_NAMES[day]
                }
            }
        }
        return null
    }

    private fun daysLabel(days: List<Int>): String = when {
        days == ALL_DAYS -> "每天"
        days == listOf(1, 2, 3, 4, 5) -> "周一至周五"
        days == listOf(0, 6) -> "周末"
        days.size == 1 -> DAY_NAMES[days.first()]
        else -> days.joinToString("、") { DAY_NAMES[it] }
    }

    /**
     * 槽解析 + 归一化：接受 "H:mm-H:mm" 宽松输入，输出严格 "HH:mm-HH:mm"；
     * 要求起<止（§2.2 不允许跨午夜）；非法返回 null。
     */
    fun parseSlot(raw: String): String? {
        val m = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$").find(raw.trim()) ?: return null
        val (h1, m1, h2, m2) = m.destructured
        val sh = h1.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val sm = m1.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val eh = h2.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val em = m2.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val start = sh * 60 + sm
        val end = eh * 60 + em
        if (start >= end) return null
        return "%02d:%02d-%02d:%02d".format(sh, sm, eh, em)
    }

    private fun slotMinutes(slot: String, end: Boolean): Int? {
        val part = slot.substringBefore("-").takeIf { !end } ?: slot.substringAfter("-")
        val p = part.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun slotCovers(slot: String, minuteOfDay: Int): Boolean {
        val start = slotMinutes(slot, end = false) ?: return false
        val endM = slotMinutes(slot, end = true) ?: return false
        return minuteOfDay in start..endM
    }

    private fun slotsOverlap(a: String, b: String): Boolean {
        val aS = slotMinutes(a, end = false) ?: return false
        val aE = slotMinutes(a, end = true) ?: return false
        val bS = slotMinutes(b, end = false) ?: return false
        val bE = slotMinutes(b, end = true) ?: return false
        return aS < bE && bS < aE
    }
}
