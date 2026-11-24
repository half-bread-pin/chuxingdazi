package com.visualzyzy.chuxingdazi.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

/**
 * P81（2026-11-05）：自绘日期选择对话框（替代 M3 DatePickerDialog）。
 * 星期行「一二三四五六日」（周一开头）、月份切换、今天描边、选中实心。
 * 不依赖 M3 内部 weekday 渲染，中文下不再出现「星星星星星」。
 *
 * 第十六轮（2026-11-14）：从 NewTripScreen 提升为公共组件——S14「选定这几天」
 * 与 S24 共用同一套日期选择器。
 */
@Composable
fun SimpleDatePickerDialog(
    title: String = "选择日期",
    initialMonth: YearMonth? = null,
    initialSelected: LocalDate? = null,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    var month by remember { mutableStateOf(initialMonth ?: YearMonth.now()) }
    var selected by remember { mutableStateOf(initialSelected) }
    val today = LocalDate.now()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                // 月份切换行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${month.year}年${month.monthValue}月",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "‹",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { month = month.minusMonths(1) }
                            .padding(horizontal = 10.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "›",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { month = month.plusMonths(1) }
                            .padding(horizontal = 10.dp),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                // 星期行（周一开头）
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                        Text(
                            text = d,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                // 日期网格（前导空白 = 当月 1 号是周几）
                val firstDow = month.atDay(1).dayOfWeek.value // Mon=1..Sun=7
                val leading = firstDow - 1
                val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
                val cells: List<LocalDate?> =
                    List(leading) { null } + days +
                        List((7 - (leading + days.size) % 7) % 7) { null }
                cells.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (day != null) {
                                    val isSelected = day == selected
                                    val isToday = day == today
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .then(
                                                when {
                                                    isSelected -> Modifier.background(
                                                        MaterialTheme.colorScheme.primary, CircleShape,
                                                    )
                                                    isToday -> Modifier.border(
                                                        1.dp, MaterialTheme.colorScheme.primary, CircleShape,
                                                    )
                                                    else -> Modifier
                                                },
                                            )
                                            .clickable { selected = day },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = day.dayOfMonth.toString(),
                                            fontSize = 12.5.sp,
                                            color = if (isSelected) {
                                                androidx.compose.ui.graphics.Color.White
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(34.dp))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let(onConfirm) },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 「选定这几天」（第十六轮 2026-11-14）：未定日期行程在 S14 的唯一日期入口。
 * 两步选完（先开始后结束），确认 = 写 trip.start_date/end_date；开始 > 结束时自动对调。
 */
@Composable
fun TripDateRangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (start: LocalDate, end: LocalDate) -> Unit,
) {
    var picking by remember { mutableStateOf(0) } // 0=选开始 1=选结束
    var start by remember { mutableStateOf<LocalDate?>(null) }
    var end by remember { mutableStateOf<LocalDate?>(null) }
    when (picking) {
        0 -> SimpleDatePickerDialog(
            title = "选定这几天 · 开始日期",
            initialSelected = start,
            onDismiss = onDismiss,
            onConfirm = {
                start = it
                picking = 1
            },
        )
        else -> SimpleDatePickerDialog(
            title = "选定这几天 · 结束日期（已选开始 ${start?.let { com.visualzyzy.chuxingdazi.ui.trip.TripTime.dateText(it) } ?: ""}）",
            initialMonth = end?.let { YearMonth.from(it) } ?: start?.let { YearMonth.from(it) },
            initialSelected = end,
            onDismiss = onDismiss,
            onConfirm = {
                val s = start ?: return@SimpleDatePickerDialog
                val e = it
                if (!e.isBefore(s)) {
                    onConfirm(s, e)
                } else {
                    // 结束早于开始：对调（与 S15「−1 天贴齐」同一宽容语义）
                    onConfirm(e, s)
                }
            },
        )
    }
}
