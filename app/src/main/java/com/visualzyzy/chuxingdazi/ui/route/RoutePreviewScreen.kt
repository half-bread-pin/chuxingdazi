package com.visualzyzy.chuxingdazi.ui.route

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMapOptions
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.visualzyzy.chuxingdazi.data.tencent.RouteLineColors
import com.visualzyzy.chuxingdazi.data.tencent.RouteMapController
import com.visualzyzy.chuxingdazi.data.tencent.RouteScheme
import com.visualzyzy.chuxingdazi.data.tencent.RouteStepInfo
import com.visualzyzy.chuxingdazi.data.tencent.RouteStepKind
import com.visualzyzy.chuxingdazi.data.tencent.TravelMode
import com.visualzyzy.chuxingdazi.ui.theme.Primary

/** 步行段灰（与 RouteMapController.WALK_GRAY 同值）与起终点圆点色（S10 同语义）。 */
private val WALK_GRAY_COLOR = Color(0xFF8A93A3)
private val START_BLUE = Color(0xFF1B6EF3)
private val END_RED = Color(0xFFE5484D)

/**
 * S10 → S30 预览快照：进程内存单例（tempView 同族先例——RouteScheme 含数百个 LatLng，
 * 无法序列化进路由参数/savedStateHandle）。S10 点「预览」写入，S30 组合期读取；
 * 生命周期 = 再点预览覆盖 / S10 退出时置空（RouteScreen onDispose）。
 */
object RoutePreviewStore {
    data class StopSnapshot(val name: String, val lat: Double, val lng: Double, val colorArgb: Int? = null)

    data class Snapshot(
        val mode: TravelMode,
        val schemes: List<RouteScheme>,
        val selectedIndex: Int,
        val origin: StopSnapshot?,
        val destination: StopSnapshot?,
        val waypoints: List<StopSnapshot> = emptyList(),
    )

    @Volatile
    var snapshot: Snapshot? = null
}

private val SHEET_COLLAPSED = 200.dp
private val TOP_BAR_HEIGHT = 48.dp

private fun modeLabel(mode: TravelMode): String = when (mode) {
    TravelMode.DRIVING -> "驾车"
    TravelMode.WALKING -> "步行"
    TravelMode.BIKING -> "骑行"
    TravelMode.TRANSIT -> "公交"
}

/** S30 路线预览（决策 #96–#99）：窄顶栏 + 大部地图 + 底部两态拖动弹层；分段画线像百度地图 App。 */
@Composable
fun RoutePreviewScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val snap = remember { RoutePreviewStore.snapshot }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (snap == null) {
            // 进程被杀恢复 / 异常入口：快照为空（§7.3 边界）
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "路线数据已失效", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "请返回路线页重新规划",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "返回",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Primary)
                        .clickable(onClick = onClose)
                        .padding(horizontal = 28.dp, vertical = 10.dp),
                )
            }
        } else {
            PreviewContent(snap = snap, onClose = onClose)
        }
    }
}

