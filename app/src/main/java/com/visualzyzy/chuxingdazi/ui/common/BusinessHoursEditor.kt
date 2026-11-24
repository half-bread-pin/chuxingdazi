package com.visualzyzy.chuxingdazi.ui.common

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.data.model.BusinessHoursCodec
import com.visualzyzy.chuxingdazi.data.model.FieldType
import com.visualzyzy.chuxingdazi.data.model.HoursGroup

/**
 * BUSINESS_HOURS 周分组多时段编辑器（字段模板升级 §4.3，2026-11-21）。
 *
 * 字段格 = 摘要文本（PickFieldBox）+ 点击弹出编辑对话框；
 * 对话框内：24小时营业开关 / 快捷 chips（每天·工作日·周末）/ 分组列表（周几 chips + 时段行
 * 点击双 TimeInput 编辑）/ ＋添加分组。上限：7 组 × 每组 4 段（§6.3 防手滑叠加）。
 * 「确定」全量校验（同一天跨组时段重叠 → Toast 不关，§4.3）+ §2.2 归一化落值；「取消」不写库。
 */
@Composable
internal fun BusinessHoursCell(value: String, onValueChange: (String) -> Unit) {
    var showEditor by remember { mutableStateOf(false) }
    val summary = remember(value) {
        BusinessHoursCodec.summarize(BusinessHoursCodec.decode(value))
    }
    PickFieldBox(
        display = summary.orEmpty(),
        placeholder = fieldPlaceholder(FieldType.BUSINESS_HOURS),
        onClick = { showEditor = true },
        onClear = { onValueChange("") },
    )
    if (showEditor) {
        BusinessHoursEditorDialog(
            initial = BusinessHoursCodec.decode(value),
            onDismiss = { showEditor = false },
            onConfirm = { groups ->
                onValueChange(BusinessHoursCodec.encodeNormalized(groups).first)
                showEditor = false
            },
        )
    }
}

