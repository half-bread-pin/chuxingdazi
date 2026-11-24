package com.visualzyzy.chuxingdazi.ui.trip

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithPlaceRow
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import java.time.LocalDate
import java.util.UUID

/**
 * S15 安排时间面板（§6 S15）：为点位新建一段安排，或修改已有的一段。
 * 每次只处理一段；同一点位可有多段，彼此独立。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ScheduleEditPanel(
    tripId: String,
    tripName: String,
    tripStart: LocalDate,
    tripDays: Int,
    placeId: String,
    placeName: String,
    // 第十四轮（2026-11-12）：新建模式的标题覆盖（点位重复安排入口 =「再安排一段「××」」）；null = 默认标题
    titleOverride: String? = null,
    existing: ScheduleEntity?,
    initialDate: LocalDate,
    initialStartMin: Int,
    initialEndMin: Int,
    allSchedules: List<ScheduleWithPlaceRow>,
    onSave: (schedule: ScheduleEntity, onResult: (ok: Boolean, err: String?) -> Unit) -> Unit,
    onRemove: (() -> Unit)?,
    onClose: () -> Unit,
    // 第二十五轮（2026-11-14）：「再安排一段」从长按菜单挪进本面板（仅编辑模式传入）——
    // 点它关本面板、以源段同日期同时长开 S15 新建模式（见 TripTimelineScreen duplicateTarget 分支）。
    // 第三十二轮（2026-11-15）：按钮移到标题行日期右侧、文本改「＋添加安排」（样式仿「在地图上显示」）。
    onRepeat: (() -> Unit)? = null,
    // 第二十五轮复测修正（2026-11-15，用户反馈）：再安排一段**不再预填源段时间**——
    // 预填完全相同时间 = 一保存就和源段原样重叠，毫无意义；改空白时间由用户自己填
    //（日期仍预填源段日期——通常还是那天；时间空态显示「--:--」引导点开时间选择器）。
    emptyTime: Boolean = false,
    // 2026-11-23 #9：编辑模式日期胶囊 = 该点位已有的各段（点选 = 切换编辑目标段）。
    // 仅编辑模式传入（新建/再安排一段为 null，维持全行程日胶囊）。调用方同时以
    // key(段 id) 包裹面板，段一换整面板重建、时间范围跟着新段走。
    onSwitchSegment: ((ScheduleWithPlaceRow) -> Unit)? = null,
) {
    var planDate by remember {
        mutableStateOf(existing?.let { LocalDate.parse(it.planDate) } ?: initialDate)
    }
    // 空白时间语义：-1 = 未填，保存按钮置灰（提示先填时间）；填了就是真实分钟数
    var startMin by remember {
        mutableStateOf(if (emptyTime && existing == null) -1 else (existing?.startMin ?: initialStartMin))
    }
    var endMin by remember {
        mutableStateOf(if (emptyTime && existing == null) -1 else (existing?.endMin ?: initialEndMin))
    }
    var pickerTarget by remember { mutableStateOf<Int?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun dayOffsetOf(date: LocalDate): Int = java.time.temporal.ChronoUnit.DAYS.between(tripStart, date).toInt()

    // 第二十五轮复测修正：空白时间（-1）不参与冲突区间计算（未填时间谈不上重叠）
    val timeEmpty = startMin < 0 || endMin < 0
    fun absStart(): Int = dayOffsetOf(planDate) * 1440 + startMin
    fun absEnd(): Int = dayOffsetOf(planDate) * 1440 + endMin

    // 冲突/提示（§6 S15 提示卡：绿 = 来得及，黄 = 日期区间外，红 = 与其他安排重叠）
    val conflict = if (timeEmpty) null else allSchedules.firstOrNull { row ->
        row.schedule.id != existing?.id &&
            TripTime.dayOffset(tripStart, row.schedule.planDate) * 1440 + row.schedule.startMin < absEnd() &&
            TripTime.dayOffset(tripStart, row.schedule.planDate) * 1440 + row.schedule.endMin > absStart()
    }
    val outOfTrip = dayOffsetOf(planDate) < 0 || dayOffsetOf(planDate) >= tripDays

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩（34% 黑，§6 S15）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x59000000))
                .clickable(onClick = onClose),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(horizontal = 16.dp),
        ) {
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                .align(Alignment.CenterHorizontally),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // 2026-11-22 修复 #4：编辑模式标题带点位名（原写死「修改这段安排」——
                    // 同店多段时无法确认在改哪段，只能开数据库对照）
                    // 2026-11-23 #12：长名标题改 marquee 滚动（原省略号截断连右引号都看不到）；
                    // 必须在有界宽度的 weight(1f) 容器内（本处满足）。
                    text = titleOverride
                        ?: if (existing == null) "安排「$placeName」" else "修改「$placeName」的这段安排",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$tripName · ${TripTime.dateText(planDate)} ${TripTime.weekdayText(planDate)}",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 第三十二轮（2026-11-15，用户反馈 333.jpg）：「再安排一段」从提示卡下方居中药丸
            // 移到标题行日期右侧、文本改「＋添加安排」，样式对齐 S14 顶栏「在地图上显示」
            //（主色文字、无底色胶囊）；仍为编辑模式独有，点它关本面板开新建模式。
            if (onRepeat != null) {
                Text(
                    text = "＋添加安排",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onRepeat() }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
            Text(
                text = "✕",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(6.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 日期胶囊（2026-11-23 #9 分模式）：
        // - 编辑模式（有 onSwitchSegment）= 该点位已有的各段；点选 = 切换编辑目标段
        //   （想加新的用「＋添加安排」——「改日期」入口按用户定调移除）；
        // - 新建/再安排一段 = 维持既有全行程日胶囊（repeat(tripDays)）。
        if (existing != null && onSwitchSegment != null) {
            val mySegments = allSchedules
                .filter { it.schedule.placeId == placeId }
                .sortedBy {
                    TripTime.absMinutes(TripTime.dayOffset(tripStart, it.schedule.planDate), it.schedule.startMin)
                }
            val perDateCount = mySegments.groupingBy { it.schedule.planDate }.eachCount()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                mySegments.forEach { seg ->
                    val segDate = java.time.LocalDate.parse(seg.schedule.planDate)
                    val selected = seg.schedule.id == existing.id
                    // 同一天有多段时第二行显示时间范围以区分，否则显示周X（与全日期胶囊同观感）
                    val secondLine = if ((perDateCount[seg.schedule.planDate] ?: 0) > 1) {
                        buildString {
                            append(TripTime.minutesText(seg.schedule.startMin))
                            append("–")
                            if (seg.schedule.endMin >= 1440) append("次日")
                            append(TripTime.minutesText(seg.schedule.endMin % 1440))
                        }
                    } else {
                        TripTime.weekdayText(segDate)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(88.dp)
                            .background(
                                if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                if (seg.schedule.id == existing.id) return@clickable
                                onSwitchSegment(seg)
                            }
                            .padding(vertical = 5.dp),
                    ) {
                        Text(
                            text = TripTime.dateText(segDate),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = secondLine,
                            fontSize = 9.5.sp,
                            color = if (selected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                repeat(tripDays) { idx ->
                    val date = tripStart.plusDays(idx.toLong())
                    val selected = date == planDate
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(88.dp)
                            .background(
                                if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                planDate = date
                                errorText = null
                            }
                            .padding(vertical = 5.dp),
                    ) {
                        Text(
                            text = TripTime.dateText(date),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = TripTime.weekdayText(date),
                            fontSize = 9.5.sp,
                            color = if (selected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 时间行：开始 / 结束两张大卡（§6 S15 布局 3）
        // 第二十五轮复测修正（2026-11-15）：再安排一段 = 空白时间（--:--），用户自己填；
        // 未填齐时点时间卡正常开选择器，保存按钮置灰（见底部按钮 enabled 判定）。
        val timeEmpty = startMin < 0 || endMin < 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeCard(
                label = "开始",
                text = if (startMin < 0) "--:--" else TripTime.minutesText(startMin),
                modifier = Modifier.weight(1f),
            ) { pickerTarget = 0 }
            Text(text = "→", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                val endDayShift = if (endMin < 0) 0 else endMin / 1440
                TimeCard(
                    label = when {
                        endMin < 0 -> "结束"
                        endDayShift >= 2 -> "结束（+${endDayShift} 天）"
                        endDayShift == 1 -> "结束（次日）"
                        else -> "结束"
                    },
                    text = when {
                        endMin < 0 -> "--:--"
                        endMin >= 1440 -> "${TripTime.dateText(planDate.plusDays(endDayShift.toLong()))} ${TripTime.minutesText(endMin % 1440)}"
                        else -> TripTime.minutesText(endMin)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { pickerTarget = 1 }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // P9：结束日期 ± 天（支持「露营 3 天」类跨多天安排；存储 endMin 上限 start+30 天，§5.3）
        // 第九轮：只要存在次日部分（endMin ≥ 1440）就允许 −1 天；减完若结束早于开始，
        // 自动置为开始 + 1 分钟（§6 S14 用户规则）
        val endDayShift = if (endMin < 0) 0 else endMin / 1440
        val endDate = planDate.plusDays(endDayShift.toLong())
        val canShrink = !timeEmpty && endMin >= 1440
        val canGrow = !timeEmpty && endMin + 1440 <= startMin + 43200
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "结束日期",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (timeEmpty) "待填时间后确定" else "${TripTime.dateText(endDate)} ${TripTime.weekdayText(endDate)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            DayShiftButton(text = "−1 天", enabled = canShrink) {
                endMin -= 1440
                if (endMin < startMin) endMin = startMin + 1
            }
            Spacer(modifier = Modifier.width(8.dp))
            DayShiftButton(text = "+1 天", enabled = canGrow) { endMin += 1440 }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (timeEmpty) "先点上方时间填开始与结束；也可拖动时间轴上的色块调整"
            else "预计停留 ${durationCrossText(endMin - startMin)} · 点时间数字可直接修改，或拖动时间轴上的色块调整",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 15.sp,
        )
        Spacer(modifier = Modifier.height(10.dp))

        // 提示卡（§6 S15 布局 5；P14/P25 2026-11-02：危险/警示色主题感知，黑底不再发黑）
        val (hintColor, hintText) = when {
            conflict != null -> MaterialTheme.colorScheme.error to "与「${conflict.placeName}」重叠（${timeRangeText(conflict.schedule, tripStart)}），仍可保存，但时间轴会重叠显示"
            errorText != null -> MaterialTheme.colorScheme.error to errorText!!
            outOfTrip -> com.visualzyzy.chuxingdazi.ui.theme.warningAmber(androidx.compose.foundation.isSystemInDarkTheme()) to "该日期在行程日期之外，仍可保存（行程 ${TripTime.dateText(tripStart)} – ${TripTime.dateText(tripStart.plusDays((tripDays - 1).toLong()))}）"
            else -> Color(0xFF1B7F3B) to "来得及 · 与其他安排不冲突"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(hintColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(hintColor, CircleShape),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = hintText, fontSize = 11.5.sp, color = hintColor, lineHeight = 16.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 底部按钮（§6 S15 布局 6）
        Row {
            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .width(112.dp)
                        .height(46.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp))
                        .clickable { confirmRemove = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "移出时间轴", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .background(
                        when {
                            saving -> Primary.copy(alpha = 0.6f)
                            timeEmpty -> MaterialTheme.colorScheme.surfaceVariant
                            else -> Primary
                        },
                        RoundedCornerShape(13.dp),
                    )
                    // 第二十五轮复测修正：空白时间（再安排一段）保存置灰——先填开始/结束
                    .clickable(enabled = !saving && !timeEmpty) {
                        saving = true
                        errorText = null
                        val now = System.currentTimeMillis()
                        val entity = ScheduleEntity(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            tripId = tripId,
                            placeId = placeId,
                            planDate = planDate.toString(),
                            startMin = startMin,
                            endMin = endMin,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        )
                        onSave(entity) { ok, err ->
                            saving = false
                            if (ok) onClose() else errorText = err
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (timeEmpty) "先填开始与结束时间" else "保存安排",
                    color = if (timeEmpty) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                    fontSize = if (timeEmpty) 12.5.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }

    // 时间选择器（滚轮；2026-11-22 修复 #2：吸附粒度 5 分钟→1 分钟——火车到站 08:43 这类
    // 硬锚点原本只能存成 08:45；存储层与时间轴渲染本就支持任意分钟，吸附纯属录入层限制。
    // 时间轴拖拽色块的 15 分钟吸附是另一条路径，不受影响）
    // 第九轮（2026-11-08）：持续时间只作计算结果——改开始不再保持时长平移结束；
    // 结束保持已有的跨天部分（点了 +1 天后改时间，次日那部分始终存在）。
    // 结束早于开始 → 贴齐为开始 + 1 分钟（0 分钟持续 = 开始等于结束，允许）。
    pickerTarget?.let { target ->
        // 空白时间打开选择器：初始给 09:00 起步值（第二十五轮复测修正）
        val initial = when {
            target == 0 && startMin < 0 -> 9 * 60
            target == 0 -> startMin
            endMin < 0 -> 10 * 60
            else -> endMin % 1440
        }
        val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickerTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    // 吸附 1 分钟 = 原值取整到 1 分钟（即不取整），用户输什么存什么
                    val picked = state.hour * 60 + state.minute
                    if (target == 0) {
                        startMin = picked
                        if (endMin in 0..startMin) endMin = startMin + 60 // 空态填好开始 → 结束还没填则给 1 小时起步
                    } else {
                        // endMin=1440 视为当天 24:00；>1440 保持跨天部分；空态(-1)视为当天
                        val dayShift = if (endMin <= 0) 0 else endMin / 1440
                        endMin = dayShift * 1440 + picked
                        if (endMin < startMin) endMin = startMin + 1
                    }
                    pickerTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickerTarget = null }) { Text("取消") } },
            text = { TimeInput(state = state) },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("移出时间轴？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("只删除这一段安排，点位保留在行程里（回到待安排池）。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove?.invoke()
                }) { Text("移出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TimeCard(label: String, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = text,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** P9 结束日期 ± 天小按钮（禁用时 38% 透明）。 */
@Composable
private fun DayShiftButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) Primary else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier
            .border(1.dp, if (enabled) Primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun timeRangeText(schedule: ScheduleEntity, tripStart: LocalDate): String {
    val s = TripTime.minutesText(schedule.startMin)
    val e = if (schedule.endMin >= 1440) {
        val endDayOffset = TripTime.dayOffset(tripStart, schedule.planDate) + schedule.endMin / 1440
        "${TripTime.dateText(tripStart.plusDays(endDayOffset.toLong()))} ${TripTime.minutesText(schedule.endMin % 1440)}"
    } else {
        TripTime.minutesText(schedule.endMin)
    }
    return "$s – $e"
}

internal fun durationText(minutes: Int): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
}

internal fun durationCrossText(minutes: Int): String = when {
    minutes < 1440 -> "共 ${durationText(minutes)}"
    else -> {
        val d = minutes / 1440
        val rest = minutes % 1440
        "共 $d 天${if (rest > 0) " ${durationText(rest)}" else ""}"
    }
}