@Composable
private fun PreviewContent(snap: RoutePreviewStore.Snapshot, onClose: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(snap.selectedIndex) }
    var focusStep by remember { mutableStateOf<Int?>(null) }
    val mapController = remember { RouteMapController() }
    val scheme = snap.schemes.getOrNull(selected) ?: snap.schemes.firstOrNull()

    Box(modifier = Modifier.fillMaxSize()) {
        // 地图（与 S10/S26 同架构：每页自己的 MapView 实例 + RouteMapController）
        val mapView = com.visualzyzy.chuxingdazi.ui.map.rememberTencentMapView(context)
        if (mapView == null) {
            Text(text = "地图引擎不可用", modifier = Modifier.align(Alignment.Center))
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    // 腾讯版：SDK 缩放控件在 RouteMapController.attach 内关（uiSettings）
                    mapController.attach(mapView)
                    mapView
                },
            )
            // 2026-11-18 修复 S30 底图空白（真机首测实锤，同决策 #108 根因）：
            // 代码创建的腾讯 MapView 必须转发 onStart/onResume 才启动渲染管线，
            // 否则只有路线层自绘、底图瓦片全不渲染。
            val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_START -> runCatching { mapView.onStart() }
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> runCatching { mapView.onResume() }
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> runCatching { mapView.onPause() }
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> runCatching { mapView.onStop() }
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    mapController.detach()
                    runCatching { mapView.onDestroy() }
                }
            }
        }

        // 分段画线 + 起终点/途经标记 + 相机（进页 / 切方案 / 点行程链行）
        LaunchedEffect(selected, focusStep) {
            val s = scheme ?: return@LaunchedEffect
            mapController.clear()
            mapController.drawSchemeSteps(s, snap.mode, focusStep)
            snap.origin?.let { mapController.addNodeMarker(it.lat, it.lng, RouteMapController.originDot(context), -1) }
            snap.waypoints.forEachIndexed { i, wp ->
                mapController.addNodeMarker(
                    wp.lat,
                    wp.lng,
                    RouteMapController.numberedNode(context, i + 1, wp.colorArgb ?: 0xFF5B8DEF.toInt()),
                    i,
                )
            }
            snap.destination?.let { mapController.addNodeMarker(it.lat, it.lng, RouteMapController.destinationDot(context), -2) }
            val fs = focusStep
            if (fs == null) {
                // 第三十一轮改版二：统一连续比例缩放（顶部 48dp 顶栏、底部弹层+导航条 ≈250dp）
                mapController.fitSpan(s.points, topReserveDp = 48f, bottomReserveDp = 250f)
            } else {
                s.steps.getOrNull(fs)?.points?.takeIf { it.size >= 2 }?.let {
                    mapController.fitSpan(it, topReserveDp = 48f, bottomReserveDp = 250f)
                }
            }
        }

        // 顶栏（窄横条：返回 < + 「路线预览」标题，48dp）
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(TOP_BAR_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.Black,
                    )
                }
                Text(text = "路线预览", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 底部两态弹层
        if (scheme != null) {
            PreviewSheet(
                scheme = scheme,
                mode = snap.mode,
                originName = snap.origin?.name ?: "我的位置",
                destinationName = snap.destination?.name ?: "终点",
                schemeCount = snap.schemes.size,
                selected = selected,
                onSelectScheme = {
                    selected = it
                    focusStep = null
                },
                focusStep = focusStep,
                onFocusStep = { i -> focusStep = if (focusStep == i) null else i },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun PreviewSheet(
    scheme: RouteScheme,
    mode: TravelMode,
    originName: String,
    destinationName: String,
    schemeCount: Int,
    selected: Int,
    onSelectScheme: (Int) -> Unit,
    focusStep: Int?,
    onFocusStep: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var dragging by remember { mutableStateOf(false) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var expanded by remember { mutableStateOf(false) }

    val collapsedPx = with(density) { SHEET_COLLAPSED.toPx() }
    // 展开上限 = 屏高 − 状态栏 − 导航条 − 顶栏 − 间隙（弹层顶边紧贴标题横条，§7.2；
    // 导航条必须扣除——2026-11-15 真机教训：折叠态圆点行被三键导航遮挡、触摸被系统吃掉）
    val maxPx = run {
        val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
        val navBarPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val reserve = statusBarPx + navBarPx + with(density) { (TOP_BAR_HEIGHT + 10.dp).toPx() }
        (with(density) { configuration.screenHeightDp.dp.toPx() } - reserve).coerceAtLeast(collapsedPx)
    }
    val targetPx = if (expanded) maxPx else collapsedPx
    val animatedPx by animateFloatAsState(targetValue = targetPx, animationSpec = tween(220), label = "sheetHeight")
    val heightPx = if (dragging) (collapsedPx + dragPx).coerceIn(collapsedPx, maxPx) else animatedPx
    val heightDp = with(density) { heightPx.toDp() }

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding() // 内容在系统导航条之上（圆点/把手可点）
            .height(heightDp),
    ) {
        Column {
            // 头部（把手 + 折叠摘要）= 唯一拖动手势面（展开后的行程链区滚动不受影响）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDrag = { change, amount ->
                                dragPx -= amount.y // 上拉（y 负增量）= 增高
                                change.consume()
                            },
                            onDragEnd = {
                                expanded = dragPx > (maxPx - collapsedPx) / 2f
                                dragPx = 0f
                                dragging = false
                            },
                            onDragCancel = {
                                dragPx = 0f
                                dragging = false
                            },
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .clickable { expanded = !expanded },
                )
                Text(
                    text = "全程 ${durationText(scheme.durationSec)}",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SegStrip(scheme = scheme, mode = mode, modifier = Modifier.padding(top = 12.dp))
                Text(
                    text = summaryLine(scheme, mode),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (schemeCount > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                    ) {
                        repeat(schemeCount) { i ->
                            val isSel = i == selected
                            // 28dp 外扩热区（6-8dp 圆点太小，真机 input tap/手指都难点中——2026-11-15 真机教训）
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { onSelectScheme(i) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(if (isSel) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(if (isSel) Primary else MaterialTheme.colorScheme.outlineVariant),
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 展开态：详细分段行程链
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (scheme.steps.isEmpty()) {
                    Text(
                        text = "该方式暂无分段详情",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        item {
                            ChainRows(
                                scheme = scheme,
                                mode = mode,
                                originName = originName,
                                destinationName = destinationName,
                                focusStep = focusStep,
                                onFocusStep = onFocusStep,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 折叠态分段示意条（参考图 1：步行灰胶囊 / 线路名胶囊）。 */
@Composable
private fun SegStrip(scheme: RouteScheme, mode: TravelMode, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scheme.steps.isEmpty()) {
            StripPill(text = modeLabel(mode), colorArgb = Primary, filled = false)
        } else if (mode == TravelMode.TRANSIT) {
            scheme.steps.forEach { step ->
                if (step.kind == RouteStepKind.WALK) {
                    StripPill(text = "步行 ${durationText(step.durationSec)}", colorArgb = WALK_GRAY_COLOR, filled = false)
                } else {
                    StripPill(
                        text = step.lineTitle ?: "乘车",
                        colorArgb = Color(RouteLineColors.colorOf(step.lineTitle)),
                        filled = true,
                    )
                }
            }
        } else {
            StripPill(text = modeLabel(mode), colorArgb = Primary, filled = false)
        }
    }
}

@Composable
private fun StripPill(text: String, colorArgb: Color, filled: Boolean) {
    if (filled) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colorArgb)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    } else {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colorArgb.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 折叠态汇总行：公交=「共 X 站 · 步行 Y 米 · N 元」；驾车=「红绿灯 · 距离」；步行/骑行=距离。 */
private fun summaryLine(scheme: RouteScheme, mode: TravelMode): String = when (mode) {
    TravelMode.TRANSIT -> {
        val stations = scheme.steps
            .filter { it.kind == RouteStepKind.BUS || it.kind == RouteStepKind.SUBWAY }
            .sumOf { it.stationCount ?: 0 }
        val walkMeters = scheme.steps.filter { it.kind == RouteStepKind.WALK }.sumOf { it.distanceM }
        val fare = scheme.steps.mapNotNull { it.fareYuan }.sum().takeIf { it > 0 }
        buildString {
            append("共 $stations 站 · 步行 ${distanceText(walkMeters)}")
            fare?.let { append(" · $it 元") }
        }
    }
    TravelMode.DRIVING -> buildString {
        scheme.lightNum?.let { append("$it 个红绿灯 · ") }
        append(distanceText(scheme.distanceM))
    }
    else -> "全程 ${distanceText(scheme.distanceM)}"
}

/** 展开态行程链（参考图 2 导轨视觉）：起点 → 各段 → 终点；点行 = 地图高亮该段。 */
@Composable
private fun ChainRows(
    scheme: RouteScheme,
    mode: TravelMode,
    originName: String,
    destinationName: String,
    focusStep: Int?,
    onFocusStep: (Int?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // 起点行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(START_BLUE),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "起点（$originName）",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        scheme.steps.forEachIndexed { i, step ->
            val selectedRow = focusStep == i
            val rowModifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (selectedRow) Primary.copy(alpha = 0.08f) else Color.Transparent)
                .clickable { onFocusStep(if (focusStep == i) null else i) }
                .padding(horizontal = 8.dp, vertical = 6.dp)
            when (step.kind) {
                RouteStepKind.WALK -> WalkChainRow(rowModifier, step)
                RouteStepKind.BUS, RouteStepKind.SUBWAY -> VehicleChainRow(rowModifier, step)
                else -> TurnChainRow(rowModifier, i, step)
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Spacer(modifier = Modifier.height(4.dp))
        // 终点行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(END_RED),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "终点（$destinationName）",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (mode == TravelMode.TRANSIT) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "方案时长包括等车时间",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 公交步步行行：灰点 + 「步行 N 米（M 分钟）」。 */
@Composable
private fun WalkChainRow(modifier: Modifier, step: RouteStepInfo) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(WALK_GRAY_COLOR),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "步行 ${distanceText(step.distanceM)}（${durationText(step.durationSec)}）",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 车段块：上车站 → 线路徽标行（线路色胶囊 + 方向 + 站数 + 时长）→ 下车站。 */
@Composable
private fun VehicleChainRow(modifier: Modifier, step: RouteStepInfo) {
    val lineColor = Color(RouteLineColors.colorOf(step.lineTitle))
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(lineColor),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = step.boardName ?: "上车点",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 18.dp, top = 4.dp),
        ) {
            Text(
                text = step.lineTitle ?: "乘车",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(lineColor)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = buildString {
                    step.directionText?.let { append("$it · ") }
                    step.stationCount?.let { append("${it}站 · ") }
                    append(durationText(step.durationSec))
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        step.alightName?.let { alight ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, lineColor, CircleShape),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = alight,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 驾车/步行/骑行步：序号徽标 + 指令文字（或路名）+ 「N 米 · M 分钟」。 */
@Composable
private fun TurnChainRow(modifier: Modifier, index: Int, step: RouteStepInfo) {
    Row(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = step.instruction
                    ?: step.roadName?.let { "沿$it 行驶" }
                    ?: "行驶",
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${distanceText(step.distanceM)} · ${durationText(step.durationSec)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
