package com.visualzyzy.chuxingdazi.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.visualzyzy.chuxingdazi.data.tencent.TencentSdk
import com.visualzyzy.chuxingdazi.data.tencent.MapController
import com.visualzyzy.chuxingdazi.data.tencent.SuggestionItem
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.ui.map.MapHost
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * S05b 搜索结果点位详情（P67 第四轮，视觉稿 444.png）：
 * 顶栏（返回=回搜索结果页；X=回搜索落地页并清空输入）+ 中部地图（可拖动缩放、
 * 标记指位、右侧 +/- 与筛选按钮）+ 底部信息卡与「为该位置添加标记」按钮。
 *
 * 2026-11-19（用户定调）：「为该位置添加标记」原屏内拖动浮层改为**独立全屏页
 * s05_add_point**（本页两条入口——底部信息卡 / 点击点位信息卡——都跳该页）。
 *
 * 第八轮（2026-11-07）：「显示手指点击的点位信息」（pointed_information 方案 A，P120）——
 * 单击地图 → 标记点.png 临时标记 + 点击点位信息卡（同 S03 视觉；取消 / 为该点位添加标记）。
 * 硬性条件：点击点与搜索结果标记 <5m 不触发；信息卡出现时原信息卡折叠成一行摘要
 * （点摘要切回）；系统返回键 = 取消；打开筛选面板 = 放弃。
 */
