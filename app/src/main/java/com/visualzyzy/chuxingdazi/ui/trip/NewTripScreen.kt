package com.visualzyzy.chuxingdazi.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** S24 新建行程（§6 S24）：名称 + 日期 + 初始点位（选填）→ 创建并进时间轴。
 *  P31（2026-11-02）：所选点位逐行给可选时间（开始/结束/持续三栏联动），全选填。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTripScreen(
    onClose: () -> Unit,
    onCreated: (tripId: String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: NewTripViewModel = hiltViewModel()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    var name by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf<LocalDate?>(null) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(true) }
    var showPlacePicker by remember { mutableStateOf(false) }
    // P31：保序（选择顺序 = 逐行时间设置行的顺序）
    val selectedPlaceIds = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    // 每个所选点位的时间（第九轮：点击选择，不再手输）
    val placeTimes = remember { androidx.compose.runtime.mutableStateMapOf<String, PlaceTimeInput>() }
    // 当前弹时间选择器的点位（开始 / 结束）
    var startPickFor by remember { mutableStateOf<String?>(null) }
    var endPickFor by remember { mutableStateOf<String?>(null) }

    val dateText = when {
        startDate != null && endDate != null ->
            "${TripTime.dateText(startDate!!)} – ${TripTime.dateText(endDate!!)} · ${
                java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
            } 天"
        startDate != null -> "${TripTime.dateText(startDate!!)} 起 · 未定回程"
        else -> "未定日期（之后还能补）"
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onClose),
                )
                Text(
                    text = "新建行程",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(26.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Text(text = "名称", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = name,
                    // 遗留 #4 修复（2026-11-18）：旧「length ≤ 20」按字符数静默丢弃超长输入（无提示），
                    // 且 S23 改名框完全没有限长。统一为 §6 S24「最多 20 字」的宽度语义
                    //（半角 0.5 计，同 NameLimit）+ 超长截断 + Toast 提示。
                    onValueChange = {
                        name = com.visualzyzy.chuxingdazi.ui.common.NameLimit.clampTo(
                            it, TRIP_NAME_MAX_WIDTH, "行程名称最多20个字",
                        )
                    },
                    singleLine = true,
                    placeholder = { Text("例：杭州三日游", fontSize = 14.sp) },
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(14.dp))

                Text(text = "日期", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                pickingStart = true
                                showDatePicker = true
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = startDate?.let { TripTime.dateText(it) } ?: "开始日期",
                            fontSize = 13.5.sp,
                            color = if (startDate == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                if (startDate != null) {
                                    pickingStart = false
                                    showDatePicker = true
                                }
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = endDate?.let { TripTime.dateText(it) } ?: "结束日期",
                            fontSize = 13.5.sp,
                            color = if (endDate == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = dateText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(14.dp))

                // 初始点位（选填）：从点位库挑（§6 S24 布局 4）+ P31 逐行可选时间
                Text(text = "地点（选填）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectedPlaceIds.forEach { id ->
                        val place = places.firstOrNull { it.id == id } ?: return@FlowRow
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = place.name,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(80.dp),
                            )
                            Text(
                                text = "×",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable {
                                    selectedPlaceIds.remove(id)
                                    placeTimes.remove(id)
                                },
                            )
                        }
                    }
                    Text(
                        text = "＋ 从点位库挑",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Primary.copy(alpha = 0.08f))
                            .clickable { showPlacePicker = true }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                // P31（2026-11-02）逐行时间设置；第九轮（2026-11-08）改为点击选择：
                // 开始 = 点选时间（24 小时制，可选「全天」= 00:00–24:00）；结束 = 点选时间 + ±1 天跨天；
                // 持续时间 = 只读计算结果（可为 0，不为负）。结束不得早于开始（点早了自动贴齐开始）。
                selectedPlaceIds.forEachIndexed { idx, id ->
                    val place = places.firstOrNull { it.id == id } ?: return@forEachIndexed
                    val input = placeTimes[id] ?: PlaceTimeInput()
                    val duration = if (input.startMin != null && input.hasEnd) input.endMin - input.startMin else null
                    // 跨天徽标：24:00 当天不算跨天；超过 1440 显示 [+N]
                    val crossDays = if (input.hasEnd && input.endMin > 1440) input.endMin / 1440 else 0
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${idx + 1}. ${place.name}",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight(600),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (duration != null) {
                                Text(
                                    text = "共 ${durationShortText(duration)}",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TimePickBox(
                                label = "开始",
                                text = when {
                                    input.allDay -> "全天"
                                    input.startMin != null -> minutesToHhmm(input.startMin)
                                    else -> "点击选择"
                                },
                                filled = input.startMin != null,
                                enabled = startDate != null,
                                onClick = { startPickFor = id },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            TimePickBox(
                                label = "结束",
                                text = when {
                                    !input.hasEnd -> if (input.startMin != null) "点击选择" else "—"
                                    input.endMin == 1440 -> "24:00"
                                    else -> minutesToHhmm(input.endMin % 1440)
                                },
                                filled = input.hasEnd,
                                enabled = startDate != null && input.startMin != null,
                                onClick = { endPickFor = id },
                            )
                            if (crossDays > 0) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 3.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "+$crossDays",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            // ±1 天：处理结束与开始跨天的情况（结束不得早于开始）
                            Column {
                                Text(
                                    text = "结束日期",
                                    fontSize = 8.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row {
                                    val canMinus = input.hasEnd && input.endMin - 1440 >= (input.startMin ?: 0)
                                    val canPlus = input.hasEnd && input.endMin + 1440 <= (input.startMin ?: 0) + 43200
                                    Text(
                                        text = "−1天",
                                        fontSize = 10.sp,
                                        color = if (canMinus) Primary else MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier
                                            .border(
                                                1.dp,
                                                if (canMinus) Primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(6.dp),
                                            )
                                            .clickable(enabled = canMinus) {
                                                placeTimes[id] = input.copy(endMin = input.endMin - 1440)
                                            }
                                            .padding(horizontal = 5.dp, vertical = 2.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "+1天",
                                        fontSize = 10.sp,
                                        color = if (canPlus) Primary else MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier
                                            .border(
                                                1.dp,
                                                if (canPlus) Primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(6.dp),
                                            )
                                            .clickable(enabled = canPlus) {
                                                placeTimes[id] = input.copy(endMin = input.endMin + 1440)
                                            }
                                            .padding(horizontal = 5.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            // 持续时间 = 计算结果，只读展示（可为 0）
                            TimePickBox(
                                label = "持续时间（分钟）",
                                text = duration?.toString() ?: "—",
                                filled = duration != null,
                                enabled = false,
                                onClick = {},
                            )
                        }
                    }
                }
                if (selectedPlaceIds.isNotEmpty() && startDate == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "先选「开始日期」才能给点位排时间；不填时间的点位会进待安排池",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "建好直接进这条行程的时间轴，上面这些点位会落在待安排池里，拖到具体时段就算排好。",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 创建按钮（P31 inset 修复：补 navigationBarsPadding，避免被手势条盖住）
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            if (name.isNotBlank()) Primary else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable(enabled = name.isNotBlank()) {
                            // P31：已设置完整时间的点位 → 创建时直接落成安排；其余进待安排池
                            // （第九轮：0 分钟安排（开始=结束）也允许落成安排）
                            val times = selectedPlaceIds.mapNotNull { id ->
                                val input = placeTimes[id] ?: return@mapNotNull null
                                val start = input.startMin ?: return@mapNotNull null
                                if (!input.hasEnd) return@mapNotNull null
                                id to (start to input.endMin)
                            }.toMap()
                            viewModel.create(name, startDate, endDate, selectedPlaceIds.toSet(), times) { tripId ->
                                onCreated(tripId)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "创建并开始排布",
                        color = if (name.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // 日期选择器
        // P81（2026-11-05）：M3 DatePickerDialog 的星期行在中文环境被裁成「星星星星星」，
        // 且 material3 1.3.0 下字体 token 已改走 BodyLarge，Typography 覆写实测无效
        // （javap DatePickerModalTokens.getWeekdaysLabelTextFont 实证 + 真机复核）。
        // 改为自绘 SimpleDatePickerDialog：星期行「一二三四五六日」、月份切换、今天描边、选中实心，行为与原 M3 一致。
        if (showDatePicker) {
            com.visualzyzy.chuxingdazi.ui.common.SimpleDatePickerDialog(
                onDismiss = { showDatePicker = false },
                onConfirm = { date ->
                    if (pickingStart) {
                        startDate = date
                        if (endDate != null && endDate!!.isBefore(date)) endDate = date
                    } else if (startDate != null && !date.isBefore(startDate)) {
                        endDate = date
                    }
                    showDatePicker = false
                },
            )
        }

        // 开始时间选择（第九轮）：24 小时制滚轮；「全天」= 00:00–24:00
        startPickFor?.let { id ->
            val input = placeTimes[id] ?: PlaceTimeInput()
            val init = input.startMin ?: 540
            val state = rememberTimePickerState(initialHour = init / 60, initialMinute = init % 60, is24Hour = true)
            AlertDialog(
                onDismissRequest = { startPickFor = null },
                title = { Text("选择开始时间", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Primary.copy(alpha = 0.08f))
                                .border(1.dp, Primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable {
                                    placeTimes[id] = PlaceTimeInput(startMin = 0, allDay = true, endMin = 1440, hasEnd = true)
                                    startPickFor = null
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "全天（00:00 – 24:00）", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "或选择具体时间（24 小时制）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TimeInput(state = state)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 2026-11-22 修复 #2：吸附 5 分钟→1 分钟（原值直存，S24 与 S15 对齐）
                        val picked = state.hour * 60 + state.minute
                        val prev = placeTimes[id] ?: PlaceTimeInput()
                        // 结束不得早于开始：早于则贴齐开始（= 0 分钟持续）
                        val newEnd = if (prev.hasEnd && prev.endMin < picked) picked else prev.endMin
                        placeTimes[id] = prev.copy(startMin = picked, allDay = false, endMin = newEnd)
                        startPickFor = null
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { startPickFor = null }) { Text("取消") } },
            )
        }

        // 结束时间选择（第九轮）：24 小时制；跨天用行内 +1天/−1天 按钮调整；不得早于开始
        endPickFor?.let { id ->
            val input = placeTimes[id] ?: PlaceTimeInput()
            val start = input.startMin ?: 0
            // endMin=1440 视为当天 24:00；跨天部分保持，只改当天分钟
            val dayShift = if (input.hasEnd && input.endMin > 1440) input.endMin / 1440 else 0
            val init = if (input.hasEnd) {
                (if (input.endMin == 1440) 1380 else input.endMin % 1440)
            } else {
                (start + 60).coerceAtMost(1435)
            }
            val state = rememberTimePickerState(initialHour = init / 60, initialMinute = init % 60, is24Hour = true)
            AlertDialog(
                onDismissRequest = { endPickFor = null },
                title = { Text("选择结束时间", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        Text(
                            text = "结束与开始跨天时，用行旁的「+1天/−1天」调整",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TimeInput(state = state)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 2026-11-22 修复 #2：吸附 5 分钟→1 分钟（原值直存）
                        val picked = state.hour * 60 + state.minute
                        var newEnd = dayShift * 1440 + picked
                        if (newEnd < start) newEnd = start // 不得早于开始（可为 0 分钟持续）
                        placeTimes[id] = input.copy(endMin = newEnd, hasEnd = true, allDay = false)
                        endPickFor = null
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { endPickFor = null }) { Text("取消") } },
            )
        }

        // 点位选择浮层（S25 简版；第九轮：加与 S29 同源的多模式搜索，弹层加高容纳搜索区）
        if (showPlacePicker) {
            androidx.activity.compose.BackHandler(enabled = true) { showPlacePicker = false }
            var pickerBars by remember { mutableStateOf(listOf(com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBar())) }
            val markedProvinces = remember(places) {
                places.mapNotNull { it.province?.takeIf { p -> p.isNotBlank() } }.distinct()
            }
            val pickerCandidates = remember(places, pickerBars) {
                com.visualzyzy.chuxingdazi.ui.common.filterPlacesBySearchBars(
                    places = places,
                    bars = pickerBars,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x59000000))
                    .clickable { showPlacePicker = false },
            )
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp).navigationBarsPadding()) {
                    Text(
                        text = "从点位库挑（可多选）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBarsSection(
                        bars = pickerBars,
                        onBarsChange = { pickerBars = it },
                        categories = categories,
                        markedProvinces = markedProvinces,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Column(
                        modifier = Modifier
                            .height(430.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        pickerCandidates.forEach { place ->
                            val selected = place.id in selectedPlaceIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selected) {
                                            selectedPlaceIds.remove(place.id)
                                            placeTimes.remove(place.id)
                                        } else {
                                            selectedPlaceIds.add(place.id)
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (selected) "✓" else " ",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(
                                            if (selected) Primary else Color.Transparent,
                                            CircleShape,
                                        )
                                        .border(1.5.dp, Primary, CircleShape),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = place.name,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        // 遗留 #9（2026-11-18）：regionLabelOf 去重直辖市「北京市-北京市」
                                        text = listOfNotNull(
                                            categoriesById[place.categoryId]?.name,
                                            com.visualzyzy.chuxingdazi.ui.common.regionLabelOf(place.province, place.city),
                                        ).joinToString(" · ").ifBlank {
                                            "%.5f, %.5f".format(place.lat, place.lng)
                                        },
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (pickerCandidates.isEmpty()) {
                            Text(
                                text = "没有匹配的点位",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(Primary, RoundedCornerShape(13.dp))
                            .clickable { showPlacePicker = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "完成", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ---------- P31 逐行时间设置（2026-11-02）／第九轮（2026-11-08）改点击选择 ----------

/**
 * 单个点位的时间状态（开始/结束均为「当天第 N 分钟」的绝对分钟存储，结束可 >1440 表示跨天）。
 * allDay = 「全天」（开始 00:00 – 结束 24:00）。持续 = endMin - startMin，只作计算结果展示。
 */
data class PlaceTimeInput(
    val startMin: Int? = null,
    val allDay: Boolean = false,
    val endMin: Int = 0,
    val hasEnd: Boolean = false,
)

private fun minutesToHhmm(total: Int): String =
    "%02d:%02d".format((total % 1440) / 60, total % 60)

private fun durationShortText(minutes: Int): String = when {
    minutes >= 1440 -> "${minutes / 1440} 天 ${(minutes % 1440) / 60} 小时"
    minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
    else -> "$minutes 分钟"
}

/** 第九轮：紧凑可点时间框（替代原过高的 OutlinedTextField；持续时间框 = 只读）。 */
@Composable
private fun TimePickBox(
    label: String,
    text: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.widthIn(min = if (label.length > 3) 92.dp else 64.dp)) {
        Text(text = label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .heightIn(min = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        filled -> Primary.copy(alpha = 0.1f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .border(
                    1.dp,
                    if (filled && enabled) Primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(8.dp),
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
                color = if (filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
        }
    }
}
