package com.visualzyzy.chuxingdazi.ui.navi

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gcj02ToBd09 / bd09ToGcJ02 公式正确性回归防线（2026-11-18，遗留 #11 双重偏移修复；
 * 同日遗留 #10 把 bd09ToGcJ02 提为公开公式供 DB v7→v8 迁移使用，锚点同步锁死）。
 * 旧实现偏移在输入端（x/y 进 z/θ）与输出端各加一次，与标准公式差 ~868 米——
 * 特征是「与标准 bd09togcj02 做往返残差 ~0.0065 度（~728 米）」，本测试把它锁死。
 */
class Bd09ConversionTest {

    @Test
    fun canonicalAnchorValues() {
        // 期望值 = 标准 gcj02tobd09 参照实现（Python 同公式）数值解，容差 1e-9 度。
        // 锚点 = 高德官方演示天安门 GCJ02 (39.90923, 116.397428)。
        val (lat, lng) = gcj02ToBd09(39.90923, 116.397428)
        assertTrue(abs(lat - 39.9155730161122) < 1e-9)
        assertTrue(abs(lng - 116.40380056450005) < 1e-9)
    }

    @Test
    fun bd09ToGcj02CanonicalAnchor() {
        // 遗留 #10（2026-11-18）：公开 bd09ToGcJ02 的锚点 = 上面 gcj02 锚点值的逆解
        //（Python 标准公式数值解，容差 1e-8 度）——DB v7→v8 存量换算与
        // tools_seed/backup_bd09_to_gcj02.py 都依赖此公式，锁死防回归。
        val (lat, lng) = bd09ToGcJ02(39.9155730161122, 116.40380056450005)
        assertTrue(abs(lat - 39.909230423200576) < 1e-8)
        assertTrue(abs(lng - 116.39742818953859) < 1e-8)
    }

    @Test
    fun roundTripWithStandardInverse() {
        // gcj02ToBd09 与 bd09ToGcJ02 互为逆：往返残差 <1e-5 度（约 1 米）。
        // 旧 bug 在此测试下残差 ~0.0065 度（~728 米），直接爆掉。
        val samples = listOf(
            39.90923 to 116.397428, // 北京 天安门
            39.9042 to 116.3914,
            30.2561 to 120.1428, // 杭州（设计文档示例坐标）
            20.044 to 110.1999, // 海口（低纬）
        )
        for ((la, lo) in samples) {
            val (blat, blng) = gcj02ToBd09(la, lo)
            val (glat, glng) = bd09ToGcJ02(blat, blng)
            assertTrue("lat 往返残差超限: $glat vs $la", abs(glat - la) < 1e-5)
            assertTrue("lng 往返残差超限: $glng vs $lo", abs(glng - lo) < 1e-5)
        }
    }
}