@Composable
fun PlacePreviewScreen(
    item: SuggestionItem,
    inLibrary: Boolean,
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onCloseToLanding: () -> Unit,
    // 2026-11-19：添加浮层改独立页——本页只负责把目标点（与预填名称）交给路由层跳 s05_add_point
    onOpenAdd: (target: SuggestionItem, presetName: String) -> Unit,
) {
    val context = LocalContext.current

    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val allLists by viewModel.allLists.collectAsStateWithLifecycle()
    // 第六轮：已有点位。第十六轮：本页筛选状态与主地图 S04 **分离**——
    // 「这次搜索要看的点」是临时视图，不再读写全局 DataStore（互不干扰，页面退出即失效）
    val places by viewModel.places.collectAsStateWithLifecycle()
    var filterState by remember {
        mutableStateOf(com.visualzyzy.chuxingdazi.data.prefs.FilterState())
    }
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
    // 第七轮（2026-11-06）：S04「按照地区筛选」数据源（与主界面同一份聚合流）
    val provinceRows by viewModel.provinceCounts.collectAsStateWithLifecycle()
    val cityRows by viewModel.provinceCityCounts.collectAsStateWithLifecycle()
    val unknownRegionCount by viewModel.unknownRegionCount.collectAsStateWithLifecycle()

    val lat = item.lat
    val lng = item.lng
    val ak by Prefs.sdkKey(context).collectAsStateWithLifecycle(initialValue = null)
    val mapAvailable = !ak.isNullOrBlank() && TencentSdk.isInitialized && lat != null && lng != null

    val controller = remember { MapController(context) }
    // P77 AOI 精准包裹：腾讯 Android SDK 无 AOI 面检索能力 → 按用户约定「做不到精准包裹就不画框」，
    // S05b 只保留落点标记 + 库内点位渲染（2026-11-17 腾讯版，原 AoiSearcher 随百度版删除）。
    // 2026-11-19：添加浮层整体改独立页 s05_add_point——本页不再持有任何添加表单状态。
    // 第六轮：筛选面板开关
    var showFilter by remember { mutableStateOf(false) }
    var mapAreaHeightPx by remember { mutableIntStateOf(0) }

    // 地图就绪：定位到结果点 + 落标记（AOI 面无对应能力，不画框）
    LaunchedEffect(mapAvailable) {
        if (!mapAvailable || lat == null || lng == null) return@LaunchedEffect
        controller.moveToImmediate(lat, lng, 17f)
        controller.showTempMarker(lat, lng, item.name)
    }

    // 第六轮：已有点位标记渲染（筛选联动；临时标记是独立覆盖物，不受影响）
    LaunchedEffect(mapAvailable, visiblePlaces, categoriesById, filterState) {
        if (mapAvailable) {
            controller.renderPlaces(visiblePlaces, categoriesById, null, filterState)
        }
    }

    // ---- 第八轮（2026-11-07）：pointed_information 方案 A（P120，待办 P91 落地）----
    // 单击地图 → 标记点.png 临时标记 + 点击点位信息卡（与 S03 同款视觉）；原信息卡折叠成一行摘要。
    // 硬性条件：点击点与搜索结果临时标记重合/过于接近（<5m，同 §14.1 #34 阈值）→ 不执行显示流程
    //（视为对搜索结果标记的确认，走既有行为——直接点中标记本身时 SDK 的 marker 点击已消费，同样无动作）。
    var tapped by remember { mutableStateOf<PreviewTappedPoint?>(null) }
    val tapGeocodeEpoch = remember { intArrayOf(0) }

    // 系统返回键在点击信息卡弹出期间 = 取消（与 S03 P99 同语义），不退出页面
    BackHandler(enabled = tapped != null) { tapped = null }

    // 打开筛选面板 → 与 S03 同规则：面板会盖住信息卡，视为放弃当前点击点位
    LaunchedEffect(showFilter) {
        if (showFilter && tapped != null) tapped = null
    }

    // 点击点位临时标记（标记点.png）：地图就绪才画；取消/切回 → 移除
    LaunchedEffect(tapped, mapAvailable) {
        if (tapped == null) {
            controller.clearTappedMarker()
        } else if (mapAvailable) {
            controller.showTappedMarker(tapped!!.lat, tapped!!.lng)
        }
    }

    // 回调接线（SideEffect 保证拿到最新组合态）
    SideEffect {
        // 第十六轮：叠点（放大也分不开的组）→ 缩放到组内点位刚好散开（本页不建散开层）
        controller.onStackTap = { ids ->
            val pts = viewModel.places.value.filter { it.id in ids }
                .map { com.tencent.tencentmap.mapsdk.maps.model.LatLng(it.lat, it.lng) }
            if (pts.isNotEmpty()) {
                val builder = com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds.Builder()
                pts.forEach { builder.include(it) }
                controller.fitBoundsGroup(pts)
            }
        }
        controller.onPlaceMarkerClick = {
            // 已有点位标记被点：与点击信息卡互斥（同 S03——点标记关信息框）
            tapped = null
        }
        controller.onMapClick = { lat, lng, poiName ->
            // S04 筛选面板弹出期间单击地图 = 不做任何事（同 S03 P101）
            if (!showFilter) {
                val resLat = item.lat
                val resLng = item.lng
                val tooCloseToResult = resLat != null && resLng != null &&
                    distanceMeters(lat, lng, resLat, resLng) < TAP_MATCH_RADIUS_METERS
                if (!tooCloseToResult) {
                    tapGeocodeEpoch[0]++
                    val myEpoch = tapGeocodeEpoch[0]
                    tapped = PreviewTappedPoint(
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
                                province = info?.province.orEmpty(),
                                city = info?.city.orEmpty(),
                                loading = false,
                            )
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回 | 标题（名称 + 区域）| X
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回搜索结果",
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onBack),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val region = (item.city.orEmpty() + item.district.orEmpty()).ifBlank { null }
                if (!region.isNullOrBlank() || !item.tag.isNullOrBlank()) {
                    Text(
                        text = listOfNotNull(item.tag, region).joinToString(" · "),
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭并回到最近搜索",
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onCloseToLanding),
            )
        }

        // 地图区
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { mapAreaHeightPx = it.size.height },
        ) {
            if (mapAvailable && lat != null && lng != null) {
                MapHost(controller = controller, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    text = "地图不可用，请先配置 AK",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            // 右侧 +/- 缩放 + 筛选按钮（444.jpg 红圈位置：缩放按钮下方；无绿边）
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PreviewZoomButton("+") { controller.zoomIn() }
                Spacer(modifier = Modifier.height(8.dp))
                PreviewZoomButton("−") { controller.zoomOut() }
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    shadowElevation = 2.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { showFilter = !showFilter },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = "点位筛选",
                            tint = Color(0xFF3D4451),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // 第六轮：S04 筛选面板（第十六轮：组件同款，但筛选状态为本页内存态，与主地图分离）
            if (showFilter) {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    com.visualzyzy.chuxingdazi.ui.filter.FilterPanel(
                        filter = filterState,
                        containerHeightPx = mapAreaHeightPx,
                        visiblePlaces = visiblePlaces,
                        categoryInfo = categoriesById,
                        placesEmpty = places.isEmpty(),
                        provinceRows = provinceRows,
                        cityRows = cityRows,
                        unknownRegionCount = unknownRegionCount,
                        panelHeightPx = { },
                        onClose = { showFilter = false },
                        onPlaceClick = { place ->
                            showFilter = false
                            controller.moveTo(place.lat, place.lng, 17f)
                        },
                        onShowMarkersChange = { value ->
                            filterState = filterState.copy(showMarkers = value)
                        },
                        onShowMarkersBlocked = { },
                        onStyleChange = { style ->
                            filterState = filterState.copy(markerStyle = style)
                        },
                        onShowLabelsChange = { value ->
                            filterState = filterState.copy(showLabels = value)
                        },
                        onClusterModeChange = { mode ->
                            filterState = filterState.copy(clusterMode = mode)
                        },
                        onUnvisitedOnlyChange = { value ->
                            filterState = filterState.copy(unvisitedOnly = value)
                        },
                        onMarkerSizeChange = { percent ->
                            filterState = filterState.copy(markerSizePercent = percent)
                        },
                        onToggleCategoryHidden = { categoryId ->
                            val hidden = filterState.hiddenCategoryIds.toMutableSet()
                            if (categoryId in hidden) hidden.remove(categoryId) else hidden.add(categoryId)
                            filterState = filterState.copy(hiddenCategoryIds = hidden)
                        },
                        onClearHidden = {
                            filterState = filterState.copy(hiddenCategoryIds = emptySet())
                        },
                        onHideAll = { ids ->
                            filterState = filterState.copy(hiddenCategoryIds = ids.toSet())
                        },
                        onRegionChange = { province, city ->
                            filterState = filterState.copy(regionProvince = province, regionCity = city)
                        },
                        lists = allLists,
                        onListFilterChange = { enabled, listIds ->
                            filterState = filterState.copy(listFilterEnabled = enabled, listIds = listIds)
                        },
                    )
                }
            }

            // 第八轮（2026-11-07）：pointed_information —— 单击地图弹出的点击点位信息卡（方案 A，P120）
            // 与 S03 同款视觉（P97：暗色黑底白字、无「信息」标题行）；「为该点位添加标记」=
            // 打开本页现有添加浮层并预填名称（逆地理候选）与坐标（浮层复用，不跨页）。
            tapped?.let { t ->
                val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val cardBg = if (darkTheme) Color.Black else Color.White
                val titleColor = if (darkTheme) Color.White else Color(0xFF14171C)
                val bodyColor = if (darkTheme) Color.White else Color(0xFF5B6472)
                val chipBg = if (darkTheme) Color(0xFF23272E) else Color(0xFFEFF2F6)
                val chipText = if (darkTheme) Color(0xFFA6AFBC) else Color(0xFF5B6472)
                Surface(
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                    color = cardBg,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        if (t.loading && t.name.isBlank()) {
                            // 逆地理编码中的骨架屏（同 S03）
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
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val tappedTagLine = t.semantic.ifBlank { t.address }
                        if (tappedTagLine.isNotBlank()) {
                            Text(
                                text = tappedTagLine,
                                fontSize = 11.5.sp,
                                color = Primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        if (t.address.isNotBlank() && t.address != tappedTagLine) {
                            Text(
                                text = t.address,
                                fontSize = 11.5.sp,
                                color = bodyColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 16.sp,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        // 第十六轮：点击信息卡不再展示 BD09 坐标行（与固定信息卡同一原则）
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = chipBg,
                                modifier = Modifier
                                    .width(92.dp)
                                    .height(46.dp)
                                    .clickable {
                                        // 取消：清除点击态，恢复原信息卡
                                        tapped = null
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
                                color = Primary,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clickable(enabled = !t.loading) {
                                        // 为该点位添加标记：跳独立页 s05_add_point，
                                        // 目标点=被点击的点（带逆地理省份），预填名称（逆地理候选）
                                        onOpenAdd(t.toSuggestionItem(), t.nameCandidate)
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
                    }
                }
            }
        }

        // 底部信息卡（444.png：信息 + 占一行「为该位置添加标记」）
        // 第八轮（2026-11-07）方案 A：点击信息卡出现时，本卡折叠成一行摘要（点摘要切回）
        // 第十六轮（互斥修复）：筛选面板打开期间固定信息卡/摘要都收起——
        // 不再出现「底卡 + 筛选面板 + 地图」三截叠放、添加按钮仍可点的状态；关掉筛选后卡片回来
        if (showFilter) {
            // 面板打开：只留地图（面板悬浮其上）
        } else if (tapped == null) {
            Surface(
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(text = "信息", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = item.name,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val tagLine = listOfNotNull(item.tag, item.district, item.city)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (tagLine.isNotBlank()) {
                        Text(
                            text = tagLine,
                            fontSize = 11.5.sp,
                            color = Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (!item.address.isNullOrBlank()) {
                        Text(
                            text = item.address!!,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 16.sp,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    // 第十六轮：坐标不再进入主信息——BD09 复制到高德/系统地图会偏移；
                    // 主信息 = 名称 + tag·区域 + 地址
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .background(
                                if (inLibrary) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    Primary
                                },
                                RoundedCornerShape(14.dp),
                            )
                            .clickable(enabled = !inLibrary) {
                                // 为该位置添加标记：跳独立页 s05_add_point（目标点=搜索结果本身）。
                                // 2026-11-22 修复 #6：名称预填 SDK 标准名（原传空串——点信息卡路径
                                // 一直有预填，唯独这条搜索结果路径漏了，46 个点位逐个手敲即因此起）
                                onOpenAdd(item, item.name)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (inLibrary) "已在点位库中" else "为该位置添加标记",
                            color = if (inLibrary) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                Color.White
                            },
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        } else {
            // 第八轮（2026-11-07）：原信息卡折叠成一行摘要（方案 A）——点摘要切回原卡（清除点击态）
            Surface(
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { tapped = null }
                            .padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "搜索结果：${item.name}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "点此切回", fontSize = 12.sp, color = Primary)
                    }
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }
    }

    // 2026-11-19：原「添加浮层」整体迁移至独立页 s05_add_point（AddPointScreen.kt），
    // 本页两条入口都经 onOpenAdd 跳转，屏内不再有添加表单。
}

@Composable
private fun PreviewZoomButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF14171C),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 点击点位与搜索结果标记的「过于接近」阈值（米）——同 §14.1 #34 的 <5m（P91 方案 A 硬性条件）。 */
private const val TAP_MATCH_RADIUS_METERS = 5.0

/**
 * 第八轮（2026-11-07）：S05b 单击地图点的本地态（逆地理异步回填）。
 * 与 S03 的 TappedPointState 同构，多保留 province/city——从点击信息卡入库时一并落库，
 * 省掉一轮自愈逆地理。
 */
private data class PreviewTappedPoint(
    val lat: Double,
    val lng: Double,
    val name: String,
    val semantic: String,
    val address: String,
    val poiName: String,
    val province: String = "",
    val city: String = "",
    val loading: Boolean,
) {
    /** 名称兜底链：附近 POI 名 → 语义化描述 → 完整地址（同 §6 S06）。 */
    val nameCandidate: String
        get() = poiName.takeIf { it.isNotBlank() }
            ?: semantic.takeIf { it.isNotBlank() }
            ?: address

    /** 转成 s05_add_point 可入库的目标（无 POI uid；地址/区划取逆地理结果）。 */
    fun toSuggestionItem() = com.visualzyzy.chuxingdazi.data.tencent.SuggestionItem(
        uid = null,
        name = nameCandidate.ifBlank { "%.6f, %.6f".format(lat, lng) },
        address = address.ifBlank { null },
        city = city.ifBlank { null },
        district = null,
        tag = null,
        lat = lat,
        lng = lng,
        province = province.ifBlank { null },
    )
}

/** 球面距离（米）——与 PlaceRepository.findNearbyDuplicate 同公式（§14.1 #34）。 */
private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
