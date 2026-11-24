package com.visualzyzy.chuxingdazi.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.visualzyzy.chuxingdazi.data.tencent.RouteMapController
import com.visualzyzy.chuxingdazi.ui.theme.Primary

/** S26 行程地图（§6 S26）：按天分段连线（顺序图，细虚线）+ 全程连续编号节点。
 *  2026-11-23 #6 三次改定：实线箭头 → 细虚线去箭头；「全部」态维持每天相邻点连线形态；
 *  选中某天时其他天改半透明虚线拟合曲线 + 仅首末端点（带编号）；过夜段+次日早段
 *  合并单编号点（边界号两天共享）。直线只表示先后顺序。 */
/** 2026-11-24 #7：天色板 3 色 → 12 色（第 4 天起不再与第 1 天撞色）。
 *  设计约束：前三色保持原值（存量行程观感不变）；不含白色/近白，纯黑排末位
 *  （第 12 天才轮到）；相邻索引色相差 ≥ 60°（10 色以后靠明度拉开）；
 *  顺序即识别约定（线色/节点底色/天芯片圆点三处同源），勿随意调换；
 *  超过 12 天继续循环（mod 语义）。 */
private val DAY_COLORS = intArrayOf(
    0xFF0E7490.toInt(), // 1  青蓝（原第1天色，保留）
    0xFFF97316.toInt(), // 2  橙（原第2天色，保留）
    0xFF8B5CF6.toInt(), // 3  紫（原第3天色，保留）
    0xFFDC2626.toInt(), // 4  红
    0xFF16A34A.toInt(), // 5  绿
    0xFF2563EB.toInt(), // 6  蓝
    0xFFDB2777.toInt(), // 7  玫红
    0xFFCA8A04.toInt(), // 8  金
    0xFFC026D3.toInt(), // 9  洋红
    0xFF4D7C0F.toInt(), // 10 橄榄
    0xFF6D28D9.toInt(), // 11 靛
    0xFF000000.toInt(), // 12 纯黑（用户明示可用；排末位，第12天才出现）
)

/** 2026-11-24 #6 终版：其他天淡化色（HSL 保色相降饱和，公式与生成脚本见
 *  docs/修复方案-20261124选天淡化补遗.md 附录；与 DAY_COLORS 按索引对齐，
 * 由 tools_seed/day_color_mute.py 生成，改参数请重跑脚本整表换，勿手改单值）。 */
private val DAY_COLORS_MUTED = intArrayOf(
    0xFF2A93B0.toInt(), // 1  青蓝 → 浅青
    0xFFE08748.toInt(), // 2  橙 → 柔橙
    0xFF9674E4.toInt(), // 3  紫 → 柔紫
    0xFFCC5454.toInt(), // 4  红 → 柔红
    0xFF33BA65.toInt(), // 5  绿 → 柔绿
    0xFF527CD7.toInt(), // 6  蓝 → 柔蓝
    0xFFCB5489.toInt(), // 7  玫红 → 柔玫
    0xFFD89E23.toInt(), // 8  金 → 柔金
    0xFFBB51C8.toInt(), // 9  洋红 → 柔洋红
    0xFF6EA22A.toInt(), // 10 橄榄 → 柔橄榄
    0xFF8355CA.toInt(), // 11 靛 → 柔靛
    0xFF363636.toInt(), // 12 纯黑 → 深灰（黑无色相，公式自然落到明度灰）
)

fun dayColor(dayIndex: Int): Int = DAY_COLORS[dayIndex.mod(DAY_COLORS.size)]

