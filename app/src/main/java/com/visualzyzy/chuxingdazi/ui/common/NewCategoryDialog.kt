package com.visualzyzy.chuxingdazi.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 新建分类对话框（第十一轮 2026-11-11 从 S06 提为公共组件）：
 * S06 卡片 / s05_add_point 添加页 / S08 编辑点位共用。回调式（不依赖具体 ViewModel）。
 *
 * 2026-11-19 新增「新建颜色」：色板末尾的 ＋ 圆点 → 调用方跳 S18 草稿模式调色，回传色值
 * 经 [pendingCustomColor] 注入（与 S09 分类编辑面板同一条 savedStateHandle 链）。
 * 注意：去 S18 调色会经历一次跨页往返（本屏组合被销毁重建），对话框内部状态一律
 * rememberSaveable——回来后已填名称/已选图标不丢。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewCategoryDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, iconKey: String, color: String) -> Unit,
    onOpenManager: () -> Unit = {},
    // 2026-11-19 新建颜色：点 ＋ 圆点 → 跳 S18 草稿模式（initial = 当前色）
    onPickCustomColor: (currentHex: String) -> Unit = {},
    // S18 回传的自定义色（调用方从 savedStateHandle 流转发进来；消费后置 null）
    pendingCustomColor: String? = null,
    onConsumeCustomColor: () -> Unit = {},
) {
    // 跨页往返（S18 调色）可恢复：全部走 rememberSaveable
    var name by rememberSaveable { mutableStateOf("") }
    var iconKey by rememberSaveable { mutableStateOf("other_point") }
    var colorHex by rememberSaveable { mutableStateOf("#0EA5E9") }
    val colorOptions = listOf(
        "#0EA5E9", "#2E9E6B", "#F97316", "#8B5CF6", "#3B82F6",
        "#EC4899", "#E5484D", "#D946EF", "#14B8A6", "#64748B",
    )

    // S18 草稿调色回传 → 应用为当前色
    LaunchedEffect(pendingCustomColor) {
        if (!pendingCustomColor.isNullOrBlank()) {
            colorHex = pendingCustomColor
            onConsumeCustomColor()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "新建分类") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = NameLimit.clamp(it) },
                    singleLine = true,
                    placeholder = { Text("例：咖啡馆") },
                    shape = RoundedCornerShape(11.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "图标", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                // 第十六轮：新建分类的图标选择改用与 S09/图标选择器同一套分组网格（98 图），
                // 不再只有内置 7 个——四个入口共用同一套选择器
                val gridScroll = rememberScrollState()
                IconGridSections(
                    selectedKey = iconKey,
                    onSelect = { iconKey = it },
                    scrollState = gridScroll,
                    modifier = Modifier
                        .height(230.dp)
                        .verticalScroll(gridScroll),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "颜色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                // P17（2026-11-02）：10 色 FlowRow 换行（每行 5 个），保持正圆
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    colorOptions.forEach { hex ->
                        val selected = hex == colorHex
                        ColorDot(
                            hex = hex,
                            selected = selected,
                            onClick = { colorHex = hex },
                        )
                    }
                    // 2026-11-19 新建颜色（用户反馈 111.jpg）：当前色不在预置色板 → 追加一枚
                    // 自定义色圆点（选中态），始终在色板末尾提供「＋ 新建颜色」入口
                    if (colorHex !in colorOptions) {
                        ColorDot(
                            hex = colorHex,
                            selected = true,
                            onClick = { },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        androidx.compose.ui.graphics.Color(0xFF14B8A6),
                                        androidx.compose.ui.graphics.Color(0xFF8B5CF6),
                                        androidx.compose.ui.graphics.Color(0xFFF97316),
                                        androidx.compose.ui.graphics.Color(0xFFEC4899),
                                        MaterialTheme.colorScheme.primary,
                                    ),
                                ),
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onPickCustomColor(colorHex) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "＋",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "管理全部分类 →",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onOpenManager),
                )
            }
        },
        confirmButton = {
            Text(
                text = "创建",
                color = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = name.isNotBlank()) { onCreate(name.trim(), iconKey, colorHex) }
                    .padding(8.dp),
            )
        },
        dismissButton = {
            Text(
                text = "取消",
                modifier = Modifier.clickable { onDismiss() }.padding(8.dp),
            )
        },
    )
}

/** 色板圆点（28dp 正圆；选中 = 3dp 深描边，原 P17 样式）。 */
@Composable
private fun ColorDot(hex: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(parseCategoryColorSafe(hex))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}