private val ALL_DAYS = (0..6).toList()
private val WEEKDAYS = listOf(1, 2, 3, 4, 5)
private val WEEKEND = listOf(0, 6)
/** 全天营业槽（§2.2：24小时营业快捷生成）。 */
private val ALL_DAY_GROUP = HoursGroup(ALL_DAYS, listOf("00:00-23:59"))
/** 周几 chips 展示顺序（周一开始）↔ days 编码（0=周日..6=周六）。 */
private val CHIP_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
private val CHIP_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BusinessHoursEditorDialog(
    initial: List<HoursGroup>,
    onDismiss: () -> Unit,
    onConfirm: (List<HoursGroup>) -> Unit,
) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf(initial) }
    var allDay by remember { mutableStateOf(initial == listOf(ALL_DAY_GROUP)) }
    // 时段编辑态：(组下标, 段下标) + 当前阶段（0=选开始 1=选结束）+ 暂存开始分钟
    var editingSlot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editingPhase by remember { mutableIntStateOf(0) }
    var editStartMin by remember { mutableIntStateOf(0) }

    fun toggleAllDay() {
        allDay = !allDay
        if (allDay) groups = listOf(ALL_DAY_GROUP)
    }

    fun addQuick(days: List<Int>) {
        if (groups.any { it.days == days }) return // 已有同 days 组：不重复加（§4.3 快捷去重）
        if (groups.size >= 7) {
            Toast.makeText(context, "最多 7 个分组", Toast.LENGTH_SHORT).show()
            return
        }
        groups = groups + HoursGroup(days, listOf("09:00-18:00"))
    }

    fun updateSlot(gi: Int, si: Int, newSlot: String) {
        val g = groups[gi]
        groups = groups.toMutableList().also {
            it[gi] = g.copy(slots = g.slots.toMutableList().also { s -> s[si] = newSlot })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "营业时间", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 460.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggleAllDay() },
                ) {
                    Checkbox(
                        checked = allDay,
                        onCheckedChange = { toggleAllDay() },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                    )
                    Text(text = "24小时营业", fontSize = 14.sp)
                }
                if (!allDay) {
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("每天" to ALL_DAYS, "工作日" to WEEKDAYS, "周末" to WEEKEND).forEach { (label, days) ->
                            QuickChip(label = label, onClick = { addQuick(days) })
                        }
                    }
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                    groups.forEachIndexed { gi, g ->
                        GroupEditorCard(
                            index = gi + 1,
                            group = g,
                            onUpdate = { updated ->
                                groups = groups.toMutableList().also { it[gi] = updated }
                            },
                            onRemove = { groups = groups.toMutableList().also { it.removeAt(gi) } },
                            onSlotEdit = { si ->
                                editingSlot = gi to si
                                editingPhase = 0
                            },
                        )
                    }
                    Spacer(modifier = Modifier.padding(top = 6.dp))
                    if (groups.size < 7) {
                        Text(
                            text = "＋ 添加分组",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable {
                                    groups = groups + HoursGroup(emptyList(), listOf("09:00-18:00"))
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val finalGroups = if (allDay) listOf(ALL_DAY_GROUP) else groups
                val conflict = BusinessHoursCodec.findOverlapConflict(finalGroups)
                if (conflict != null) {
                    Toast.makeText(context, "${conflict}的时段重叠", Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(finalGroups)
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    // 时段编辑：两连 TimeInput（开始 → 结束），起<止校验
    editingSlot?.let { (gi, si) ->
        val current = groups.getOrNull(gi)?.slots?.getOrNull(si) ?: ""
        val startM = current.substringBefore("-").hm()
        val endM = current.substringAfter("-", "").hm()
        if (editingPhase == 0) {
            TimeInputDialog(
                initialMinutes = startM ?: (9 * 60),
                onDismiss = { editingSlot = null },
                onConfirm = { m ->
                    editStartMin = m
                    editingPhase = 1
                },
            )
        } else {
            TimeInputDialog(
                initialMinutes = endM ?: (editStartMin + 60).coerceAtMost(23 * 60 + 59),
                onDismiss = { editingSlot = null },
                onConfirm = { m ->
                    if (m <= editStartMin) {
                        Toast.makeText(context, "结束时间需晚于开始时间", Toast.LENGTH_SHORT).show()
                    } else {
                        updateSlot(gi, si, "%02d:%02d-%02d:%02d".format(editStartMin / 60, editStartMin % 60, m / 60, m % 60))
                        editingSlot = null
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupEditorCard(
    index: Int,
    group: HoursGroup,
    onUpdate: (HoursGroup) -> Unit,
    onRemove: () -> Unit,
    onSlotEdit: (Int) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "组 $index",
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "删组",
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.padding(top = 6.dp))
            // 周几 chips（组内多选 toggle）
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CHIP_ORDER.forEachIndexed { i, day ->
                    val on = day in group.days
                    Text(
                        text = CHIP_LABELS[i],
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (on) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .clickable {
                                val next = group.days.toMutableList()
                                if (on) next.remove(day) else next.add(day)
                                onUpdate(group.copy(days = next.sorted()))
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.padding(top = 6.dp))
            // 时段行：点击双 TimeInput 编辑；× 删段
            group.slots.forEachIndexed { si, slot ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = slot,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSlotEdit(si) }
                            .padding(vertical = 6.dp),
                    )
                    Text(
                        text = "×",
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable {
                                onUpdate(group.copy(slots = group.slots.toMutableList().also { it.removeAt(si) }))
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            if (group.slots.size < 4) {
                Text(
                    text = "＋时段",
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            if (group.slots.size >= 4) {
                                Toast.makeText(context, "每组最多 4 个时段", Toast.LENGTH_SHORT).show()
                            } else {
                                onUpdate(group.copy(slots = group.slots + "09:00-18:00"))
                            }
                        }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** "HH:mm" → 分钟；解析不了返回 null。 */
private fun String.hm(): Int? {
    val p = trim().split(":")
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}
