package com.visualzyzy.chuxingdazi.ui.trip

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.data.tencent.TravelMode
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithPlaceRow
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TrafficLegCacheEntity
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** S14 行程时间轴（§6 S14）：跨天连续轴（1 小时 = 30dp）+ 色块 + 待安排池 + 现在线。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TripTimelineScreen(
    tripId: String,
    onClose: () -> Unit,
    onOpenTripMap: (tripId: String) -> Unit = {},
    onWalkThisDay: (tripId: String, date: String, originFromSchedule: Boolean) -> Unit = { _, _, _ -> },
) {
    val viewModel: TripTimelineViewModel = hiltViewModel()
    LaunchedEffect(tripId) { viewModel.load(tripId) }

    val trip by viewModel.trip.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val pendingPlaces by viewModel.pendingPlaces.collectAsStateWithLifecycle()
    val tripPlaces by viewModel.tripPlaces.collectAsStateWithLifecycle()
    val allPlaces by viewModel.allPlaces.collectAsStateWithLifecycle()
    val categoriesById by viewModel.categoriesById.collectAsStateWithLifecycle()

    // P7 交通腿：结果缓存 + 计算中状态 + 方式选择/手填弹窗
    val legCache by viewModel.legCache.collectAsStateWithLifecycle()
    val computingLegs by viewModel.computingLegs.collectAsStateWithLifecycle()
    var legDialogPair by remember { mutableStateOf<Pair<PlaceEntity, PlaceEntity>?>(null) }
    var manualLegPair by remember { mutableStateOf<Pair<PlaceEntity, PlaceEntity>?>(null) }
    var manualLegInput by remember { mutableStateOf("") }
    val toastContext = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.legEvents.collect { msg ->
            android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 第十六轮（2026-11-14）：未定日期行程不再虚构「今天起的 7 天」——
    // 无日期且无安排 = 空态（「选定这几天」是唯一入口）；有日期 = 正常轴；
    // 未定日期但已有安排（旧自动填日期时期/备份导入的遗留）→ 轴锚定最早安排日（派生、不落库）。
    val datedStart = trip?.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val undated = trip != null && datedStart == null
    val undatedEmpty = undated && schedules.isEmpty()
    val firstScheduleDay: LocalDate? =
        schedules.mapNotNull { runCatching { LocalDate.parse(it.schedule.planDate) }.getOrNull() }.minOrNull()
    val tripStart = datedStart ?: firstScheduleDay ?: LocalDate.now()
    val dayCount = if (datedStart != null) {
        TripTime.tripDayCount(trip?.startDate, trip?.endDate)
    } else {
        val lastDay = schedules.maxOfOrNull { row ->
            (runCatching { LocalDate.parse(row.schedule.planDate) }.getOrNull() ?: LocalDate.now())
                .plusDays(((row.schedule.endMin - 1) / 1440).toLong())
        } ?: LocalDate.now()
        (java.time.temporal.ChronoUnit.DAYS.between(tripStart, lastDay).toInt() + 1).coerceIn(1, 365)
    }
    // 第十六轮：日胶囊/周胶囊上的「有安排」标记（含跨天段覆盖到的天）
    // 2026-11-23 #11 修真 bug：原 `until start + spannedDays` 是空区间（spannedDays 同日段=0，
    // 它的语义是「额外多跨的天数」）→ 同日段所在天永远记不进 → 11/27/12/3 这类只有同日段的
    // 日期永远缺点。改闭区间 `..`：同日段记入当天、跨 N 天段记入 N+1 天。
    val scheduledDayOffsets = remember(schedules, tripStart) {
        val set = mutableSetOf<Int>()
        schedules.forEach { row ->
            val start = TripTime.dayOffset(tripStart, row.schedule.planDate)
            for (d in start..start + row.schedule.spannedDays) set.add(d)
        }
        set
    }
    // 「选定这几天」弹层（未定日期行程的日期入口）
    var showDateRangePicker by remember { mutableStateOf(false) }

    // S15 面板目标：编辑已有段 / 从待安排池新建
    var editTarget by remember { mutableStateOf<ScheduleWithPlaceRow?>(null) }
    var createTarget by remember { mutableStateOf<PlaceEntity?>(null) }
    // 第十四轮（2026-11-12）：点位重复安排——「再安排一段」→ S15 新建模式（预填源段日期/时长）。
    // 第二十五轮（2026-11-14）：入口从长按菜单挪进 S15 编辑面板（长按菜单整体废除，用户定调）。
    var duplicateTarget by remember { mutableStateOf<ScheduleWithPlaceRow?>(null) }
    // 第二十五轮（2026-11-14）：色块把手激活态——按住满 2 秒解锁拖拽时出现（默认色块干净无把手）；
    // 松手只结束这一笔、把手保留，点时间轴空白 / 点别的色块 / 离开本屏才收起。
    var activeHandlesId by remember { mutableStateOf<String?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }
    var wizardQueue by remember { mutableStateOf<List<String>>(emptyList()) }

    // P31（2026-11-02）：待安排点位长按拖到时间轴 → 松手直接成块（默认 1 小时，15 分钟吸附）
    var poolDragPlace by remember { mutableStateOf<PlaceEntity?>(null) }
    var poolDragPos by remember { mutableStateOf(Offset.Zero) } // 根坐标系（跟随手指）
    var timelineContentOrigin by remember { mutableStateOf(Offset.Zero) } // 时间轴视口 root 坐标
    var timelineViewportH by remember { mutableStateOf(0f) } // 时间轴视口高度（root px）

    // 第九轮（2026-11-08）：「按这天走」弹层（两种出发点）；无安排时「在地图上显示」的 3 秒提示
    var showWalkSheet by remember { mutableStateOf(false) }
    // 第十三轮（2026-11-12）：3 秒阻断提示（「今日无安排」/「行程无安排」）——期间拦截全部触摸
    var blockTip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(blockTip) {
        if (blockTip != null) {
            delay(3_000)
            blockTip = null
        }
    }

    // 向导推进：当前段关闭（保存或取消）后弹下一个待安排项
    fun advanceWizard() {
        wizardQueue = wizardQueue.drop(1)
        createTarget = pendingPlaces.firstOrNull { it.id == wizardQueue.firstOrNull() }
    }

    // 「现在」线：每 15 秒刷新（§6 S14；第九轮：页面久留也跟随实际时间移动）
    var nowMinutes by remember { mutableStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }
            delay(15_000)
        }
    }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pxPerMin = remember(density) { with(density) { TripTime.DP_PER_MIN.dp.toPx() } }
    val pxPerDay = (pxPerMin * 1440).roundToInt()

    // P31 落点换算：拖拽根坐标 → 时间轴内容坐标（+ 滚动补偿）→ 绝对分钟 → 15 分钟吸附 → 1 小时块
    // 第九轮修复：原判定「x < 56dp 刻度区就拒绝」导致从底部待安排池竖直上拖（x 落在刻度列内）
    // 全部被拒 → 待安排点位「拖不上时间轴」。改为只要求手指落在时间轴视口内（任意 x，含刻度列）。
    fun dropPoolOnTimeline(place: PlaceEntity, pos: Offset) {
        val fingerY = pos.y - timelineContentOrigin.y
        // 只要求手指纵向落在时间轴视口内（x 不再限制——左侧 56dp 刻度列也允许落下）
        if (timelineViewportH <= 0f || fingerY < 0f || fingerY > timelineViewportH) {
            android.widget.Toast.makeText(toastContext, "松手时要在时间轴区域内", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val y = fingerY + scrollState.value
        val absMin = (y / pxPerMin).toInt()
        val day = (absMin / 1440).coerceIn(0, (dayCount - 1).coerceAtLeast(0))
        val startMin = TripTime.snap15(absMin % 1440).coerceIn(0, 23 * 60 + 45)
        val now = System.currentTimeMillis()
        viewModel.saveSchedule(
            com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity(
                id = java.util.UUID.randomUUID().toString(),
                tripId = tripId,
                placeId = place.id,
                planDate = tripStart.plusDays(day.toLong()).toString(),
                startMin = startMin,
                endMin = startMin + 60, // 默认时长 1 小时
                createdAt = now,
                updatedAt = now,
            ),
        ) { ok, err ->
            android.widget.Toast.makeText(
                toastContext,
                if (ok) "已安排 ${TripTime.minutesText(startMin)} · 1 小时（可点色块微调）" else (err ?: "保存失败"),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // 滚动联动：视口顶部所在那天 → 顶部日期胶囊高亮（§6 S14 关键交互）
    val viewportDay by remember(dayCount) {
        derivedStateOf { (scrollState.value / pxPerDay).toInt().coerceIn(0, dayCount - 1) }
    }

    fun scrollToDay(idx: Int, animated: Boolean = true) {
        // 点胶囊 → 滚到那天的第一个安排（没有则 08:00），§6 S14
        val first = schedules
            .filter { TripTime.dayOffset(tripStart, it.schedule.planDate) == idx }
            .minByOrNull { it.schedule.startMin }
        val targetMin = first?.let { TripTime.absMinutes(idx, it.schedule.startMin) } ?: (idx * 1440 + 480)
        val targetPx = (targetMin * pxPerMin).roundToInt().coerceAtLeast(0)
        scope.launch {
            if (animated) scrollState.animateScrollTo(targetPx) else scrollState.scrollTo(targetPx)
        }
    }

    val todayIdx = java.time.temporal.ChronoUnit.DAYS.between(tripStart, LocalDate.now()).toInt()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // 导航栏（§6 S14：返回 / 行程名 + 日期区间 / 地图）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier
                        .size(26.dp)
                        .combinedClickable(onClick = onClose),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trip?.name ?: "…",
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 第三十二轮（2026-11-15，用户反馈）：「点此修改」原与日期同为灰色看不出来
                    // 可点——后缀部分单独上主色（日期部分保持灰色）；整行仍可点开日期选择。
                    Text(
                        text = buildAnnotatedString {
                            append(tripDateRangeText(trip?.startDate, trip?.endDate))
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                append(if (undated) " · 点此选定" else " · 点此修改")
                            }
                        },
                        fontSize = 10.5.sp,
                        modifier = Modifier.clickable { showDateRangePicker = true },
                    )
                }
                Text(
                    text = "在地图上显示", // P26（2026-11-02）：原「地图」
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .combinedClickable(onClick = {
                            // 第九轮：还没有任何时间安排时不再跳转地图，只弹 3 秒提示
                            // 第十三轮：文本改「行程无安排」，期间全屏拦截触摸
                            if (schedules.isEmpty()) {
                                blockTip = "行程无安排"
                            } else {
                                onOpenTripMap(tripId)
                            }
                        })
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }

            // 日期胶囊行（§6 S14/S17）：≤7 天一行日期胶囊横滚；>7 天折叠为周胶囊 + 展开视口所在周。
            // 第十六轮：未定日期且无安排 → 不渲染胶囊（不虚构日期）；胶囊上标出有安排的天/周。
            if (!undatedEmpty) {
                if (dayCount > 7) {
                var expandedWeek by remember { mutableStateOf(viewportDay / 7) }
                LaunchedEffect(viewportDay) { expandedWeek = viewportDay / 7 }
                val weekCount = (dayCount + 6) / 7
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                ) {
                    repeat(weekCount) { w ->
                        val firstDay = w * 7
                        val lastDay = minOf(firstDay + 6, dayCount - 1)
                        val selected = viewportDay / 7 == w
                            // 第十六轮：该周内有安排 → 标记；2026-11-23 #11：小圆点改「有安排」文字
                            //（小点语义不明，用户定调换文字；选中态琥珀、未选中主色）
                            val weekHasSchedule = (firstDay..lastDay).any { it in scheduledDayOffsets }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .background(
                                        if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .combinedClickable(onClick = {
                                        expandedWeek = w
                                        scrollToDay(firstDay, animated = false)
                                    })
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "第${w + 1}周",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (weekHasSchedule) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "有安排",
                                            fontSize = 8.sp,
                                            lineHeight = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (selected) Color(0xFFFFD54F) else Primary,
                                        )
                                    }
                                }
                            Text(
                                text = "${TripTime.dateText(tripStart.plusDays(firstDay.toLong()))}–${TripTime.dateText(tripStart.plusDays(lastDay.toLong()))}",
                                fontSize = 9.sp,
                                color = if (selected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // 展开行：视口所在周每天的日胶囊（点击滚到那天）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    val weekStart = expandedWeek * 7
                    repeat(minOf(7, dayCount - weekStart)) { i ->
                        DayPill(
                            date = tripStart.plusDays((weekStart + i).toLong()),
                            selected = weekStart + i == viewportDay,
                            hasSchedule = (weekStart + i) in scheduledDayOffsets,
                            onClick = { scrollToDay(weekStart + i) },
                        )
                    }
                }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                    ) {
                        repeat(dayCount) { idx ->
                            DayPill(
                                date = tripStart.plusDays(idx.toLong()),
                                selected = idx == viewportDay,
                                hasSchedule = idx in scheduledDayOffsets,
                                onClick = { scrollToDay(idx) },
                            )
                        }
                    }
                }
            }

            // 摘要行（§6 S14 布局 3：右侧「按这天走」→ 弹层选择出发点，第九轮）
            if (!undatedEmpty) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    // 第三十二轮（2026-11-15，用户给定文案）：说明「现在线」是红色虚线、拖动手势为
                    // 「按住色块松手后拖动」（P200 把手体系的实际交互口径）
                    text = "$dayCount 天 · ${schedules.size} 个安排 · 红色虚线指示当前时间 · 按住时间轴上地点后松手，可拖动调整",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (schedules.isNotEmpty()) {
                    Text(
                        text = "按这天走 →",
                        fontSize = 11.5.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(Primary, RoundedCornerShape(14.dp))
                            .clickable {
                                // 第十三轮（2026-11-12）：所选当天没有任何安排 → 弹 3 秒「今日无安排」
                                // 阻断提示，不弹「选择出发点」弹层
                                val walkDate = tripStart.plusDays(viewportDay.toLong()).toString()
                                if (viewModel.schedulesOnDay(walkDate).isEmpty()) {
                                    blockTip = "今日无安排"
                                } else {
                                    showWalkSheet = true
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (undatedEmpty) {
                // 第十六轮：未定日期行程的空态——不虚构 7 天轴，唯一入口 = 「选定这几天」
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "还没有安排",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (tripPlaces.isEmpty()) "这是一趟未定日期的行程\n先「选定这几天」，再把点位放上时间轴"
                        else "行程里已有 ${tripPlaces.size} 个点位\n先「选定这几天」，再把点位放上时间轴",
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "选定这几天 →",
                        fontSize = 13.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(Primary, RoundedCornerShape(16.dp))
                            .clickable { showDateRangePicker = true }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            } else {
            // 时间轴（自适应高度，纵向连续滚动）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        timelineContentOrigin = it.positionInRoot()
                        timelineViewportH = it.size.height.toFloat()
                    }
                    // 第二十五轮（2026-11-14）：点时间轴空白 → 收起色块把手。
                    // P200 修②③（2026-11-15 v12）：精确矩形命中已迁入 TimelineContent 内容 Box
                    //（那里有分列布局数据且坐标=内容坐标）。本层只管**刻度列**（x < 56dp）：
                    // Initial pass 只读不消费，落点在刻度列 → 收起。
                    .pointerInput(Unit, density) {
                        val gutterPx = with(density) { 56.dp.toPx() }
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                            )
                            if (down.position.x < gutterPx) activeHandlesId = null
                            while (true) {
                                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (ev.changes.none { it.pressed }) break
                            }
                        }
                    },
            ) {
                Row(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    TimelineGutter(tripStart = tripStart, dayCount = dayCount)
                    Box(modifier = Modifier.weight(1f)) {
                        TimelineContent(
                            tripStart = tripStart,
                            dayCount = dayCount,
                            schedules = schedules,
                            tripPlaces = tripPlaces,
                            categoriesById = categoriesById,
                            nowMinutes = nowMinutes,
                            showNowLine = !undated && todayIdx in 0 until dayCount,
                            totalDp = dayCount * 1440 * TripTime.DP_PER_MIN,
                            // 第二十五轮：点别的色块 → 收起当前把手（点自己=开 S15 不收，见 onTap 内判定）
                            onBlockTap = { row ->
                                if (activeHandlesId != row.schedule.id) activeHandlesId = null
                                editTarget = row
                            },
                            onBlockShift = { id, delta -> viewModel.shiftSchedule(id, delta) },
                            onBlockResize = { id, edge, delta -> viewModel.resizeSchedule(id, edge, delta) },
                            legCache = legCache,
                            computingLegKeys = computingLegs,
                            onComputeLeg = { from, to -> legDialogPair = from to to },
                            // 第二十五轮：把手激活态（哪个色块的 2 秒按住解锁过）下发到每个块
                            activeHandlesId = activeHandlesId,
                            onHandlesActivate = { id -> activeHandlesId = id },
                            // P200 修②③：点所有色块矩形之外（时段内空白/上下空白/缝隙）→ 收起
                            onHandlesDismiss = { activeHandlesId = null },
                        )
                    }
                }

                // P8 跨多天背景带「头部吸顶」：视口顶部滚进某条带内时，顶部吸附显示带标题
                //（§6 S14：滚到第三天也知道自己还在露营中）
                val bandsWithRange = remember(schedules, tripStart) {
                    schedules.filter { it.schedule.spannedDays >= 2 }.map { row ->
                        val topPx = TripTime.absMinutes(
                            TripTime.dayOffset(tripStart, row.schedule.planDate),
                            row.schedule.startMin,
                        ) * pxPerMin
                        val bottomPx = topPx + (row.schedule.endMin - row.schedule.startMin) * pxPerMin
                        row to (topPx to bottomPx)
                    }
                }
                val stickyRow by remember(bandsWithRange) {
                    derivedStateOf {
                        val scrollPx = scrollState.value.toFloat()
                        bandsWithRange.firstOrNull { (_, r) -> scrollPx > r.first && scrollPx < r.second }?.first
                    }
                }
                stickyRow?.let { row ->
                    val bandColor = parseColor(categoriesById[row.categoryId]?.color)
                    val endAbs = TripTime.absMinutes(
                        TripTime.dayOffset(tripStart, row.schedule.planDate),
                        row.schedule.endMin,
                    )
                    val endDate = tripStart.plusDays(((endAbs - 1) / 1440).toLong())
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .background(bandColor.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = {
                                // 第二十五轮：点吸顶药丸 = 点别的色块语义，收起当前把手
                                if (activeHandlesId != row.schedule.id) activeHandlesId = null
                                editTarget = row
                            })
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = row.placeName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "至 ${TripTime.dateText(endDate)} · 跨 ${row.schedule.spannedDays} 天",
                            fontSize = 9.5.sp,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                if (schedules.isEmpty()) {
                    Text(
                        text = "还没有安排\n从下方选一个点位放到时间上",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // 第二十五轮（2026-11-14）：长按快捷菜单整体废除（用户定调）——
                // 用户按住 2 秒是想拖动色块，菜单反而弹出来打断。原菜单项的去处：
                // 修改时段/移出时间轴 = S15 面板本就有；再安排一段 = 挪进 S15 编辑面板
                // （见 editTarget 分支 onRepeat）；上移/下移/标记到访不再有时间轴入口
                // （改时间用拖拽平移，到访标记在 S07/S29 均有）。
            }
            } // undatedEmpty else（时间轴区域）结束

            // 待安排池（§6 S14 布局 5 + §6 S25：＋添加/全部安排；P31 长按拖上时间轴）
            // 第十三轮（2026-11-12）：其上方新增「已安排」部分——行程里有安排的点位，
            // 每张卡片显示该点位各段的日期与起止时间；点击 = 打开 S15 修改/移出该段。
            val scheduledGroups = remember(schedules, tripPlaces, tripStart) {
                schedules
                    .groupBy { it.schedule.placeId }
                    .mapNotNull { (placeId, rows) ->
                        val place = tripPlaces.firstOrNull { it.id == placeId } ?: return@mapNotNull null
                        place to rows.sortedBy { row ->
                            TripTime.absMinutes(
                                TripTime.dayOffset(tripStart, row.schedule.planDate),
                                row.schedule.startMin,
                            )
                        }
                    }
                    .sortedBy { (_, rows) ->
                        TripTime.absMinutes(
                            TripTime.dayOffset(tripStart, rows.first().schedule.planDate),
                            rows.first().schedule.startMin,
                        )
                    }
            }
            ScheduledPool(
                groups = scheduledGroups,
                categoriesById = categoriesById,
                // 第二十五轮：点已安排卡片 = 点别的色块语义，收起当前把手（点卡开的是该段 S15）
                onPick = { row ->
                    if (activeHandlesId != row.schedule.id) activeHandlesId = null
                    editTarget = row
                },
            )
            PendingPool(
                places = pendingPlaces,
                categoriesById = categoriesById,
                onPick = { createTarget = it },
                onAdd = { showAddPicker = true },
                onArrangeAll = {
                    // 向导式：依次为待安排项打开 S15
                    wizardQueue = pendingPlaces.map { it.id }
                    createTarget = pendingPlaces.firstOrNull()
                },
                onPoolDragStart = { place, rootPos ->
                    poolDragPlace = place
                    poolDragPos = rootPos
                },
                onPoolDragMove = { delta -> poolDragPos += delta },
                onPoolDragEnd = {
                    poolDragPlace?.let { dropPoolOnTimeline(it, poolDragPos) }
                    poolDragPlace = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
            )
        }

        // P31：拖拽中的幽灵芯片（跟随手指，提供拖动反馈）
        poolDragPlace?.let { place ->
            Text(
                text = place.name,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = Color.White,
                modifier = Modifier
                    .offset { IntOffset(poolDragPos.x.roundToInt() - 40, poolDragPos.y.roundToInt() - 64) }
                    .graphicsLayer { alpha = 0.9f }
                    .background(Primary.copy(alpha = 0.92f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        // 返回键守卫：屏内 AlertDialog（S15/交通腿/再安排一段/添加选择/选定这几天）开着时，
        // 返回键若从 Dialog 窗口泄漏会落到 NavHost 直接弹回 S23（第十五轮实测）——
        // 先按堆叠优先级关最上层的弹层，再放行路由返回。
        androidx.activity.compose.BackHandler(
            enabled = editTarget != null || duplicateTarget != null ||
                legDialogPair != null || manualLegPair != null || showAddPicker || createTarget != null ||
                showDateRangePicker,
        ) {
            when {
                editTarget != null -> editTarget = null
                duplicateTarget != null -> duplicateTarget = null
                legDialogPair != null -> legDialogPair = null
                manualLegPair != null -> manualLegPair = null
                showAddPicker -> showAddPicker = false
                showDateRangePicker -> showDateRangePicker = false
                // 与面板取消路径一致：关当前段并推进「全部安排」向导
                createTarget != null -> {
                    createTarget = null
                    if (wizardQueue.isNotEmpty()) advanceWizard()
                }
            }
        }

        // 第九轮：「按这天走」出发点弹层——X / 点空白 / 系统返回 = 取消不跳转
        if (showWalkSheet) {
            androidx.activity.compose.BackHandler(enabled = true) { showWalkSheet = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x52000000))
                    .clickable(onClick = { showWalkSheet = false }),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) {}
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "✕",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { showWalkSheet = false }
                                .padding(6.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "按这天走 · 选择出发点",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val walkDate = tripStart.plusDays(viewportDay.toLong()).toString()
                    WalkOptionRow(
                        title = "从[我的位置]出发",
                        subtitle = "起点 = 实时定位的位置",
                    ) {
                        showWalkSheet = false
                        onWalkThisDay(tripId, walkDate, false)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    WalkOptionRow(
                        title = "从第一个安排的点位出发",
                        subtitle = "不使用我的位置；起点 = 这天时间最早的安排，其余按时间先后途经",
                    ) {
                        showWalkSheet = false
                        onWalkThisDay(tripId, walkDate, true)
                    }
                }
            }
        }

            // 第九轮：无任何安排时点「在地图上显示」→ 3 秒提示弹窗，不跳转
            // 第十三轮（2026-11-12）：提示统一走 blockTip（文本「行程无安排」），
            // 且 3 秒期间全屏拦截触摸（与「今日无安排」同一机制）。
            // 第十六轮：「选定这几天」——未定日期行程的日期入口（两步选开始/结束）
            if (showDateRangePicker) {
                com.visualzyzy.chuxingdazi.ui.common.TripDateRangeDialog(
                    onDismiss = { showDateRangePicker = false },
                    onConfirm = { start, end ->
                        // 第三十二轮（2026-11-15）：改日期后，区间外的安排移回待安排（repo 同事务删）；
                        // 删了几段用 Toast 说明，避免「安排不见了」的困惑（222.jpg 黄山上客运站）
                        viewModel.setTripDates(start.toString(), end.toString()) { removed ->
                            if (removed > 0) {
                                android.widget.Toast.makeText(
                                    toastContext,
                                    "行程日期变了，${removed} 段区间外的安排已移回待安排",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        showDateRangePicker = false
                    },
                )
            }
            blockTip?.let { tip ->
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xE614171C), RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = tip,
                        fontSize = 13.5.sp,
                        color = Color.White,
                    )
                }
            }

        // S15 安排时间面板（模态，覆盖全屏）
        editTarget?.let { row ->
            // 2026-11-23 #9：段胶囊切换编辑目标——段一换整面板重建（与 2026-11-22 #1 修法
            // 同款 key 包裹），内部 remember 全部重新初始化，时间范围自然跟着新段走。
            key(row.schedule.id) {
            ScheduleEditPanel(
                tripId = tripId,
                tripName = trip?.name ?: "",
                tripStart = tripStart,
                tripDays = dayCount,
                placeId = row.schedule.placeId,
                placeName = row.placeName,
                existing = row.schedule,
                initialDate = tripStart,
                initialStartMin = 9 * 60,
                initialEndMin = 11 * 60,
                allSchedules = schedules,
                onSave = { entity, onResult -> viewModel.saveSchedule(entity, onResult) },
                onRemove = {
                    viewModel.removeSchedule(row.schedule.id)
                    editTarget = null
                },
                // 第二十五轮（2026-11-14）：「再安排一段」从长按菜单挪进 S15 编辑面板
                //（用户定调）——点它关编辑面板、打开新建模式（预填源段日期/时长，见下方
                // duplicateTarget 分支），保存走 saveSchedule 新 UUID，与源段彼此独立。
                onRepeat = {
                    duplicateTarget = row
                    editTarget = null
                },
                onClose = { editTarget = null },
                // 2026-11-23 #9：编辑模式日期胶囊 = 该点位已有各段，点选切换编辑目标段
                onSwitchSegment = { segRow ->
                    editTarget = schedules.firstOrNull { it.schedule.id == segRow.schedule.id } ?: segRow
                },
            )
            }
        }
        createTarget?.let { place ->
            val category = categoriesById[place.categoryId]
            val defaults = viewModel.defaultTimesFor(place, category, tripStart)
            // 2026-11-22 修复 #1（向导状态残留）：向导推进 createTarget 从 A 换到 B 不经过 null，
            // Compose 位置复用让面板内部 remember（日期/时间/跨天天数）沿用 A 的旧值——过夜段后
            // 连锁 +1d/+2d 即此根因。key 强制点位一换整面板重建，瞬态状态（保存中/确认弹窗等）
            // 一次性清空，不会漏项。duplicateTarget 调用点同款保险（该路径每次经 null 重开本无残留）。
            key(place.id) {
            ScheduleEditPanel(
                tripId = tripId,
                tripName = trip?.name ?: "",
                tripStart = tripStart,
                tripDays = dayCount,
                placeId = place.id,
                placeName = place.name,
                existing = null,
                initialDate = tripStart,
                initialStartMin = defaults.first,
                initialEndMin = defaults.second,
                allSchedules = schedules,
                onSave = { entity, onResult ->
                    viewModel.saveSchedule(entity) { ok, err ->
                        onResult(ok, err)
                        if (ok) advanceWizard()
                    }
                },
                onRemove = null,
                onClose = {
                    createTarget = null
                    if (wizardQueue.isNotEmpty()) advanceWizard()
                },
            )
            }
        }

        // 第十四轮（2026-11-12）：点位重复安排——「再安排一段」→ S15 新建模式。
        // 保存走 saveSchedule（新 UUID → addSchedule 校验 trip_place 成员与时间区间 → refresh），
        // 与源段彼此独立（§5.3 多段设计）；只引用 place_id，点位在别处改名/删除时两段随 JOIN/CASCADE 天然同步。
        // 第二十五轮复测修正（2026-11-15，用户反馈）：不再预填源段时间（原预填 = 与源段完全相同，
        // 一保存就原样重叠毫无意义）——时间空白由用户自己填（保存置灰直到填齐）；日期仍预填源段日期。
        duplicateTarget?.let { row ->
            // 2026-11-22 修复 #1 同款保险：段一换面板重建（见上方 createTarget 分支注解）
            key(row.schedule.id) {
            ScheduleEditPanel(
                tripId = tripId,
                tripName = trip?.name ?: "",
                tripStart = tripStart,
                tripDays = dayCount,
                placeId = row.schedule.placeId,
                placeName = row.placeName,
                existing = null,
                titleOverride = "再安排一段「${row.placeName}」",
                initialDate = java.time.LocalDate.parse(row.schedule.planDate),
                initialStartMin = 9 * 60,
                initialEndMin = 10 * 60,
                emptyTime = true,
                allSchedules = schedules,
                onSave = { entity, onResult -> viewModel.saveSchedule(entity, onResult) },
                onRemove = null,
                onClose = { duplicateTarget = null },
            )
            }
        }

        // S25「＋添加」：从点位库选加入本行程（排除已在行程的）
        if (showAddPicker) {
            val candidates = allPlaces.filter { p -> tripPlaces.none { it.id == p.id } }
            TripPlacePickerSheet(
                candidates = candidates,
                onConfirm = { ids ->
                    viewModel.addPlacesToTrip(ids)
                    showAddPicker = false
                },
                onDismiss = { showAddPicker = false },
            )
        }

        // P7 交通腿：方式选择弹窗（点方式即调百度接口；结果按当前时刻/当前路况）
        legDialogPair?.let { (from, to) ->
            val existing = legCache.values
                .filter { it.fromPlaceId == from.id && it.toPlaceId == to.id }
                .maxByOrNull { it.createdAt }
            AlertDialog(
                onDismissRequest = { legDialogPair = null },
                title = {
                    // 2026-11-23 #12：双长名标题必然超宽，改 marquee 滚动（同 S15 标题款）
                    Text(
                        "${from.name} → ${to.name}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    )
                },
                text = {
                    Column {
                        Text(
                            "出发按上一段结束时间 · 结果按当前时刻/当前路况查询",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TravelMode.entries.forEach { mode ->
                                Text(
                                    text = legModeLabel(mode.name),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                        .clickable {
                                            viewModel.computeLeg(from, to, mode)
                                            legDialogPair = null
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "公交按当前时刻查询，可能与你安排时段的运营情况不同；也可去百度地图 App 查询后手动填写",
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 14.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "手动填写用时 ›",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    manualLegInput = ""
                                    manualLegPair = from to to
                                }
                                .padding(vertical = 4.dp),
                        )
                        existing?.let { e ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "清除结果（当前：${legResultText(e)}）",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clickable {
                                        viewModel.clearLeg(from.id, to.id)
                                        legDialogPair = null
                                    }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { legDialogPair = null }) { Text("关闭", color = MaterialTheme.colorScheme.primary) }
                },
            )
        }

        // P7 交通腿：手动填写用时（如去百度地图 App 按实际时刻查完回来填）
        manualLegPair?.let { (from, to) ->
            AlertDialog(
                onDismissRequest = { manualLegPair = null },
                title = { Text("手动填写交通用时", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        Text(
                            "${from.name} → ${to.name}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = manualLegInput,
                            onValueChange = { v -> if (v.length <= 4 && v.all { it.isDigit() }) manualLegInput = v },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            placeholder = { Text("分钟数，如 35", fontSize = 13.sp) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = (manualLegInput.toIntOrNull() ?: 0) in 1..1440,
                        onClick = {
                            viewModel.saveManualLeg(from, to, manualLegInput.toInt())
                            manualLegPair = null
                            legDialogPair = null
                        },
                    ) { Text("保存", color = MaterialTheme.colorScheme.primary) }
                },
                dismissButton = {
                    TextButton(onClick = { manualLegPair = null }) { Text("取消") }
                },
            )
        }
    }
}

private fun tripDateRangeText(startDate: String?, endDate: String?): String {
    val s = TripTime.parse(startDate)
    val e = TripTime.parse(endDate)
    return when {
        s != null && e != null -> "${TripTime.dateText(s)} ${TripTime.weekdayText(s)} – ${TripTime.dateText(e)} ${TripTime.weekdayText(e)}"
        s != null -> "${TripTime.dateText(s)} 起 · 未定回程"
        else -> "未定日期"
    }
}

/** 左侧时间刻度 + 日期胶囊（§6 S14：每天 00:00 深色日期胶囊，每 2 小时一个刻度）。 */
@Composable
private fun TimelineGutter(tripStart: LocalDate, dayCount: Int) {
    val totalDp = dayCount * 1440 * TripTime.DP_PER_MIN
    Box(modifier = Modifier.width(56.dp).height(totalDp.dp)) {
        repeat(dayCount) { idx ->
            val date = tripStart.plusDays(idx.toLong())
            Box(
                modifier = Modifier
                    .offset(y = (idx * 1440 * TripTime.DP_PER_MIN + 2).dp)
                    .padding(start = 6.dp),
            ) {
                Text(
                    text = TripTime.dateText(date),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color(0xFF3D4451), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // 每 2 小时一个刻度
            for (hour in 2 until 24 step 2) {
                val yDp = idx * 1440 * TripTime.DP_PER_MIN + hour * 60 * TripTime.DP_PER_MIN
                Text(
                    text = "%02d:00".format(hour),
                    fontSize = 8.5.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .offset(y = (yDp - 6).dp)
                        .padding(start = 8.dp),
                )
            }
        }
    }
}

/** 时间轴内容区：天分隔线 + 跨多天背景带 + 普通色块 + 现在线（§6 S14 色块规格）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineContent(
    tripStart: LocalDate,
    dayCount: Int,
    schedules: List<ScheduleWithPlaceRow>,
    tripPlaces: List<PlaceEntity>,
    categoriesById: Map<String, CategoryEntity>,
    nowMinutes: Int,
    showNowLine: Boolean,
    totalDp: Float,
    onBlockTap: (ScheduleWithPlaceRow) -> Unit,
    onBlockShift: (scheduleId: String, deltaMin: Int) -> Unit,
    onBlockResize: (scheduleId: String, edge: Int, deltaMin: Int) -> Unit,
    legCache: Map<String, TrafficLegCacheEntity>,
    computingLegKeys: Set<String>,
    onComputeLeg: (from: PlaceEntity, to: PlaceEntity) -> Unit,
    // 第二十五轮（2026-11-14）：把手激活态（2 秒按住解锁过的色块 id）——下发到各块决定是否画把手
    activeHandlesId: String?,
    onHandlesActivate: (String) -> Unit,
    // P200 修②③（v12）：点中所有色块矩形之外（时段内空白/块上下空白/天间空白）→ 收起把手
    onHandlesDismiss: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(totalDp.dp)) {
        val density = LocalDensity.current
        val pxPerMin = with(density) { TripTime.DP_PER_MIN.dp.toPx() }
        // P200 修②③：内容区宽度（px）——TimelineContent 的 BoxWithConstraints 即刻度列右侧
        // 的内容区，宽=分列布局的基准宽
        val contentWidthPx = with(density) { maxWidth.toPx() }

        fun rowStartAbs(row: ScheduleWithPlaceRow): Int =
            TripTime.absMinutes(TripTime.dayOffset(tripStart, row.schedule.planDate), row.schedule.startMin)
        fun rowEndAbs(row: ScheduleWithPlaceRow): Int = rowStartAbs(row) + (row.schedule.endMin - row.schedule.startMin)

        // 时间重叠检测（§6 S14 边界：重叠 → 警示黄框只包住重叠部分，第九轮 2026-11-08；
        // 跨 ≥2 天背景带不参与冲突判定 §6 S14）。值 = 每块的相对分钟区段（start..end，已合并）。
        val overlapSegments = remember(schedules) {
            val raw = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
            val normal = schedules.filter { it.schedule.spannedDays < 2 }
            for (i in normal.indices) for (j in normal.indices) {
                if (i == j) continue
                val a = normal[i]
                val b = normal[j]
                val s = maxOf(rowStartAbs(a), rowStartAbs(b))
                val e = minOf(rowEndAbs(a), rowEndAbs(b))
                if (s < e) raw.getOrPut(a.schedule.id) { mutableListOf() }.add(s to e)
            }
            raw.mapValues { (_, list) ->
                list.sortedBy { it.first }
                    .fold(mutableListOf<Pair<Int, Int>>()) { acc, seg ->
                        val last = acc.lastOrNull()
                        if (last != null && seg.first <= last.second) {
                            acc[acc.size - 1] = last.first to maxOf(last.second, seg.second)
                        } else {
                            acc.add(seg)
                        }
                        acc
                    }
            }
        }

        val bands = schedules.filter { it.schedule.spannedDays >= 2 }
        val blocks = schedules.filter { it.schedule.spannedDays < 2 }

        // ---- 第十六轮：重叠分列（优化建议：互相重叠的几段左右分列，纵向仍表示钟点）----
        // 互相相交（直接或经由第三段连通）的段归为一组；组内按区间图贪心着色分配列号；
        // 只有成员 ≥2 的组才分列，其余照旧全宽。
        data class ColPlacement(val group: Int, val column: Int, val columns: Int)
        val colPlacements = remember(schedules) {
            val normal = blocks.sortedBy { rowStartAbs(it) }
            val n = normal.size
            val parent = IntArray(n) { it }
            fun find(x: Int): Int {
                var root = x
                while (parent[root] != root) root = parent[root]
                var cur = x
                while (parent[cur] != root) {
                    val next = parent[cur]
                    parent[cur] = root
                    cur = next
                }
                return root
            }
            fun union(a: Int, b: Int) {
                parent[find(a)] = find(b)
            }
            // 2026-11-23 #1-#4 改造 A6：分列碰撞判定改用「画布矩形」——把保底高度的
            // 虚胖算进去，视觉上真要压到一起的两块像日历应用一样并排分列，而不是上下压叠。
            // 注意：⚠ 徽标（overlapSegments）与琥珀色带（overlapStrips）仍用数据区间，
            // 画布碰撞只影响「分不分列」，不许把假重叠画成警示。
            // 画布终点：真实结束 + 保底高度补齐（<30 分钟的块画布比数据长）
            fun paintedEnd(row: ScheduleWithPlaceRow): Int =
                rowEndAbs(row) + (30 - (row.schedule.endMin - row.schedule.startMin)).coerceAtLeast(0)
            fun overlaps(a: ScheduleWithPlaceRow, b: ScheduleWithPlaceRow) =
                rowStartAbs(a) < paintedEnd(b) && rowStartAbs(b) < paintedEnd(a)
            for (i in 0 until n) for (j in i + 1 until n) {
                if (overlaps(normal[i], normal[j])) union(i, j)
            }
            val placement = mutableMapOf<String, ColPlacement>()
            normal.groupBy { find(normal.indexOf(it)) }
                .forEach { (_, members) ->
                    if (members.size < 2) {
                        placement[members[0].schedule.id] = ColPlacement(0, 0, 1)
                        return@forEach
                    }
                    // 贪心着色：按开始时间遍历，放进步骤尾（画布终点）≤ 自己开始的最低列
                    val lastEnd = mutableListOf<Int>()
                    val colOf = mutableMapOf<String, Int>()
                    members.forEach { row ->
                        val s = rowStartAbs(row)
                        var col = lastEnd.indexOfFirst { it <= s }
                        if (col < 0) {
                            col = lastEnd.size
                            lastEnd.add(paintedEnd(row))
                        } else {
                            lastEnd[col] = paintedEnd(row)
                        }
                        colOf[row.schedule.id] = col
                    }
                    val gid = members.first().schedule.id.hashCode()
                    members.forEach { row ->
                        placement[row.schedule.id] = ColPlacement(gid, colOf[row.schedule.id]!!, lastEnd.size)
                    }
                }
            placement
        }
        // 组内同列块的横向内缩（BoxWithConstraints.maxWidth 减去刻度列由调用方处理，
        // 这里按内容区总宽均分：每列宽 = maxWidth / columns，块自己再留 2dp 端距）
        val contentWidthDp = maxWidth.value
        fun blockInsets(row: ScheduleWithPlaceRow): Pair<Float, Float> {
            val p = colPlacements[row.schedule.id] ?: return 2f to 6f
            if (p.columns <= 1) return 2f to 6f
            val colW = contentWidthDp / p.columns
            return (colW * p.column + 2f) to (colW * (p.columns - p.column - 1) + 4f)
        }

        // P200 修②③（v13）+ 修⑧（v15）：点空白收把手——**最底层 child**（第一个组合=最低 z）。
        // Compose hit-test 同级短路：点色块/背景带/腿药丸时它们命中（在底层之上）短路，
        // 本层不触发（点色块=开 S15/拖拽，不收 ✓）；点**任何空白**（含分列组短块所在列的
        // 空白时段、块间缝、天间空白、刻度列以外全部）→ 唯一命中的是本层 → 收起 ✓。
        // **修⑧（关键）**：本层必须用**纯观察者手写手势**（不消费任何事件）——v13/v14 用的
        // detectTapGestures 会消费 down，把块的 AfterLongPress 长按在每个检查点的
        // isConsumed 检查里静默取消（「半秒即拖」路径全哑、只有时钟 2 秒路径活着的根因）。
        // 判定：整笔手势未被任何人消费且位移 ≤slop = 空白轻点 → 收起；被消费（点中了块）
        // 或移动（滚动）→ 不收。
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(schedules, tripStart) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalMove = 0f
                        var consumed = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.isConsumed) consumed = true
                            totalMove += kotlin.math.abs(ch.positionChange().x) +
                                kotlin.math.abs(ch.positionChange().y)
                            if (ch.changedToUpIgnoreConsumed() || !ch.pressed) {
                                if (!consumed && totalMove <= viewConfiguration.touchSlop) {
                                    onHandlesDismiss()
                                }
                                break
                            }
                        }
                    }
                },
        )

        // 跨 ≥2 天安排 → 背景带（z 在普通色块下面，§6 S14）
        bands.forEach { row ->
            val color = parseColor(categoriesById[row.categoryId]?.color)
            val topDp = rowStartAbs(row) * TripTime.DP_PER_MIN
            val heightDp = (row.schedule.endMin - row.schedule.startMin) * TripTime.DP_PER_MIN
            val startOffset = TripTime.dayOffset(tripStart, row.schedule.planDate)
            Box(
                modifier = Modifier
                    .offset(y = topDp.dp)
                    .padding(start = 2.dp, end = 6.dp)
                    .height(heightDp.dp.coerceAtLeast(20.dp))
                    .fillMaxWidth()
                    .background(color.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                    .drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                    .combinedClickable(onClick = { onBlockTap(row) }),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, top = 4.dp),
                ) {
                    Text(
                        text = row.placeName,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "跨 ${row.schedule.spannedDays} 天",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xFF3F51B5), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Text(
                    text = "${TripTime.dateText(tripStart.plusDays(startOffset.toLong()))} " +
                        "${TripTime.minutesText(row.schedule.startMin)} → " +
                        "${TripTime.dateText(tripStart.plusDays((startOffset + (row.schedule.endMin - 1) / 1440).toLong()))} " +
                        "${TripTime.minutesText(row.schedule.endMin % 1440)} · ${durationCrossText(row.schedule.endMin - row.schedule.startMin)}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, top = 22.dp),
                )
            }
        }

        // 天分隔线（每天 00:00 一条 1.5dp 线，§6 S14）
        for (idx in 1 until dayCount) {
            val yDp = idx * 1440 * TripTime.DP_PER_MIN
            Box(
                modifier = Modifier
                    .offset(y = yDp.dp)
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }

        // 普通色块（含跨 1 天：渲染成跨过分隔线的整块，不断开）
        // 第十六轮：重叠组的块按列内缩渲染；第二十五轮：把手激活态下发
        blocks.forEach { row ->
            val (insetS, insetE) = blockInsets(row)
            ScheduleBlock(
                row = row,
                tripStart = tripStart,
                category = categoriesById[row.categoryId],
                place = tripPlaces.firstOrNull { it.id == row.schedule.placeId },
                topPx = rowStartAbs(row) * pxPerMin,
                heightDp = (row.schedule.endMin - row.schedule.startMin) * TripTime.DP_PER_MIN,
                overlapSegments = overlapSegments[row.schedule.id].orEmpty()
                    .map { (s, e) -> (s - rowStartAbs(row)).toFloat() to (e - rowStartAbs(row)).toFloat() },
                pxPerMin = pxPerMin,
                onShift = onBlockShift,
                onResize = onBlockResize,
                onTap = { onBlockTap(row) },
                handlesActive = activeHandlesId == row.schedule.id,
                onHandlesActivate = { onHandlesActivate(row.schedule.id) },
                insetStartDp = insetS,
                insetEndDp = insetE,
            )
        }

        // ---- 第十六轮：琥珀色带（横跨分列组的重叠区间）+ 「重叠 N 分钟」标注 ----
        // 只有互相相交的段参与；色带横跨该区间上有安排的列；字写在色带外（下方右侧）
        data class OverlapStrip(
            val startMin: Int,
            val endMin: Int,
            val colFrom: Int,
            val colTo: Int,
            val columns: Int,
        )
        val overlapStrips = remember(schedules) {
            buildList {
                colPlacements.values
                    .filter { it.columns > 1 }
                    .distinctBy { it.group }
                    .forEach { groupHead ->
                        val members = blocks.filter {
                            colPlacements[it.schedule.id]?.group == groupHead.group
                        }
                        // 组内两两求交集，合并成色带区间
                        val raw = mutableListOf<Pair<Int, Int>>()
                        for (i in members.indices) for (j in i + 1 until members.size) {
                            val s = maxOf(rowStartAbs(members[i]), rowStartAbs(members[j]))
                            val e = minOf(rowEndAbs(members[i]), rowEndAbs(members[j]))
                            if (s < e) raw.add(s to e)
                        }
                        val merged = raw.sortedBy { it.first }
                            .fold(mutableListOf<Pair<Int, Int>>()) { acc, seg ->
                                val last = acc.lastOrNull()
                                if (last != null && seg.first <= last.second) {
                                    acc[acc.size - 1] = last.first to maxOf(last.second, seg.second)
                                } else {
                                    acc.add(seg)
                                }
                                acc
                            }
                        merged.forEach { (s, e) ->
                            // 该区间上有安排的列范围（任一成员覆盖即可）
                            val covering = members.mapNotNull { row ->
                                colPlacements[row.schedule.id]?.takeIf {
                                    rowStartAbs(row) < e && rowEndAbs(row) > s
                                }
                            }
                            if (covering.isNotEmpty()) {
                                add(
                                    OverlapStrip(
                                        startMin = s,
                                        endMin = e,
                                        colFrom = covering.minOf { it.column },
                                        colTo = covering.maxOf { it.column },
                                        columns = groupHead.columns,
                                    ),
                                )
                            }
                        }
                    }
            }
        }
        overlapStrips.forEach { strip ->
            val colW = contentWidthDp / strip.columns
            val xStart = colW * strip.colFrom + 2f
            val xEnd = colW * (strip.colTo + 1) - 4f
            val topDp = strip.startMin * TripTime.DP_PER_MIN
            val heightDp = ((strip.endMin - strip.startMin) * TripTime.DP_PER_MIN).coerceAtLeast(3f)
            // P200 修 ⑦（2026-11-15 用户定调）：色带上下沿实线改**虚线**（降低视觉强度）；
            // 块本体不再画琥珀描边（见 ScheduleBlock drawBehind 注记）。
            Box(
                modifier = Modifier
                    .offset(x = xStart.dp, y = topDp.dp)
                    .width((xEnd - xStart).dp)
                    .height(heightDp.dp)
                    .background(Color(0xFFE8A800).copy(alpha = 0.16f), RoundedCornerShape(3.dp))
                    .drawBehind {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                        drawLine(
                            color = Color(0xFFE8A800),
                            start = Offset(0f, 0.5f),
                            end = Offset(size.width, 0.5f),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dash,
                        )
                        drawLine(
                            color = Color(0xFFE8A800),
                            start = Offset(0f, size.height - 0.5f),
                            end = Offset(size.width, size.height - 0.5f),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dash,
                        )
                    },
            )
            // 「重叠 N 分钟」写在色带外面（下方右侧，不占块内空间）
            Text(
                text = "重叠 ${strip.endMin - strip.startMin} 分钟",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9A6700),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(y = (topDp + heightDp + 1f).dp)
                    .padding(end = 10.dp),
            )
        }

        // P7 交通腿槽位（2026-11-23 #8 重排）：
        // - 全部安排（含跨天段）按绝对时间排序取相邻对 → 酒店过夜段→次日早餐这类腿也能配对；
        // - 「画布间隙」≥32 分钟（16dp）才显示——保证药丸永远落在两块画布之间的空白里，不压任何色块；
        // - 真实间隙 >8 小时视为过夜/休整，不算「赶到下一站」，不配对。
        val legSlots = remember(schedules, tripPlaces) {
            val bs = schedules.sortedBy { rowStartAbs(it) }
            buildList {
                for (i in 0 until bs.size - 1) {
                    val a = bs[i]
                    val b = bs[i + 1]
                    val paintedEndA = rowEndAbs(a) + (30 - (a.schedule.endMin - a.schedule.startMin)).coerceAtLeast(0)
                    val visualGapMin = rowStartAbs(b) - paintedEndA
                    val trueGapMin = rowStartAbs(b) - rowEndAbs(a)
                    if (visualGapMin < 32 || trueGapMin > 480) continue
                    val pa = tripPlaces.firstOrNull { it.id == a.schedule.placeId } ?: continue
                    val pb = tripPlaces.firstOrNull { it.id == b.schedule.placeId } ?: continue
                    add(LegSlot(a, b, pa, pb, paintedEndA))
                }
            }
        }
        legSlots.forEach { slot ->
            val pairCache = legCache.values.filter {
                it.fromPlaceId == slot.fromPlace.id && it.toPlaceId == slot.toPlace.id
            }
            val latest = pairCache.maxByOrNull { it.createdAt }
            val computing = pairCache.any {
                "${slot.fromPlace.id}_${slot.toPlace.id}_${it.mode}" in computingLegKeys
            }
            // 间隙顶=上一段画布终点；「来不及」警告继续用真实间隙（数据语义）
            val gapTopDp = slot.endAbsMin * TripTime.DP_PER_MIN
            val trueGapMin = rowStartAbs(slot.to) - rowEndAbs(slot.from)
            val tooTight = latest != null && !computing && latest.minutes > trueGapMin
            LegPill(
                topDp = gapTopDp + 1f, // 贴间隙顶 +1dp，永不压块（原为间隙中线居中）
                text = when {
                    computing -> "计算中…"
                    tooTight -> "⚠ 可能来不及 · ${legResultText(latest)} · 间隔 $trueGapMin 分钟"
                    latest != null -> legResultText(latest)
                    else -> "估算到下一点的交通耗时" // P28：原「计算交通耗时」
                },
                isComputing = computing,
                hasResult = latest != null && !computing,
                onClick = { onComputeLeg(slot.fromPlace, slot.toPlace) },
            )
        }

        // 「现在」指示线（§6 S14：红虚线 + 右端标签，每分钟量级的刷新由调用方 30s 轮询）
        if (showNowLine) {
            val todayIdx = java.time.temporal.ChronoUnit.DAYS.between(tripStart, LocalDate.now()).toInt()
            val yDp = (todayIdx * 1440 + nowMinutes) * TripTime.DP_PER_MIN
            if (yDp in 0f..totalDp) {
                Box(
                    modifier = Modifier
                        .offset(y = yDp.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .drawBehind {
                            drawLine(
                                color = Color(0xFFE5484D),
                                start = Offset(0f, size.height / 2),
                                end = Offset(size.width, size.height / 2),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                            )
                        },
                )
                // 第十三轮（2026-11-12）修复：标签原用 align(CenterEnd)+offset(yDp−9)——offset 是
                // 相对「垂直中心」的偏移而非绝对定位，现在线在轴下方时标签会被推到容器外很远
                //（用户反馈：红框出现在虚线很后面）。改为 align(TopEnd)+offset(yDp+4)——标签
                // 顶边恒在虚线下方 4dp，紧跟虚线。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = (yDp + 4).dp)
                        .padding(end = 4.dp)
                        .background(Color(0xFFE5484D), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "现在 ${TripTime.minutesText(nowMinutes)}",
                        fontSize = 8.5.sp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** 「按这天走」弹层里的选项行（第九轮）。 */
@Composable
private fun WalkOptionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 日期胶囊（§6 S14）：46dp 圆角，视口那天高亮，点击滚到那天；第十六轮起标有安排的天。
 *  2026-11-23 #11：小圆点改「有安排」文字（周X Row 下方第三行；无安排的天不渲染、自然矮一截）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayPill(
    date: LocalDate,
    selected: Boolean,
    hasSchedule: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(46.dp)
            .background(
                if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )
            .combinedClickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = TripTime.dateText(date),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = TripTime.weekdayText(date),
            fontSize = 9.sp,
            color = if (selected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasSchedule) {
            Text(
                text = "有安排",
                fontSize = 8.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Color(0xFFFFD54F) else Primary,
            )
        }
    }
}

/** P7 交通腿槽位（相邻安排对；2026-11-23 #8 起含跨天段，画布间隙 ≥32 分钟才产生）。 */
private data class LegSlot(
    val from: ScheduleWithPlaceRow,
    val to: ScheduleWithPlaceRow,
    val fromPlace: PlaceEntity,
    val toPlace: PlaceEntity,
    val endAbsMin: Int, // 画布间隙顶（上一段画布终点：真实结束 + 保底高度补齐）
)

private fun legModeLabel(mode: String): String = when (mode) {
    "DRIVING" -> "驾车"
    "WALKING" -> "步行"
    "BIKING" -> "骑行"
    "TRANSIT" -> "公交"
    else -> "约"
}

private fun legDistanceText(distanceM: Int): String = when {
    distanceM <= 0 -> ""
    distanceM < 1000 -> " · ${distanceM}m"
    else -> " · " + String.format("%.1f", distanceM / 1000.0) + "km"
}

private fun legResultText(e: TrafficLegCacheEntity): String = when (e.mode) {
    "MANUAL" -> "约 ${e.minutes} 分钟 · 手填"
    else -> "${legModeLabel(e.mode)} ${e.minutes} 分钟${legDistanceText(e.distanceM)}"
}

/** P7 交通腿按钮/结果药丸（§6 S14：默认不显示，用户点击才调接口计算真实路线）。
 *  P29（2026-11-02）：紧贴时间轴左侧（时间轴色块起点一致），上下内边距收窄到 2dp。 */
@Composable
private fun LegPill(
    topDp: Float,
    text: String,
    isComputing: Boolean,
    hasResult: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        fontSize = 8.5.sp,
        lineHeight = 11.sp, // 2026-11-23 #8：补显式行高（根因 A 同款），药丸高度收窄装进 16dp 间隙
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        color = if (hasResult) MaterialTheme.colorScheme.onSurface else Primary,
        modifier = Modifier
            .offset(y = topDp.dp)
            .padding(start = 4.dp)
            .background(
                if (hasResult) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(9.dp),
            )
            .border(
                width = 1.dp,
                color = if (hasResult) Color.Transparent else Primary.copy(alpha = 0.55f),
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(enabled = !isComputing, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 单个色块（§6 S14 色块规格：8% 底 + 3dp 左色条 + 三档排版）。
 *  第九轮（2026-11-08）：① 单行短块 = 开始 + 名称 + 结束（0 分钟块同样式）；
 *  ② 拖拽（整段平移 / 上下缘改时间）需按住 2 秒才能开始；③ 重叠警示黄框只包住重叠区段。
 *  第十六轮：insetStartDp/insetEndDp 支持重叠分列（列宽减半时块只占自己的列）。
 *  第二十五轮（2026-11-14）：把手体系重做（用户定调）——默认色块干净无把手；
 *  按住满 2 秒解锁拖拽时把手才出现（松手只结束这一笔、把手保留，点时间轴空白 /
 *  点别的色块 / 离开本屏才收起，收起逻辑在屏层 activeHandlesId）；上把手=改开始、
 *  下把手=改结束、块身=整段平移；把手 22dp 宽 × 2.5dp 高，≥72dp（=144 分钟，
 *  0.5dp/min）的块把手在块内侧上/下缘（inset 3dp），<72dp 的块贴顶边上方 /
 *  底边下方（块外绘制，间隙不足时压在相邻块上属可接受遮盖）；触摸热区以把手
 *  中心为基准上下各 9dp（20dp 带，宽与色块相同）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduleBlock(
    row: ScheduleWithPlaceRow,
    tripStart: LocalDate,
    category: CategoryEntity?,
    place: PlaceEntity?,
    topPx: Float,
    heightDp: Float,
    overlapSegments: List<Pair<Float, Float>>,
    pxPerMin: Float,
    onShift: (String, Int) -> Unit,
    onResize: (String, Int, Int) -> Unit,
    onTap: () -> Unit,
    handlesActive: Boolean,
    onHandlesActivate: () -> Unit,
    insetStartDp: Float = 2f,
    insetEndDp: Float = 6f,
) {
    val color = parseColor(category?.color)
    val context = LocalContext.current
    var dragging by remember { mutableStateOf(false) }
    var dragDeltaPx by remember { mutableStateOf(0f) }
    // 拖拽模式：0=整段平移，1=拖上把手改开始，2=拖下把手改结束（P5）
    var dragMode by remember { mutableStateOf(0) }
    // 第二十五轮复测修正（2026-11-15）：按下时刻（0 = 没有手指按在本块上）。
    // 事件驱动方案在这台 ROM 上失败两次（「按住不动松手」的 up/cancel 都到不了
    // pointerInput——事件流被外层滚动/系统长按截获），改**时钟驱动**亮把手：
    // 手指按下记 pressAt，LaunchedEffect 每 100ms 检查「按住满 2 秒」→ onHandlesActivate。
    // 事件流断不断都不影响（拖拽解锁逻辑仍走事件路径）。
    var pressAtMs by remember { mutableStateOf(0L) }
    // 第二十五轮复测终极修正（2026-11-15，拖不动的真凶）：pointerInput 的 key 曾含
    // handlesActive——把手亮的瞬间（全局 state 变化）pointerInput **重启**，正在挂起的
    // 手势协程被取消；此时用户手指还按着（刚亮把手立刻拖），重启后的协程 awaitFirstDown
    // 等新 down 永远等不到 → 这笔手势对色块「不存在」→ 滚动接手 → 拖不动 + 松手判定
    // 丢失（up 落到外层轻点检测 → 弹 S15）。全部症状同源。
    // 修法：key 去掉 handlesActive，改 rememberUpdatedState 在手势里读实时值——激活态
    // 变化不再重启 pointerInput，进行中的手势不中断。
    val handlesActiveState = androidx.compose.runtime.rememberUpdatedState(handlesActive)
    LaunchedEffect(handlesActive) {
        while (true) {
            delay(50)
            if (!handlesActive && pressAtMs != 0L && System.currentTimeMillis() - pressAtMs >= 2000L) {
                onHandlesActivate()
            }
        }
    }

    val crossesDay = row.schedule.endMin >= 1440
    val timeRange = buildString {
        append(TripTime.minutesText(row.schedule.startMin))
        append(" – ")
        if (crossesDay) append("次日 ")
        append(TripTime.minutesText(row.schedule.endMin % 1440))
    }
    val shape = RoundedCornerShape(topEnd = 11.dp, bottomEnd = 11.dp, topStart = 3.dp, bottomStart = 3.dp)
    // 拖拽中的实时视觉（P200 修 ③ 真凶=单位错配）：visualHeightDp 是 dp，但公式把
    // dragDeltaPx(px) / pxPerMin(px/min)=分钟数当 dp 用——顶边位移(px) 与高度减量(dp)
    // 数量级不匹配 → 底边跟动+松手回弹。正确换算：dragDeltaPx / density = dp；
    // 分钟数 = dragDeltaPx / pxPerMin（提交用）。mode 1 底边锚定（顶跟手+高度减同量 dp）；
    // mode 2 顶边锚定（高度增同量 dp）；mode 0 平移（高度不变）。
    // 保底高度 22dp → 15dp（2026-11-23 #1-#4 改造 A5）：30 分钟以上全部真实高度，
    // <30 分钟的块画 15dp，残余虚胖最多 15 分钟（原 22dp 虚胖是六组「假重叠」压叠的根因 B）。
    val baseHeightDp = heightDp.coerceAtLeast(15f)
    val dragDeltaDp = dragDeltaPx / LocalDensity.current.density
    val visualHeightDp = when {
        !dragging -> baseHeightDp
        dragMode == 1 -> (baseHeightDp - dragDeltaDp).coerceAtLeast(15f)
        dragMode == 2 -> (baseHeightDp + dragDeltaDp).coerceAtLeast(15f)
        else -> baseHeightDp
    }
    // 拖拽中各模式顶边 offset（px）：mode 0/1 顶跟手 dragDeltaPx；mode 2 顶锚定不动。
    // mode 1 底边 = 顶(+delta px) + 高(减 delta px/density*dp→同量) = 不动 ✓（单位已对齐）。

    // P200 修⑥（v15）：两个互斥检测器共用的回调（定义在组合作用域，闭包捕获状态变量）。
    // mode 判定：块顶 12dp 内=改开始(1)、块底 12dp 内=改结束(2)、其余=平移(0)。
    // hPx 用 baseHeightDp（拖拽起始时的布局高，等价于拖起瞬间的 size.height 且不受
    // 拖拽中高度变化影响）。
    val dragStart: (Float, Float) -> (Offset) -> Unit = { edgePx, hPx ->
        { start ->
            pressAtMs = 0L
            dragMode = when {
                start.y <= edgePx -> 1
                start.y >= hPx - edgePx -> 2
                else -> 0
            }
            dragging = true
            dragDeltaPx = 0f
            onHandlesActivate()
        }
    }
    val dragMove: (PointerInputChange, Offset) -> Unit = { change, amount ->
        change.consume()
        dragDeltaPx += amount.y
    }
    val dragEnd: () -> Unit = {
        val deltaMin = TripTime.snap15((dragDeltaPx / pxPerMin).roundToInt())
        val mode = dragMode
        dragging = false
        dragDeltaPx = 0f
        when (mode) {
            1 -> if (deltaMin != 0) onResize(row.schedule.id, 0, deltaMin)
            2 -> if (deltaMin != 0) onResize(row.schedule.id, 1, deltaMin)
            else -> if (deltaMin != 0) onShift(row.schedule.id, deltaMin)
        }
    }
    val dragCancel: () -> Unit = {
        dragging = false
        dragDeltaPx = 0f
    }

    Box(
        modifier = Modifier
            .offset {
                val yShift = when {
                    !dragging -> 0f
                    dragMode == 0 -> dragDeltaPx // 平移：整块走
                    dragMode == 1 -> dragDeltaPx // 改开始：顶边跟手（底边由高度减少锚定）
                    else -> 0f // 改结束：顶边锚定
                }
                IntOffset(0, topPx.roundToInt() + yShift.roundToInt())
            }
            .padding(start = insetStartDp.dp, end = insetEndDp.dp)
            .height(visualHeightDp.dp)
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), shape)
            .drawBehind {
                drawRect(color, size = Size(3.dp.toPx(), size.height))
                // 第九轮：重叠警示（P200 修 ⑦ 视觉降级，2026-11-15 用户定调）：
                // 色块本体**不再画琥珀描边**（原来重叠段包 1.5dp 琥珀粗实线，视觉太吵）——
                // 重叠警示只保留第十六轮的琥珀色带（TimelineContent 里）+ 块顶 ⚠ 徽标。
                // 第二十五轮：把手（22dp 宽 × 2.5dp 高）只在激活态（2 秒按住解锁过）绘制——
                // 默认干净的色块。≥72dp（=144 分钟，0.5dp/min）把手在块内侧上/下缘（inset 3dp）；
                // <72dp 的块把手贴顶边上方 / 底边下方（画在块 bounds 外，间隙充足时落在相邻
                // 空白上；紧贴相邻块时压在其边缘上，半透明可接受）。触摸热区见下方手势
                //（把手中心上下各 9dp，与可见把手条分离）。
                if (handlesActive) {
                    val barW = 22.dp.toPx()
                    val barH = 2.5.dp.toPx()
                    val barX = (size.width - barW) / 2f
                    val barColor = color.copy(alpha = 0.5f)
                    if (size.height >= 72.dp.toPx()) {
                        val inset = 3.dp.toPx()
                        drawRoundRect(barColor, Offset(barX, inset), Size(barW, barH), CornerRadius(barH / 2f))
                        drawRoundRect(barColor, Offset(barX, size.height - inset - barH), Size(barW, barH), CornerRadius(barH / 2f))
                    } else {
                        drawRoundRect(barColor, Offset(barX, -barH), Size(barW, barH), CornerRadius(barH / 2f))
                        drawRoundRect(barColor, Offset(barX, size.height), Size(barW, barH), CornerRadius(barH / 2f))
                    }
                }
            }
            // 第二十五轮（2026-11-14）：手势重做（用户定调：按住 2 秒 = 拖拽准备，长按菜单废除）。
            // 2026-11-15 复测修正（用户反馈「按住 2 秒松手把手没出现 / 把手亮了却拖不动」）：
            // - 把手已激活（handlesActive）的块：**新按下立即解锁拖拽**（把手亮 = 该块处于
            //   调整模式，无需再等 2 秒——原先二次按住仍要 2 秒，用户感觉「拖不动」的根因）；
            // - 未激活块：按住满 2 秒后的第一个超 slop 移动解锁拖拽并亮把手；纯按住不动满
            //   2 秒松手也亮把手（下一笔即可直接拖）；
            // - 2 秒内的移动不消费不放弃（外层滚动接管，第十三轮铁律）；
            // - 松手判定：未解锁时 <长按阈值（约 500ms）= 轻点开 S15（consume 掉 up，
            //   防外层「点空白」误清自己的把手）；500ms~2 秒 = 无动作（原长按菜单已废）。
            // 第二十五轮终极方案（2026-11-15 P200）：手写 awaitEachGesture 五轮修不动
            //（up/cancel、时钟亮把手、坐标判定、Initial pass、rememberUpdatedState 全试过，
            // 症状不变=拖不动+松手弹 S15，详见待办册 P200）。改抄**同页实证可用**的方案：
            // 待安排池芯片用的 detectDragGesturesAfterLongPress（从 P31 起从未坏过）——
            // 官方检测器自带消费管理与滚动互斥。
            // P200 修 ①（立即拖）：把手已激活的块改用 detectDragGestures（无长按阈值，
            // 按下即拖）；未激活块仍 AfterLongPress（约 500ms 阈值后才算拖拽意图，
            // 期间滑动归时间轴滚动）。
            // P200 修 ④（热区放宽）：模式判定改为整条边缘带——块顶 20dp 内=改开始（mode 1）、
            // 块底 20dp 内=改结束（mode 2）、其余=平移（mode 0）。把手条可见位置不变（22×2.5dp），
            // 热区不再跟随把手中心 ±9dp（<72dp 块把手在块外，旧判定几乎够不到 mode 2）。
            // P200 修 ①（立即拖，v11 真正生效的写法）：v10 把 detectDragGestures 的选型放在
            // pointerInput 协程**启动时**读 handlesActiveState——把手亮的瞬间协程可能还没重启
            // （读到 false 走了 AfterLongPress 分支），所以体感仍要按住一会儿。改为**组合期增删
            // 两个 pointerInput**：常态只挂 AfterLongPress（未亮把手，防误触）；handlesActive=true
            // 时**再挂一个 detectDragGestures**（无长按阈值，按下即拖）。两个都挂着时
            // detectDragGestures 先抢到拖拽（Main pass 顺序=modifier 顺序，后挂的在前）。
            // P200 修 ④（热区收窄，v11）：20dp 全边带会与并列/相邻块重合——收窄为 12dp
            //（≥72dp 块把手在内侧 3dp，12dp 带足够覆盖；中间仍=平移）。
            // P200 修⑥（v15）：两检测器互斥二选一——见下方 .then 处注记
            .then(
                if (handlesActive) {
                    Modifier.pointerInput(row.schedule.id, pxPerMin) {
                        val edgePx = 12.dp.toPx()
                        detectDragGestures(
                            onDragStart = { start ->
                                pressAtMs = 0L
                                dragMode = when {
                                    start.y <= edgePx -> 1
                                    start.y >= size.height - edgePx -> 2
                                    else -> 0
                                }
                                dragging = true
                                dragDeltaPx = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragDeltaPx += amount.y
                            },
                            onDragEnd = {
                                val deltaMin = TripTime.snap15((dragDeltaPx / pxPerMin).roundToInt())
                                val mode = dragMode
                                dragging = false
                                dragDeltaPx = 0f
                                when (mode) {
                                    1 -> if (deltaMin != 0) onResize(row.schedule.id, 0, deltaMin)
                                    2 -> if (deltaMin != 0) onResize(row.schedule.id, 1, deltaMin)
                                    else -> if (deltaMin != 0) onShift(row.schedule.id, deltaMin)
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                dragDeltaPx = 0f
                            },
                        )
                    }
                } else {
                    Modifier
                }
            )
            // P200 修⑥（v15，用户复测：把手亮着往上拖弹回原位）根因：把手亮时 **ALP 与
            // instant 两个拖拽检测器同时在场**——按住超 500ms 后 ALP 也进入拖拽态；instant
            // （Main pass 内层先收）消费 move → ALP 的 awaitDragOrCancellation 判定被抢 →
            // ALP onDragCancel 把共享 dragging/dragDeltaPx **当场清零**（视觉弹回+位移丢失）。
            // 「往下拖正常」只是按下后 500ms 内就动身（ALP 未及触发）的时序巧合。
            // 修法：**两检测器互斥二选一**（组合期 .then 切换）——把手亮=只挂 instant（按下
            // 即拖），未亮=只挂 ALP（长按出把手）。回调提为共享局部 lambda。首按长按的换装
            // 瞬间该笔拖拽会中断（松手重按即拖，与既有 UX 一致）；tap 的 onPress 兜底复位
            // 拖拽视觉态（防换装瞬间冻结的 dragging/dragDeltaPx 残留）。
            .then(
                if (handlesActive) {
                    val edgePx = with(LocalDensity.current) { 12.dp.toPx() }
                    val hPx = with(LocalDensity.current) { baseHeightDp.dp.toPx() }
                    Modifier.pointerInput(row.schedule.id, pxPerMin) {
                        detectDragGestures(
                            onDragStart = dragStart(edgePx, hPx),
                            onDrag = dragMove,
                            onDragEnd = dragEnd,
                            onDragCancel = dragCancel,
                        )
                    }
                } else {
                    val edgePx = with(LocalDensity.current) { 12.dp.toPx() }
                    val hPx = with(LocalDensity.current) { baseHeightDp.dp.toPx() }
                    Modifier.pointerInput(row.schedule.id, pxPerMin) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = dragStart(edgePx, hPx),
                            onDrag = dragMove,
                            onDragEnd = dragEnd,
                            onDragCancel = dragCancel,
                        )
                    }
                }
            )
            // 轻点（开 S15）：P200 修 ⑤——按住时长过滤：超过 600ms 的「tap」不是轻点
            //（长按 2 秒亮把手后的松手不该弹 S15）。onPress 记时刻，onTap 判时长；
            // onPress 同时兜底复位上一笔换装瞬间可能冻结的拖拽视觉态。
            .pointerInput(row.schedule.id) {
                var tapDownAt = 0L
                detectTapGestures(
                    onPress = {
                        tapDownAt = System.currentTimeMillis()
                        dragging = false
                        dragDeltaPx = 0f
                    },
                    onTap = {
                        if (System.currentTimeMillis() - tapDownAt < 600L) onTap()
                    },
                )
            }
            // 时钟亮把手：按住 2 秒（未进入拖拽）也会亮——AfterLongPress 的阈值（约 500ms）
            // 后用户若按住不动，2 秒时钟把把手点亮（下一笔按下即拖）。
            .pointerInput(row.schedule.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressAtMs = System.currentTimeMillis()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed() || !change.pressed) break
                        }
                    } finally {
                        pressAtMs = 0L
                    }
                }
            }
            .padding(start = 8.dp, end = 6.dp),
    ) {
        val visited = place?.visited == true
        // 三档排版重构（2026-11-23 #1-#4 改造 A）：时间字号三档统一 10.5sp Bold；
        // 全部 Text 显式配 lineHeight（根因 A：小字号不配行高会继承 23sp 行框 → 两行档
        // 实需 ~53dp，68–106 分钟档名称必被裁）；档位阈值改按真实内容需求（<36dp 单行、
        // 36–72dp 两行、≥72dp 三行）；名称行全部 basicMarquee（超宽自动滚动替代省略号）。
        when {
            visualHeightDp < 36f -> {
                // < 36dp（< 72 分钟）：单行 = 开始-结束 + 3 空格 + 名称（超宽滚动）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize().padding(end = 4.dp),
                ) {
                    Text(
                        text = buildString {
                            append(TripTime.minutesText(row.schedule.startMin))
                            append('-')
                            if (crossesDay) append("次日 ")
                            append(TripTime.minutesText(row.schedule.endMin % 1440))
                        },
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1,
                        lineHeight = 13.sp,
                    )
                    Text(text = "   ", fontSize = 10.5.sp, color = color, lineHeight = 13.sp)
                    Text(
                        text = row.placeName,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        lineHeight = 13.sp,
                        modifier = Modifier
                            .weight(1f)
                            .basicMarquee(iterations = Int.MAX_VALUE),
                    )
                    if (visited) VisitedCheck()
                }
            }
            visualHeightDp < 72f -> {
                // 36–72dp：两行 = 时间行 + 名称行
                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeRange,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = color,
                            lineHeight = 13.sp,
                        )
                        if (crossesDay) {
                            Spacer(modifier = Modifier.width(4.dp))
                            CrossDayBadge()
                        }
                    }
                    Spacer(modifier = Modifier.height(1.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.placeName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            lineHeight = 15.sp,
                            modifier = Modifier
                                .weight(1f)
                                .basicMarquee(iterations = Int.MAX_VALUE),
                        )
                        if (visited) VisitedCheck()
                    }
                }
            }
            else -> {
                // ≥ 72dp：三行 = 时间 + 名称 + 备注（备注里 D 码转日期，见 #5）
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeRange,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = color,
                            lineHeight = 13.sp,
                        )
                        if (crossesDay) {
                            Spacer(modifier = Modifier.width(4.dp))
                            CrossDayBadge()
                        }
                    }
                    Spacer(modifier = Modifier.height(1.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.placeName,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .weight(1f)
                                .basicMarquee(iterations = Int.MAX_VALUE),
                        )
                        if (visited) VisitedCheck()
                    }
                    place?.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = TripTime.expandDayCodes(note, tripStart),
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 12.sp,
                        )
                    }
                    if (heightDp >= 100) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "时长 ${durationText(row.schedule.endMin - row.schedule.startMin)}",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            }
        }

        // 时间重叠 ⚠ 徽标（§6 S14；2026-11-23 #3：加浅琥珀底更醒目）
        if (overlapSegments.isNotEmpty()) {
            Text(
                text = "⚠",
                fontSize = 10.sp,
                color = Color(0xFF9A6700),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .background(Color(0xFFE8A800).copy(alpha = 0.20f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

@Composable
private fun VisitedCheck() {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(Color(0xFF1B7F3B), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✓", fontSize = 8.sp, color = Color.White)
    }
}

@Composable
private fun CrossDayBadge() {
    Text(
        text = "跨天",
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .background(Color(0xFF3F51B5), RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 0.5.dp),
    )
}

/**
 * 已安排部分（第十三轮 2026-11-12）：行程里有安排的点位，横向卡片展示——
 * 复用待安排芯片的视觉（分类图标 + 名称），名称下方逐段显示「M/d HH:MM–HH:MM」。
 * 点击卡片 = 打开该点位第一段安排的 S15（可修改/移出时间轴）；安排与待安排之间的
 * 移动由数据驱动（saveSchedule/removeSchedule → refresh → 两侧各自重算），无需额外逻辑。
 *
 * 第三十二轮（2026-11-15，用户反馈 111.jpg）：卡内多段时间行距 + 已安排/待安排两段间隔收紧——
 * 根因 = 本区各 Text 只给 fontSize 不给 lineHeight，继承 M3 bodyLarge 的 24sp 行框，
 * 9sp 的时间行各占 24sp 高（多段时间之间吊出大空隙、卡片底部也拖着死空间）。
 * 显式补 lineHeight（名称 15sp/时间行 12sp），尾部 Spacer 6→2dp。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduledPool(
    groups: List<Pair<PlaceEntity, List<ScheduleWithPlaceRow>>>,
    categoriesById: Map<String, CategoryEntity>,
    onPick: (ScheduleWithPlaceRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                text = "已安排 · ${groups.size}",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            groups.forEach { (place, rows) ->
                val category = categoriesById[place.categoryId]
                val color = parseColor(category?.color)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                        .combinedClickable(onClick = { onPick(rows.first()) })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    CategoryIconSmall(category = category, color = color)
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = place.name,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 15.sp, // 第三十二轮：补显式行高，去除 bodyLarge 24sp 行框的死空间
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(76.dp),
                        )
                        // 2026-11-23 #10：每卡最多显示前 2 段（原全量渲染把「已安排」行撑高，
                        // 北海宾馆 4 段 → 4 行）；超出收进提示行，全部段经 #9 的段胶囊浏览。
                        rows.take(2).forEach { row ->
                            val s = row.schedule
                            val endText = if (s.endMin >= 1440) {
                                "次日 ${TripTime.minutesText(s.endMin % 1440)}"
                            } else {
                                TripTime.minutesText(s.endMin)
                            }
                            Text(
                                text = "${TripTime.dateText(java.time.LocalDate.parse(s.planDate))} " +
                                    "${TripTime.minutesText(s.startMin)}–$endText",
                                fontSize = 9.sp,
                                lineHeight = 12.sp, // 第三十二轮：多段时间行距收紧（原继承 24sp 行框）
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (rows.size > 2) {
                            Text(
                                text = "…还有 ${rows.size - 2} 段 · 点卡片可切换",
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp)) // 第三十二轮：与下方「待安排」的间隔收紧（原 6dp）
    }
}

/** 待安排池（§6 S14 布局 5）：属于行程且在该行程里没有任何安排的点位。
 *  P31（2026-11-02）：芯片支持长按拖动上时间轴（松手成 1 小时块），轻点仍打开 S15。
 *  第十三轮（2026-11-12）：删「未安排」文字行、压缩芯片高度——为上方新增的「已安排」
 *  部分与时间轴留出空间。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PendingPool(
    places: List<PlaceEntity>,
    categoriesById: Map<String, CategoryEntity>,
    onPick: (PlaceEntity) -> Unit,
    onAdd: () -> Unit,
    onArrangeAll: () -> Unit,
    onPoolDragStart: (PlaceEntity, Offset) -> Unit,
    onPoolDragMove: (Offset) -> Unit,
    onPoolDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.navigationBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                text = "待安排 · ${places.size}",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (places.isNotEmpty()) {
                Text(
                    text = "全部安排",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onArrangeAll)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                text = "＋ 添加",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (places.isEmpty()) {
            // 第十六轮：池子空 = 点位都排上时间轴了，不等于行程排满——文案按池子语义说
            Text(
                text = "点位都放上时间轴了 ✓",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                places.forEach { place ->
                    val category = categoriesById[place.categoryId]
                    val color = parseColor(category?.color)
                    var chipRoot by remember(place.id) { mutableStateOf(Offset.Zero) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                            .combinedClickable(onClick = { onPick(place) })
                            .onGloballyPositioned { chipRoot = it.positionInRoot() }
                            .pointerInput(place.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start -> onPoolDragStart(place, chipRoot + start) },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        onPoolDragMove(amount)
                                    },
                                    onDragEnd = { onPoolDragEnd() },
                                    onDragCancel = { onPoolDragEnd() },
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        CategoryIconSmall(category = category, color = color)
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = place.name,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 15.sp, // 第三十二轮：与「已安排」卡名称同款显式行高（原继承 24sp 行框）
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(76.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

/** S25「＋添加」选择浮层：点位库中未加入本行程的点位，多选后批量加入待安排池。 */
@Composable
private fun TripPlacePickerSheet(
    candidates: List<PlaceEntity>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val filtered = if (query.isBlank()) candidates else candidates.filter { it.name.contains(query.trim(), ignoreCase = true) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x59000000))
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(16.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "从点位库添加 · 已选 ${selected.size}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "✕",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(6.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索点位", fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .height(300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (filtered.isEmpty()) {
                    Text(
                        text = if (candidates.isEmpty()) "点位库里的点位都已在本行程" else "没有匹配的点位",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                filtered.forEach { place ->
                    val isSel = place.id in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSel) selected.remove(place.id) else selected.add(place.id)
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(
                                    if (isSel) Primary else Color.Transparent,
                                    androidx.compose.foundation.shape.CircleShape,
                                )
                                .border(
                                    1.5.dp,
                                    if (isSel) Primary else MaterialTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSel) {
                                Text(text = "✓", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = place.name,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        if (selected.isNotEmpty()) Primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(13.dp),
                    )
                    .clickable(enabled = selected.isNotEmpty()) { onConfirm(selected.toList()) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "确定 · ${selected.size} 个",
                    color = if (selected.isNotEmpty()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun CategoryIconSmall(category: CategoryEntity?, color: Color) {
    val painter = categoryIconPainter(category?.iconKey)
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Icon(painter = painter, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        } else {
            Text(text = "·", fontSize = 14.sp, color = color)
        }
    }
}

@Composable
private fun categoryIconPainter(iconKey: String?): Painter? {
    val context = LocalContext.current
    val id = remember(iconKey) {
        context.resources.getIdentifier("ic_cat_${iconKey ?: "other_point"}", "drawable", context.packageName)
    }
    return if (id != 0) painterResource(id) else null
}

private fun parseColor(hex: String?): Color = try {
    Color(android.graphics.Color.parseColor(hex ?: "#5B8DEF"))
} catch (e: Exception) {
    Color(0xFF5B8DEF)
}
