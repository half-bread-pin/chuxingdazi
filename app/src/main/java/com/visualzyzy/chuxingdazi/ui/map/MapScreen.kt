package com.visualzyzy.chuxingdazi.ui.map

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import kotlin.math.roundToInt
import kotlin.math.pow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.visualzyzy.chuxingdazi.data.tencent.TencentSdk
import com.visualzyzy.chuxingdazi.data.tencent.MapController
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.ui.filter.FilterPanel
import com.visualzyzy.chuxingdazi.ui.place.PlaceDetailCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 第六轮（2026-11-06）：pointed_information（主界面点击地图查看点位信息）跨页传递的数据。
 * 存在 S03 返回栈条目的 savedStateHandle 上（JSON 字符串），去 S06 添加标记后返回仍可恢复。
 */
data class PointedPending(
    val lat: Double,
    val lng: Double,
    val name: String,
    val semantic: String,
    val address: String,
    val poiName: String,
)

/** 单击地图点的本地态（逆地理异步回填）。 */
private data class TappedPointState(
    val lat: Double,
    val lng: Double,
    val name: String,
    val semantic: String,
    val address: String,
    val poiName: String,
    val loading: Boolean,
)

/**
 * S03 主地图（M3：标记渲染 + 点位详情 + 长按快速入库 + 空态）。
 * 第六轮（2026-11-06）：新增「显示手指点击的点位信息」（pointed_information）——
 * 单击（非拖动）地图 → 标记点.png 临时标记 + 底部信息框（取消 / 为该点位添加标记）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onConfigureAk: () -> Unit,
    onAddPlace: () -> Unit,
    onLongPressPlace: (lat: Double, lng: Double) -> Unit,
    onEditPlace: (placeId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenRoute: () -> Unit,
    onNavigateToPlace: (placeId: String) -> Unit = {},
    // S07「已安排」区块行点击 → 进对应行程时间轴（2026-11-24 实装；带默认值不强迫其他调用点必改）
    onOpenTrip: (tripId: String) -> Unit = {},
    tempView: Triple<Double, Double, String>?,
    addedCoords: DoubleArray,
    pointedPending: PointedPending?,
    onPointedDismiss: () -> Unit = {},
    onPointedAdd: (PointedPending) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: MapViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()

    val places by viewModel.places.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    val ak by Prefs.sdkKey(context).collectAsState(initial = null)
    // 第三十一轮：DataStore 异步读取完成前 ak 首帧为 null——若据此显示「地图不可用」空态，
    // 冷启动会先闪一屏错误页再恢复（用户实测）。等首个真实值落地（akChecked）后才允许空态出现；
    // 未就绪期间地图区静默留白（MapView 同样等 mapAvailable 才创建，几十 ms 级）。
    var akChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Prefs.sdkKey(context).first()
        akChecked = true
    }
    val mapAvailable = !ak.isNullOrBlank() && TencentSdk.isInitialized

    // 第七轮（2026-11-06）：地图就绪 → 触发一轮区域自愈（补齐缺失的 province/city，纯后台）
    LaunchedEffect(mapAvailable) {
        if (mapAvailable) viewModel.kickRegionHeal()
    }

    val controller = remember {
        MapController(context).apply {
            onFirstFix = { lat, lng -> moveTo(lat, lng, zoom = 16f) }
        }
    }

    var selectedPlaceId by remember { mutableStateOf<String?>(null) }
    // 第十六轮（近距离叠点）：散开态——叠点被点后，组内点位按真位置散开 + 底部列表
    var spreadIds by remember { mutableStateOf<List<String>?>(null) }
    var spreadTick by remember { mutableStateOf(0) } // 相机停稳/打开散开时重算屏幕位置
    val selectedPlace = places.firstOrNull { it.id == selectedPlaceId }
    var cardHeightPx by remember { mutableIntStateOf(0) }
    var showEmptyHint by remember { mutableStateOf(false) }

    // P10 城市下拉（2026-11-02）：显示当前地图对应城市（市级），点开省→市两级选择
    // P44（2026-11-03）问题4：初值改为上次记录的城市（DataStore），从设置页返回时不再闪「全国」
    var currentCity by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        Prefs.lastCity(context).first()?.takeIf { it.isNotBlank() }?.let { saved ->
            if (currentCity == null) currentCity = saved
        }
    }
    var showCityPicker by remember { mutableStateOf(false) }
    var cityResolveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // S04 筛选面板（§6 S04）：状态持久化 + 面板高度联动地图 padding
    val filterState by com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.filterState(context)
        .collectAsStateWithLifecycle(initialValue = com.visualzyzy.chuxingdazi.data.prefs.FilterState())
    var showFilter by remember { mutableStateOf(false) }
    var filterPanelHeightPx by remember { mutableIntStateOf(0) }
    var containerHeightPx by remember { mutableIntStateOf(0) }

    // 第十三轮（2026-11-12）：S04「按清单筛选」——开启且选中清单时，加载**所选清单点位
    // id 的并集**（2026-11-24 多选；null = 该维度不参与筛选）；visiblePlaces 的过滤链：
    // 所有点位→分类→地区→清单
    val filterListPlaceIds by androidx.compose.runtime.produceState<Set<String>?>(
        initialValue = null,
        filterState.listFilterEnabled,
        filterState.listIds,
    ) {
        value = if (filterState.listFilterEnabled && filterState.listIds.isNotEmpty()) {
            runCatching { viewModel.placeIdsInLists(filterState.listIds) }.getOrNull() ?: emptySet()
        } else {
            null
        }
    }
    val visiblePlaces = remember(places, filterState, filterListPlaceIds) {
        places.filter { p ->
            p.categoryId !in filterState.hiddenCategoryIds &&
                (!filterState.unvisitedOnly || !p.visited) &&
                filterState.matchesRegion(p.province, p.city) &&
                filterState.matchesList(p.id, filterListPlaceIds)
        }
    }
    // 第七轮（2026-11-06）：S04「按照地区筛选」数据源（省份表 / 省份-城市记录表聚合流）
    val provinceRows by viewModel.provinceCounts.collectAsState()
    val cityRows by viewModel.provinceCityCounts.collectAsState()
    val unknownRegionCount by viewModel.unknownRegionCount.collectAsState()
    // 第十三轮（2026-11-12）：S04「按清单筛选」的清单数据源
    val allLists by viewModel.allLists.collectAsState()
    // P66（2026-11-04）第四轮：筛选图标绿边改为常驻 2dp（与行程图标同宽），不再受任何筛选开关影响
    var showAddChooser by remember { mutableStateOf(false) }

    // 第六轮（2026-11-06）：pointed_information —— 单击地图查看点位信息
    var tapped by remember { mutableStateOf<TappedPointState?>(null) }
    val tapGeocodeEpoch = remember { intArrayOf(0) }
    // pointed_pending 恢复标记：handle 被清空（保存成功）时据此忘记信息
    var restoredFromPending by remember { mutableStateOf(false) }

    // R3（第七轮 2026-11-06）：pointed_information 弹出期间按系统返回 = 取消弹层并去掉临时标记
    //（此前返回键会直接退出应用）
    androidx.activity.compose.BackHandler(enabled = tapped != null) {
        tapped = null
        restoredFromPending = false
        onPointedDismiss()
    }

    // 定位权限：只在用户点「定位」按钮时申请（§7.5：冷启动全程不弹权限框）。
    // P19（2026-11-02）：授权成功后拿不到定位 → 提示（避免「点了没反应」）。
    // 第二十四轮（2026-11-14）重做超时判定：
    // 旧实现 5 秒固定 delay + 靠只发一次的 onFirstFix 取消——二次点击时 onFirstFix 不再触发，
    // 提示必弹（「定位明明在跑却提示失败」）；且能走到这里的前提是定位服务已开（P75 在按钮
    // 点击时拦过 LOCATION_MODE），「请检查 GPS 是否打开」文案永远误导。改为：
    // ① 点按钮时已有定位 → moveToLastFix 立即回当前位置；② 500ms 轮询 lastValidFixAt——
    // 点按钮后又拿到任一有效 fix（SDK 已在跑时每 3 秒一报）即静默取消；③ 8 秒（冷启动/室内
    // 网络定位可超 5 秒）仍无 fix 才提示，文案如实说「定位较慢」。
    var locateTimeoutJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 第二十五轮（2026-11-14）：返回 S03 重建 MapView 时恢复定位蓝点的专用标志——
    // 重启定位后的第一个 fix 不把相机移到当前位置（相机停在快照/用户离开时的视野），
    // 蓝点自行出现即可；用户主动点定位按钮的首次 fix 仍走 moveTo（P19 既有语义）。
    var suppressFirstFixMove by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            viewModel.markLocateStarted()
            controller.startLocate()
            controller.moveToLastFix() // 已有定位（二次点击）→ 立即回位，不等新 fix
            suppressFirstFixMove = false
            locateTimeoutJob?.cancel()
            locateTimeoutJob = scope.launch {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                while (true) {
                    delay(500)
                    if (controller.lastValidFixAt >= startedAt) return@launch
                    if (android.os.SystemClock.elapsedRealtime() - startedAt >= 8000L) break
                }
                Toast.makeText(context, "定位较慢：室内或信号弱时可能需要更久，地图上出现蓝色圆点即定位成功", Toast.LENGTH_LONG).show()
            }
        } else {
            val activity = context as? android.app.Activity
            // 刚在授权弹窗里拒绝且不再询问时 rationale=false → 永久拒绝，引导去系统设置
            val permanentlyDenied = activity != null &&
                !activity.shouldShowRequestPermissionRationale(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                )
            if (permanentlyDenied) {
                Toast.makeText(context, "定位权限已被拒绝，可到系统设置 → 应用权限中开启", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "未授予定位权限，地图仍可浏览与标点", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 第二十二轮（2026-11-14，决策 #91，用户定调）：筛选/行程/设置悬浮按钮暗色模式**不再换深色底**，
    // 暗色环境渲染与明亮模式完全一致（白卡片 + 灰图标）。第十六轮的 #1E232B 深色底实测太丑
    // （灰图标淹在深色卡里），按用户要求废除；白卡片压暗色底图属可接受外观。
    val mapControlBg = Color.White

    // 回调接线（SideEffect 保证拿到最新组合态）
    SideEffect {
        controller.onStackTap = { ids ->
            // 第十六轮：叠点 → 散开（真位置小圆点已由 controller.setSpreadGroup 渲染）
            spreadIds = ids
            spreadTick++
            controller.setSpreadGroup(ids)
        }
        controller.onPlaceMarkerClick = { placeId ->
            selectedPlaceId = placeId
            // 第六轮：点开已有标记详情 → 关闭 pointed_information（两者互斥，均占底部卡片）
            tapped = null
            restoredFromPending = false
            onPointedDismiss()
        }
        controller.onMapClick = { lat, lng, poiName ->
            // 第十六轮：散开态下点地图空白 = 收起散开（不弹 pointed_information）
            if (spreadIds != null) {
                spreadIds = null
                controller.setSpreadGroup(null)
            } else if (!showFilter) {
            // 第六轮（2026-11-06）：单击地图 → pointed_information（临时标记 + 信息框）
            // R7（第七轮 2026-11-06）：S04 筛选面板弹出期间单击地图 = 不做任何事
            //（不收面板、不弹信息框——面板占下半屏，上半屏地图点按应无副作用）
                selectedPlaceId = null
                tapGeocodeEpoch[0]++
                val myEpoch = tapGeocodeEpoch[0]
                tapped = TappedPointState(
                    lat = lat,
                    lng = lng,
                    name = poiName.orEmpty(),
                    semantic = "",
                    address = "",
                    poiName = poiName.orEmpty(),
                    loading = true,
                )
                controller.reverseGeocode(lat, lng) { info ->
                    if (tapGeocodeEpoch[0] != myEpoch) return@reverseGeocode
                    val cur = tapped
                    if (cur != null && cur.lat == lat && cur.lng == lng) {
                        tapped = cur.copy(
                            name = info?.nameCandidate.orEmpty().ifBlank { poiName.orEmpty() },
                            semantic = info?.semantic.orEmpty(),
                            address = info?.address.orEmpty(),
                            poiName = poiName.orEmpty(),
                            loading = false,
                        )
                    }
                }
            }
        }
        controller.onMapLongClick = { lat, lng ->
            onLongPressPlace(lat, lng) // → S06 地图选点（§6 S03：长按 500ms 进 S06 带入坐标）
        }
        controller.onFirstFix = { lat, lng ->
            if (suppressFirstFixMove) {
                // 返回 S03 的定位恢复：蓝点自己回来，相机停在快照不动
                suppressFirstFixMove = false
                locateTimeoutJob?.cancel()
            } else {
                controller.moveTo(lat, lng, zoom = 16f)
                locateTimeoutJob?.cancel()
            }
        }
        controller.onCityKnown = { city ->
            // GPS 城市仅作初值（地图逆地理才是「当前地图对应城市」的本源，P11）
            if (currentCity == null) {
                currentCity = city
                scope.launch { Prefs.saveLastCity(context, city) }
            }
        }
        controller.onCameraIdle = { lat, lng, zoom ->
            viewModel.saveCamera(lat, lng, zoom) // 持久化相机位置（S06/下次启动恢复）
            if (spreadIds != null) spreadTick++ // 散开态下相机移动 → 圆周图标跟着真位置走
            // P10/P11：相机静止 1.2s 后逆地理解析当前城市（防抖 + 城市不变不重复请求）
            if (zoom >= 7f) {
                cityResolveJob?.cancel()
                cityResolveJob = scope.launch {
                    delay(1200)
                    controller.reverseGeocodeCity(lat, lng) { city ->
                        if (city != null && city != currentCity) {
                            currentCity = city
                            scope.launch { Prefs.saveLastCity(context, city) }
                        }
                    }
                }
            }
        }
    }

    // 新建 MapView 后恢复上次相机位置（§6 S03：中心为上次退出时的位置）——
    // 恢复逻辑接线在下方布局分支的 MapHost(onMapCreated=...)。
    // P16.1/P16.2 修复（2026-11-02）：从 S05/S06 返回时 MapView 是**新建**的（离开组合即 onDestroy），
    // 必须在每次 MapView 创建时都恢复快照——此前 cameraRestored 挂在存活的 ViewModel 上只恢复一次，
    // 返回后新 MapView 停在 SDK 默认中心（天安门）。

    // 数据变化 → 重建标记（含筛选：样式/大小/标签/聚合/分类显隐/只看未到访）
    LaunchedEffect(mapAvailable, visiblePlaces, categories, selectedPlaceId, filterState) {
        if (mapAvailable) {
            controller.renderPlaces(visiblePlaces, categoriesById, selectedPlaceId, filterState)
        }
    }

    // 面板/详情互斥时的地图 padding 联动
    LaunchedEffect(showFilter, filterPanelHeightPx, selectedPlaceId, cardHeightPx) {
        when {
            selectedPlaceId != null && cardHeightPx > 0 -> controller.setViewPadding(cardHeightPx)
            showFilter && filterPanelHeightPx > 0 -> controller.setViewPadding(filterPanelHeightPx)
            else -> controller.setViewPadding(0)
        }
    }

    // 选中点位 → 移到卡片上方可视区中央并放大一级（§6 S07）
    LaunchedEffect(selectedPlaceId, cardHeightPx) {
        val place = selectedPlace ?: return@LaunchedEffect
        if (cardHeightPx > 0) {
            controller.focusPlace(place.lat, place.lng, cardHeightPx)
        }
    }

    // P66（2026-11-04）第四轮：空态提示卡不再"进主界面就弹"——
    // 只在「点位库为空 + 筛选面板把显示标记开关从关拨到开」时弹出（FilterPanel onShowMarkersBlocked 回调），5 秒后自动淡出

    // S05「在地图上查看」：临时标记（不入库）+ 相机移动。
    // key 带 mapAvailable：返回 S03 时 ak flow 先发 null 再发真值，只有 mapAvailable 变 true 才能画
    LaunchedEffect(tempView, mapAvailable) {
        tempView?.let { (lat, lng, name) ->
            if (mapAvailable) controller.showTempMarker(lat, lng, name)
        }
    }

    // 第六轮（2026-11-06）：pointed_information 状态恢复 / 忘记
    // - 从 S06 取消返回：savedStateHandle 里的 pointed_pending 仍在 → 恢复信息框与临时标记
    // - 从 S06 保存返回：onSaved 已把 pointed_pending 清空 → 忘记框内信息
    LaunchedEffect(pointedPending) {
        if (pointedPending != null) {
            restoredFromPending = true
            tapped = TappedPointState(
                lat = pointedPending.lat,
                lng = pointedPending.lng,
                name = pointedPending.name,
                semantic = pointedPending.semantic,
                address = pointedPending.address,
                poiName = pointedPending.poiName,
                loading = false,
            )
        } else if (restoredFromPending) {
            tapped = null
            restoredFromPending = false
        }
    }

    // pointed_information 临时标记（标记点.png）：地图就绪才画；信息被忘记 → 移除
    LaunchedEffect(tapped, mapAvailable) {
        if (tapped == null) {
            controller.clearTappedMarker()
        } else if (mapAvailable) {
            controller.showTappedMarker(tapped!!.lat, tapped!!.lng)
        }
    }

    // 第六轮：打开筛选面板 → 面板会盖住底部信息框，视为放弃当前点击点位（忘记信息）
    LaunchedEffect(showFilter) {
        if (showFilter && tapped != null) {
            tapped = null
            restoredFromPending = false
            onPointedDismiss()
        }
    }

    // S05 批量添加完成 → 缩放到包含全部新点位（§7.2 fitBounds）
    // P67（2026-11-04）第四轮：单点（S05b 详情页添加）直接定位置 17 级——
    // 退化 bounds 交给 newLatLngBounds 会得到不可用视野
    LaunchedEffect(addedCoords, mapAvailable) {
        if (addedCoords.isNotEmpty() && mapAvailable) {
            if (addedCoords.size == 2) {
                controller.moveToImmediate(addedCoords[0], addedCoords[1], 17f)
            } else {
                val list = buildList {
                    for (i in addedCoords.indices step 2) {
                        add(LatLng(addedCoords[i], addedCoords[i + 1]))
                    }
                }
                controller.fitBounds(list, 48)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .onGloballyPositioned { containerHeightPx = it.size.height },
    ) {
        if (akChecked && !mapAvailable) {
            // 无 Key（且 DataStore 已读完，避免冷启动闪错误页）：地图区显示不可用占位（§6 S02 边界）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "地图不可用", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "还没有配置地图密钥（Key）\n配置后即可查看底图、搜索地点",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                        .clickable(onClick = onConfigureAk)
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "点此配置 Key",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else {
            MapHost(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                onMapCreated = {
                    // P16（2026-11-02）：每次 MapView 新建都恢复相机快照（返回 S03 时不再跳回天安门）
                    // P67（2026-11-04）第四轮：S05b 详情页「添加标记」回传 added_coords 时跳过恢复，
                    // 让位给 fitBounds 聚焦新点位（否则恢复快照会把刚算好的视野顶掉）
                    // 第十五轮：tempView（S05/S26「在地图上查看」回传）同理跳过——快照恢复与
                    // showTempMarker 的 17f 定位竞态，快照后到会把相机顶回原处、临时标记落在屏外
                    // 第二十五轮（2026-11-14）：恢复定位蓝点——MapView 每次重建（离开组合即销毁），
                    // detach() 里 stopLocate 已停掉定位 client，返回主界面后蓝点无人喂数据也不更新。
                    // 本会话点过定位（viewModel.locateEverStarted，VM 绑定 S03 backStackEntry 存活）
                    // 且权限仍在 → 自动重启定位；首个 fix 只恢复蓝点、不动相机（suppressFirstFixMove，
                    // 相机停在快照）。冷启动从未点过定位 → 不启动（§7.5：权限只在用户点按钮时申请）。
                    scope.launch {
                        if (addedCoords.isEmpty() && tempView == null) {
                            Prefs.cameraSnapshot(context)?.let { snap ->
                                controller.moveToImmediate(snap.lat, snap.lng, snap.zoom)
                            }
                        }
                        if (viewModel.locateEverStarted &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.ACCESS_FINE_LOCATION,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            suppressFirstFixMove = true
                            controller.startLocate()
                        }
                    }
                },
            )

            // 顶部：城市下拉（P10）+ 搜索栏 + 路线按钮 + 临时 Key 入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // P10（2026-11-02）：城市选择下拉，显示当前地图对应城市（市级，不带「市」字）
                // P48（2026-11-03）问题8：加与行程按钮同宽同色的 2dp 绿边
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .height(48.dp)
                        .border(2.dp, PrimaryColor, RoundedCornerShape(15.dp))
                        .clickable { showCityPicker = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = "选择城市",
                            tint = PrimaryColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = currentCity?.removeSuffix("市")?.ifBlank { "全国" } ?: "全国",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF14171C),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // P46（2026-11-03）问题6：设置按钮移到左下角后，搜索栏自动拉长（weight(1f) 占满剩余宽度）
                // P48（2026-11-03）问题8：同样加 2dp 绿边
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .border(2.dp, PrimaryColor, RoundedCornerShape(15.dp))
                        .clickable(onClick = onOpenSearch),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = TextSecondaryIcon,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "搜索地点、商户",
                            fontSize = 14.sp,
                            color = TextSecondaryIcon,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(PrimaryColor, RoundedCornerShape(15.dp))
                        .clickable(onClick = onOpenRoute),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = Icons.Filled.Route, contentDescription = "路线", tint = Color.White)
                }
            }

            // 空态提示卡（P66：仅「无点位+筛选面板拨显示标记开」时弹出，5 秒自动淡出）
            if (showEmptyHint) {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    shadowElevation = 4.dp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                ) {
                    Text(
                        text = "还没有添加任何标记，点击主界面右下角绿色＋添加第一个地点",
                        fontSize = 12.5.sp,
                        color = Color(0xFF3D4451),
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // 左侧悬浮按钮组：筛选 + 行程（P15，2026-11-02：上移到筛选图标原来的区域，
            // 即搜索栏下方左侧；行程图标加与筛选同宽的常驻绿边）
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 14.dp, top = 76.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // P66（2026-11-04）第四轮问题1：绿边改常驻 2dp（与行程图标同宽同色），
                // 不再随「只看未到访」等筛选开关变粗变细
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(mapControlBg, RoundedCornerShape(14.dp))
                        .border(
                            width = 2.dp,
                            color = PrimaryColor,
                            shape = RoundedCornerShape(14.dp),
                        )
                        // 第十六轮（互斥修复）：打开筛选时把已开的点位详情卡一并收起——
                        // 注释写互斥但此前只关临时点击卡，S07 详情卡会跟筛选面板抢底部
                        .clickable {
                            showFilter = true
                            selectedPlaceId = null
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "筛选",
                        tint = TextSecondaryIcon,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                // 行程入口（M8：S23 行程列表；P15：2dp 绿边与筛选图标的选中边同宽）
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(mapControlBg, RoundedCornerShape(14.dp))
                        .border(
                            width = 2.dp,
                            color = PrimaryColor,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable(onClick = onOpenTrips),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Event,
                        contentDescription = "行程",
                        tint = TextSecondaryIcon,
                    )
                }
            }

            // P46（2026-11-03）问题6：设置按钮从顶部行移到左下角（地图比例尺稍上方），
            // 顶部搜索栏因此可以拉长；P48 问题8：加与行程按钮同宽同色的 2dp 绿边
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 64.dp)
                    .size(44.dp)
                    .background(mapControlBg, RoundedCornerShape(14.dp))
                    .border(2.dp, PrimaryColor, RoundedCornerShape(14.dp))
                    .clickable(onClick = onConfigureAk),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = TextSecondaryIcon,
                )
            }

            // 右下工具栈：＋（主色）+ 定位 + 放大 + 缩小（§6 S03 层级 4）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PrimaryColor,
                    shadowElevation = 2.dp,
                    modifier = Modifier.semantics { contentDescription = "添加点位" },
                ) {
                    Box(
                        // P66（2026-11-04）第四轮：点 ＋ 不再直接进 S06——先弹底部选择面板
                        // （通过搜索添加 / 从地图上选择点位 / 取消）
                        modifier = Modifier
                            .size(44.dp)
                            .clickable { showAddChooser = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "＋",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                MapToolButton(onClick = {
                    // P75（2026-11-05）：系统定位服务（GPS）关闭时点定位 → 立即提醒，文案与
                    // 设置页「地图与定位」分组的状态行一致（"定位服务已关闭"）。
                    // P82（2026-11-05 复测 Redmi）：isProviderEnabled 在 LineageOS/部分 ROM 上
                    // location_mode=0 时 NETWORK_PROVIDER 仍返回 true，绕过即时提醒走了 5 秒超时
                    // 兜底——改读 Settings.Secure.LOCATION_MODE（跨 ROM 一致，语义即「定位服务开关」）。
                    val locationMode = try {
                        android.provider.Settings.Secure.getInt(
                            context.contentResolver,
                            android.provider.Settings.Secure.LOCATION_MODE,
                        )
                    } catch (e: Exception) {
                        android.provider.Settings.Secure.LOCATION_MODE_OFF
                    }
                    val serviceOn = locationMode != android.provider.Settings.Secure.LOCATION_MODE_OFF
                    if (!serviceOn) {
                        Toast.makeText(context, "定位服务已关闭", Toast.LENGTH_LONG).show()
                        return@MapToolButton
                    }
                    permissionLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }) {
                    Icon(Icons.Filled.LocationOn, contentDescription = "定位", tint = PrimaryColor)
                }
                Spacer(modifier = Modifier.height(10.dp))
                MapToolButton(onClick = controller::zoomIn) {
                    Text(text = "＋", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF14171C))
                }
                Spacer(modifier = Modifier.height(1.dp))
                Surface(color = Color(0xFFEFF2F6)) {
                    Box(modifier = Modifier.width(26.dp).height(1.dp))
                }
                MapToolButton(onClick = controller::zoomOut) {
                    Text(text = "－", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF14171C))
                }
            }

            // 第六轮（2026-11-06）：pointed_information —— 单击地图弹出的点位信息框
            // （高度参考 444.jpg 绿框：名称/地址/坐标 + 取消/为该点位添加标记 两枚并列按钮）
            // R1（第七轮 2026-11-06）：暗色模式 = 黑底 + 名称/地址/坐标白字；删掉「信息」标题行
            tapped?.let { t ->
                val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val cardBg = if (darkTheme) Color.Black else Color.White
                val titleColor = if (darkTheme) Color.White else Color(0xFF14171C)
                val bodyColor = if (darkTheme) Color.White else Color(0xFF5B6472)
                val chipBg = if (darkTheme) Color(0xFF23272E) else Color(0xFFEFF2F6)
                val chipText = if (darkTheme) Color(0xFFA6AFBC) else Color(0xFF5B6472)
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        color = cardBg,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            if (t.loading && t.name.isBlank()) {
                                // 逆地理编码中的骨架屏
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f)
                                        .height(18.dp)
                                        .background(chipBg, RoundedCornerShape(6.dp)),
                                )
                            } else {
                                Text(
                                    text = t.name.ifBlank { "%.6f, %.6f".format(t.lat, t.lng) },
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = titleColor,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val tagLine = t.semantic.ifBlank { t.address }
                            if (tagLine.isNotBlank()) {
                                Text(
                                    text = tagLine,
                                    fontSize = 11.5.sp,
                                    color = PrimaryColor,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            if (t.address.isNotBlank() && t.address != tagLine) {
                                Text(
                                    text = t.address,
                                    fontSize = 11.5.sp,
                                    color = bodyColor,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    lineHeight = 16.sp,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            // 第十六轮：信息卡不再展示 BD09 坐标行（复制到别的地图会偏）
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = chipBg,
                                    modifier = Modifier
                                        .width(92.dp)
                                        .height(46.dp)
                                        .clickable {
                                            // 取消：隐藏信息框并忘记框内信息
                                            tapped = null
                                            restoredFromPending = false
                                            onPointedDismiss()
                                        },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "取消",
                                            fontSize = 15.sp,
                                            color = chipText,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = PrimaryColor,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .clickable(enabled = !t.loading) {
                                            // 为该点位添加标记：隐藏信息框但记住信息 → S06
                                            onPointedAdd(
                                                PointedPending(
                                                    lat = t.lat,
                                                    lng = t.lng,
                                                    name = t.name,
                                                    semantic = t.semantic,
                                                    address = t.address,
                                                    poiName = t.poiName,
                                                ),
                                            )
                                        },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "为该点位添加标记",
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.navigationBarsPadding())
                        }
                    }
                }
            }

            // S07 点位详情卡（与筛选面板互斥）
            selectedPlace?.let { place ->
                val listsState = remember(place.id) { mutableStateOf<List<ListEntity>>(emptyList()) }
                LaunchedEffect(place.id) {
                    listsState.value = viewModel.listsOfPlace(place.id)
                }
                // S07「已安排」区块数据（2026-11-24 实装）：该点位在各行程里的时段（未出行过滤在卡片内做）
                val schedulesState = remember(place.id) {
                    mutableStateOf<List<com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithTripRow>>(emptyList())
                }
                LaunchedEffect(place.id) {
                    schedulesState.value = viewModel.schedulesForPlace(place.id)
                }
                // 第十六轮：散开组内的详情卡带「1/2」左右切换（不用关卡片再点另一个几乎重合的图标）
                val groupPos = spreadIds?.indexOf(place.id) ?: -1
                val groupSize = spreadIds?.size ?: 0
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    PlaceDetailCard(
                        place = place,
                        category = categoriesById[place.categoryId],
                        lists = listsState.value,
                        impactProvider = { placeId -> viewModel.impactOfDelete(placeId) },
                        schedules = schedulesState.value,
                        onOpenTrip = onOpenTrip,
                        cardHeightPx = { cardHeightPx = it },
                        onDismiss = {
                            selectedPlaceId = null
                            controller.clearFocusPadding()
                        },
                        onNavigateTo = { onNavigateToPlace(place.id) },
                        onToggleVisited = { viewModel.toggleVisited(place) },
                        onDelete = {
                            viewModel.deletePlace(place.id)
                            selectedPlaceId = null
                            controller.clearFocusPadding()
                        },
                        onEdit = { onEditPlace(place.id) },
                        groupIndex = groupPos,
                        groupCount = groupSize,
                        onGroupMove = { delta ->
                            val ids = spreadIds ?: return@PlaceDetailCard
                            if (ids.isEmpty()) return@PlaceDetailCard
                            val cur = ids.indexOf(place.id)
                            if (cur < 0) return@PlaceDetailCard
                            val next = (cur + delta).mod(ids.size)
                            selectedPlaceId = ids[next]
                        },
                    )
                }
            }

            // ---- 第十六轮：叠点散开层 ----
            // 控制器已把组内点位按真位置画成小圆点；这里画细线 + 圆周散开图标（2–4 个）+ 底部列表。
            // 点散开图标/列表行 = 打开该点位的 S07；点地图空白/返回 = 收起散开。
            if (spreadIds != null && mapAvailable) {
                val idsNow = spreadIds.orEmpty()
                val members = idsNow.mapNotNull { id -> visiblePlaces.firstOrNull { it.id == id } }
                val positions = remember(idsNow, spreadTick) { controller.screenPositionsOf(idsNow) }
                androidx.activity.compose.BackHandler(enabled = true) {
                    spreadIds = null
                    controller.setSpreadGroup(null)
                }
                if (positions.isNotEmpty()) {
                    val cx = positions.map { it.second }.average().toFloat()
                    val cy = positions.map { it.third }.average().toFloat()
                    // 细线连回真位置小圆点 + 中心小圆点
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        positions.forEach { (_, x, y) ->
                            drawLine(
                                color = Color(0x663D4451),
                                start = Offset(cx, cy),
                                end = Offset(x.toFloat(), y.toFloat()),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                        drawCircle(color = Primary, radius = 4.dp.toPx(), center = Offset(cx, cy))
                        drawCircle(
                            color = Color.White,
                            radius = 1.6.dp.toPx(),
                            center = Offset(cx, cy),
                        )
                    }
                    // 圆周散开图标：只有 2–4 个成员才绕圈排（5+ 只用底部列表）
                    if (members.size in 2..4) {
                        val radiusPx = with(LocalDensity.current) { 60.dp.toPx() }
                        positions.forEachIndexed { i, (id, x, y) ->
                            val angle = Math.toRadians((-90.0 + i * 360.0 / positions.size))
                            val ix = cx + (kotlin.math.cos(angle) * radiusPx).toFloat()
                            val iy = cy + (kotlin.math.sin(angle) * radiusPx).toFloat()
                            val place = members.firstOrNull { it.id == id }
                            val cat = categoriesById[place?.categoryId]
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .offset { androidx.compose.ui.unit.IntOffset(ix.roundToInt() - 22, iy.roundToInt() - 22) }
                                    .width(44.dp)
                                    .clickable { selectedPlaceId = id },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(
                                            com.visualzyzy.chuxingdazi.ui.common.parseCategoryColorSafe(cat?.color),
                                            RoundedCornerShape(9.dp),
                                        )
                                        .border(1.5.dp, Color.White, RoundedCornerShape(9.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    com.visualzyzy.chuxingdazi.ui.common.CategoryIconTinted(
                                        iconKey = place?.iconKey ?: cat?.iconKey ?: "other_point",
                                        size = 16.dp,
                                        tint = Color.White,
                                    )
                                }
                                Text(
                                    text = place?.name.orEmpty(),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    maxLines = 2,
                                    color = Color(0xFF3D4451),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier
                                        .background(Color(0xB3FFFFFF), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 2.dp),
                                )
                            }
                        }
                    }
                    // 底部列表：名称、分类、与中心相距多少米
                    val centroidLat = members.map { it.lat }.average()
                    val centroidLng = members.map { it.lng }.average()
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "重叠的 ${members.size} 个点位",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "收起 ✕",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        spreadIds = null
                                        controller.setSpreadGroup(null)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        members.forEach { place ->
                            val dist = distanceMeters(
                                centroidLat,
                                centroidLng,
                                place.lat,
                                place.lng,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPlaceId = place.id }
                                    .padding(vertical = 7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .background(
                                            com.visualzyzy.chuxingdazi.ui.common.parseCategoryColorSafe(categoriesById[place.categoryId]?.color),
                                            RoundedCornerShape(8.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    com.visualzyzy.chuxingdazi.ui.common.CategoryIconTinted(
                                        iconKey = place.iconKey
                                            ?: categoriesById[place.categoryId]?.iconKey
                                            ?: "other_point",
                                        size = 14.dp,
                                        tint = Color.White,
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = place.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${categoriesById[place.categoryId]?.name ?: "其他"} · 相距 ${dist.roundToInt()} 米",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // S04 筛选面板（§6 S04）
            if (showFilter && mapAvailable) {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    FilterPanel(
                    filter = filterState,
                    containerHeightPx = containerHeightPx,
                    visiblePlaces = visiblePlaces,
                    categoryInfo = categoriesById,
                    placesEmpty = places.isEmpty(),
                    provinceRows = provinceRows,
                    cityRows = cityRows,
                    unknownRegionCount = unknownRegionCount,
                    panelHeightPx = { filterPanelHeightPx = it },
                    onClose = { showFilter = false },
                    onPlaceClick = { place ->
                        showFilter = false
                        selectedPlaceId = place.id
                    },
                    // P66（2026-11-04）第四轮：无点位时拨「显示标记」开 → 面板拦截并回调这里弹空态提示卡
                    onShowMarkersBlocked = {
                        showEmptyHint = true
                        scope.launch {
                            delay(5000)
                            showEmptyHint = false
                        }
                    },
                    onShowMarkersChange = { value ->
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.saveShowMarkers(context, value) }
                    },
                    onStyleChange = { style ->
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.saveStyle(context, style) }
                    },
                    onShowLabelsChange = { value ->
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.saveShowLabels(context, value) }
                    },
                    onClusterModeChange = { mode ->
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.saveClusterMode(context, mode) }
                    },
                    onUnvisitedOnlyChange = { value ->
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.saveUnvisitedOnly(context, value) }
                    },
                    onMarkerSizeChange = { percent ->
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.saveMarkerSize(context, percent) }
                    },
                    onToggleCategoryHidden = { categoryId ->
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.toggleHiddenCategory(context, categoryId) }
                    },
                    onClearHidden = {
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.clearHiddenCategories(context) }
                    },
                    onHideAll = { ids ->
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.hideAllCategories(context, ids) }
                    },
                    onRegionChange = { province, city ->
                        scope.launch { com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.saveRegion(context, province, city) }
                    },
                    lists = allLists,
                    onListFilterChange = { enabled, listIds ->
                        scope.launch {
                            com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.saveListFilter(context, enabled, listIds)
                        }
                    },
                )
                }
            }

            // P10 城市选择框（省 → 市两级；2026-11-17 腾讯版数据源 = DistrictRepository）
            if (showCityPicker) {
                CityPickerDialog(
                    currentCity = currentCity,
                    repository = viewModel.districtRepository,
                    onSelect = { _, city ->
                        showCityPicker = false
                        currentCity = city
                        scope.launch { Prefs.saveLastCity(context, city) }
                        controller.geocodeCity(city) { pt ->
                            if (pt != null) {
                                controller.moveTo(pt.latitude, pt.longitude, zoom = 11f)
                            } else {
                                Toast.makeText(context, "没有找到「$city」的位置", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDismiss = { showCityPicker = false },
                )
            }

            // P66（2026-11-04）第四轮：手动选点入口选择面板（从上往下：通过搜索添加 / 从地图上选择点位 / 取消）
            // R4（第七轮 2026-11-06）：删除滑动/下滑收起——改为 scrim + 底部面板，
            // 只有「取消」、点空白处或系统返回键收起（面板不可拖拽）
            if (showAddChooser) {
                androidx.activity.compose.BackHandler(enabled = true) { showAddChooser = false }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x52000000))
                        .clickable(onClick = { showAddChooser = false }),
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // 消费面板内空白处的点击（无涟漪），防止透传到 scrim 误收起
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) {},
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .size(width = 38.dp, height = 4.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                                    .align(Alignment.CenterHorizontally),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            AddChooserRow(
                                icon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                                text = "通过搜索添加",
                            ) {
                                showAddChooser = false
                                onOpenSearch()
                            }
                            AddChooserRow(
                                icon = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                                text = "从地图上选择点位",
                            ) {
                                showAddChooser = false
                                onAddPlace()
                            }
                            AddChooserRow(
                                icon = { Icon(Icons.Filled.Close, contentDescription = null, tint = TextSecondaryIcon, modifier = Modifier.size(22.dp)) },
                                text = "取消",
                            ) {
                                showAddChooser = false
                            }
                            Spacer(modifier = Modifier.navigationBarsPadding())
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddChooserRow(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
    ) {
        icon()
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MapToolButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private val PrimaryColor = com.visualzyzy.chuxingdazi.ui.theme.Primary
private val TextSecondaryIcon = com.visualzyzy.chuxingdazi.ui.theme.TextSecondary

/** 球面距离（米）——散开层底部列表「相距 N 米」用。 */
private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val h = kotlin.math.sin(dLat / 2).pow(2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLng / 2).pow(2)
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
}
