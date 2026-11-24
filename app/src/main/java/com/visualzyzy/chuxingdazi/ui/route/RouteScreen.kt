package com.visualzyzy.chuxingdazi.ui.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.visualzyzy.chuxingdazi.data.tencent.OneShotLocator
import com.visualzyzy.chuxingdazi.data.tencent.RouteMapController
import com.visualzyzy.chuxingdazi.data.tencent.TravelMode
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import com.visualzyzy.chuxingdazi.ui.trip.TripTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val ROUTE_LINE_COLOR = 0xFF2A7DE1.toInt()

/** S10 路线规划（§6 S10）：顶部卡片（起/途经/终）+ 交通方式 + 地图 polyline + 底部方案面板。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RouteScreen(
    onClose: () -> Unit,
    onOpenWaypointPicker: () -> Unit,
    onOpenPointSearch: (target: String) -> Unit,
    onStartNavigation: (schemeIndex: Int) -> Unit = {},
    onPreview: () -> Unit = {}, // S30 路线预览（决策 #98）：点「预览」写快照并跳转独立页
) {
    val context = LocalContext.current
    val viewModel: RouteViewModel = hiltViewModel()
    val origin by viewModel.origin.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val wayStops by viewModel.wayStops.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val schemes by viewModel.schemes.collectAsStateWithLifecycle()
    val selectedScheme by viewModel.selectedScheme.collectAsStateWithLifecycle()
    val staleLine by viewModel.staleLine.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val errorMsg by viewModel.errorMsg.collectAsStateWithLifecycle()
    val planFailDialogMsg by viewModel.planFailDialogMsg.collectAsStateWithLifecycle()
    val timelineBadge by viewModel.timelineBadge.collectAsStateWithLifecycle()
    val orderEdited by viewModel.orderEdited.collectAsStateWithLifecycle()
    val myLocationFailed by viewModel.myLocationFailed.collectAsStateWithLifecycle()
    val categoriesById by viewModel.categoriesById.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    val mapController = remember { RouteMapController() }
    var showOriginPick by remember { mutableStateOf(false) }
    var showDestPick by remember { mutableStateOf(false) }
    var showNaviSheet by remember { mutableStateOf(false) }
    var showNaviNotice by remember { mutableStateOf(false) }
    var pendingJumpApp by remember { mutableStateOf<com.visualzyzy.chuxingdazi.ui.navi.ExternalApp?>(null) }
    var s20Seen by remember { mutableStateOf(true) }
    // S16 跳转丢途经点确认框「不再提示」：true = 有途经点跳转不再弹确认
    var viaDropAck by remember { mutableStateOf(false) }

    // P35（2026-11-02）：起点区定位小图标 —— 授权流程 + 定位落起点
    fun locateOriginNow() {
        OneShotLocator.locate(context) { lat, lng ->
            if (lat != null && lng != null) {
                viewModel.forceOriginMyLocation(lat, lng)
            } else {
                android.widget.Toast.makeText(
                    context,
                    "未获取到定位，请检查系统定位开关",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val originLocateLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            locateOriginNow()
        } else {
            android.widget.Toast.makeText(
                context,
                "未授予定位权限，起点保持不变（无起终点时不能确认路线）",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        s20Seen = Prefs.s20NoticeSeen(context).first()
        viaDropAck = Prefs.viaDropConfirmAck(context).first()
        // 第九轮（2026-11-08）：S20 只在进入路线页时弹；点「开始导航」直接弹 S16，不再弹 S20
        if (!s20Seen && !viewModel.s20NoticeShown) {
            viewModel.s20NoticeShown = true
            showNaviNotice = true
        }
    }

    // 应用内导航：算路节点 = 起点/途经/终点（GCJ02 直传，腾讯版免转换）
    // 2026-11-21 方案 A 落地（#20 收口）：全 App 改用导航包内置地图库（官方形态）→
    // 步行/骑行走导航 SDK 自建地图、native 0x58 崩溃根因链消除（真机验证，见
    // docs/导航SDK自建地图-MapView共存备选方案.md §九）→ 应用内导航恢复**驾车/步行/
    // 骑行**；公交无导航器（官方无 NavigatorBus）仍拦；步行/骑行官方算路无途经点参数，
    // 有途经点时拦并说明。
    val launchInAppNavi: () -> Unit = navGuard@{
        if (mode == com.visualzyzy.chuxingdazi.data.tencent.TravelMode.TRANSIT) {
            android.widget.Toast.makeText(
                context,
                "应用内导航暂不支持公交，请选择地图 App 跳转",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return@navGuard
        }
        if (mode != com.visualzyzy.chuxingdazi.data.tencent.TravelMode.DRIVING && wayStops.isNotEmpty()) {
            android.widget.Toast.makeText(
                context,
                "步行/骑行导航不支持途经点，请移除途经点或改用地图 App",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return@navGuard
        }
        val o = origin
        val d = destination
        if (o == null || d == null) {
            android.widget.Toast.makeText(context, "请先设置起终点", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fineGranted) {
                android.widget.Toast.makeText(context, "导航需要定位权限，请先授权定位", android.widget.Toast.LENGTH_LONG).show()
            } else {
                // 遗留 #16（2026-11-18）：选「应用内导航」即刻预热系统 TTS 引擎——引擎 init
                // 与算路（等 GPS fix 实测 ~9s）并行跑，引导开始播首条时通常已就绪；未就绪的
                // 播报也有 SystemTtsBridge 的待播队列兜底补播。（2026-11-21：预热条件改
                // 非公交即预热——步行/骑行引导同样播报。）
                com.visualzyzy.chuxingdazi.data.navi.SystemTtsBridge.preheat(context)
                // 2026-11-16（-5）：首次算路要等引擎拿到 GPS fix 才回包（-4 日志实测 9 秒），
                // 无反馈的空窗被用户当成「卡顿/没反应」——点击即给提示
                android.widget.Toast.makeText(
                    context,
                    "正在获取定位并规划导航路线，请稍候…",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                // 2026-11-17 腾讯版：算路走 navix searchRoute（请求内部串行），成功记录
                // pendingRouteId，NavGuideActivity bindView 后 startNavigation。
                // （2026-11-20 深夜页内算路实验已回退：序列与 non-motor tick 崩溃无关——
                // 六连二分排除；且页内序列下驾车引导 UI 不渲染=新回归，恢复跨页形态。）
                com.visualzyzy.chuxingdazi.data.navi.NaviSdk.ensureInit(
                    context,
                    onReady = {
                        com.visualzyzy.chuxingdazi.data.navi.NaviSdk.routePlanToNavi(
                            context = context,
                            start = Pair(o.lat, o.lng),
                            via = wayStops.map { Pair(it.lat, it.lng) },
                            end = Pair(d.lat, d.lng),
                            travelMode = mode,
                        ) { ok, err ->
                            if (ok) {
                                context.startActivity(
                                    android.content.Intent(context, com.visualzyzy.chuxingdazi.ui.navi.NavGuideActivity::class.java)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } else {
                                android.widget.Toast.makeText(context, err ?: "算路失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onFail = { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    val distanceLabel = schemes.getOrNull(selectedScheme)?.let { distanceText(it.distanceM) } ?: ""
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val jumpExternal: (com.visualzyzy.chuxingdazi.ui.navi.ExternalApp) -> Unit = { app ->
        val o = origin
        val d = destination
        if (o != null && d != null) {
            // 第二十八轮：出行方式跟随所选（百度 mode/高德 t/腾讯 type），驾车时带途经点见 NaviSheets
            com.visualzyzy.chuxingdazi.ui.navi.jumpToExternal(context, app, o, d, wayStops, mode)
        }
    }

    // 一次性定位我的位置（起点默认）+ 消费 S11/S21 回传
    LaunchedEffect(Unit) {
        viewModel.consumeTransfers()
        runCatching { Prefs.lastCity(context).first()?.takeIf { it.isNotBlank() }?.let { viewModel.city = it } }
        OneShotLocator.locate(context) { lat, lng ->
            if (lat != null && lng != null) viewModel.setMyLocation(lat, lng) else viewModel.markMyLocationFailed()
        }
    }

    // 地图叠加层随状态重画
    // P50（2026-11-03）问题5：视野适配只发生在「必要时刻」，其余情况不动相机——
    // 进入时不重置（相机恢复在 AndroidView factory 里），首次有终点时适配一次，
    // 首个算路方案到达时全览一次。规划期间用户可自由拖动，不会被拉回。
    LaunchedEffect(origin, wayStops, destination, phase, schemes, selectedScheme, staleLine, categoriesById) {
        val map = mapController
        map.clear()
        val scheme = schemes.getOrNull(selectedScheme)
        if (scheme != null && scheme.points.size >= 2) {
            val alpha = if (phase == RouteViewModel.Phase.PENDING && staleLine) 32 else 100
            map.showRoute(scheme.points, ROUTE_LINE_COLOR, alpha)
        }
        origin?.let { map.addNodeMarker(it.lat, it.lng, RouteMapController.originDot(context), -1) }
        wayStops.forEachIndexed { i, stop ->
            val color = stop.placeId?.let { categoriesById[it]?.color }?.let(::parseRouteColor) ?: 0xFF5B8DEF.toInt()
            // 第十八轮：途经点改「分类色序号圆标」（白描边 + 白色序号，S26 行程地图同一套 numberedNode）——
            // 与 GUI 途径行徽标同号同色，点线混在一起也能认出「途经 1/2/3」各是哪个点
            map.addNodeMarker(stop.lat, stop.lng, RouteMapController.numberedNode(context, i + 1, color), i)
        }
        destination?.let { map.addNodeMarker(it.lat, it.lng, RouteMapController.destinationDot(context), -2) }
        if (!viewModel.mapInitDone && destination != null) {
            // 首次出现终点（点位详情「导航前往」/用户选终点）→ 让起终点同框
            viewModel.mapInitDone = true
            val pts = listOfNotNull(origin, *wayStops.toTypedArray(), destination)
                .map { LatLng(it.lat, it.lng) }
            if (pts.isNotEmpty()) map.fitTo(pts, 140)
        }
        if (scheme != null && !viewModel.schemeFitted) {
            viewModel.schemeFitted = true
            // 第三十一轮改版二（用户确认）：连续比例缩放——线路跨度占可见区 70%，锚 = 包围盒中心
            // （顶部遮挡 = 卡片+胶囊 ≈250dp，底部 = 面板+导航条 ≈190dp）；无档位跳变、任何长度完整入画。
            // 2026-11-16 用户需求：起终点圆点有被屏幕边缘裁掉的情况 → 算出的 zoom 再放开一级
            //（extraZoomOutLevels=1，百度 zoom−1 = 视野扩大一级），保证圆点完整落屏
            map.fitSpan(scheme.points, topReserveDp = 250f, bottomReserveDp = 190f, extraZoomOutLevels = 1f)
        }
    }

    // P50（2026-11-03）问题5：路线页拖动/缩放只记录到 viewModel.lastCamera（内存），
    // 不写主界面的相机快照 → 规划期间自由看地图，退出后主界面原地不动
    LaunchedEffect(Unit) {
        mapController.setOnCameraIdle { lat, lng, zoom ->
            viewModel.lastCamera = Prefs.CameraSnapshot(lat, lng, zoom)
        }
    }

    // 2026-11-16（-9，临时诊断期）：每次退出导航回 S10 后定位管线卡死（用户实测 errorMsg
    // 「未获取到我的位置」红字常驻、主界面定位不动，重启才恢复）——回前台时检测「刚退出
    // 导航」→ 重启定位客户端（新建 LocationClient）+ 重新定位，成功即清掉红字。
    val resumeOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val lastHandledGuideExit = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.DisposableEffect(resumeOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val t = com.visualzyzy.chuxingdazi.data.navi.NaviSdk.lastGuideDestroyAt
                if (t > 0 && t != lastHandledGuideExit.value) {
                    lastHandledGuideExit.value = t
                    com.visualzyzy.chuxingdazi.data.tencent.OneShotLocator.locate(context) { lat, lng ->
                        if (lat != null && lng != null) {
                            viewModel.setMyLocation(lat, lng)
                            viewModel.errorMsg.value = null
                        } else {
                            viewModel.markMyLocationFailed()
                        }
                    }
                }
            }
        }
        resumeOwner.lifecycle.addObserver(obs)
        onDispose { resumeOwner.lifecycle.removeObserver(obs) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 地图
        val mapView = com.visualzyzy.chuxingdazi.ui.map.rememberTencentMapView(context)
        if (mapView == null) {
            Text(
                text = "地图引擎不可用",
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    // 腾讯版：SDK 缩放控件在 RouteMapController.attach 内关（uiSettings）
                    mapController.attach(mapView)
                    // P50（2026-11-03）问题5：每次 MapView 新建（含子页返回）都恢复相机——
                    // 优先本页记录（规划期间拖动过的位置），否则主界面快照（进入时地图不变化）。
                    // 只恢复不落盘：路线页的相机改动只存 viewModel.lastCamera，不影响主界面。
                    scope.launch {
                        val cam = viewModel.lastCamera
                            ?: Prefs.cameraSnapshot(context)
                        cam?.let { mapController.moveToImmediate(it.lat, it.lng, it.zoom) }
                    }
                    mapView
                },
            )
            // 遗留 #19 修复（2026-11-18）：S10 是全项目唯一没转发生命周期事件的裸 MapView 宿主
            //（S03 走 MapHost、S26=决策 #108、S30=决策 #112 都已带 observer）——腾讯 MapView
            // 收不到 ON_RESUME 不启动渲染管线，表现为「地图区画面纯色，起终点标记与路线线
            // 均不出、方案方块/预览/开始导航正常」，与 key 无关（同 build 同 manifest 下 S03
            // 渲染完好）。补 onStart/onResume/onPause/onStop 转发（照 MapHost/S30 同款写法）。
            val mapLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
            androidx.compose.runtime.DisposableEffect(mapLifecycle) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_START -> runCatching { mapView.onStart() }
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> runCatching { mapView.onResume() }
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> runCatching { mapView.onPause() }
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> runCatching { mapView.onStop() }
                        else -> Unit
                    }
                }
                mapLifecycle.addObserver(observer)
                onDispose {
                    mapLifecycle.removeObserver(observer)
                    mapController.detach()
                    runCatching { mapView.onDestroy() }
                }
            }
        }

        // 顶部卡片
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // P51（2026-11-03）问题12：返回箭头改黑色（底图恒为浅色样式，黑箭头可见）
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.Black,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onClose)
                        .padding(2.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                timelineBadge?.let { badge ->
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xCC14171C)) {
                        Text(
                            text = badge,
                            fontSize = 10.5.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (orderEdited) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "顺序已改，与时间轴不一致",
                        fontSize = 10.sp,
                        color = Color(0xFFFFC53D),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    // 起点行（P33：标签紧贴圆点右边；P34：已选点位后「改为点位」→「修改点位」；P35：定位小图标）
                    StopRow(
                        colorDot = { Box(modifier = Modifier.size(11.dp).background(Color(0xFF1B6EF3), CircleShape)) },
                        // P52（2026-11-03）问题16：定位失败文案精简为「未获取到位置」
                        name = origin?.name ?: if (myLocationFailed) "未获取到位置" else "定位中…",
                        tag = "起点",
                        tagAfterDot = true,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // P35（2026-11-02）：定位小图标 → 起点变为实时我的位置（含授权处理）
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = "起点改为我的位置",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable {
                                            val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                                context,
                                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                            if (fineGranted) {
                                                locateOriginNow()
                                            } else {
                                                // P35：未授权 → 触发系统授权弹窗；拒绝时 Toast 且起点不变、界面不崩
                                                originLocateLauncher.launch(
                                                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                                                )
                                            }
                                        },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (origin != null) "修改点位" else "改为点位",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showOriginPick = true },
                                )
                                Text(text = "｜", fontSize = 11.sp, color = MaterialTheme.colorScheme.outlineVariant)
                                // P53（2026-11-03）问题13：「地图搜点」文字颜色与「改为点位」一致（主色）
                                Text(
                                    text = "地图搜点",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpenPointSearch("origin") },
                                )
                            }
                        },
                    )
                    Divider()
                    // 途经区（最多可见 5 行，内部滚动；长按拖拽排序）
                    val rowHeightPx = with(LocalDensity.current) { 46.dp.toPx() }
                    var dragIndex by remember { mutableStateOf(-1) }
                    var dragOffsetY by remember { mutableStateOf(0f) }
                    Column(
                        modifier = Modifier
                            .heightIn(max = with(LocalDensity.current) { (46 * 3).dp })
                            .verticalScroll(rememberScrollState()),
                    ) {
                        wayStops.forEachIndexed { index, stop ->
                            val color = stop.placeId?.let { categoriesById[it]?.color }?.let(::parseRouteColor)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .graphicsLayer {
                                        if (dragIndex == index) translationY = dragOffsetY
                                    }
                                    .pointerInput(wayStops.size) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                dragIndex = index
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, amt ->
                                                change.consume()
                                                dragOffsetY += amt.y
                                                while (dragOffsetY > rowHeightPx / 2 && dragIndex < wayStops.size - 1) {
                                                    viewModel.reorderWay(dragIndex, dragIndex + 1)
                                                    dragIndex++
                                                    dragOffsetY -= rowHeightPx
                                                }
                                                while (dragOffsetY < -rowHeightPx / 2 && dragIndex > 0) {
                                                    viewModel.reorderWay(dragIndex, dragIndex - 1)
                                                    dragIndex--
                                                    dragOffsetY += rowHeightPx
                                                }
                                            },
                                            onDragEnd = {
                                                viewModel.onReorderFinished()
                                                dragIndex = -1
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                dragIndex = -1
                                                dragOffsetY = 0f
                                            },
                                        )
                                    }
                                    .padding(horizontal = 12.dp),
                            ) {
                                // 第十八轮：序号圆标（分类色底 + 白色序号，与地图途经点标记同号同色）——
                                // 替代原 11dp 方形色点 + 行尾「途径 N」文字（序号入圆标后文字重复）
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background((color ?: 0xFF5B8DEF.toInt()).let { Color(it) }, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 10.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stop.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "⠿",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.clickable { },
                                )
                                Text(
                                    text = "×",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable { viewModel.removeWay(index) }
                                        .padding(start = 8.dp),
                                )
                            }
                        }
                    }
                    if (wayStops.isNotEmpty()) Divider()
                    // 终点行（P33：标签紧贴圆点右边；P54 问题9：红方框→与起点同大的红色圆点；
                    // P55 问题10：按钮顺序/文本改为「从点位选择 地图搜点」，地图搜点在行尾与起点行对齐；
                    // P56 问题11：默认占位文字「设置终点」→「尚未设置」）
                    StopRow(
                        colorDot = { Box(modifier = Modifier.size(11.dp).background(Color(0xFFE5484D), CircleShape)) },
                        name = destination?.name ?: "尚未设置",
                        tag = "终点",
                        tagAfterDot = true,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "从点位选择",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showDestPick = true },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "地图搜点",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpenPointSearch("destination") },
                                )
                            }
                        },
                    )
                    Divider()
                    // 底部操作行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        // P57（2026-11-03）问题15：去掉灰色底，上下箭头+文字与「改为点位」同主色，
                        // 视觉与右侧「＋ 添加途经点」一致
                        Text(
                            text = "⇅ 起终点互换",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { viewModel.swapEnds() }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "＋ 添加途径点",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(onClick = onOpenWaypointPicker),
                        )
                    }
                }
            }

            // 交通方式切换（悬浮胶囊）
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    listOf(
                        TravelMode.DRIVING to "驾车",
                        TravelMode.WALKING to "步行",
                        TravelMode.BIKING to "骑行",
                        TravelMode.TRANSIT to "公交",
                    ).forEach { (m, label) ->
                        val selectedMode = mode == m
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (selectedMode) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selectedMode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selectedMode) Primary else Color.Transparent)
                                .clickable { viewModel.setMode(m) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // 底部面板
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .padding(16.dp)
                .navigationBarsPadding(),
        ) {
            if (phase == RouteViewModel.Phase.PENDING) {
                // P36（2026-11-02）：删掉默认提示行；只保留「改动后旧线已作数」的必要提示
                if (schemes.isNotEmpty() && staleLine) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0x1A9A6B00),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "上图那条是改之前算的，已经不作数了 · 确认后重新计算",
                            fontSize = 11.5.sp,
                            color = com.visualzyzy.chuxingdazi.ui.theme.warningAmber(androidx.compose.foundation.isSystemInDarkTheme()),
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                errorMsg?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 第十六轮（优化建议）：缺起点或终点时禁用「确认路线」，并写明缺哪一端
                val missingEnds = listOfNotNull(
                    if (origin == null) "起点" else null,
                    if (destination == null) "终点" else null,
                )
                PrimaryButton(
                    text = when {
                        loading -> "规划中…"
                        missingEnds.isNotEmpty() -> "确认路线（缺${missingEnds.joinToString("和")}）"
                        else -> "确认路线"
                    },
                    enabled = !loading && missingEnds.isEmpty(),
                ) { viewModel.confirmRoute() }
            } else {
                // 2026-11-15（用户定调，444.png）：方案卡改横向方块——n 个均分宽度（n<3 自动扩宽）、
                // 压缩高度给地图留空间；原竖排列表与「N 条X路线」标题废除
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    schemes.forEachIndexed { i, s ->
                        val sel = i == selectedScheme
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    width = if (sel) 1.5.dp else 0.dp,
                                    color = if (sel) Primary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable { viewModel.selectScheme(i) }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                        ) {
                            if (sel) {
                                // 浮层小徽标：不占布局宽（占位尾距会让长标题截断，第三十一轮改浮层）
                                Text(
                                    text = "✓",
                                    color = Primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 2.dp, end = 4.dp),
                                )
                            }
                            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                                Text(
                                    text = "约 ${durationText(s.durationSec)}",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = buildString {
                                        append(distanceText(s.distanceM))
                                        s.lightNum?.let { append(" · $it 个红绿灯") }
                                    },
                                    fontSize = 10.sp,
                                    lineHeight = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                errorMsg?.let { err ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = err, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clickable {
                                // S30 路线预览（决策 #98）：快照进 RoutePreviewStore（tempView 同族先例，
                                // RouteScheme 含数百 LatLng 无法走 nav args）→ 跳转独立页；返回后 S10 状态不丢
                                if (schemes.isNotEmpty()) {
                                    RoutePreviewStore.snapshot = RoutePreviewStore.Snapshot(
                                        mode = mode,
                                        schemes = schemes,
                                        selectedIndex = selectedScheme,
                                        origin = origin?.let {
                                            RoutePreviewStore.StopSnapshot(it.name, it.lat, it.lng)
                                        },
                                        destination = destination?.let {
                                            RoutePreviewStore.StopSnapshot(it.name, it.lat, it.lng)
                                        },
                                        waypoints = wayStops.map { stop ->
                                            RoutePreviewStore.StopSnapshot(
                                                name = stop.name,
                                                lat = stop.lat,
                                                lng = stop.lng,
                                                colorArgb = stop.placeId?.let { id -> categoriesById[id]?.color }
                                                    ?.let(::parseRouteColor),
                                            )
                                        },
                                    )
                                    onPreview()
                                }
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "预览", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .weight(1.6f)
                            .height(46.dp)
                            .background(Primary, RoundedCornerShape(14.dp))
                            .clickable {
                                // 第九轮（2026-11-08）：点「开始导航」不再弹 S20（只在进入路线页时弹），直接弹 S16
                                showNaviSheet = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "开始导航",
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // 起点/终点「从点位选」浮层
        val allPlaces by viewModel.places.collectAsStateWithLifecycle()
        if (showOriginPick) {
            PlacePickSheet(
                title = "起点改为…",
                places = allPlaces,
                categories = categories,
                onPick = { place ->
                    viewModel.setOrigin(RouteViewModel.Stop(placeId = place.id, name = place.name, lat = place.lat, lng = place.lng))
                    showOriginPick = false
                },
                onDismiss = { showOriginPick = false },
            )
        }
        if (showDestPick) {
            PlacePickSheet(
                title = "终点改为…",
                places = allPlaces,
                categories = categories,
                onPick = { place ->
                    viewModel.setDestination(RouteViewModel.Stop(placeId = place.id, name = place.name, lat = place.lat, lng = place.lng))
                    showDestPick = false
                },
                onDismiss = { showDestPick = false },
            )
        }

        // S20 导航说明（P68：样式改为视觉稿 s20.png；只在进入路线页时自动弹，
        // 点「知道了」只关闭；「开始导航」按钮不再触发 S20，直接弹 S16——第九轮 2026-11-08）
        if (showNaviNotice) {
            com.visualzyzy.chuxingdazi.ui.navi.NaviNoticeDialog(
                onGotIt = { never ->
                    showNaviNotice = false
                    if (never) {
                        s20Seen = true
                        scope.launch { Prefs.setS20NoticeSeen(context, true) }
                    }
                },
            )
        }

        // S16 导航方式（每次点「开始导航」都重弹，§6 S16）
        // 第二十三轮：传 travelMode——2026-11-20 起驾车/步行/骑行可用（骑行/步行有途经点时禁用），公交禁用
        if (showNaviSheet) {
            Box(modifier = Modifier.fillMaxSize()) {
                com.visualzyzy.chuxingdazi.ui.navi.NaviMethodSheet(
                    distanceText = distanceLabel,
                    viaCount = wayStops.size,
                    travelMode = mode,
                    onStartInApp = launchInAppNavi,
                onJump = { app ->
                    // 第二十八轮：仅「真丢途经点」的组合（非驾车任意 App / 驾车跳高德）才弹确认——
                    // 驾车跳百度/腾讯会带途经点（上限15），不再拦
                    val dropsVia = wayStops.isNotEmpty() &&
                        com.visualzyzy.chuxingdazi.ui.navi.jumpDropsWaypoints(app, mode)
                    if (dropsVia && !viaDropAck) {
                        pendingJumpApp = app
                    } else {
                        jumpExternal(app)
                    }
                },
                    onCopy = {
                        origin?.let { o ->
                            destination?.let { d ->
                                com.visualzyzy.chuxingdazi.ui.navi.copyRouteText(context, o, d, wayStops, mode)
                            }
                        }
                    },
                    onDismiss = { showNaviSheet = false },
                )
            }
        }

        // 跳转丢途经点二次确认（§6 S16）
        // 2026-11-20：驾车（应用内导航可带途经点）才显示「改用应用内导航」；步行/骑行的
        // 应用内导航同样不带途经点，弹窗只确认丢失、不给假出口
        pendingJumpApp?.let { app ->
            com.visualzyzy.chuxingdazi.ui.navi.ViaDropConfirmDialog(
                viaCount = wayStops.size,
                inAppAvailable = mode == com.visualzyzy.chuxingdazi.data.tencent.TravelMode.DRIVING,
                onStillJump = { dontRemind ->
                    jumpExternal(app)
                    if (dontRemind && !viaDropAck) {
                        viaDropAck = true
                        scope.launch { Prefs.setViaDropConfirmAck(context, true) }
                    }
                    pendingJumpApp = null
                },
                onUseInApp = { dontRemind ->
                    if (dontRemind && !viaDropAck) {
                        viaDropAck = true
                        scope.launch { Prefs.setViaDropConfirmAck(context, true) }
                    }
                    pendingJumpApp = null
                    launchInAppNavi()
                },
                onDismiss = { pendingJumpApp = null },
            )
        }

        // 算路失败简短弹窗（2026-11-23，用户令：SDK 明说原因时只提示几个字，不加详细说明）。
        // 返回键只关弹窗不穿透路由（P141 同族约定）。
        planFailDialogMsg?.let { failMsg ->
            BackHandler(enabled = true) { viewModel.clearPlanFailDialog() }
            AlertDialog(
                onDismissRequest = { viewModel.clearPlanFailDialog() },
                title = { Text(text = "无法规划路线") },
                text = { Text(text = failMsg) },
                confirmButton = {
                    Text(
                        text = "知道了",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { viewModel.clearPlanFailDialog() }
                            .padding(8.dp),
                    )
                },
            )
        }
    }
}

/**
 * 起点/终点「从点位选」底部浮层（第十七轮 2026-11-14 重做）：
 * 与「清单管理-添加点位」（S28 添加浮层）同一套点位搜索——
 * ui/common/PlaceSearchBars 多模式搜索（名称/地区/分类，可 ＋ 追加多栏 AND）+ 全库点位列表，
 * 行 = 分类色块图标 + 名称 + 「地址/区域 · 分类名」；单选，点行即回填并关闭。
 * 弹出期间系统返回键 = 关浮层（P141 同族；原 AlertDialog 在 Compose ui 1.7 下返回键会穿透）。
 */
