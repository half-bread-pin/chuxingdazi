package com.visualzyzy.chuxingdazi.ui.pick

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.data.tencent.TencentSdk
import com.visualzyzy.chuxingdazi.data.tencent.MapController
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec
import com.visualzyzy.chuxingdazi.data.model.FieldValuesCodec
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.ui.common.CategorySelectorRow
import com.visualzyzy.chuxingdazi.ui.common.IconPickerDialog
import com.visualzyzy.chuxingdazi.ui.common.NewCategoryDialog
import com.visualzyzy.chuxingdazi.ui.common.PlaceIconRow
import com.visualzyzy.chuxingdazi.ui.map.MapHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * S06 地图选点添加（§6 S06）。
 * - 准星 = 高对比十字（青蓝），位于「剩余地图区域」正中（随底部卡片高度自适应），
 *   并通过 setViewPadding 让地图逻辑中心与准星重合（坐标语义一致）。
 * - 打开时恢复上次相机位置（S03 持久化），不再跳回默认中心（用户反馈 2026-10-31）。
 * - 选中分类后卡片自动延伸出该分类模板的字段编辑器 + 备注。
 * - 分类行末「＋新建分类」：对话框内创建（不离开本屏，已填名称/坐标天然保留），创建后自动选中。
 * - editPlaceId 非空 = S08「调整」模式（紧凑卡片，只更新坐标/地址）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PickPlaceScreen(
    editPlaceId: String?,
    initialLat: Double?,
    initialLng: Double?,
    // 第十一轮（2026-11-11）D1：pointed_information「为该点位添加标记」进入时 keepZoom=true——
    // 相机对位沿用主界面当时保存的缩放级别（取消进 S06 的缩放变化）；长按/调整等入口仍 17f。
    keepZoom: Boolean = false,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onOpenEditor: (placeId: String) -> Unit,
    onManageCategories: () -> Unit = {},
    // 2026-11-19 新建分类「新建颜色」：S18 草稿调色回传（savedStateHandle 链，同 S09 口径）
    draftColorFlow: kotlinx.coroutines.flow.StateFlow<String>? = null,
    onConsumeDraftColor: () -> Unit = {},
    onPickDraftColor: (initialHex: String) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: PickPlaceViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val categories by viewModel.categories.collectAsState()
    // 第六轮（2026-11-06）：S06 地图也显示已有点位标记（按 S04 筛选面板的设置）
    val places by viewModel.places.collectAsState()
    val filterState by com.visualzyzy.chuxingdazi.data.prefs.FilterPrefs.filterState(context)
        .collectAsState(initial = com.visualzyzy.chuxingdazi.data.prefs.FilterState())
    val ak by Prefs.sdkKey(context).collectAsState(initial = null)
    val mapAvailable = !ak.isNullOrBlank() && TencentSdk.isInitialized
    val categoriesById = remember(categories) { categories.associateBy { it.id } }
    // 第十三轮（2026-11-12）：S04「按清单筛选」——开启且选中清单时加载所选清单点位 id 并集（2026-11-24 多选）
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

    val controller = remember { MapController(context) }
    // 第十六轮：一动地图就收起选点提示（避免压住 POI 名）
    var centerLat by remember { mutableStateOf(initialLat) }
    var centerLng by remember { mutableStateOf(initialLng) }
    var geoInfo by remember { mutableStateOf<MapController.ReverseGeocodeInfo?>(null) }
    var geocoding by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf("other") }
    var noteInput by remember { mutableStateOf("") }
    val fieldInputs = remember { mutableStateMapOf<String, String>() }
    // 点位自定义字段 schema（字段模板升级 §3.2）：新建态存表单状态，随保存同事务落库；
    // rememberSaveable（JSON 串）——新建分类/调色跨页往返时不丢已加字段
    var customSchemaJson by rememberSaveable { mutableStateOf("[]") }
    var adjustPlace by remember { mutableStateOf<PlaceEntity?>(null) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var cardHeightPx by remember { mutableIntStateOf(0) }
    // rememberSaveable：新建分类对话框内去 S18 调色是跨页往返（本屏组合销毁重建），
    // 打开态跨往返恢复（2026-11-19 新建颜色链）
    var showNewCategoryDialog by rememberSaveable { mutableStateOf(false) }
    // S18 草稿调色回传 → 注入 NewCategoryDialog（新建颜色的色值）
    var pendingCustomColor by remember { mutableStateOf<String?>(null) }
    val draftColor = draftColorFlow?.collectAsStateWithLifecycle(initialValue = "")?.value
    androidx.compose.runtime.LaunchedEffect(draftColor) {
        if (!draftColor.isNullOrBlank()) {
            pendingCustomColor = draftColor
            onConsumeDraftColor()
        }
    }
    // 第十一轮（2026-11-11）：点位自定义图标（null = 跟随分类；只在添加时设置，S29 也可改）
    var customIconKey by remember { mutableStateOf<String?>(null) }
    var showIconPicker by remember { mutableStateOf(false) }
    // 第十六轮：选点提示 4 秒/首次手势后自动收起
    var showPickHint by remember { mutableStateOf(true) }
    // 切换分类 → 图标重置为跟随新分类（图标语义跟分类走）
    LaunchedEffect(selectedCategoryId) { customIconKey = null }

    // P18（2026-11-02）：卡片拖拽高度。null = 跟随内容自适应；非 null = 用户拖动后的固定高度。
    var cardOverrideHeightPx by remember { mutableStateOf<Int?>(null) }
    var dragStartCardHeightPx by remember { mutableIntStateOf(0) }
    // P18：拖卡片时准星冻结在当前位置（选点不漂移），下次地图手势恢复「剩余区域居中」
    var crosshairAnchorY by remember { mutableStateOf<Int?>(null) }
    // 分类条滚动（P12：新建分类后滚到末尾并自动选中）
    val categoryScrollState = rememberScrollState()
    var pendingScrollToEnd by remember { mutableStateOf(false) }
    LaunchedEffect(categories.size, pendingScrollToEnd) {
        if (pendingScrollToEnd && categories.isNotEmpty()) {
            categoryScrollState.animateScrollTo(categoryScrollState.maxValue)
            pendingScrollToEnd = false
        }
    }
    // 2026-11-19 修复（用户反馈）：进入时默认分类 = 其他（横排第 7 格、屏外看不见）——
    // 首次分类加载完成后把选中方块滚进视野，「默认是其他」一眼可见
    val density = androidx.compose.ui.platform.LocalDensity.current
    var initialCategoryScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(categories.isNotEmpty()) {
        if (!initialCategoryScrollDone && categories.isNotEmpty()) {
            initialCategoryScrollDone = true
            androidx.compose.runtime.withFrameNanos { }
            val target = com.visualzyzy.chuxingdazi.ui.common.categoryRowScrollTargetPx(
                categories, selectedCategoryId, density,
            )
            if (target != null) {
                categoryScrollState.scrollTo(target.coerceIn(0, categoryScrollState.maxValue))
            }
        }
    }

    val isAdjustMode = editPlaceId != null

    // 调整模式：加载原点位
    LaunchedEffect(editPlaceId) {
        editPlaceId?.let { id ->
            viewModel.getPlace(id)?.let { place ->
                adjustPlace = place
                centerLat = place.lat
                centerLng = place.lng
                nameInput = place.name
                selectedCategoryId = place.categoryId
                noteInput = place.note.orEmpty()
                FieldValuesCodec.decode(place.fieldValues).forEach { (key, element) ->
                    fieldInputs[key] = (element as? kotlinx.serialization.json.JsonPrimitive)?.content ?: element.toString()
                }
            }
        }
    }

    // 准星中心（窗口坐标）：选点/逆地理/恢复镜头都以它为锚（不用 status.target，见下方说明）
    var crosshairCenter by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // 地图就绪：有显式坐标（长按/调整）→ 定位过去；否则恢复上次相机位置（不跳回北京）。
    // 用 moveSoScreenPointIsAt 让准星精确落回目标点（渲染中心 ≠ 准星，差半屏，直接移 target 会错位）
    // R2（第七轮 2026-11-06 修复）：必须等准星完成布局（crosshairCenter != Zero）再移相机——
    // 旧实现在准星还没布局时直接 moveToImmediate，把目标点放到「全屏渲染中心」，而准星在
    // 「剩余地图区域中心」（底部卡片占掉下半屏），结果准星与目标点差半张卡片（pointed_information
    // 「为该点位添加标记」进入 S06 时准星没有对准蓝色临时标记的根因）。调整模式还要等原点位加载完。
    // 第十二轮（2026-11-11）：再补两个条件，否则目标点仍会落在「全屏中心」而非准星处——
    // ①cardHeightPx > 0：首帧卡片尚未测量（effectiveCardHeight=0）时准星被放在「全屏中心」，
    //   卡片首测后准星才移到「剩余区域中心」——恢复快照链路（＋面板进入）实测准星处偏了
    //   半个卡片高（主界面中心显示在全屏中心、被卡片盖住，准星指的是它上方）；修复后
    //   「进入前的地图中心点」自动移动到准星处（上半区中心），与用户要求一致。
    // ②对位前等两帧（withFrameNanos），确保读到的是准星收敛后的最终位置。
    var initialCameraApplied by remember { mutableStateOf(false) }
    val cameraTargetReady = mapAvailable &&
        crosshairCenter != androidx.compose.ui.geometry.Offset.Zero &&
        cardHeightPx > 0 &&
        (!isAdjustMode || adjustPlace != null)
    LaunchedEffect(cameraTargetReady) {
        if (!cameraTargetReady || initialCameraApplied) return@LaunchedEffect
        initialCameraApplied = true
        viewModel.kickRegionHeal()
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        val lat = initialLat ?: adjustPlace?.lat
        val lng = initialLng ?: adjustPlace?.lng
        val c = crosshairCenter
        if (lat != null && lng != null) {
            centerLat = lat
            centerLng = lng
            // D1（第十一轮 2026-11-11）：pointed 链路（keepZoom=true）沿用主界面相机快照里的
            // 缩放级别——进 S06 只重新居中、不发生缩放变化；长按/调整未传时保持原 17f
            val zoomLevel = if (keepZoom) {
                Prefs.cameraSnapshot(context)?.zoom ?: 17f
            } else {
                17f
            }
            controller.moveSoScreenPointIsAt(c.x.toInt(), c.y.toInt(), com.tencent.tencentmap.mapsdk.maps.model.LatLng(lat, lng), zoomLevel)
        } else {
            Prefs.cameraSnapshot(context)?.let { snap ->
                centerLat = snap.lat
                centerLng = snap.lng
                controller.moveSoScreenPointIsAt(c.x.toInt(), c.y.toInt(), com.tencent.tencentmap.mapsdk.maps.model.LatLng(snap.lat, snap.lng), snap.zoom)
            }
        }
    }

    // 相机静止 → 更新标注中心 + 持久化相机（返回 S03 时保持原位）
    // 逆地理结果用 epoch 防串台：任何新意图（手势开始/新中心）都递增，晚到的旧回调丢弃——
    // 否则连续拖放微调时，慢返回的旧地址会覆盖当前位置（真机实测：准星在北七家、地址是禄米仓）
    val geocodeEpoch = remember { intArrayOf(0) }
    var geocodeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(mapAvailable) {
        if (mapAvailable) {
            controller.onCameraIdle = { _, _, zoom ->
                // 选点坐标一律用 projection(准星) 现场换算，**不用 status.target**——
                // 真机实测（2026-11-01）：setViewPadding 下百度 SDK 的渲染中心与 status.target
                // 错位可达 18km（padding 只影响换算、不移动渲染中心），projection 与渲染一致
                val ll = controller.screenPointToLatLng(
                    crosshairCenter.x.toInt(),
                    crosshairCenter.y.toInt(),
                )
                if (ll != null) {
                    centerLat = ll.latitude
                    centerLng = ll.longitude
                    scope.launch { Prefs.saveCamera(context, ll.latitude, ll.longitude, zoom) }
                }
                // 手指重新按上地图 → 作废已排队/已发出的读取（大缩放级别下连续拖放微调不被打断）
            }
            // 手指重新按上地图 → 作废已排队/已发出的读取（大缩放级别下连续拖放微调不被打断）
            controller.onCameraGestureStarted = {
            showPickHint = false
                geocodeEpoch[0]++
                geocodeJob?.cancel()
                geocodeJob = null
                geocoding = false
                crosshairAnchorY = null // P18：地图手势恢复准星「剩余区域居中」
            }
        }
    }

    // 相机中心变化 → 800ms 防抖逆地理（§6 S06：松手后才刷新；拉长防抖给多次拖放微调留操作余量）
    LaunchedEffect(centerLat, centerLng) {
        val lat = centerLat ?: return@LaunchedEffect
        val lng = centerLng ?: return@LaunchedEffect
        // P66（2026-11-04）第四轮：删掉 P16.3 的「准星处水滴预览标记」——它会跟着准星一起动，
        // 被误读为"当前位置图标跟着准星跑"。选点指示只保留准星本体；
        // 用户真实位置由下方 startLocate 的 SDK 定位蓝点表达（保持在真实位置不动）。
        geocodeEpoch[0]++
        val myEpoch = geocodeEpoch[0]
        geocoding = true
        val job = launch {
            delay(800)
            controller.reverseGeocode(lat, lng) { info ->
                if (geocodeEpoch[0] != myEpoch) return@reverseGeocode
                geoInfo = info
                geocoding = false
            }
        }
        geocodeJob = job
    }

    // P66（2026-11-04）第四轮：S06 内显示用户当前位置蓝点（SDK 定位图层，地图拖动时蓝点
    // 保持钉在真实位置，不随准星移动）。onFirstFix 不设置 → 不移动相机，不打断选点；
    // MapHost onDispose → controller.detach() 里会 stopLocate。
    LaunchedEffect(mapAvailable) {
        if (mapAvailable) controller.startLocate()
    }

    // 第六轮（2026-11-06）：S06 地图也渲染已有点位标记（显示规则由 S04 筛选面板设置决定：
    // 显示标记总开关 / 样式 / 大小 / 名称标签 / 聚合 / 分类显隐 / 只看未到访，与主界面同一份偏好）
    LaunchedEffect(mapAvailable, visiblePlaces, categoriesById, filterState) {
        if (mapAvailable) {
            controller.renderPlaces(visiblePlaces, categoriesById, null, filterState)
        }
    }

    // 第十六轮：叠点点击 = 缩放到组内点位刚好散开（与 S05b 同语义）
    SideEffect {
        controller.onStackTap = { ids ->
            val pts = places.filter { it.id in ids }
                .map { com.tencent.tencentmap.mapsdk.maps.model.LatLng(it.lat, it.lng) }
            if (pts.isNotEmpty()) {
                val builder = com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds.Builder()
                pts.forEach { builder.include(it) }
                controller.fitBoundsGroup(pts)
            }
        }
    }

    // 说明：S06 不再使用 setViewPadding——真机实测（2026-11-01）百度 SDK 的 padding 不移动
    // 渲染中心、只扰乱 status.target 换算（错位可达 18km）。选点坐标一律走 projection(准星)。

    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
    val saveSchema = selectedCategory?.let { FieldSchemaCodec.decode(it.fieldSchema) }.orEmpty()

    // 第十六轮：保存位置前的 5 米近邻软确认（优化建议：调整/新增坐标不再完全不走查重）。
    // Triple(openEditor, 附近的点, 是否调整模式)；确认后照常保存。
    var nearDupAsk by remember {
        mutableStateOf<Triple<Boolean, PlaceEntity, Boolean>?>(null)
    }

    val doSave: (openEditor: Boolean, adjust: Boolean) -> Unit = { openEditor, adjust ->
        val lat = centerLat
        val lng = centerLng
        if (lat == null || lng == null) {
            android.widget.Toast.makeText(context, "还没有选定位置", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val nameCandidate = geoInfo?.nameCandidate.orEmpty()
            val address = geoInfo?.address?.takeIf { it.isNotBlank() }
                ?: "%.6f, %.6f".format(lat, lng) // 逆地理失败显示坐标文本，不阻塞添加（§6 S06 边界）
            if (adjust && editPlaceId != null) {
                // 第七轮：坐标变了 → province/city 用本次逆地理结果一并刷新（拿不到则保留旧值，自愈兜底）
                viewModel.updateCoords(
                    editPlaceId,
                    lat,
                    lng,
                    address,
                    province = geoInfo?.provinceOrNull,
                    city = geoInfo?.cityOrNull,
                )
                // 第六轮（2026-11-06）：调整模式也算「保存成功」→ 走 onSaved（清 pointed_pending 并返回）
                onSaved()
            } else {
                viewModel.saveNew(
                    lat = lat,
                    lng = lng,
                    name = nameInput,
                    address = address,
                    nameCandidate = nameCandidate,
                    categoryId = selectedCategoryId,
                    note = noteInput,
                    fieldValuesJson = FieldValuesCodec.fromTextInputs(
                        saveSchema + com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.decode(customSchemaJson),
                        fieldInputs,
                    ), // 字段模板升级 §3.1：自定义字段（p_ 前缀）的值也要编码落库
                    customSchemaJson = customSchemaJson,
                    province = geoInfo?.provinceOrNull, // 第七轮：只信百度逆地理，随保存落库
                    city = geoInfo?.cityOrNull,
                    iconKey = customIconKey, // 第十一轮：点位自定义图标（null = 跟随分类）
                    onSaved = { place ->
                        // 第六轮：保存成功统一走 onSaved（主界面忘记 pointed_pending 信息）
                        onSaved()
                        if (openEditor) onOpenEditor(place.id)
                    },
                    onDuplicate = {
                        android.widget.Toast.makeText(
                            context,
                            "这个地点已经在库里了",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }

    val saveAndClose: (openEditor: Boolean) -> Unit = { openEditor ->
        val lat = centerLat
        val lng = centerLng
        if (lat == null || lng == null) {
            android.widget.Toast.makeText(context, "还没有选定位置", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                val excludeId = if (isAdjustMode) editPlaceId else null
                val near = viewModel.nearDuplicateOf(lat, lng, excludeId)
                if (near != null) {
                    nearDupAsk = Triple(openEditor, near, isAdjustMode)
                } else {
                    doSave(openEditor, isAdjustMode)
                }
            }
        }
    }

    // 近邻确认框：5 米内已有点位仍要保存 / 取消
    nearDupAsk?.let { (openEditor, near, adjust) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { nearDupAsk = null },
            title = { Text("附近已有点位", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "新位置 5 米内已有点位「${near.name}」，\n仍要${if (adjust) "保存这个位置" else "添加为新点位"}吗？",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    nearDupAsk = null
                    doSave(openEditor, adjust)
                }) { Text("仍要保存", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { nearDupAsk = null }) { Text("取消") }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .onGloballyPositioned { containerHeightPx = it.size.height },
    ) {
        if (!mapAvailable) {
            Text(
                text = "地图不可用，请先配置 AK",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MapHost(controller = controller, modifier = Modifier.fillMaxSize())

            // 准星：位于「剩余地图区域」正中（问题 2，用户反馈 2026-10-31）。
            // P18（2026-11-02）：拖动卡片手柄期间准星冻结在原位（选点不漂移、缩放不变），
            // 直到下一次地图手势才恢复居中。
            // D3（第十一轮 2026-11-11）：删除「卡片长高盖过准星就上推」的钳制——冻结期间准星
            // 钉死在 anchor，卡片拖过准星位置也不再推动它（点位信息随之稳定不漂移，用户要求）；
            // 非冻结的自动模式 autoCrosshairY = (容器高−卡高)/2 本身就居中于剩余区域，无需钳制。
            val autoCrosshairY = ((containerHeightPx - (cardOverrideHeightPx ?: cardHeightPx)) / 2).coerceAtLeast(0)
            val crosshairY = (crosshairAnchorY ?: autoCrosshairY).coerceAtLeast(0)
            CrosshairGraphic(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = with(density) { crosshairY.toDp() })
                    .onGloballyPositioned { coords ->
                        val topLeft = coords.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                        crosshairCenter = androidx.compose.ui.geometry.Offset(
                            topLeft.x + coords.size.width / 2f,
                            topLeft.y + coords.size.height / 2f,
                        )
                        // 冻结期间准星若被上推 → 现场重算选点，保持显示的地址与准星一致
                        //（D3 后冻结期间准星不再移动，此分支仅在跨手势高度自适应变化时生效）
                        if (crosshairAnchorY != null) {
                            controller.screenPointToLatLng(
                                crosshairCenter.x.toInt(),
                                crosshairCenter.y.toInt(),
                            )?.let {
                                centerLat = it.latitude
                                centerLng = it.longitude
                            }
                        }
                    },
            )

            // 顶部深色提示条——第十六轮：显示 4 秒或用户一动地图就淡出，
            // 不再长期压住地图上的 POI 名（提示是引导，不是常驻遮罩）
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(4000)
                showPickHint = false
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showPickHint,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(
                    color = Color(0xCC14171C),
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(top = 8.dp),
                ) {
                    Text(
                        text = "拖动地图，把准星对准要标注的位置",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }

            // 底部卡片（选中分类后自动延伸字段编辑器，问题 3）。
            // P18（2026-11-02）：顶部小白条是真把手——往下拖收起（保留地址信息栏，最小 160dp），
            // 从最小处往上拖展开；拖动期间准星/缩放不变。松手后保持该高度。
            // 第六轮（2026-11-06）：把手移出 verticalScroll 列（与滚动列并列）——此前把手在滚动列
            // 内部，真机上垂直拖动手势被滚动容器的手势竞争吞掉，白条拖不动整块（只有内容能滚）。
            val dragMinPx = with(density) { 160.dp.toPx() }.roundToInt()
            // D2（第十一轮 2026-11-11）：卡片高度上限 = 容器高度 − 状态栏高度——顶边永不进入
            // 系统状态栏区域，把手不会停在状态栏区内（否则按住把手下拉会拉出系统通知栏）
            val maxCardHeightPx = (containerHeightPx - WindowInsets.statusBars.getTop(density))
                .coerceAtLeast(dragMinPx)
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(
                        cardOverrideHeightPx?.let {
                            Modifier.height(with(density) { it.coerceAtLeast(dragMinPx).toDp() })
                        } ?: Modifier,
                    )
                    .onGloballyPositioned { cardHeightPx = it.size.height },
            ) {
                // 第六轮修复（2026-11-06）注记：自适应回退比对「内容自然高度 vs Surface 固定高度」，
                // 内容比视口矮超过 48px（Surface 出现空白）才恢复自适应。
                // 遗留 #5 修复（2026-11-18）：旧实现把 onGloballyPositioned 挂在**外层列**上——
                // Surface（propagateMinConstraints）会把外层列钉死在固定高度，它回报的高度
                // 恒等于 Surface 高度，「比视口矮」永远不成立，回退从未触发。真实内容高度改在
                // **滚动列**上测（verticalScroll 对子内容无高度上限，测出的就是完整内容高度），
                // 自然高度 = 滚动列内容 + 把手区（10+18+8dp 的 Spacer/把手在滚动列之外）。
                var scrollContentHeightPx by remember { mutableIntStateOf(0) }
                val cardChromePx = with(density) { (10 + 18 + 8).dp.toPx().roundToInt() }
                LaunchedEffect(cardOverrideHeightPx, scrollContentHeightPx) {
                    val ov = cardOverrideHeightPx ?: return@LaunchedEffect
                    if (scrollContentHeightPx > 0 &&
                        ov > scrollContentHeightPx + cardChromePx + 48
                    ) {
                        cardOverrideHeightPx = null
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Spacer(modifier = Modifier.height(10.dp))
                    // 把手：可拖拽（P18）。crosshairY 是组合期局部值，闭包里要用 rememberUpdatedState 读最新
                    val currentCrosshairY by androidx.compose.runtime.rememberUpdatedState(crosshairY)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .pointerInput(Unit) {
                                // 第六轮修复（2026-11-06）：拖动量必须**累计**——detectDragGestures 的
                                // dragAmount 是「相对上一个事件」的增量，旧实现直接 start - 单次增量，
                                // 高度每帧只在起点附近抖动 ~10px，等于拖不动（真机 logcat 实证）。
                                var accumY = 0f
                                detectDragGestures(
                                    onDragStart = {
                                        accumY = 0f
                                        dragStartCardHeightPx = cardOverrideHeightPx ?: cardHeightPx
                                        if (crosshairAnchorY == null) crosshairAnchorY = currentCrosshairY
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        accumY += dragAmount.y
                                        // 向下拖（dy>0）→ 高度减；向上拖 → 增。
                                        // 上限 = 容器高度 − 状态栏（D2），下限 = dragMinPx
                                        val newHeight = dragStartCardHeightPx - accumY.roundToInt()
                                        cardOverrideHeightPx = newHeight
                                            .coerceAtLeast(dragMinPx)
                                            .coerceAtMost(maxCardHeightPx)
                                    },
                                    onDragEnd = { },
                                    onDragCancel = { },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 38.dp, height = 4.dp)
                                .background(Color(0xFFD3D9E0), RoundedCornerShape(2.dp)),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState())
                            // 遗留 #5：真实内容高度在这里测（见上方修复注记）
                            .onGloballyPositioned { scrollContentHeightPx = it.size.height },
                    ) {
                    // 地址行：编码中骨架屏（§6 S06）
                    if (geocoding) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .background(Color(0xFFEFF2F6), RoundedCornerShape(6.dp)),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(14.dp)
                                .background(Color(0xFFF4F6F8), RoundedCornerShape(6.dp)),
                        )
                    } else {
                        val info = geoInfo
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = info?.semantic?.ifBlank { null }
                                        ?: info?.address?.ifBlank { null }
                                        ?: centerLat?.let { lat ->
                                            "%.6f, %.6f".format(lat, centerLng)
                                        }
                                        ?: "拖动地图选择位置",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                                if (!info?.address.isNullOrBlank()) {
                                    Text(
                                        text = info!!.address,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    if (!isAdjustMode) {
                        // 名称输入（选填）；第六轮：名称最长 40 个汉字（超长截断 + 3 秒全屏阻断）
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = com.visualzyzy.chuxingdazi.ui.common.NameLimit.clamp(it) },
                            singleLine = true,
                            placeholder = { Text("给这个地点起个名字（选填）", fontSize = 13.sp) },
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 分类横向选择 + ＋新建分类（问题 4；P12：预设在前、新建按时间序在后）
                    Text(text = "分类", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    // 分类横排（第十一轮：提为公共组件 CategorySelectorRow，S05b/S05 弹框同形态）
                    CategorySelectorRow(
                        categories = categories,
                        selectedId = selectedCategoryId,
                        onSelect = { selectedCategoryId = it },
                        onNewCategory = { showNewCategoryDialog = true },
                        scrollState = categoryScrollState,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 第十一轮（2026-11-11）：点位自定义图标行（null = 跟随分类；
                    // 调整模式下不显示——调整只改坐标，外观归 S08/标记管理管）
                    if (!isAdjustMode) {
                        PlaceIconRow(
                            categoryColorHex = selectedCategory?.color,
                            categoryIconKey = selectedCategory?.iconKey,
                            customIconKey = customIconKey,
                            onPick = { showIconPicker = true },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 选中分类的模板字段编辑器（问题 3：选分类后自动延伸；调整模式下隐藏——字段归 S08 管）
                    if (!isAdjustMode && selectedCategory != null) {
                        val schema = FieldSchemaCodec.decode(selectedCategory.fieldSchema)
                        if (schema.isNotEmpty()) {
                            Text(
                                text = "${selectedCategory.name}信息（选填，也可保存后进编辑页补充）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            schema.chunked(2).forEach { rowDefs ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowDefs.forEach { def ->
                                        com.visualzyzy.chuxingdazi.ui.common.TemplateFieldCell(
                                            def = def,
                                            value = fieldInputs[def.key].orEmpty(),
                                            onValueChange = { fieldInputs[def.key] = it },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (rowDefs.size == 1) Spacer(modifier = Modifier.weight(1f))
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }

                        // 点位自定义字段区（字段模板升级 §3.2）：调整模式下隐藏（同模板字段——字段归 S08 管）
                        com.visualzyzy.chuxingdazi.ui.common.CustomFieldsSection(
                            defs = com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.decode(customSchemaJson),
                            templateLabels = schema.map { it.label }.toSet(),
                            values = fieldInputs,
                            onValueChange = { key, value ->
                                if (value.isBlank()) fieldInputs.remove(key) else fieldInputs[key] = value
                            },
                            onDefsChanged = {
                                customSchemaJson = com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.encode(it)
                            },
                        )
                        // 备注
                        Text(text = "备注", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            shape = RoundedCornerShape(11.dp),
                            placeholder = { Text("想记点什么都可以（选填）", fontSize = 12.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 按钮行：取消（92dp 灰）+ 标注此地点（主色；长按进 S08 填更多信息）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .width(92.dp)
                                .height(46.dp)
                                .clickable(onClick = onDismiss),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (isAdjustMode) "返回" else "取消",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .combinedClickable(
                                    onClick = { saveAndClose(false) },
                                    onLongClick = { saveAndClose(true) },
                                ),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (isAdjustMode) "保存位置" else "标注此地点",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }
        }
    }

    if (showNewCategoryDialog) {
        NewCategoryDialog(
            onDismiss = { showNewCategoryDialog = false },
            onCreate = { name, iconKey, color ->
                scope.launch {
                    viewModel.createCategory(name, iconKey, color)?.let { created ->
                        selectedCategoryId = created.id // 新分类自动选中（坐标/名称状态不离开本屏，天然保留）
                        showNewCategoryDialog = false
                        // P12（2026-11-02）：新分类排在末尾，等列表刷新后由 LaunchedEffect 滚动到最后
                        pendingScrollToEnd = true
                    } ?: android.widget.Toast.makeText(
                        context,
                        "已有同名分类",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onOpenManager = onManageCategories,
            onPickCustomColor = { currentHex -> onPickDraftColor(currentHex) },
            pendingCustomColor = pendingCustomColor,
            onConsumeCustomColor = { pendingCustomColor = null },
        )
    }

    if (showIconPicker) {
        IconPickerDialog(
            currentKey = customIconKey,
            categoryIconKey = selectedCategory?.iconKey,
            categoryColorHex = selectedCategory?.color,
            onDismiss = { showIconPicker = false },
            onConfirm = {
                customIconKey = it
                showIconPicker = false
            },
        )
    }
}

/** 高对比十字准星（问题 2：明显、居中于剩余地图区域）。 */
@Composable
private fun CrosshairGraphic(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.size(76.dp)) {
        // 外圈：44dp 主色描边
        Box(
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.Center)
                .border(3.dp, primary, CircleShape)
                .border(1.5.dp, Color.White, CircleShape),
        )
        // 中心点
        Box(
            modifier = Modifier
                .size(7.dp)
                .align(Alignment.Center)
                .background(primary, CircleShape)
                .border(1.dp, Color.White, CircleShape),
        )
        // 四向刻线（延伸到圈外）
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(y = (-32).dp)
                .width(3.dp)
                .height(14.dp)
                .background(primary),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(y = 32.dp)
                .width(3.dp)
                .height(14.dp)
                .background(primary),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(x = (-32).dp)
                .width(14.dp)
                .height(3.dp)
                .background(primary),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(x = 32.dp)
                .width(14.dp)
                .height(3.dp)
                .background(primary),
        )
    }
}