@Composable
fun TripMapScreen(
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: TripMapViewModel = hiltViewModel()
    val trip by viewModel.trip.collectAsStateWithLifecycle()
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val unscheduledCount by viewModel.unscheduledCount.collectAsStateWithLifecycle()

    val mapController = remember { RouteMapController() }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    var selectedNode by remember { mutableStateOf<TripMapNode?>(null) }
    // 2026-11-23 #7：删 navBarBottomPx——地图画布已避让系统导航条，按天筛选的 fitTo
    // 底部只需为天芯片面板+提示行留位（120dp），不再补偿导航条高度。
    val density = LocalDensity.current

    // 2026-11-23 #6 三次改定：days = 各节点所属天集合的并集去重排序（合并节点横跨两天）
    val days = remember(nodes) { nodes.flatMap { it.dayOffsets }.distinct().sorted() }
    val tripStart = TripTime.tripStart(trip?.startDate)

    mapController.onMarkerTap = { index ->
        selectedNode = nodes.getOrNull(index)
    }

    // 画图
    // 2026-11-23 #6 三次改定重写：
    // - 线样式：全部细虚线（原生 pattern()——旧注记「SDK 无虚线开关 → 走 tile 纹理」是
    //   错的，官方 javadoc + AAR 7.10.0 javap 双证 pattern() 存在；colorTexture 实际是
    //   抽纹理某一行拉成整条线，tile 方案必出实线）、无箭头、
    //   无白描边（showRoute 一字未动，S10/S30 实线箭头不受影响）；
    // - 「全部」态：形态与旧版同构——每天相邻节点依次连线（不用曲线）+ 全量编号点；
    //   换天酒店位只立一枚合并编号点（过夜段+次日早段合并，见 TripMapViewModel）；
    // - 选中某天态：当天直线细虚线全量；其他天 = 半透明虚线拟合曲线（中点二次贝塞尔，
    //   不刺出不回环）+ 仅首末端点半透明带编号（中间点不画）；重合去重：
    //   ① nodeId 已画过跳过 ② 节点属选中天跳过（全量标记已在位）③ 同点位兜底；
    //   不重合的端点就是普通半透明编号点，无任何特殊处理。
    LaunchedEffect(nodes, selectedDay) {
        mapController.clear()
        if (nodes.isEmpty()) return@LaunchedEffect
        // selDay = 普通局部 val（selectedDay 是委托属性，智能转换不可依赖）
        val selDay = selectedDay
        val indexOfId = nodes.withIndex().associate { (idx, node) -> node.scheduleId to idx }
        // 合并点的圆点颜色取「早段所属天」（dayOffsets 末位）的天色
        fun nodeColor(node: TripMapNode): Int = dayColor(days.indexOf(node.dayOffsets.last()))

        if (selDay == null) {
            // 「全部」态：每天相邻节点依次连线（细虚线 alpha=100 宽 5dp，不用曲线）
            days.forEach { day ->
                val dayNodes = nodes.filter { day in it.dayOffsets }
                if (dayNodes.size < 2) return@forEach
                mapController.showSchematicRoute(
                    dayNodes.map { LatLng(it.lat, it.lng) },
                    dayColor(days.indexOf(day)),
                    100,
                    5f,
                )
            }
            // 全量连续编号节点（每节点只渲染一次）
            nodes.forEachIndexed { index, node ->
                mapController.addNodeMarker(
                    lat = node.lat,
                    lng = node.lng,
                    icon = RouteMapController.numberedNode(context, index + 1, nodeColor(node), 100),
                    index = index,
                )
            }
        } else {
            val day = selDay
            // 选中天：直线细虚线 alpha=100、宽 5dp（同「全部」态样式，不用曲线）+ 全量编号点
            val selNodes = nodes.filter { day in it.dayOffsets }
            if (selNodes.size >= 2) {
                mapController.showSchematicRoute(
                    selNodes.map { LatLng(it.lat, it.lng) },
                    dayColor(days.indexOf(day)),
                    100,
                    5f,
                )
            }
            selNodes.forEach { node ->
                val gid = indexOfId[node.scheduleId] ?: return@forEach
                mapController.addNodeMarker(
                    lat = node.lat,
                    lng = node.lng,
                    // 2026-11-24 #6b：选中天节点统一染**选中天色**——傍晚边界合并点
                    // [selDay, nextDay] 此前按 nodeColor(dayOffsets.last()) 染成次日色，
                    // 选中天自己的收尾酒店被染错；普通节点与清晨边界点 [prevDay, selDay]
                    // 本来就等于选中天色，不受影响。「全部」态维持 dayOffsets.last() 约定不动。
                    icon = RouteMapController.numberedNode(context, gid + 1, dayColor(days.indexOf(day)), 100),
                    index = gid,
                )
            }
            // 其他天 = 淡化显示（2026-11-24 #6 终版三件套）：淡化色虚线（HSL 保色相降饱和，
            // DAY_COLORS_MUTED 烘在色值里、alpha 恒 100 不受底图渗色）+ 宽 2.5dp + 稀疏短划
            // 8/14（选中天 5dp/14-10）+ 端点空心化（天色环+天色编号，见 numberedNodeHollow）。
            // 共享边界点（前一天的终点=选中天起点、后一天的起点=选中天终点）由下方
            // dayOffsets 闸门豁免、走选中天分支满色实心，不参与淡化。
            val drawnEndpointIds = mutableSetOf<String>()
            val drawnEndpointPlaceIds = mutableSetOf<String>()
            // 2026-11-24 #6b：选中天占坑预登记——连住多晚酒店产出两枚同坐标合并节点
            // （如全季酒店 11 号 [1,2] 与 18 号 [2,3]），此前 drawnEndpointPlaceIds 只登记
            // 端点分支自己画过的 placeId，属选中天的节点 11 被闸门跳过、placeId 未登记，
            // 同点位的节点 18 空心环就叠画在实心盘上。先把选中天全部 placeId 坑位占住：
            // 已被选中天满色实心标记的点位，其他天端点不再叠画（点位信息由选中天标记承担）。
            selNodes.forEach { node -> drawnEndpointPlaceIds.add(node.placeId) }
            days.filter { it != day }.forEach { otherDay ->
                val otherNodes = nodes.filter { otherDay in it.dayOffsets }
                if (otherNodes.size >= 2) {
                    mapController.showSchematicRoute(
                        schematicCurvePoints(otherNodes.map { LatLng(it.lat, it.lng) }),
                        DAY_COLORS_MUTED[days.indexOf(otherDay).mod(DAY_COLORS_MUTED.size)],
                        100,          // 不用透明度——淡化色已是固定值
                        2.5f,         // 方案 B：比选中天 5dp 明显细
                        listOf(8, 14) // 方案 B：短划长空（选中天 14/10），px 单位
                    )
                }
                listOfNotNull(otherNodes.firstOrNull(), otherNodes.lastOrNull()).forEach { endpoint ->
                    val gid = indexOfId[endpoint.scheduleId] ?: return@forEach
                    if (endpoint.scheduleId in drawnEndpointIds) return@forEach
                    if (day in endpoint.dayOffsets) return@forEach
                    if (endpoint.placeId in drawnEndpointPlaceIds) return@forEach
                    drawnEndpointIds.add(endpoint.scheduleId)
                    drawnEndpointPlaceIds.add(endpoint.placeId)
                    mapController.addNodeMarker(
                        lat = endpoint.lat,
                        lng = endpoint.lng,
                        icon = RouteMapController.numberedNodeHollow(context, gid + 1, nodeColor(endpoint)), // 满色天色环+编号
                        index = gid,
                    )
                }
            }
        }

        // 视野：选天 = 集合包含筛当天点；全部 = 全程（2026-11-22 #7 的 bottomPad 保留，
        // 2026-11-23 #7 起不再叠加导航条高度——画布已避让）
        val fitTargets = if (selDay != null) {
            nodes.filter { selDay in it.dayOffsets }
        } else {
            nodes
        }
        if (fitTargets.isNotEmpty()) {
            val bottomPad = if (selectedDay != null) {
                with(density) { 120.dp.toPx() }.toInt()
            } else {
                120
            }
            mapController.fitTo(fitTargets.map { LatLng(it.lat, it.lng) }, 120, bottomPad)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val mapView = com.visualzyzy.chuxingdazi.ui.map.rememberTencentMapView(context)
        if (mapView == null) {
            Text(text = "地图引擎不可用", modifier = Modifier.align(Alignment.Center))
        } else {
            // 2026-11-18 修复 S26 底图空白（模拟器首测实锤）：腾讯 MapView 代码创建模式下
            // 必须转发 onStart/onResume 才启动渲染管线——此前只在上层 Activity 空闲时挂靠，
            // MapView 收不到 ON_RESUME，底图/标记全不渲染（S03 MapHost 有同款 observer 故正常）。
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            AndroidView(
                // 2026-11-23 #7：画布避让系统状态栏与手势/按键栏（用户规矩：整个应用都
                // 不用这两块区域显示；navigationBarsPadding 自动满足——三键设备让出整个
                // 按键栏，全面屏手势只让出底部小条）
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                factory = {
                // 腾讯版：SDK 缩放控件在 RouteMapController.attach 内关（uiSettings）
                mapController.attach(mapView)
                mapView
            })
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> runCatching { mapView.onStart() }
                        Lifecycle.Event.ON_RESUME -> runCatching { mapView.onResume() }
                        Lifecycle.Event.ON_PAUSE -> runCatching { mapView.onPause() }
                        Lifecycle.Event.ON_STOP -> runCatching { mapView.onStop() }
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

        // 顶部提示条（§6 S26：直线不是实际路线）
        Text(
            text = "连线只表示先后顺序，不是实际路线",
            fontSize = 10.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp)
                .background(Color(0x99000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        // 顶部悬浮卡
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "←", fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trip?.name ?: "…",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = tripDateRangeLine(trip?.startDate, trip?.endDate),
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${nodes.size} 个点位",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 底部面板：天芯片 + 提示
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .padding(14.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                DayChip(label = "全部", color = null, selected = selectedDay == null) { selectedDay = null }
                days.forEachIndexed { idx, day ->
                    DayChip(
                        label = "第 ${day + 1} 天",
                        color = dayColor(idx),
                        selected = selectedDay == day,
                    ) { selectedDay = if (selectedDay == day) null else day }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (unscheduledCount > 0) {
                Text(
                    text = "还有 $unscheduledCount 个点位没排时间 · 点标记可看详情",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "点任一标记可看详情 · 双指缩放",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 空态
        if (nodes.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(text = "这趟还没排点位", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "去时间轴排",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(Primary.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // 节点简卡
        selectedNode?.let { node ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 2026-11-23 #7：避让系统导航条（先让位再留 14dp 边距——与顶部悬浮卡
                    // statusBarsPadding 在前的写法对称，卡片整体不进按键栏区域）
                    .navigationBarsPadding()
                    .padding(14.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = node.name,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "✕",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { selectedNode = null }
                                .padding(6.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // 2026-11-23 #6：显示口径 = 次日早段（合并点 dayOffsets 末位）的天与出发时刻
                    Text(
                        text = "第 ${node.dayOffsets.last() + 1} 天 · " +
                            TripTime.dateText(tripStart.plusDays(node.dayOffsets.last().toLong())) +
                            " ${TripTime.minutesText(node.startMin)} 出发",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 2026-11-23 #6 可选加分项：合并点补一行过夜段（A）起止
                    if (node.overnightStartAbs != null && node.overnightEndAbs != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "过夜 ${absDateTimeText(tripStart, node.overnightStartAbs)} – " +
                                absDateTimeText(tripStart, node.overnightEndAbs),
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayChip(label: String, color: Int?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            color?.let {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(Color(it), CircleShape),
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = label,
                fontSize = 11.5.sp,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun tripDateRangeLine(startDate: String?, endDate: String?): String {
    val s = TripTime.parse(startDate)
    val e = TripTime.parse(endDate)
    return when {
        s != null && e != null -> "${TripTime.dateText(s)} – ${TripTime.dateText(e)}"
        s != null -> "${TripTime.dateText(s)} 起 · 未定回程"
        else -> "未定日期"
    }
}

/** 2026-11-23 #6：其他天的半透明拟合曲线顶点（默认方案 = 中点二次贝塞尔，不刺出不回环）。
 *  首段直线 P0→M0；每个内部节点 Pi 一条二次贝塞尔（起点 M(i-1)、控制点 Pi、终点 Mi），
 *  B(u) = (1-u)²M(i-1) + 2(1-u)u·Pi + u²Mi，每段 u=j/8（j=0…8）采样 9 点；末段直线
 *  M(n-2)→P(n-1)；相邻段共享端点去重。n=2 直接直线（返回原点列）。
 *  曲线严格落在各段「两中点+控制点」凸包内、起终点精确命中（其他天首末端点规则与
 *  合并点衔接才成立）；不精确穿过中间节点（符合「大致体现走势」的定调）。 */
private fun schematicCurvePoints(points: List<LatLng>): List<LatLng> {
    val n = points.size
    if (n < 3) return points
    val mids = Array(n - 1) { i ->
        LatLng(
            (points[i].latitude + points[i + 1].latitude) / 2.0,
            (points[i].longitude + points[i + 1].longitude) / 2.0,
        )
    }
    val out = mutableListOf<LatLng>()
    out.add(points[0])
    out.add(mids[0])
    for (i in 1 until n - 1) {
        val mPrev = mids[i - 1]
        val p = points[i]
        val mNext = mids[i]
        for (j in 1..8) { // j=0 即 M(i-1)，与上一段尾点重合 → 跳过
            val u = j / 8.0
            val v = 1 - u
            out.add(
                LatLng(
                    v * v * mPrev.latitude + 2 * v * u * p.latitude + u * u * mNext.latitude,
                    v * v * mPrev.longitude + 2 * v * u * p.longitude + u * u * mNext.longitude,
                ),
            )
        }
    }
    out.add(points[n - 1])
    return out
}

/** 合并点的过夜段起止 →「M/d HH:mm」（2026-11-23 #6 节点卡过夜行）。 */
private fun absDateTimeText(tripStart: java.time.LocalDate, absMin: Int): String =
    "${TripTime.dateText(tripStart.plusDays((absMin / 1440).toLong()))} ${TripTime.minutesText(absMin % 1440)}"
