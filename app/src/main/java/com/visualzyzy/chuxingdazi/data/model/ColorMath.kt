package com.visualzyzy.chuxingdazi.data.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 颜色数学（§6 S18）：RGB↔Lab 转换、CIEDE2000 色差、WCAG 对比度。
 *
 * 第七轮（2026-11-06）：
 * - 色差公式 CIE76（ΔE76<10）→ **CIEDE2000**（用户决策：ΔE00 < 4.5 拒绝）。CIE76 在高彩度
 *   蓝区系统性高估色差（同对 ΔE76=10 的 ΔE00 最低仅 1.84），换公式本身修 bug；
 *   阈值 4.5 按「用户不介意多给一点可选色」放宽（2026-11-06 标定留档推荐 5，取 4.5 更松）。
 * - 实现先过 Sharma, Wu & Dalal (2005) 官方 34 测试对（ColorMathTest 全部 1e-4 级吻合）才可信。
 * - 对比度提示规则（用户决策）：白图标对比度 **< 2.0** 时提示「户外强光下可能看不清」，
 *   不管明度/饱和度（原 L*30–75/S≥35% 启发式提示删除）；≥2.0 不提示、不拦截（建议非硬拦）。
 */
object ColorMath {

    /** RGB(int ARGB) → CIE Lab（D65 白点）。 */
    fun rgbToLab(argb: Int): Triple<Double, Double, Double> {
        val r = srgbToLinear(((argb shr 16) and 0xFF) / 255.0)
        val g = srgbToLinear(((argb shr 8) and 0xFF) / 255.0)
        val b = srgbToLinear((argb and 0xFF) / 255.0)

        // sRGB → XYZ（D65）
        val x = r * 0.4124564 + g * 0.3575761 + b * 0.1804375
        val y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750
        val z = r * 0.0193339 + g * 0.1191920 + b * 0.9503041

        // XYZ → Lab（D65 参考白）
        val xn = 0.95047
        val yn = 1.0
        val zn = 1.08883
        val fx = labF(x / xn)
        val fy = labF(y / yn)
        val fz = labF(z / zn)
        return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun labF(t: Double): Double =
        if (t > 216.0 / 24389.0) cbrt(t) else (841.0 / 108.0) * t + 4.0 / 29.0

    /**
     * CIEDE2000 色差（Sharma 2005 实现注记；入参为 CIELab 三元组）。
     * 精心处理四象限色相均值与 a* 轴蓝区修正（SC=1+0.045·C̄′ —— 不是 SH 的 0.015，写错校验必炸）。
     */
    fun deltaE00(lab1: Triple<Double, Double, Double>, lab2: Triple<Double, Double, Double>): Double {
        val (l1, a1, b1) = lab1
        val (l2, a2, b2) = lab2

        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1 + c2) / 2.0
        val cBar7 = cBar.pow(7)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + 25.0.pow(7))))

        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)

        // 色相角（0–360°；atan2 结果为弧度）
        var h1p = Math.toDegrees(atan2(b1, a1p))
        if (h1p < 0) h1p += 360.0
        var h2p = Math.toDegrees(atan2(b2, a2p))
        if (h2p < 0) h2p += 360.0

        val dLp = l2 - l1
        val dCp = c2p - c1p

        val productZero = c1p * c2p == 0.0
        var dhp: Double
        when {
            productZero -> dhp = 0.0
            abs(h2p - h1p) <= 180.0 -> dhp = h2p - h1p
            h2p - h1p > 180.0 -> dhp = h2p - h1p - 360.0
            else -> dhp = h2p - h1p + 360.0
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))

        val lBarP = (l1 + l2) / 2.0
        val cBarP = (c1p + c2p) / 2.0

        var hBarP: Double
        when {
            productZero -> hBarP = h1p + h2p
            abs(h1p - h2p) <= 180.0 -> hBarP = (h1p + h2p) / 2.0
            h1p + h2p < 360.0 -> hBarP = (h1p + h2p + 360.0) / 2.0
            else -> hBarP = (h1p + h2p - 360.0) / 2.0
        }

        val t = 1.0 - 0.17 * cos(Math.toRadians(hBarP - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hBarP)) +
            0.32 * cos(Math.toRadians(3.0 * hBarP + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hBarP - 63.0))

        val dTheta = 30.0 * exp(-((hBarP - 275.0) / 25.0).pow(2))
        val cBarP7 = cBarP.pow(7)
        val rc = 2.0 * sqrt(cBarP7 / (cBarP7 + 25.0.pow(7)))
        val sl = 1.0 + 0.015 * (lBarP - 50.0).pow(2) / sqrt(20.0 + (lBarP - 50.0).pow(2))
        val sc = 1.0 + 0.045 * cBarP
        val sh = 1.0 + 0.015 * cBarP * t
        val rt = -sin(Math.toRadians(2.0 * dTheta)) * rc

        val tl = dLp / sl
        val tc = dCp / sc
        val th = dHp / sh
        return sqrt(tl * tl + tc * tc + th * th + rt * tc * th)
    }

    /** CIEDE2000（ARGB 直入）。同色快速路径（拖动选色时逐帧比对用）。 */
    fun deltaE00(argb1: Int, argb2: Int): Double {
        if (argb1 == argb2) return 0.0
        return deltaE00(rgbToLab(argb1), rgbToLab(argb2))
    }

    /** 白色文字/图标对该底色的 WCAG 对比度（≥3:1 为非文本建议线）。 */
    fun whiteContrastRatio(argb: Int): Double {
        val l = relativeLuminance(argb)
        return (1.0 + 0.05) / (l + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        val r = srgbToLinear(((argb shr 16) and 0xFF) / 255.0)
        val g = srgbToLinear(((argb shr 8) and 0xFF) / 255.0)
        val b = srgbToLinear((argb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun hexOf(argb: Int): String = "#%02X%02X%02X".format(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
    )

    fun parseOrNull(hex: String): Int? =
        runCatching { android.graphics.Color.parseColor(hex.trim()) }.getOrNull()

    private fun Double.round1(): String = abs(this).let { "%.0f".format(it) }
}
