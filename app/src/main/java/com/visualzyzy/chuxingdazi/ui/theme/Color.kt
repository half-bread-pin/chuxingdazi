package com.visualzyzy.chuxingdazi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

// 通用设计规范（设计文档 §6）
val Primary = Color(0xFF0E7490) // 主色 青蓝（浅色态文字/实底按钮底色）
val PrimaryBright = Color(0xFF5BC6E3) // 暗色态文字/描边主色（P14：#0E7490 在黑底上对比度不足）
val PrimaryLightBg = Color(0xFFEAF4F6) // 浅底
val PrimaryDark = Color(0xFF0B5F75) // 深色态
val TextPrimary = Color(0xFF14171C)
val TextSecondary = Color(0xFF5B6472)
val TextTertiary = Color(0xFF939CAA)
val Divider = Color(0xFFEFF2F6)
val PageBackground = Color(0xFFFFFFFF)
val GroupBackground = Color(0xFFF7F8FA)

// 分类色板（地图标记、分类筛选、表单高亮统一使用）
val CategorySight = Color(0xFF2E9E6B) // 景点
val CategoryFood = Color(0xFFF97316) // 美食
val CategoryStay = Color(0xFF8B5CF6) // 住宿
val CategoryTransit = Color(0xFF3B82F6) // 交通
val CategoryShop = Color(0xFFEC4899) // 购物
val CategoryEvent = Color(0xFFE5484D) // 活动
val CategoryOther = Color(0xFF64748B) // 其他

/**
 * P14/P25（2026-11-02）暗色对比度适配：
 * - 文字/描边类的主色一律走 MaterialTheme.colorScheme.primary（暗色 scheme 已换成 PrimaryBright）；
 * - 警示琥珀色两套明暗取值（#9A6B00 在黑底上太暗）。
 */
fun warningAmber(dark: Boolean): Color = if (dark) Color(0xFFE8A800) else Color(0xFF9A6B00)
