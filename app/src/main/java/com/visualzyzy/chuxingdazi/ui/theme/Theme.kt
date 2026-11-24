package com.visualzyzy.chuxingdazi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLightBg,
    onPrimaryContainer = PrimaryDark,
    secondary = PrimaryDark,
    onSecondary = Color.White,
    background = PageBackground,
    onBackground = TextPrimary,
    surface = PageBackground,
    onSurface = TextPrimary,
    surfaceVariant = GroupBackground,
    onSurfaceVariant = TextSecondary,
    outlineVariant = Divider,
    error = CategoryEvent,
)

private val DarkColorScheme = darkColorScheme(
    // P14（2026-11-02）：暗色态 primary 用浅青——文字/描边在深底上才有对比度；
    // 实底按钮仍直接用 Primary 常量 + 白字（对比度不受影响）；onPrimary 保持白色供默认组件（Switch 滑块等）使用
    primary = PrimaryBright,
    onPrimary = Color.White,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = PrimaryLightBg,
    background = Color(0xFF101418),
    onBackground = Color(0xFFE6EAEE),
    surface = Color(0xFF161B21),
    onSurface = Color(0xFFE6EAEE),
    surfaceVariant = Color(0xFF1E252D),
    onSurfaceVariant = Color(0xFFA6AFBC),
    outlineVariant = Color(0xFF2A323B),
    error = CategoryEvent,
)

// 圆角（设计文档 §6）：卡片 16 / 按钮 14 / 输入框 13 / 浮层 24（顶部两角）
private val ChuxingdaziShapes = Shapes(
    extraSmall = RoundedCornerShape(13.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val ChuxingdaziTypography = Typography(
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp),
)

@Composable
fun ChuxingdaziTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = ChuxingdaziTypography,
        shapes = ChuxingdaziShapes,
        content = content,
    )
}
