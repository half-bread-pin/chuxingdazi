package com.visualzyzy.chuxingdazi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.ui.ak.AkConfigScreen
import com.visualzyzy.chuxingdazi.ui.category.CategoryManageScreen
import com.visualzyzy.chuxingdazi.ui.category.ColorPickerScreen
import com.visualzyzy.chuxingdazi.ui.list.ListsScreen
import com.visualzyzy.chuxingdazi.ui.map.MapScreen
import com.visualzyzy.chuxingdazi.ui.pick.PickPlaceScreen
import com.visualzyzy.chuxingdazi.ui.place.EditPlaceScreen
import com.visualzyzy.chuxingdazi.ui.privacy.PrivacyScreen
import com.visualzyzy.chuxingdazi.ui.route.PointSearchScreen
import com.visualzyzy.chuxingdazi.ui.route.RoutePreviewScreen
import com.visualzyzy.chuxingdazi.ui.route.RouteScreen
import com.visualzyzy.chuxingdazi.ui.route.SelectWaypointsScreen
import com.visualzyzy.chuxingdazi.ui.search.SearchScreen
import com.visualzyzy.chuxingdazi.ui.settings.SettingsScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.ui.theme.ChuxingdaziTheme
import com.visualzyzy.chuxingdazi.ui.trip.NewTripScreen
import com.visualzyzy.chuxingdazi.ui.trip.TripListScreen
import com.visualzyzy.chuxingdazi.ui.trip.TripMapScreen
import com.visualzyzy.chuxingdazi.ui.trip.TripTimelineScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 第十六轮：NameLimit 超长提示改 Toast（需要 application context）
        com.visualzyzy.chuxingdazi.ui.common.NameLimit.appContext = applicationContext
        enableEdgeToEdge()
        // 首启路由（设计文档 §7.1）：已同意隐私 → 直进主地图；否则 S01 起步。
        val startRoute = runBlocking {
            if (Prefs.privacyAgreed(applicationContext).first()) ROUTE_MAP else ROUTE_PRIVACY
        }
        setContent {
            // P42（2026-11-03）软件显示配色：明亮/暗色/跟随系统（S13 设置切换，DataStore 持久化）
            val themeMode by Prefs.appThemeMode(applicationContext).collectAsState(initial = Prefs.THEME_SYSTEM)
            ChuxingdaziTheme(
                darkTheme = when (themeMode) {
                    Prefs.THEME_DARK -> true
                    Prefs.THEME_LIGHT -> false
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                },
            ) {
                val navController = rememberNavController()
                // P43（2026-11-03）搜索页切换残影修复：全局改 90ms 快速淡入淡出（不再用默认长过渡），
                // 且 NavHost 包一层 Surface（不透明背景 + 默认 contentColor），过渡期不会透出底下的地图
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = startRoute,
                        enterTransition = {
                            androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(90),
                            )
                        },
                        exitTransition = {
                            androidx.compose.animation.fadeOut(
                                animationSpec = androidx.compose.animation.core.tween(90),
                            )
                        },
                        popEnterTransition = {
                            androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(90),
                            )
                        },
                        popExitTransition = {
                            androidx.compose.animation.fadeOut(
                                animationSpec = androidx.compose.animation.core.tween(90),
                            )
                        },
                    ) {
                    composable(ROUTE_PRIVACY) {
                        PrivacyScreen(
                            onAgreed = {
                                // 设计文档 §7.1：同意 → 写标记 → 此时才允许初始化腾讯 SDK（隐私接口+start）→ S02
                                lifecycleScope.launch {
                                    Prefs.setPrivacyAgreed(applicationContext, true)
                                    com.visualzyzy.chuxingdazi.data.tencent.TencentSdk.ensureInitialized(applicationContext, null)
                                    navController.navigate(ROUTE_AK) { popUpTo(ROUTE_PRIVACY) { inclusive = true } }
                                }
                            },
                            onDeclined = { finishAffinity() },
                        )
                    }
                    composable(ROUTE_AK) {
                        AkConfigScreen(
                            onDone = {
                                navController.navigate(ROUTE_MAP) { popUpTo(ROUTE_AK) { inclusive = true } }
                            },
                        )
                    }
                    composable(ROUTE_MAP) { backStackEntry ->
                        val mapHandle = backStackEntry.savedStateHandle
                        val viewLatFlow: kotlinx.coroutines.flow.StateFlow<Double> =
                            mapHandle.getStateFlow("view_lat", 0.0)
                        val viewLngFlow: kotlinx.coroutines.flow.StateFlow<Double> =
                            mapHandle.getStateFlow("view_lng", 0.0)
                        val viewNameFlow: kotlinx.coroutines.flow.StateFlow<String> =
                            mapHandle.getStateFlow("view_name", "")
                        val addedCoordsFlow: kotlinx.coroutines.flow.StateFlow<DoubleArray> =
                            mapHandle.getStateFlow("added_coords", DoubleArray(0))
                        val viewLat by viewLatFlow.collectAsState()
                        val viewLng by viewLngFlow.collectAsState()
                        val viewName by viewNameFlow.collectAsState()
                        val addedCoords by addedCoordsFlow.collectAsState()
                        // 第六轮（2026-11-06）：pointed_information 跨页信息（存在本条目 savedStateHandle）
                        val pointedJsonFlow: kotlinx.coroutines.flow.StateFlow<String> =
                            mapHandle.getStateFlow("pointed_pending", "")
                        val pointedJson by pointedJsonFlow.collectAsState()
                        val pointedPending = androidx.compose.runtime.remember(pointedJson) {
                            parsePointedPending(pointedJson)
                        }
                        MapScreen(
                            onConfigureAk = { navController.navigate(ROUTE_SETTINGS) },
                            onAddPlace = { navController.navigate("s06") },
                            onOpenRoute = { navController.navigate(ROUTE_ROUTE) },
                            onLongPressPlace = { lat, lng ->
                                // 第六轮：长按 = 开始新的选点，忘记未保存的 pointed 信息
                                mapHandle["pointed_pending"] = ""
                                navController.navigate("s06?lat=$lat&lng=$lng")
                            },
                            onEditPlace = { placeId ->
                                navController.navigate("s08?placeId=$placeId")
                            },
                            onOpenSearch = { navController.navigate(ROUTE_SEARCH) },
                            onOpenTrips = { navController.navigate(ROUTE_TRIP_LIST) },
                            onNavigateToPlace = { placeId ->
                                navController.navigate("s10_route?destPlaceId=$placeId")
                            },
                            // S07「已安排」行点击 → 对应行程时间轴（2026-11-24 实装）
                            onOpenTrip = { tripId -> navController.navigate("s14_timeline?tripId=$tripId") },
                            tempView = if (viewLat == 0.0) {
                                null
                            } else {
                                Triple(viewLat, viewLng, viewName)
                            },
                            addedCoords = addedCoords,
                            pointedPending = pointedPending,
                            onPointedDismiss = { mapHandle["pointed_pending"] = "" },
                            onPointedAdd = { pending ->
                                // 为该点位添加标记：记住信息 → S06；取消返回时恢复，保存后清除
                                // 第十一轮（2026-11-11）D1：keepZoom=true → S06 用主界面原缩放打开（取消缩放变化）
                                mapHandle["pointed_pending"] = encodePointedPending(pending)
                                navController.navigate("s06?lat=${pending.lat}&lng=${pending.lng}&keepZoom=true")
                            },
                        )
                    }
                    composable(ROUTE_SEARCH) { entry ->
                        SearchScreen(
                            navEntry = entry,
                            onClose = { navController.popBackStack() },
                            // 2026-11-19：多选/批量添加已取消（SearchScreen 不再有 onBatchAdded）
                            // P67（2026-11-04）第四轮：小眼睛 → S05b 点位详情页（带回全部信息）
                            onOpenPreview = { item ->
                                navController.navigate(
                                    "s05_preview?name=${android.net.Uri.encode(item.name)}" +
                                        "&tag=${android.net.Uri.encode(item.tag.orEmpty())}" +
                                        "&district=${android.net.Uri.encode(item.district.orEmpty())}" +
                                        "&city=${android.net.Uri.encode(item.city.orEmpty())}" +
                                        "&address=${android.net.Uri.encode(item.address.orEmpty())}" +
                                        "&lat=${item.lat ?: 0.0}&lng=${item.lng ?: 0.0}" +
                                        "&uid=${android.net.Uri.encode(item.uid.orEmpty())}",
                                )
                            },
                        )
                    }
                    // S05b 搜索结果点位详情（P67 第四轮，444.png 形态）。
                    // 复用 S05 的 SearchViewModel（scope 到 ROUTE_SEARCH 条目）拿分类/清单并单条入库。
                    composable(
                        route = "s05_preview?name={name}&tag={tag}&district={district}&city={city}" +
                            "&address={address}&lat={lat}&lng={lng}&uid={uid}",
                        arguments = listOf(
                            androidx.navigation.navArgument("name") {
                                type = androidx.navigation.NavType.StringType; defaultValue = ""
                            },
                            androidx.navigation.navArgument("tag") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("district") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("city") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("address") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("lat") {
                                type = androidx.navigation.NavType.StringType; defaultValue = "0.0"
                            },
                            androidx.navigation.navArgument("lng") {
                                type = androidx.navigation.NavType.StringType; defaultValue = "0.0"
                            },
                            androidx.navigation.navArgument("uid") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                        ),
                    ) { entry ->
                        val args = entry.arguments!!
                        val item = com.visualzyzy.chuxingdazi.data.tencent.SuggestionItem(
                            uid = args.getString("uid")?.takeIf { it.isNotBlank() },
                            name = args.getString("name").orEmpty(),
                            address = args.getString("address")?.takeIf { it.isNotBlank() },
                            city = args.getString("city")?.takeIf { it.isNotBlank() },
                            district = args.getString("district")?.takeIf { it.isNotBlank() },
                            tag = args.getString("tag")?.takeIf { it.isNotBlank() },
                            lat = args.getString("lat")?.toDoubleOrNull()?.takeIf { it != 0.0 },
                            lng = args.getString("lng")?.toDoubleOrNull()?.takeIf { it != 0.0 },
                        )
                        // 已在库判定：poi_uid 命中或同名近距（同 §14.1 #34 的规则，进详情时查一次）
                        val inLibState = androidx.compose.runtime.remember(item) {
                            androidx.compose.runtime.mutableStateOf(false)
                        }
                        val searchEntry = androidx.compose.runtime.remember(item) {
                            navController.getBackStackEntry(ROUTE_SEARCH)
                        }
                        val searchVm: com.visualzyzy.chuxingdazi.ui.search.SearchViewModel =
                            androidx.hilt.navigation.compose.hiltViewModel(searchEntry)
                        androidx.compose.runtime.LaunchedEffect(item) {
                            inLibState.value = searchVm.isInLibrary(item)
                        }
                        com.visualzyzy.chuxingdazi.ui.search.PlacePreviewScreen(
                            item = item,
                            inLibrary = inLibState.value,
                            viewModel = searchVm,
                            onBack = { navController.popBackStack() },
                            // X：回搜索落地页（222.jpg 形态）并清空进入结果页前的输入内容
                            onCloseToLanding = {
                                searchEntry.savedStateHandle["clear_query"] = true
                                navController.popBackStack()
                            },
                            // 2026-11-19：添加浮层改独立页——两条入口（底部信息卡/点击信息卡）
                            // 都跳 s05_add_point，目标点与预填名称随路由传递
                            onOpenAdd = { target, presetName ->
                                navController.navigate(
                                    "s05_add_point?name=${android.net.Uri.encode(target.name)}" +
                                        "&tag=${android.net.Uri.encode(target.tag.orEmpty())}" +
                                        "&district=${android.net.Uri.encode(target.district.orEmpty())}" +
                                        "&city=${android.net.Uri.encode(target.city.orEmpty())}" +
                                        "&address=${android.net.Uri.encode(target.address.orEmpty())}" +
                                        "&lat=${target.lat ?: 0.0}&lng=${target.lng ?: 0.0}" +
                                        "&uid=${android.net.Uri.encode(target.uid.orEmpty())}" +
                                        "&province=${android.net.Uri.encode(target.province.orEmpty())}" +
                                        "&presetName=${android.net.Uri.encode(presetName)}",
                                )
                            },
                        )
                    }
                    // s05_add_point 添加点位（2026-11-19 新增独立全屏页，S05b 两条入口同路由）：
                    // 元素与布局沿用原 S05b 添加浮层；页面不使用系统状态栏/底部按键栏区域。
                    // ViewModel 复用 S05 条目的 SearchViewModel（拿分类/清单并单条入库）。
                    composable(
                        route = "s05_add_point?name={name}&tag={tag}&district={district}&city={city}" +
                            "&address={address}&lat={lat}&lng={lng}&uid={uid}&province={province}" +
                            "&presetName={presetName}",
                        arguments = listOf(
                            androidx.navigation.navArgument("name") {
                                type = androidx.navigation.NavType.StringType; defaultValue = ""
                            },
                            androidx.navigation.navArgument("tag") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("district") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("city") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("address") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("lat") {
                                type = androidx.navigation.NavType.StringType; defaultValue = "0.0"
                            },
                            androidx.navigation.navArgument("lng") {
                                type = androidx.navigation.NavType.StringType; defaultValue = "0.0"
                            },
                            androidx.navigation.navArgument("uid") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("province") {
                                type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = ""
                            },
                            androidx.navigation.navArgument("presetName") {
                                type = androidx.navigation.NavType.StringType; defaultValue = ""
                            },
                        ),
                    ) { entry ->
                        val args = entry.arguments!!
                        val target = com.visualzyzy.chuxingdazi.data.tencent.SuggestionItem(
                            uid = args.getString("uid")?.takeIf { it.isNotBlank() },
                            name = args.getString("name").orEmpty(),
                            address = args.getString("address")?.takeIf { it.isNotBlank() },
                            city = args.getString("city")?.takeIf { it.isNotBlank() },
                            district = args.getString("district")?.takeIf { it.isNotBlank() },
                            tag = args.getString("tag")?.takeIf { it.isNotBlank() },
                            lat = args.getString("lat")?.toDoubleOrNull()?.takeIf { it != 0.0 },
                            lng = args.getString("lng")?.toDoubleOrNull()?.takeIf { it != 0.0 },
                            province = args.getString("province")?.takeIf { it.isNotBlank() },
                        )
                        val searchEntry = androidx.compose.runtime.remember(target) {
                            navController.getBackStackEntry(ROUTE_SEARCH)
                        }
                        val searchVm: com.visualzyzy.chuxingdazi.ui.search.SearchViewModel =
                            androidx.hilt.navigation.compose.hiltViewModel(searchEntry)
                        com.visualzyzy.chuxingdazi.ui.search.AddPointScreen(
                            item = target,
                            presetName = args.getString("presetName").orEmpty(),
                            viewModel = searchVm,
                            // 新建颜色：S18 草稿调色往返链（与本条目 savedStateHandle 交互）
                            draftColorFlow = entry.savedStateHandle.getStateFlow("s18_draft_color", ""),
                            onConsumeDraftColor = { entry.savedStateHandle["s18_draft_color"] = "" },
                            onPickDraftColor = { initialHex ->
                                navController.navigate(
                                    "s18?categoryId=__draft__&draft=1&initial=${android.net.Uri.encode(initialHex)}",
                                )
                            },
                            onClose = { navController.popBackStack() },
                            onAdded = { coords ->
                                navController.getBackStackEntry(ROUTE_MAP).savedStateHandle
                                    .set("added_coords", coords)
                                navController.popBackStack(ROUTE_MAP, false)
                            },
                        )
                    }
                    composable(
                        // 2026-11-19：分类管理路由 S09 → S09_group_management（用户定调）
                        route = "S09_group_management?editId={editId}",
                        arguments = listOf(
                            androidx.navigation.navArgument("editId") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        CategoryManageScreen(
                            onClose = { navController.popBackStack() },
                            onPickColor = { categoryId ->
                                navController.navigate("s18?categoryId=$categoryId")
                            },
                            // R15/R16（第七轮）：新建分类草稿的调色——S18 草稿模式，
                            // 回传色值经 savedStateHandle，点 S09 的「保存」才落库
                            draftColorFlow = entry.savedStateHandle.getStateFlow("s18_draft_color", ""),
                            onConsumeDraftColor = { entry.savedStateHandle["s18_draft_color"] = "" },
                            onPickDraftColor = { initialHex ->
                                navController.navigate(
                                    "s18?categoryId=__draft__&draft=1&initial=${android.net.Uri.encode(initialHex)}",
                                )
                            },
                        )
                        // 支持 S06「＋新建」直达编辑面板：进入即打开对应分类
                        entry.arguments?.getString("editId")?.let { editId ->
                            if (editId.isNotBlank()) {
                                // 通过 LaunchedEffect 在组合后打开（此处简化：交给屏内 VM 初次读取）
                            }
                        }
                    }
                    composable(
                        route = "s18?categoryId={categoryId}&draft={draft}&initial={initial}",
                        arguments = listOf(
                            androidx.navigation.navArgument("categoryId") {
                                type = androidx.navigation.NavType.StringType
                            },
                            androidx.navigation.navArgument("draft") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            androidx.navigation.navArgument("initial") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) {
                        ColorPickerScreen(
                            onClose = { navController.popBackStack() },
                            // 草稿模式（第七轮 R15/R16）：保存 = 色值写回 S09 的 savedStateHandle 并返回
                            onDraftColor = { hex ->
                                navController.previousBackStackEntry?.savedStateHandle?.set("s18_draft_color", hex)
                                navController.popBackStack()
                            },
                        )
                    }
                    composable(
                        route = "s06?editPlaceId={editPlaceId}&lat={lat}&lng={lng}&keepZoom={keepZoom}",
                        arguments = listOf(
                            androidx.navigation.navArgument("editPlaceId") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            androidx.navigation.navArgument("lat") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            androidx.navigation.navArgument("lng") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            androidx.navigation.navArgument("keepZoom") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        PickPlaceScreen(
                            editPlaceId = entry.arguments?.getString("editPlaceId"),
                            initialLat = entry.arguments?.getString("lat")?.toDoubleOrNull(),
                            initialLng = entry.arguments?.getString("lng")?.toDoubleOrNull(),
                            keepZoom = entry.arguments?.getString("keepZoom") == "true",
                            onDismiss = { navController.popBackStack() },
                            // 第六轮（2026-11-06）：保存成功 → 清掉 pointed_pending（忘记框内信息）再回主地图
                            onSaved = {
                                runCatching {
                                    navController.getBackStackEntry(ROUTE_MAP).savedStateHandle["pointed_pending"] = ""
                                }
                                navController.popBackStack()
                            },
                            onOpenEditor = { placeId ->
                                navController.navigate("s08?placeId=$placeId") {
                                    popUpTo(ROUTE_MAP) { inclusive = false }
                                }
                            },
                            onManageCategories = { navController.navigate(ROUTE_CATEGORY_MANAGE) },
                            // 2026-11-19 新建分类「新建颜色」：S18 草稿调色往返链
                            draftColorFlow = entry.savedStateHandle.getStateFlow("s18_draft_color", ""),
                            onConsumeDraftColor = { entry.savedStateHandle["s18_draft_color"] = "" },
                            onPickDraftColor = { initialHex ->
                                navController.navigate(
                                    "s18?categoryId=__draft__&draft=1&initial=${android.net.Uri.encode(initialHex)}",
                                )
                            },
                        )
                    }
                    composable(
                        route = "s08?placeId={placeId}",
                        arguments = listOf(
                            androidx.navigation.navArgument("placeId") {
                                type = androidx.navigation.NavType.StringType
                            },
                        ),
                    ) { entry ->
                        EditPlaceScreen(
                            onClose = { navController.popBackStack() },
                            onAdjustCoords = { placeId ->
                                navController.navigate("s06?editPlaceId=$placeId")
                            },
                            // 2026-11-19 新建分类「新建颜色」：S18 草稿调色往返链
                            draftColorFlow = entry.savedStateHandle.getStateFlow("s18_draft_color", ""),
                            onConsumeDraftColor = { entry.savedStateHandle["s18_draft_color"] = "" },
                            onPickDraftColor = { initialHex ->
                                navController.navigate(
                                    "s18?categoryId=__draft__&draft=1&initial=${android.net.Uri.encode(initialHex)}",
                                )
                            },
                        )
                    }
                    // M8 行程：S23 列表 / S24 新建 / S14 时间轴
                    composable(ROUTE_TRIP_LIST) {
                        TripListScreen(
                            onClose = { navController.popBackStack() },
                            onOpenTrip = { tripId -> navController.navigate("s14_timeline?tripId=$tripId") },
                            onOpenTripMap = { tripId -> navController.navigate("s26_tripmap?tripId=$tripId") },
                            onNewTrip = { navController.navigate(ROUTE_NEW_TRIP) },
                        )
                    }
                    composable(ROUTE_NEW_TRIP) {
                        NewTripScreen(
                            onClose = { navController.popBackStack() },
                            onCreated = { tripId ->
                                // §7.6：创建 → 直接进这条行程的时间轴，不回列表
                                navController.navigate("s14_timeline?tripId=$tripId") {
                                    popUpTo(ROUTE_TRIP_LIST)
                                }
                            },
                        )
                    }
                    composable(
                        route = "s14_timeline?tripId={tripId}",
                        arguments = listOf(
                            androidx.navigation.navArgument("tripId") {
                                type = androidx.navigation.NavType.StringType
                            },
                        ),
                    ) { entry ->
                        TripTimelineScreen(
                            tripId = entry.arguments?.getString("tripId").orEmpty(),
                            onClose = { navController.popBackStack() },
                            onOpenTripMap = { tripId ->
                                navController.navigate("s26_tripmap?tripId=$tripId")
                            },
                            onWalkThisDay = { tripId, date, originFromSchedule ->
                                navController.navigate(
                                    "s10_route?fromTripId=$tripId&date=$date&originFromSchedule=$originFromSchedule",
                                )
                            },
                        )
                    }
                    // M9 路线：S10 路线规划 / S11 选择途经点 / S26 行程地图
                    composable(
                        route = "s10_route?fromTripId={fromTripId}&date={date}&destPlaceId={destPlaceId}" +
                            "&originFromSchedule={originFromSchedule}",
                        arguments = listOf(
                            androidx.navigation.navArgument("fromTripId") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            androidx.navigation.navArgument("date") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            androidx.navigation.navArgument("destPlaceId") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            androidx.navigation.navArgument("originFromSchedule") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) {
                        RouteScreen(
                            onClose = { navController.popBackStack() },
                            onOpenWaypointPicker = { navController.navigate(ROUTE_WAYPOINTS) },
                            onOpenPointSearch = { target ->
                                navController.navigate("s21_point_search?target=$target")
                            },
                            onStartNavigation = { _ ->
                                android.widget.Toast
                                    .makeText(applicationContext, "导航将在下一步接入", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            },
                            onPreview = { navController.navigate(ROUTE_PREVIEW) },
                        )
                    }
                    // S30 路线预览（决策 #96–#99，2026-11-15）：S10「预览」进入，数据经 RoutePreviewStore 快照
                    composable(ROUTE_PREVIEW) {
                        RoutePreviewScreen(onClose = { navController.popBackStack() })
                    }
                    composable(ROUTE_WAYPOINTS) {
                        SelectWaypointsScreen(
                            onClose = { navController.popBackStack() },
                            onDone = { navController.popBackStack() },
                        )
                    }
                    // S21 地图搜点（起点/终点）
                    composable(
                        route = "s21_point_search?target={target}",
                        arguments = listOf(
                            androidx.navigation.navArgument("target") {
                                type = androidx.navigation.NavType.StringType
                                defaultValue = "origin"
                            },
                        ),
                    ) { entry ->
                        PointSearchScreen(
                            target = entry.arguments?.getString("target") ?: "origin",
                            onClose = { navController.popBackStack() },
                        )
                    }
                    // M11：S13 设置 / S27 清单列表
                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            onClose = { navController.popBackStack() },
                            onOpenAk = { navController.navigate(ROUTE_AK) },
                            onOpenLists = { navController.navigate(ROUTE_LISTS) },
                            onOpenCategories = { navController.navigate(ROUTE_CATEGORY_MANAGE) },
                            onOpenMarkers = { navController.navigate(ROUTE_MARKERS) },
                        )
                    }
                    composable(ROUTE_LISTS) {
                        ListsScreen(
                            onClose = { navController.popBackStack() },
                            // R25（第七轮）：S27 点清单 → S28 清单详情
                            onOpenList = { listId ->
                                navController.navigate("s28_listdetail?listId=$listId")
                            },
                        )
                    }
                    // S28 清单详情（第七轮 R25）：添加/移出清单点位 + 搜索选点
                    composable(
                        route = "s28_listdetail?listId={listId}",
                        arguments = listOf(
                            androidx.navigation.navArgument("listId") {
                                type = androidx.navigation.NavType.StringType
                            },
                        ),
                    ) {
                        com.visualzyzy.chuxingdazi.ui.list.ListDetailScreen(
                            onClose = { navController.popBackStack() },
                        )
                    }
                    // P76（2026-11-05）S29 标记管理（设置 → 点位显示）
                    // 2026-11-21 字段模板升级：点标记行改跳 S08 编辑点位页（原底部编辑面板废除）
                    composable(ROUTE_MARKERS) {
                        com.visualzyzy.chuxingdazi.ui.markers.MarkerManageScreen(
                            onClose = { navController.popBackStack() },
                            onEditPlace = { placeId ->
                                navController.navigate("s08?placeId=$placeId")
                            },
                        )
                    }
                    composable(
                        route = "s26_tripmap?tripId={tripId}",
                        arguments = listOf(
                            androidx.navigation.navArgument("tripId") {
                                type = androidx.navigation.NavType.StringType
                            },
                        ),
                    ) { entry ->
                        TripMapScreen(
                            onClose = { navController.popBackStack() },
                        )
                    }
                }
                }
            }
        }
    }

    private companion object {
        const val ROUTE_PRIVACY = "s01_privacy"
        const val ROUTE_AK = "s02_ak"
        const val ROUTE_MAP = "s03_map"
        const val ROUTE_SEARCH = "s05_search"
        // 2026-11-19（用户定调）：分类管理路由 S09 → S09_group_management。
        // P74 教训仍在：常量必须与注册路由 "S09_group_management?editId={editId}" 的基名一致，
        // 否则 navigate 抛 IllegalArgumentException（设置/S06 进分类管理闪退）
        const val ROUTE_CATEGORY_MANAGE = "S09_group_management"
        const val ROUTE_TRIP_LIST = "s23_trips"
        const val ROUTE_NEW_TRIP = "s24_new_trip"
        const val ROUTE_ROUTE = "s10_route"
        const val ROUTE_PREVIEW = "route_preview"
        const val ROUTE_WAYPOINTS = "s11_waypoints"
        const val ROUTE_SETTINGS = "s13_settings"
        // 第九轮（2026-11-08）：S27/S29 路由更名（s27_lists → s27_lists_management，s29_markers → s29_markers_management）
        const val ROUTE_LISTS = "s27_lists_management"
        // P76（2026-11-05）S29 标记管理
        const val ROUTE_MARKERS = "s29_markers_management"

        // 第六轮（2026-11-06）：pointed_information 信息在 savedStateHandle 上的编解码（"|" 分隔 + Uri 编码）
        fun encodePointedPending(p: com.visualzyzy.chuxingdazi.ui.map.PointedPending): String =
            listOf(
                p.lat.toString(),
                p.lng.toString(),
                android.net.Uri.encode(p.name),
                android.net.Uri.encode(p.semantic),
                android.net.Uri.encode(p.address),
                android.net.Uri.encode(p.poiName),
            ).joinToString("|")

        fun parsePointedPending(raw: String): com.visualzyzy.chuxingdazi.ui.map.PointedPending? {
            if (raw.isBlank()) return null
            val parts = raw.split("|")
            if (parts.size < 6) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lng = parts[1].toDoubleOrNull() ?: return null
            return com.visualzyzy.chuxingdazi.ui.map.PointedPending(
                lat = lat,
                lng = lng,
                name = android.net.Uri.decode(parts[2]),
                semantic = android.net.Uri.decode(parts[3]),
                address = android.net.Uri.decode(parts[4]),
                poiName = android.net.Uri.decode(parts[5]),
            )
        }
    }
}
