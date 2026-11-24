package com.visualzyzy.chuxingdazi.ui.trip

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** #5（2026-11-23）：行程速记 D 码 → 具体日期文本（仅显示用）的单测。 */
class TripTimeDayCodeTest {

    // 「庐山景德镇黄山7日」行程首日 2026-11-27（D1）
    private val start: LocalDate = LocalDate.of(2026, 11, 27)

    @Test
    fun singleCode() {
        assertEquals("11/28", TripTime.expandDayCodes("D2", start))
        assertEquals("11/27", TripTime.expandDayCodes("D1", start))
    }

    @Test
    fun rangeCodeWithSlash() {
        assertEquals("11/28–11/29两晚;大林路93号", TripTime.expandDayCodes("D2/D3两晚;大林路93号", start))
    }

    @Test
    fun rangeCodeWithDashAndTilde() {
        assertEquals("11/28–11/29", TripTime.expandDayCodes("D2-D3", start))
        assertEquals("11/28–11/29", TripTime.expandDayCodes("D2~D3", start))
    }

    @Test
    fun twoSeparateCodes() {
        // 两码不相邻（中间隔汉字），各自替换
        assertEquals("12/2进山/12/3出山", TripTime.expandDayCodes("D6进山/D7出山", start))
    }

    @Test
    fun nonDayCodeTextUntouched() {
        // 机场段备注（无 D 码）与普通文本原样保留
        val note = "12/3 GS7422 16:30→天津18:25;提前2小时到"
        assertEquals(note, TripTime.expandDayCodes(note, start))
        assertEquals("大林路93号;0792-8289966", TripTime.expandDayCodes("大林路93号;0792-8289966", start))
    }

    @Test
    fun outOfRangeOrEmbeddedCodesUntouched() {
        // 三位数（正则只吃两位且后随数字不过前瞻）原样
        assertEquals("D100", TripTime.expandDayCodes("D100", start))
        // 序号 0 越界原样
        assertEquals("D0", TripTime.expandDayCodes("D0", start))
        // 嵌在字母数字里不是独立词元，不动
        assertEquals("AD2", TripTime.expandDayCodes("AD2", start))
        assertEquals("D2x", TripTime.expandDayCodes("D2x", start))
        // 区间码倒序（b < a）区间不替换，但两个独立单码仍各自替换（方案函数的确切行为）
        assertEquals("11/29/11/28", TripTime.expandDayCodes("D3/D2", start))
    }

    @Test
    fun mixedText() {
        assertEquals(
            "11/28–11/29两晚;大林路93号;0792-8289966;淡季价未核实",
            TripTime.expandDayCodes("D2/D3两晚;大林路93号;0792-8289966;淡季价未核实", start),
        )
    }
}
