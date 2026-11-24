package com.visualzyzy.chuxingdazi.data.tencent

/**
 * 公交/地铁线路稳定配色（决策 #96，docs/路线规划优化方案.md §6.1）。
 * SDK 不给线路官方颜色 → 按线路名哈希从固定色板取色：同一条线永远同色、不同线不同色
 * （像地图 App 每条地铁线一个颜色）；相邻两个车段撞色顺延一格保证可区分。
 * 自百度版原样迁移（SDK 无关，仅包名随目录调整）。
 */
object RouteLineColors {

    /** 14 色板：中等饱和度/明度，白 chevron 纹理可辨、明暗底图可辨、彼此 CIE 距离足够。 */
    private val PALETTE = intArrayOf(
        0xFFD64541.toInt(), // 红
        0xFFF07617.toInt(), // 橙
        0xFFC9A227.toInt(), // 芥黄（压暗，白箭头可辨）
        0xFF2E9E5B.toInt(), // 绿
        0xFF0E7490.toInt(), // 青
        0xFF2A7DE1.toInt(), // 蓝
        0xFF5B5BD6.toInt(), // 靛
        0xFF8B5CF6.toInt(), // 紫
        0xFFC026D3.toInt(), // 品红
        0xFFDB2777.toInt(), // 玫粉
        0xFF92400E.toInt(), // 棕
        0xFF475569.toInt(), // 蓝灰
        0xFF0F766E.toInt(), // 深青绿
        0xFF7E22CE.toInt(), // 深紫
    )

    /** 线路名 → 稳定色（空名回退蓝色，等同路线页主色族）。 */
    fun colorOf(lineTitle: String?): Int {
        if (lineTitle.isNullOrBlank()) return PALETTE[5]
        val h = kotlin.math.abs(lineTitle.hashCode())
        return PALETTE[h % PALETTE.size]
    }

    /** 线路名 → 稳定色，但避开 [avoid]（相邻车段撞色顺延一格）。 */
    fun colorOfAvoiding(lineTitle: String?, avoid: Int?): Int {
        val base = colorOf(lineTitle)
        if (avoid == null || base != avoid) return base
        val idx = PALETTE.indexOf(base)
        return PALETTE[(idx + 1) % PALETTE.size]
    }
}
