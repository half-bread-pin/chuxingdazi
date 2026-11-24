package com.visualzyzy.chuxingdazi.ui.common

/**
 * 省份-城市副标题（遗留 #9 修复，2026-11-18）：直辖市/特别行政区的市级子区域与省同名
 * （district 表「北京市 → 北京市」），裸拼接会显示「北京市-北京市」——省市同名时只显示一个。
 * 此前只有 S29 的 regionLabel 有去重，S28/S24/S21/S07 详情卡/S10 选点浮层都是裸 joinToString("-")。
 *
 * @return 「省-市」/「省」/「市」；两者都缺 = null（调用方自行兜底「暂无地址」等文案）
 */
fun regionLabelOf(province: String?, city: String?): String? {
    val p = province?.takeIf { it.isNotBlank() }
    val c = city?.takeIf { it.isNotBlank() }
    return when {
        p != null && c != null && p != c -> "$p-$c"
        p != null -> p
        else -> c
    }
}
