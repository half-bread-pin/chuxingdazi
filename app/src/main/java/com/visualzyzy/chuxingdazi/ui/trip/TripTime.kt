package com.visualzyzy.chuxingdazi.ui.trip

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 行程时间轴的时间数学（§5.3 + §6 S14）。
 * 轴比例：1 小时 = 30dp（每天 720dp），跨天段按绝对分钟统一布局（不按天切块）。
 */
object TripTime {

    const val DP_PER_HOUR = 30
    const val MIN_PER_DAY = 1440
    val DP_PER_MIN = DP_PER_HOUR / 60f

    private val FMT = DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(date: String?): LocalDate? = date?.let {
        try {
            LocalDate.parse(it)
        } catch (e: Exception) {
            null
        }
    }

    fun today(): LocalDate = LocalDate.now()

    /** 行程首日（未定日期 → 今天起连排 7 天，§7.6）。 */
    fun tripStart(startDate: String?): LocalDate = parse(startDate) ?: today()

    /** 行程天数（§6 S14：未定日期按 7 天）。 */
    fun tripDayCount(startDate: String?, endDate: String?): Int {
        val s = parse(startDate) ?: return 7
        val e = parse(endDate) ?: return 7
        val days = java.time.temporal.ChronoUnit.DAYS.between(s, e).toInt() + 1
        return days.coerceIn(1, 365)
    }

    /** schedule 的 plan_date 相对行程首日的天数偏移（可为负/超尾——允许区间外，只提示不拦）。 */
    fun dayOffset(tripStart: LocalDate, planDate: String): Int =
        java.time.temporal.ChronoUnit.DAYS.between(tripStart, LocalDate.parse(planDate)).toInt()

    /** 绝对分钟（自行程首日 00:00 起）→ 轴 y 值分钟。 */
    fun absMinutes(dayOffset: Int, startMin: Int): Int = dayOffset * 1440 + startMin

    /** 绝对分钟 → (日期偏移, 当天分钟)。 */
    fun splitAbs(absMin: Int): Pair<Int, Int> = Pair(absMin / 1440, absMin % 1440)

    fun minutesText(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    fun dateText(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}"

    fun weekdayText(date: LocalDate): String =
        date.dayOfWeek.value.let { listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[(it + 6) % 7] }

    /** 行程速记 D 码 → 具体日期文本（仅显示用，不改数据；2026-11-23 #5）。
     *  D2/D3、D2-D3、D2~D3 → "11/28–11/29"；单独 D2 → "11/28"。
     *  只替换独立 D+数字 词元（前后都不是字母数字），避免误伤普通文本；序号超出 1..99 原样保留。 */
    fun expandDayCodes(text: String, tripStart: LocalDate): String {
        val range = Regex("(?<![A-Za-z0-9])D(\\d{1,2})\\s*[/\\-~－—]\\s*D(\\d{1,2})(?![A-Za-z0-9])")
        var out = range.replace(text) { m ->
            val a = m.groupValues[1].toIntOrNull()
            val b = m.groupValues[2].toIntOrNull()
            if (a != null && b != null && a in 1..99 && b in a..99) {
                "${dateText(tripStart.plusDays((a - 1).toLong()))}–${dateText(tripStart.plusDays((b - 1).toLong()))}"
            } else m.value
        }
        val single = Regex("(?<![A-Za-z0-9])D(\\d{1,2})(?![A-Za-z0-9])")
        out = single.replace(out) { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..99) dateText(tripStart.plusDays((n - 1).toLong())) else m.value
        }
        return out
    }

    /** 拖拽 15 分钟吸附。 */
    fun snap15(min: Int): Int = (min / 15.0).roundToInt() * 15
}
