package com.visualzyzy.chuxingdazi.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BusinessHoursCodec 编码归一化矩阵 + 解码容错 + 营业判定（字段模板升级 §2.2/§4.3，2026-11-21）。
 */
class BusinessHoursCodecTest {

    private fun group(vararg days: Int, slots: List<String>) = HoursGroup(days.toList(), slots)

    // ---------- parseSlot ----------

    @Test
    fun `parseSlot 接受宽松输入并归一化为两位 HH-mm`() {
        assertEquals("09:00-18:00", BusinessHoursCodec.parseSlot("9:00-21:00".replace("21:00", "18:00")))
        assertEquals("09:30-21:00", BusinessHoursCodec.parseSlot("09:30 - 21:00"))
    }

    @Test
    fun `parseSlot 起止相同或跨午夜非法`() {
        assertNull(BusinessHoursCodec.parseSlot("09:00-09:00")) // 起止相同
        assertNull(BusinessHoursCodec.parseSlot("22:00-06:00")) // 跨午夜（§2.2 不允许）
        assertNull(BusinessHoursCodec.parseSlot("24:00-25:00"))
        assertNull(BusinessHoursCodec.parseSlot("abc"))
    }

    // ---------- encodeNormalized 归一化矩阵 ----------

    @Test
    fun `days 升序去重 相同days组合并 槽去重`() {
        val (json, dropped) = BusinessHoursCodec.encodeNormalized(
            listOf(
                group(5, 1, 3, 1, slots = listOf("09:00-18:00")),
                group(3, 5, 1, slots = listOf("09:00-18:00", "09:00-18:00")),
            ),
        )
        assertEquals(0, dropped)
        assertEquals("""[{"days":[1,3,5],"slots":["09:00-18:00"]}]""", json)
    }

    @Test
    fun `全7天同slots的多组合并为单组`() {
        val (json, _) = BusinessHoursCodec.encodeNormalized(
            listOf(
                group(0, 1, 2, slots = listOf("10:00-16:00")),
                group(3, 4, 5, 6, slots = listOf("10:00-16:00")),
            ),
        )
        // days 合并后同 days 组再合并
        assertEquals("""[{"days":[0,1,2,3,4,5,6],"slots":["10:00-16:00"]}]""", json)
    }

    @Test
    fun `非法槽与空时段组丢弃并计数`() {
        val (json, dropped) = BusinessHoursCodec.encodeNormalized(
            listOf(
                group(1, slots = listOf("22:00-06:00")), // 跨午夜非法
                group(2, slots = listOf()),              // 空时段组
                group(3, slots = listOf("08:00-12:00")),
            ),
        )
        assertEquals(2, dropped)
        assertEquals("""[{"days":[3],"slots":["08:00-12:00"]}]""", json)
    }

    @Test
    fun `排序键 days最小值然后slots首段`() {
        val (json, _) = BusinessHoursCodec.encodeNormalized(
            listOf(
                group(6, slots = listOf("09:00-12:00")),
                group(0, slots = listOf("14:00-18:00")),
                group(1, slots = listOf("10:00-11:00", "08:00-09:00")),
            ),
        )
        // days 0 最小；days 1 组内 slots 无法重排（保留原序），键取首段
        assertEquals("""[{"days":[0],"slots":["14:00-18:00"]},{"days":[1],"slots":["10:00-11:00","08:00-09:00"]},{"days":[6],"slots":["09:00-12:00"]}]""", json)
    }

    @Test
    fun `days越界值被剔除`() {
        val (json, _) = BusinessHoursCodec.encodeNormalized(listOf(group(1, 7, -1, slots = listOf("09:00-10:00"))))
        assertEquals("""[{"days":[1],"slots":["09:00-10:00"]}]""", json)
    }

    // ---------- decode 容错 ----------

    @Test
    fun `decode 数组编码往返保真`() {
        val raw = """[{"days":[1,2,3,4,5],"slots":["09:00-18:00"]},{"days":[6],"slots":["10:00-14:00","16:00-22:00"]}]"""
        val groups = BusinessHoursCodec.decode(raw)
        assertEquals(2, groups.size)
        assertEquals(listOf(1, 2, 3, 4, 5), groups[0].days)
        assertEquals(listOf("10:00-14:00", "16:00-22:00"), groups[1].slots)
    }

    @Test
    fun `decode 旧格式字符串视为全年适用单组`() {
        val groups = BusinessHoursCodec.decode("\"09:00-18:00\"")
        assertEquals(1, groups.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), groups[0].days)
        assertEquals(listOf("09:00-18:00"), groups[0].slots)
    }

    @Test
    fun `decode 脏文本与空串返回空表`() {
        assertTrue(BusinessHoursCodec.decode("").isEmpty())
        assertTrue(BusinessHoursCodec.decode("旺季8点到17点").isEmpty())
        assertTrue(BusinessHoursCodec.decode("[{\"days\":[9],\"slots\":[]}]").isEmpty())
    }

    // ---------- summarize ----------

    @Test
    fun `summarize 输出方案口径文案`() {
        val groups = BusinessHoursCodec.decode(
            """[{"days":[1,2,3,4,5],"slots":["09:00-18:00"]},{"days":[6],"slots":["10:00-14:00","16:00-22:00"]},{"days":[0],"slots":["10:00-16:00"]}]""",
        )
        assertEquals(
            "周一至周五 09:00-18:00 · 周六 10:00-14:00、16:00-22:00 · 周日 10:00-16:00",
            BusinessHoursCodec.summarize(groups),
        )
        assertEquals("每天 00:00-23:59", BusinessHoursCodec.summarize(listOf(ALL_DAY)))
        assertNull(BusinessHoursCodec.summarize(emptyList()))
    }

    // ---------- isOperatingNow ----------

    @Test
    fun `isOperatingNow 按星期与时刻判定`() {
        val groups = listOf(group(1, 2, 3, 4, 5, slots = listOf("09:00-18:00")))
        assertTrue(BusinessHoursCodec.isOperatingNow(groups, dayIdx = 1, minuteOfDay = 9 * 60)) // 周一 09:00 起含
        assertTrue(BusinessHoursCodec.isOperatingNow(groups, dayIdx = 5, minuteOfDay = 18 * 60)) // 周五 18:00 止含
        assertFalse(BusinessHoursCodec.isOperatingNow(groups, dayIdx = 0, minuteOfDay = 10 * 60)) // 周日
        assertFalse(BusinessHoursCodec.isOperatingNow(groups, dayIdx = 1, minuteOfDay = 18 * 60 + 1))
        // 24小时营业：任何时刻都营业中
        assertTrue(BusinessHoursCodec.isOperatingNow(listOf(ALL_DAY), 0, 1439))
        assertTrue(BusinessHoursCodec.isOperatingNow(listOf(ALL_DAY), 3, 0))
    }

    // ---------- findOverlapConflict ----------

    @Test
    fun `同一天跨组时段重叠报周几`() {
        val groups = listOf(
            group(1, slots = listOf("09:00-12:00")),
            group(1, slots = listOf("11:30-14:00")), // 与上一组在周一重叠
        )
        assertEquals("周一", BusinessHoursCodec.findOverlapConflict(groups))
    }

    @Test
    fun `允许同一天出现在多组但时段不重叠`() {
        val groups = listOf(
            group(1, slots = listOf("09:00-12:00")),
            group(1, slots = listOf("12:00-14:00")), // 相邻不重叠（边界相接允许）
        )
        assertNull(BusinessHoursCodec.findOverlapConflict(groups))
    }

    private val ALL_DAY = HoursGroup((0..6).toList(), listOf("00:00-23:59"))
}