@Composable
private fun PlacePickSheet(
    title: String,
    places: List<com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity>,
    categories: List<com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity>,
    onPick: (com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var bars by remember { mutableStateOf(listOf(com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBar())) }
    val markedProvinces = remember(places) {
        places.mapNotNull { it.province?.takeIf { p -> p.isNotBlank() } }.distinct()
    }
    val filtered = remember(places, bars) {
        com.visualzyzy.chuxingdazi.ui.common.filterPlacesBySearchBars(places = places, bars = bars)
    }
    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    BackHandler(enabled = true) { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x52000000))
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(onClick = onDismiss),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBarsSection(
                    bars = bars,
                    onBarsChange = { bars = it },
                    categories = categories,
                    markedProvinces = markedProvinces,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Column(
                    modifier = Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    filtered.forEach { place ->
                        val category = categoriesById[place.categoryId]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(place) }
                                .padding(vertical = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        com.visualzyzy.chuxingdazi.ui.common.parseCategoryColorSafe(category?.color),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                com.visualzyzy.chuxingdazi.ui.common.CategoryIconTinted(
                                    iconKey = category?.iconKey,
                                    size = 20.dp,
                                    tint = null,
                                )
                            }
                            Spacer(modifier = Modifier.width(11.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = place.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight(600),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    // 与 S28 列表行同构：地址（缺失回退省-市，再缺失「暂无地址」）· 分类名
                                    // 遗留 #9（2026-11-18）：regionLabelOf 去重直辖市「北京市-北京市」
                                    text = listOfNotNull(
                                        place.address
                                            ?: com.visualzyzy.chuxingdazi.ui.common.regionLabelOf(place.province, place.city)
                                            ?: "暂无地址",
                                        category?.name,
                                    ).joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            text = if (places.isEmpty()) "点位库还是空的，先回主地图添加点位" else "没有匹配的点位",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun StopRow(
    colorDot: @Composable () -> Unit,
    name: String,
    tag: String,
    trailing: @Composable () -> Unit,
    tagAfterDot: Boolean = false, // P33（2026-11-02）：标签紧贴圆点右边
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(horizontal = 12.dp),
    ) {
        colorDot()
        if (tagAfterDot) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = tag, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!tagAfterDot) {
            Text(text = tag, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(10.dp))
        }
        trailing()
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 19.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(if (enabled) Primary else Primary.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun modeText(mode: TravelMode): String = when (mode) {
    TravelMode.DRIVING -> "驾车"
    TravelMode.WALKING -> "步行"
    TravelMode.BIKING -> "骑行"
    TravelMode.TRANSIT -> "公交"
}

fun durationText(sec: Int): String {
    val min = (sec + 59) / 60
    return if (min >= 60) "${min / 60} 小时 ${min % 60} 分钟" else "$min 分钟"
}

fun distanceText(m: Int): String = if (m >= 1000) "%.1f km".format(m / 1000f) else "$m 米"

private fun parseRouteColor(hex: String?): Int = try {
    android.graphics.Color.parseColor(hex ?: "#5B8DEF")
} catch (e: Exception) {
    0xFF5B8DEF.toInt()
}
