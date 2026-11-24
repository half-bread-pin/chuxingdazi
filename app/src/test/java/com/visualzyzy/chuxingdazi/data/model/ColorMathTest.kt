package com.visualzyzy.chuxingdazi.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CIEDE2000 实现校验（第七轮 2026-11-06）：
 * 数据源 = Sharma, Wu & Dalal (2005) 官方 34 测试对
 * （www.hajim.rochester.edu/ece/~gsharma/ciede2000/dataNprograms/ciede2000testdata.txt）。
 * 实现必须在全部 34 对上与期望值吻合（容差 1e-4，与官方 4 位小数一致）——
 * 没过这 34 对的 CIEDE2000 实现一律不可信（易错点：SC=1+0.045·C̄′ 不是 SH 的 0.015）。
 */
class ColorMathTest {

    private data class Pair(
        val l1: Double,
        val a1: Double,
        val b1: Double,
        val l2: Double,
        val a2: Double,
        val b2: Double,
        val expected: Double,
    )

    private val sharma34 = listOf(
        Pair(50.0000, 2.6772, -79.7751, 50.0000, 0.0000, -82.7485, 2.0425),
        Pair(50.0000, 3.1571, -77.2803, 50.0000, 0.0000, -82.7485, 2.8615),
        Pair(50.0000, 2.8361, -74.0200, 50.0000, 0.0000, -82.7485, 3.4412),
        Pair(50.0000, -1.3802, -84.2814, 50.0000, 0.0000, -82.7485, 1.0000),
        Pair(50.0000, -1.1848, -84.8006, 50.0000, 0.0000, -82.7485, 1.0000),
        Pair(50.0000, -0.9009, -85.5211, 50.0000, 0.0000, -82.7485, 1.0000),
        Pair(50.0000, 0.0000, 0.0000, 50.0000, -1.0000, 2.0000, 2.3669),
        Pair(50.0000, -1.0000, 2.0000, 50.0000, 0.0000, 0.0000, 2.3669),
        Pair(50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0009, 7.1792),
        Pair(50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0010, 7.1792),
        Pair(50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0011, 7.2195),
        Pair(50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0012, 7.2195),
        Pair(50.0000, -0.0010, 2.4900, 50.0000, 0.0009, -2.4900, 4.8045),
        Pair(50.0000, -0.0010, 2.4900, 50.0000, 0.0010, -2.4900, 4.8045),
        Pair(50.0000, -0.0010, 2.4900, 50.0000, 0.0011, -2.4900, 4.7461),
        Pair(50.0000, 2.5000, 0.0000, 50.0000, 0.0000, -2.5000, 4.3065),
        Pair(50.0000, 2.5000, 0.0000, 73.0000, 25.0000, -18.0000, 27.1492),
        Pair(50.0000, 2.5000, 0.0000, 61.0000, -5.0000, 29.0000, 22.8977),
        Pair(50.0000, 2.5000, 0.0000, 56.0000, -27.0000, -3.0000, 31.9030),
        Pair(50.0000, 2.5000, 0.0000, 58.0000, 24.0000, 15.0000, 19.4535),
        Pair(50.0000, 2.5000, 0.0000, 50.0000, 3.1736, 0.5854, 1.0000),
        Pair(50.0000, 2.5000, 0.0000, 50.0000, 3.2972, 0.0000, 1.0000),
        Pair(50.0000, 2.5000, 0.0000, 50.0000, 1.8634, 0.5757, 1.0000),
        Pair(50.0000, 2.5000, 0.0000, 50.0000, 3.2592, 0.3350, 1.0000),
        Pair(60.2574, -34.0099, 36.2677, 60.4626, -34.1751, 39.4387, 1.2644),
        Pair(63.0109, -31.0961, -5.8663, 62.8187, -29.7946, -4.0864, 1.2630),
        Pair(61.2901, 3.7196, -5.3901, 61.4292, 2.2480, -4.9620, 1.8731),
        Pair(35.0831, -44.1164, 3.7933, 35.0232, -40.0716, 1.5901, 1.8645),
        Pair(22.7233, 20.0904, -46.6940, 23.0331, 14.9730, -42.5619, 2.0373),
        Pair(36.4612, 47.8580, 18.3852, 36.2715, 50.5065, 21.2231, 1.4146),
        Pair(90.8027, -2.0831, 1.4410, 91.1528, -1.6435, 0.0447, 1.4441),
        Pair(90.9257, -0.5406, -0.9208, 88.6381, -0.8985, -0.7239, 1.5381),
        Pair(6.7747, -0.2908, -2.4247, 5.8714, -0.0985, -2.2286, 0.6377),
        Pair(2.0776, 0.0795, -1.1350, 0.9033, -0.0636, -0.5514, 0.9082),
    )

    @Test
    fun `deltaE00 matches Sharma 2005 official 34 test pairs`() {
        sharma34.forEachIndexed { index, p ->
            val actual = ColorMath.deltaE00(
                Triple(p.l1, p.a1, p.b1),
                Triple(p.l2, p.a2, p.b2),
            )
            assertEquals("pair #${index + 1}", p.expected, actual, 1e-4)
        }
    }

    @Test
    fun `deltaE00 argb fast path and symmetry`() {
        val a = 0xFFF97316.toInt()
        val b = 0xFF8B5CF6.toInt()
        assertEquals(0.0, ColorMath.deltaE00(a, a), 0.0)
        assertEquals(
            ColorMath.deltaE00(a, b),
            ColorMath.deltaE00(b, a),
            1e-9,
        )
    }

    /** 内置 7 分类色两两 ΔE00 最小间距必须远大于阈值 4.5（文档口径：最小 15.02）。 */
    @Test
    fun `builtin categories keep comfortable margin over threshold`() {
        val colors = listOf(
            "#2E9E6B", "#F97316", "#3B82F6", "#8B5CF6",
            "#EC4899", "#0E7490", "#64748B",
        ).mapNotNull { ColorMath.parseOrNull(it) }
        var min = Double.MAX_VALUE
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) {
                min = minOf(min, ColorMath.deltaE00(colors[i], colors[j]))
            }
        }
        assert(min > 4.5) { "内置色最小间距 $min 应远大于阈值 4.5" }
    }

    @Test
    fun `white contrast ratio sanity`() {
        // 白底上的白字 = 1:1；黑底上的白字 = 21:1
        assertEquals(1.0, ColorMath.whiteContrastRatio(0xFFFFFFFF.toInt()), 0.01)
        assertEquals(21.0, ColorMath.whiteContrastRatio(0xFF000000.toInt()), 0.01)
    }
}
