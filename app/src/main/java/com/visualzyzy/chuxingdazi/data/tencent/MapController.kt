package com.visualzyzy.chuxingdazi.data.tencent

import android.content.Context
import android.graphics.BitmapFactory
import android.location.Location
import android.util.Log
import com.tencent.map.geolocation.TencentLocation
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.LocationSource
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds
import com.tencent.tencentmap.mapsdk.maps.model.MapPoi
import com.tencent.tencentmap.mapsdk.maps.model.Marker
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions
import com.tencent.tencentmap.mapsdk.maps.model.MyLocationStyle
import com.tencent.tencentmap.mapsdk.maps.model.TencentMapGestureListener
import com.visualzyzy.chuxingdazi.R
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.prefs.FilterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 腾讯地图 + 定位的薄封装（设计文档 §8.2 data/tencent 层，2026-11-17 腾讯版重写）。
 * UI 层只依赖本类与 [MapHost]，不直接接触腾讯类型（LatLng 等在参数里出现是可接受的边界）。
 *
 * 与百度版实现差异（公开 API 保持一致，UI 层只换 import）：
 * - 蓝点：地图 SDK 不含定位（官方注意事项），必须外部喂 —— TencentLocationManager
 *   持续定位 → LocationSource.OnLocationChangedListener 喂 android Location。
 * - 相机状态 = getCameraPosition()（百度 mapStatus.target）。
 * - 手势开始检测 = TencentMapGestureListener.onScroll（百度 REASON_GESTURE）。
 */
/** 渲染上下文（第十六轮：相机停稳后碰撞分组用）。 */
private data class MapControllerRenderContext(
    val places: List<PlaceEntity>,
    val categoriesById: Map<String, CategoryEntity>,
    val selectedPlaceId: String?,
    val filter: FilterState,
)

class MapController(context: Context) {

    private val appContext = context.applicationContext
    private val markerRenderer = MarkerRenderer(appContext)
    private var mapView: MapView? = null
    private var removeGeoObserver: (() -> Unit)? = null
    private var firstFixDispatched = false

    // 第二十四轮（2026-11-14）：最近一次有效定位的坐标与时刻（elapsedRealtime）。
    // onFirstFix 只发一次（供首次把相机移过去），不能当「定位在持续更新」的信号——
    // 二次点定位时它不再触发，旧实现拿它取消 5 秒提示导致「定位明明在跑却提示失败」。
    @Volatile var lastValidFixAt = 0L
        private set
    @Volatile private var lastFixLat = 0.0
    @Volatile private var lastFixLng = 0.0
    private val placeMarkers = mutableMapOf<String, Marker>()
    private val clusterMarkers = mutableListOf<Marker>()

    // ---- 第十六轮（近距离叠点）：最近一次渲染的上下文，相机停稳后做屏幕碰撞分组 ----
    private var renderContext: MapControllerRenderContext? = null
    /** 碰撞分组产生的组标记（聚合气泡 / 叠点），每轮全清重建（幂等修复：此前逐轮累积）。 */
    private val groupMarkers = mutableListOf<Marker>()
    /** 组标记 → (canSeparate, 组内 placeId)。canSeparate=false = 放大也分不开的叠点。 */
    private val groupMarkerInfo = mutableMapOf<Marker, Pair<Boolean, List<String>>>()
    /** 散开态：这些点位按真位置小圆点渲染（不做碰撞分组）。 */
    private var spreadPlaceIds: Set<String> = emptySet()

    /** 第一次拿到定位时回调（用于把相机移过去）。 */
    var onFirstFix: ((lat: Double, lng: Double) -> Unit)? = null

    /** 点击点位标记（S03 → S07）。 */
    var onPlaceMarkerClick: ((placeId: String) -> Unit)? = null

    /**
     * 第十六轮（近距离叠点）：点击「放到最大也分不开」的叠点标记 → 回传组内点位 id，
     * 屏层进入散开态（真位置小圆点 + 圆周散开图标 + 底部列表）。
     */
    var onStackTap: ((placeIds: List<String>) -> Unit)? = null

    /** 长按地图（S03 → S06）。 */
    var onMapLongClick: ((lat: Double, lng: Double) -> Unit)? = null

    /**
     * 单击地图（第六轮 2026-11-06，pointed_information）。
     * poiName 非空 = 点到了地图上的 POI 兴趣点图标（附带名称）。
     */
    var onMapClick: ((lat: Double, lng: Double, poiName: String?) -> Unit)? = null

    /** 相机静止（拖动/缩放手势结束后；S03 持久化相机位置、S06 触发防抖逆地理）。 */
    var onCameraIdle: ((lat: Double, lng: Double, zoom: Float) -> Unit)? = null

    /** 手势拖动/缩放开始（编程动画不触发；S06 用它取消已排队的逆地理）。 */
    var onCameraGestureStarted: (() -> Unit)? = null

    /** 定位回调发现城市（S05 联想按城市检索用）。 */
    var onCityKnown: ((city: String) -> Unit)? = null

    // ---- 蓝点数据桥（地图 SDK 不含定位）：TencentLocationManager → LocationSource ----

    private var myLocationChangedListener: LocationSource.OnLocationChangedListener? = null

    private val myLocationSource = object : LocationSource {
        override fun activate(listener: LocationSource.OnLocationChangedListener) {
            myLocationChangedListener = listener
            // 若定位已在跑（返回 S03 恢复蓝点），把最近一次定位立即喂给蓝点层
            if (lastValidFixAt > 0L) {
                feedMyLocation(lastFixLat, lastFixLng, Float.MAX_VALUE)
            }
        }

        override fun deactivate() {
            myLocationChangedListener = null
        }
    }

    private fun feedMyLocation(lat: Double, lng: Double, accuracy: Float) {
        val listener = myLocationChangedListener ?: return
        runCatching {
            val loc = Location("tencent")
            loc.latitude = lat
            loc.longitude = lng
            if (accuracy != Float.MAX_VALUE) loc.accuracy = accuracy
            loc.time = System.currentTimeMillis()
            listener.onLocationChanged(loc)
        }
    }

    fun attach(view: MapView) {
        mapView = view
        val map = view.map
        // P45（2026-11-03）问题2：关掉 SDK 自带的 +/- 缩放控件（全 App 只保留自绘一层）
        runCatching { map.uiSettings.setZoomControlsEnabled(false) }
        // 2026-11-19 用户定调：隐藏 3D 建筑物
        runCatching { map.setBuilding3dEffectEnable(false) }
        // 蓝点样式与数据源（SDK 不含定位，全部来自 TencentLocationManager 喂数据）。
        // 2026-11-19 用户定调：定位点展示模式 = 定位点旋转、地图不随定位居中
        //（LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER；AAR 6.15.1 已核对该常量存在）
        runCatching {
            map.setMyLocationStyle(
                MyLocationStyle().myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER),
            )
            map.setLocationSource(myLocationSource)
            map.setMyLocationEnabled(true)
        }
        map.setOnMarkerClickListener { marker ->
            if (clusterMarkers.any { it === marker }) {
                // 旧网格聚合（>60 点模式）：放大两级
                val zoom = (map.cameraPosition.zoom + 2f).coerceAtMost(21f)
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, zoom))
                return@setOnMarkerClickListener true
            }
            groupMarkerInfo[marker]?.let { (canSeparate, ids) ->
                if (canSeparate) {
                    // 聚合气泡（还能放大散开的组）→ 以组质心为中心放大三级。
                    // 第十六轮实测（百度版）：bounds 对角≈0 会被 SDK 静默忽略；
                    // 固定三级对「图标已相交」的近距组足以散开，且不依赖 bounds 合法性。
                    val members = renderContext?.places?.filter { it.id in ids }.orEmpty()
                    if (members.isNotEmpty()) {
                        val cLat = members.map { it.lat }.average()
                        val cLng = members.map { it.lng }.average()
                        val zoom = (map.cameraPosition.zoom + 3f).coerceAtMost(21f)
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(cLat, cLng), zoom),
                        )
                    }
                } else {
                    // 叠点（放大也分不开）→ 通知屏层散开
                    onStackTap?.invoke(ids)
                }
                return@setOnMarkerClickListener true
            }
            val placeId = placeMarkers.entries.firstOrNull { it.value === marker }?.key
            if (placeId != null) onPlaceMarkerClick?.invoke(placeId)
            true
        }
        map.setOnMapLongClickListener { coord ->
            onMapLongClick?.invoke(coord.latitude, coord.longitude)
        }
        // 第六轮（2026-11-06）：单击地图 → pointed_information（非拖动、非长按）
        map.setOnMapClickListener { point ->
            onMapClick?.invoke(point.latitude, point.longitude, null)
        }
        map.setOnMapPoiClickListener { poi: MapPoi ->
            poi.position?.let {
                onMapClick?.invoke(it.latitude, it.longitude, poi.name?.takeIf { n -> n.isNotBlank() })
            }
        }
        map.setOnCameraChangeListener(object : TencentMap.OnCameraChangeListener {
            override fun onCameraChange(position: com.tencent.tencentmap.mapsdk.maps.model.CameraPosition?) = Unit
            override fun onCameraChangeFinished(position: com.tencent.tencentmap.mapsdk.maps.model.CameraPosition?) {
                position?.target?.let { onCameraIdle?.invoke(it.latitude, it.longitude, position.zoom) }
                // 第十六轮：相机停稳 → 按屏幕图标矩形相交重新分组（聚合气泡/叠点）
                regroupForCamera()
            }
        })
        // 手势开始（S06 取消已排队逆地理；腾讯无 REASON_GESTURE，用 gesture listener 的 onScroll）
        map.addTencentMapGestureListener(object : TencentMapGestureListener {
            override fun onDoubleTap(x: Float, y: Float) = false
            override fun onSingleTap(x: Float, y: Float) = false
            override fun onFling(vx: Float, vy: Float): Boolean {
                onCameraGestureStarted?.invoke()
                return false
            }

            override fun onScroll(distanceX: Float, distanceY: Float): Boolean {
                onCameraGestureStarted?.invoke()
                return false
            }

            override fun onLongPress(x: Float, y: Float) = false
            override fun onDown(x: Float, y: Float) = false
            override fun onUp(x: Float, y: Float) = false
            override fun onTwoFingerMoveAgainst(
                status: com.tencent.tencentmap.mapsdk.maps.model.TencentMapGestureListener.TwoFingerMoveAgainstStatus,
                position: com.tencent.tencentmap.mapsdk.maps.model.CameraPosition?,
            ): Boolean {
                onCameraGestureStarted?.invoke()
                return false
            }

            override fun onMapStable() = Unit
            override fun onMapStableBy(trigger: com.tencent.tencentmap.mapsdk.maps.model.CameraPosition.Trigger?) = Unit
        })
    }

    /** 必须在 MapView 销毁前调用（设计文档 §8.3：泄漏/崩溃高发点）。 */
    fun detach() {
        stopLocate()
        stopMarquee()
        marqueeTargets.clear()
        tappedMarker = null
        tempMarker = null
        mapView?.map?.setMyLocationEnabled(false)
        mapView = null
    }

    fun resume() = mapView?.onResume()
    fun pause() = mapView?.onPause()

    fun moveTo(lat: Double, lng: Double, zoom: Float = 16f) {
        mapView?.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom))
    }

    fun zoomIn() = zoomBy(+1)
    fun zoomOut() = zoomBy(-1)

    /**
     * 渲染全部点位标记（§6 S03 + §6 S04 筛选联动）。
     * 全量清除后重建；聚合开启（AUTO 且 >60 / ON）时按 80dp 屏幕网格分组，≥3 个合并为数字气泡。
     * filter.showMarkers = false（P22 显示标记总开关，2026-11-02）时只清除不重建。
     * 第六轮（2026-11-06）：名称超 12 汉字宽的标记登记进跑马灯（labelMarquee），定时滚动。
     */
    fun renderPlaces(
        places: List<PlaceEntity>,
        categoriesById: Map<String, CategoryEntity>,
        selectedPlaceId: String?,
        filter: FilterState = FilterState(),
    ) {
        // 第十六轮：记录渲染上下文（相机停稳后的碰撞分组要用同一份输入）
        renderContext = MapControllerRenderContext(places, categoriesById, selectedPlaceId, filter)
        if (!filter.showMarkers) {
            clearAllPlaceMarkers()
            marqueeTargets.removeAll { it.marker === tempMarker }
            stopMarquee()
            return
        }
        renderAllMarkers()
    }

    /** 清全部点位/组标记（跑马灯登记同步清；临时标记登记保留，P85）。 */
    private fun clearAllPlaceMarkers() {
        placeMarkers.values.forEach { runCatching { it.remove() } }
        placeMarkers.clear()
        clusterMarkers.forEach { runCatching { it.remove() } }
        clusterMarkers.clear()
        groupMarkers.forEach { runCatching { it.remove() } }
        groupMarkers.clear()
        groupMarkerInfo.clear()
        marqueeTargets.removeAll { it.marker !== tempMarker }
    }

    private fun sizeDpOf(filter: FilterState): Int =
        when (filter.markerStyle) {
            FilterState.STYLE_DOT ->
                (16 * filter.markerSizePercent / 100f).roundToInt()
            else ->
                (26 * filter.markerSizePercent / 100f).roundToInt()
        }.coerceAtLeast(8)

    private fun addSinglePlaceMarker(
        place: PlaceEntity,
        map: TencentMap,
        filter: FilterState,
        selectedPlaceId: String?,
        categoriesById: Map<String, CategoryEntity>,
        sizeDp: Int,
    ) {
        val category = categoriesById[place.categoryId]
        val colorHex = category?.color ?: "#64748B"
        val iconKey = place.iconKey ?: category?.iconKey
        // 第十六轮：散开态成员按真位置小圆点渲染（叠点组的「真位置留一个小圆点」）
        val isSpread = place.id in spreadPlaceIds
        val label = if (filter.showLabels && !isSpread) place.name else ""
        val rendered = markerRenderer.getMarkerBitmap(
            colorHex = colorHex,
            iconKey = iconKey,
            label = label,
            sizeDp = if (isSpread) 14 else sizeDp,
            selected = place.id == selectedPlaceId,
            markerStyle = if (isSpread) FilterState.STYLE_DOT else filter.markerStyle,
        )
        val marker = map.addMarker(
            MarkerOptions(LatLng(place.lat, place.lng))
                .icon(com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory.fromBitmap(rendered.bitmap))
                .anchor(0.5f, rendered.anchorRatio)
                .zIndex(if (place.id == selectedPlaceId) 20f else 0f),
        )
        marker?.let {
            placeMarkers[place.id] = it
            if (markerRenderer.labelScrollRange(label) > 0f) {
                marqueeTargets.add(
                    MarqueeTarget(
                        label = label,
                        colorHex = colorHex,
                        iconKey = iconKey,
                        sizeDp = sizeDp,
                        selected = place.id == selectedPlaceId,
                        markerStyle = filter.markerStyle,
                        marker = it,
                    ),
                )
            }
        }
    }

    /**
     * 完整重建全部点位标记 + 屏幕碰撞分组。
     * 第十六轮幂等修复：每轮全清重建——此前 regroup 只增不清，相机每停稳一次就多一枚
     * 组标记（stackSize=6 实锤），点击命中的是已被 remove 的旧对象，散开永远不触发。
     */
    private fun renderAllMarkers() {
        val ctx = renderContext ?: return
        val map = mapView?.map ?: return
        clearAllPlaceMarkers()
        val clusterActive = when (ctx.filter.clusterMode) {
            FilterState.CLUSTER_ON -> true
            FilterState.CLUSTER_OFF -> false
            else -> ctx.places.size > 60
        }
        val sizeDp = sizeDpOf(ctx.filter)
        if (clusterActive && ctx.places.size >= 3) {
            // 简易网格聚合：按当前屏幕投影分 80dp 网格，同格 ≥3 合并为数字气泡（§6 S04）
            try {
                val projection = map.projection
                val gridPx = 80 * appContext.resources.displayMetrics.density
                val groups = LinkedHashMap<String, MutableList<PlaceEntity>>()
                ctx.places.forEach { place ->
                    val screen = projection.toScreenLocation(LatLng(place.lat, place.lng))
                    val key = "${(screen.x / gridPx).toInt()}_${(screen.y / gridPx).toInt()}"
                    groups.getOrPut(key) { mutableListOf() }.add(place)
                }
                groups.values.forEach { group ->
                    if (group.size >= 3) {
                        val cLat = group.map { it.lat }.average()
                        val cLng = group.map { it.lng }.average()
                        val clusterBitmap = markerRenderer.getClusterBitmap(group.size)
                        val marker = map.addMarker(
                            MarkerOptions(LatLng(cLat, cLng))
                                .icon(com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory.fromBitmap(clusterBitmap))
                                .anchor(0.5f, 0.5f)
                                .zIndex(50f),
                        )
                        marker?.let { clusterMarkers.add(it) }
                    } else {
                        group.forEach {
                            addSinglePlaceMarker(it, map, ctx.filter, ctx.selectedPlaceId, ctx.categoriesById, sizeDp)
                        }
                    }
                }
            } catch (e: Exception) {
                // 投影异常兜底：全量正常渲染
                ctx.places.forEach {
                    addSinglePlaceMarker(it, map, ctx.filter, ctx.selectedPlaceId, ctx.categoriesById, sizeDp)
                }
            }
            startMarqueeIfNeeded()
            return
        }
        ctx.places.forEach {
            addSinglePlaceMarker(it, map, ctx.filter, ctx.selectedPlaceId, ctx.categoriesById, sizeDp)
        }
        // 第十六轮：屏幕碰撞分组（图标矩形相交 → 聚合气泡 / 叠点）
        regroupOnScreen(map, ctx, sizeDp)
        startMarqueeIfNeeded()
    }

    /**
     * 相机停稳后重算碰撞分组（第十六轮，近距离叠点）。幂等：内部走 [renderAllMarkers]
     * 全量重建。地理坐标不改，分组只影响画法与点击；筛选/散开态先过滤再分组。
     */
    fun regroupForCamera() {
        val ctx = renderContext ?: return
        if (!ctx.filter.showMarkers) return
        val clusterActive = when (ctx.filter.clusterMode) {
            FilterState.CLUSTER_ON -> true
            FilterState.CLUSTER_OFF -> false
            else -> ctx.places.size > 60
        }
        if (clusterActive) return // 网格聚合模式下不做碰撞分组
        renderAllMarkers()
    }

    /**
     * 把屏幕上图标矩形相交的单点合并为组标记（renderAllMarkers 重建后调用）：
     * - 组内 ≥2 且放大还能分开（组内最大间距 > 最大级下图标覆盖的地面距离）→ 聚合气泡，
     *   点击 = 组质心 + 放大三级散开；
     * - 组内 ≥2 且放大也分不开（约 ≤1 米）→ 叠点，点击 = onStackTap 散开。
     */
    private fun regroupOnScreen(
        map: TencentMap,
        ctx: MapControllerRenderContext,
        sizeDp: Int,
    ) {
        val projection = try {
            map.projection
        } catch (e: Exception) {
            return
        }
        val density = appContext.resources.displayMetrics.density
        val iconHalfPx = sizeDp * density / 2f
        val visible = ctx.places.filter { it.id !in spreadPlaceIds }
        if (visible.isEmpty()) return
        val screenOf = HashMap<String, android.graphics.Point>()
        visible.forEach { place ->
            try {
                screenOf[place.id] = projection.toScreenLocation(LatLng(place.lat, place.lng))
            } catch (e: Exception) {
                // 投影失败（可能地图刚重建）：跳过本轮
            }
        }
        val ids = visible.map { it.id }.filter { screenOf.containsKey(it) }
        // 并查集：图标矩形相交 → 同组
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var root = x
            while (parent[root] != root) root = parent[root]!!
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]!!
                parent[cur] = root
                cur = next
            }
            return root
        }
        ids.forEach { parent[it] = it }
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = screenOf[ids[i]]!!
            val b = screenOf[ids[j]]!!
            val hit = kotlin.math.abs(a.x - b.x) < iconHalfPx * 2 &&
                kotlin.math.abs(a.y - b.y) < iconHalfPx * 2
            if (hit) {
                val ra = find(ids[i])
                val rb = find(ids[j])
                if (ra != rb) parent[rb] = ra
            }
        }
        val groups = ids.groupBy { find(it) }.values
        var changed = false
        groups.forEach { group ->
            if (group.size < 2) return@forEach
            val members = visible.filter { it.id in group }
            // 组内最大地面间距（米）
            var spreadM = 0.0
            for (a in members) for (b in members) {
                val d = distanceMeters(a.lat, a.lng, b.lat, b.lng)
                if (d > spreadM) spreadM = d
            }
            // zoom 21 时一枚图标覆盖的地面宽度（米）——放大到头也分不开的阈值
            val metersPerPixel21 = 156543.03392 * kotlin.math.cos(Math.toRadians(members[0].lat)) / (1 shl 21)
            val iconGroundM = metersPerPixel21 * sizeDp * density
            changed = true
            // 移除组内单点标记，换组标记（先清跑马灯登记再移除标记）
            members.forEach { m ->
                val marker = placeMarkers.remove(m.id)
                if (marker != null) {
                    marqueeTargets.removeAll { t -> t.marker === marker }
                    runCatching { marker.remove() }
                }
            }
            val cLat = members.map { it.lat }.average()
            val cLng = members.map { it.lng }.average()
            val canSeparate = spreadM > iconGroundM
            val bitmap = markerRenderer.getClusterBitmap(members.size)
            val marker = map.addMarker(
                MarkerOptions(LatLng(cLat, cLng))
                    .icon(com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory.fromBitmap(bitmap))
                    .anchor(0.5f, 0.5f)
                    .zIndex(if (canSeparate) 50f else 60f),
            ) ?: return@forEach
            groupMarkers.add(marker)
            groupMarkerInfo[marker] = canSeparate to members.map { it.id }
        }
        if (changed) {
            stopMarquee()
            // 组标记重建后登记跑马灯的只剩单点标签——重新启动（若还有）
            startMarqueeIfNeeded()
        }
    }

    /** 两点球面距离（米）。 */
    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = kotlin.math.sin(dLat / 2).pow(2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2).pow(2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    /**
     * 第十六轮：散开态——组内点位按真位置渲染成小圆点（不再参与碰撞分组）。
     * null = 退出散开态恢复常规渲染。
     */
    fun setSpreadGroup(placeIds: List<String>?) {
        spreadPlaceIds = placeIds?.toSet() ?: emptySet()
        val ctx = renderContext ?: return
        renderPlaces(ctx.places, ctx.categoriesById, ctx.selectedPlaceId, ctx.filter)
    }

    /** 散开态下：这些点位在屏幕上的位置（Compose 散开层画圆周图标/连线用；屏幕坐标=mapView 内）。 */
    fun screenPositionsOf(placeIds: List<String>): List<Triple<String, Int, Int>> {
        val map = mapView?.map ?: return emptyList()
        val projection = try {
            map.projection
        } catch (e: Exception) {
            return emptyList()
        }
        val ctx = renderContext ?: return emptyList()
        return placeIds.mapNotNull { id ->
            val place = ctx.places.firstOrNull { it.id == id } ?: return@mapNotNull null
            val pt = try {
                projection.toScreenLocation(LatLng(place.lat, place.lng))
            } catch (e: Exception) {
                return@mapNotNull null
            }
            Triple(id, pt.x, pt.y)
        }
    }

    /** 散开态成员（id 顺序保持传入）。 */
    fun spreadMembers(): List<String> = spreadPlaceIds.toList()

    /** S07：把点位移到"卡片上方可视区中央"（§6 S07）。
     *  第十六轮：比例尺保持街道级（≤17 级，约 100m）——不再放大一级推到 5 米，
     *  避免详情卡打开时地图几乎空白、标记缩成小点。 */
    fun focusPlace(lat: Double, lng: Double, bottomPaddingPx: Int) {
        val map = mapView?.map ?: return
        if (bottomPaddingPx > 0) map.setPadding(0, 0, 0, bottomPaddingPx)
        val zoom = map.cameraPosition.zoom
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), (zoom + 1).coerceAtMost(17f)))
    }

    fun clearFocusPadding() {
        mapView?.map?.setPadding(0, 0, 0, 0)
    }

    /** 设置底部 ViewPadding（S06：让地图逻辑中心 = 准星位置）。 */
    fun setViewPadding(bottomPx: Int) {
        mapView?.map?.setPadding(0, 0, 0, bottomPx)
    }

    /** 屏幕坐标（view 内）→ 经纬度（S06 诊断/准星换算用）。 */
    fun screenPointToLatLng(x: Int, y: Int): LatLng? {
        val map = mapView?.map ?: return null
        return runCatching { map.projection.fromScreenLocation(android.graphics.Point(x, y)) }.getOrNull()
    }

    /**
     * 第十二轮（2026-11-11）：多段动画对位——把屏幕点 (x,y) 精确对准 target（suspend，协程内调用）。
     * 原百度版实测：程序化 setMapStatus 后 SDK 的 projection / status.target 与实际渲染
     * 不同步（误差可达数百米），只有手势/动画路径才同步。因此对位改为动画 + 收敛循环：
     *   第一段动画把 target 移到渲染中心 → 每轮：动画结束后（投影已同步）实测准星处与
     *   target 的偏移 → 下一段动画把渲染中心平移该偏移（fixed = centerNow + (target − cur)），
     *   最多 3 轮、残差 <9m 即停。准星处最终精确等于 target。
     * 腾讯版保留同一收敛循环（是否同样存在不同步待真机复核；即使 SDK 同步正确，
     * 该循环也会在第一轮收敛判据命中后立即返回，无副作用）。
     */
    suspend fun moveSoScreenPointIsAt(x: Int, y: Int, target: LatLng, zoom: Float) {
        val map = mapView?.map ?: return
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
        kotlinx.coroutines.delay(800)
        repeat(3) {
            val cur = runCatching { map.projection.fromScreenLocation(android.graphics.Point(x, y)) }.getOrNull()
                ?: return
            val centerNow = map.cameraPosition.target ?: return
            val dLat = target.latitude - cur.latitude
            val dLng = target.longitude - cur.longitude
            // 收敛判据：残差 < 8e-5°（≈9m）即停——准星视觉判定精度以内
            if (kotlin.math.abs(dLat) < 8e-5 && kotlin.math.abs(dLng) < 8e-5) return
            val fixed = LatLng(
                centerNow.latitude + dLat,
                centerNow.longitude + dLng,
            )
            map.animateCamera(CameraUpdateFactory.newLatLng(fixed))
            kotlinx.coroutines.delay(800)
        }
    }

    /** 第十六轮：S05b/S06 叠点点击——缩放到组内点位刚好散开的视野。 */
    fun fitBoundsGroup(latLngs: List<LatLng>) {
        val map = mapView?.map ?: return
        if (latLngs.isEmpty()) return
        val builder = LatLngBounds.Builder()
        latLngs.forEach { builder.include(it) }
        val d = appContext.resources.displayMetrics.density
        val pad = (120 * d).toInt()
        map.animateCamera(
            CameraUpdateFactory.newLatLngBoundsRect(builder.build(), pad, pad, pad, pad),
        )
    }

    /** S05 批量添加后：缩放到包含全部新点位的视野。 */
    fun fitBounds(latLngs: List<LatLng>, paddingPx: Int) {
        if (latLngs.isEmpty()) return
        val builder = LatLngBounds.Builder()
        latLngs.forEach { builder.include(it) }
        val map = mapView?.map ?: return
        val d = appContext.resources.displayMetrics.density
        val pad = (paddingPx * d).toInt()
        map.animateCamera(
            CameraUpdateFactory.newLatLngBoundsRect(builder.build(), pad, pad, pad, pad),
        )
    }

    // ---- 第六轮（2026-11-06）：名称标签跑马灯（超 12 汉字宽的标签在框内滚动）----

    /** 名称标签超宽、需要滚动的标记（点位标记与临时标记统一登记）。 */
    private data class MarqueeTarget(
        val label: String,
        val colorHex: String,
        val iconKey: String?,
        val sizeDp: Int,
        val selected: Boolean,
        val markerStyle: String,
        val marker: Marker,
    )

    private val marqueeTargets = mutableListOf<MarqueeTarget>()
    private val marqueeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var marqueeJob: Job? = null
    private var marqueePhasePx = 0f
    private var marqueeHoldTicks = 0
    private var marqueeAtEnd = false

    private fun startMarqueeIfNeeded() {
        if (marqueeTargets.isEmpty()) {
            stopMarquee()
            return
        }
        if (marqueeJob?.isActive == true) return
        marqueePhasePx = 0f
        marqueeHoldTicks = 6
        marqueeAtEnd = false
        marqueeJob = marqueeScope.launch {
            val density = appContext.resources.displayMetrics.density
            while (isActive) {
                delay(120)
                tickMarquee(stepPx = 2.4f * density)
            }
        }
    }

    private fun stopMarquee() {
        marqueeJob?.cancel()
        marqueeJob = null
        marqueePhasePx = 0f
        marqueeHoldTicks = 0
        marqueeAtEnd = false
    }

    private fun tickMarquee(stepPx: Float) {
        if (marqueeTargets.isEmpty()) return
        val globalMax = marqueeTargets.maxOf { markerRenderer.labelScrollRange(it.label) }
        if (globalMax <= 0f) return
        if (marqueeHoldTicks > 0) {
            marqueeHoldTicks--
            if (marqueeHoldTicks == 0 && marqueeAtEnd) {
                marqueePhasePx = 0f
                marqueeAtEnd = false
                marqueeHoldTicks = 6 // 回到开头再停 0.7 秒左右
            }
        } else {
            marqueePhasePx = (marqueePhasePx + stepPx).coerceAtMost(globalMax)
            if (marqueePhasePx >= globalMax) {
                marqueeAtEnd = true
                marqueeHoldTicks = 10 // 滚到末尾停 1.2 秒左右
            }
        }
        marqueeTargets.forEach { target ->
            val range = markerRenderer.labelScrollRange(target.label)
            val rendered = markerRenderer.getMarkerBitmap(
                colorHex = target.colorHex,
                iconKey = target.iconKey,
                label = target.label,
                sizeDp = target.sizeDp,
                selected = target.selected,
                markerStyle = target.markerStyle,
                labelScrollPx = marqueePhasePx.coerceAtMost(range),
            )
            runCatching {
                target.marker.setIcon(
                    com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory.fromBitmap(rendered.bitmap),
                )
            }
        }
    }

    /** S05/S05b「在地图上查看」：临时标记（不入库，青蓝主色），同一时间只有一个。
     *  第六轮（2026-11-06）：selected=false——不再画选中态的半透明光晕。 */
    private var tempMarker: Marker? = null

    fun showTempMarker(lat: Double, lng: Double, name: String) {
        renderTempPin(lat, lng, label = name)
        moveToImmediate(lat, lng, 17f)
    }

    /**
     * S06 选点预览（P16.3，2026-11-02）：准星处实时画一枚水滴标记（ICON 样式渲染），
     * 让「正在标记的点位」直接以水滴样式呈现；只更新标记，**不移动相机**。
     */
    fun showPickPin(lat: Double, lng: Double) {
        renderTempPin(lat, lng, label = "")
    }

    private fun renderTempPin(lat: Double, lng: Double, label: String) {
        val map = mapView?.map ?: return
        runCatching { tempMarker?.remove() }
        val rendered = markerRenderer.getMarkerBitmap(
            colorHex = "#0E7490",
            iconKey = "other_point",
            label = label,
            sizeDp = 26,
            selected = false,
        )
        tempMarker = map.addMarker(
            MarkerOptions(LatLng(lat, lng))
                .icon(com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory.fromBitmap(rendered.bitmap))
                .anchor(0.5f, rendered.anchorRatio)
                .zIndex(30f),
        )
        // 临时标记名称超宽 → 同样登记跑马灯（S05b 搜索点位名可能很长）
        marqueeTargets.removeAll { it.marker === tempMarker }
        if (tempMarker != null && markerRenderer.labelScrollRange(label) > 0f) {
            marqueeTargets.add(
                MarqueeTarget(
                    label = label,
                    colorHex = "#0E7490",
                    iconKey = "other_point",
                    sizeDp = 26,
                    selected = false,
                    markerStyle = FilterState.STYLE_ICON,
                    marker = tempMarker!!,
                ),
            )
        }
        startMarqueeIfNeeded()
    }

    // ---- 第六轮（2026-11-06）：主界面 pointed_information 的「点击点位」临时标记（标记点.png）----

    private var tappedMarker: Marker? = null
    private var tappedPinBitmap: android.graphics.Bitmap? = null

    /** 标记点.png 缩放到位图（宽 36dp），缓存复用；资源缺失返回 null。 */
    private fun tappedPinBitmapOrNull(): android.graphics.Bitmap? {
        tappedPinBitmap?.let { return it }
        val src = BitmapFactory.decodeResource(appContext.resources, R.drawable.ic_tapped_pin) ?: return null
        val w = (36 * appContext.resources.displayMetrics.density).roundToInt().coerceAtLeast(8)
        val h = (src.height * w / src.width).coerceAtLeast(8)
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
        tappedPinBitmap = scaled
        return scaled
    }

    /** 用户单击地图的点 → 用用户提供的 标记点.png 做临时标记（不入库，仅 pointed_information 使用）。 */
    fun showTappedMarker(lat: Double, lng: Double) {
        val map = mapView?.map ?: return
        runCatching { tappedMarker?.remove() }
        val bmp = tappedPinBitmapOrNull() ?: return
        tappedMarker = map.addMarker(
            MarkerOptions(LatLng(lat, lng))
                .icon(com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory.fromBitmap(bmp))
                .anchor(0.5f, 1f)
                .zIndex(40f),
        )
    }

    /** 移除「点击点位」的临时标记（pointed_information 取消 / 忘记信息时）。 */
    fun clearTappedMarker() {
        runCatching { tappedMarker?.remove() }
        tappedMarker = null
    }

    /** 当前地图中心（S06 从「＋」进入时以中心为标注点）。 */
    fun currentCenter(): Pair<Double, Double>? =
        mapView?.map?.cameraPosition?.target?.let { it.latitude to it.longitude }

    /** 无动画定位（新建 MapView 后恢复上次相机位置用）。 */
    fun moveToImmediate(lat: Double, lng: Double, zoom: Float) {
        mapView?.map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom))
    }

    // ---- 逆地理（腾讯版）：经 GeoCoderService（lbssearch geo2address），本类保持百度版公开 API ----

    /** 逆地理编码结果（与 GeoCoderService.ReverseGeocodeInfo 同构；UI 沿用旧签名）。 */
    data class ReverseGeocodeInfo(
        val address: String,
        val semantic: String,
        val poiName: String?,
        val province: String = "",
        val city: String = "",
    ) {
        /** 名称兜底链：附近 POI 名 → 语义化描述 → 完整地址（§6 S06 名称留空回退）。 */
        val nameCandidate: String
            get() = poiName?.takeIf { it.isNotBlank() }
                ?: semantic.takeIf { it.isNotBlank() }
                ?: address

        val provinceOrNull: String? get() = province.takeIf { it.isNotBlank() }
        val cityOrNull: String? get() = city.takeIf { it.isNotBlank() }
    }

    private val geocodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 坐标 → 逆地理（S06 防抖 800ms / pointed_information 单击；回调线程 = Main）。 */
    fun reverseGeocode(lat: Double, lng: Double, onResult: (ReverseGeocodeInfo?) -> Unit) {
        geocodeScope.launch {
            val info = GeoCoderService.reverseGeocode(appContext, lat, lng)
            onResult(
                info?.let {
                    ReverseGeocodeInfo(
                        address = it.address,
                        semantic = it.semantic,
                        poiName = it.poiName,
                        province = it.province,
                        city = it.city,
                    )
                },
            )
        }
    }

    /** 坐标 → 城市名（市级，如「北京市」；P10「显示当前地图对应城市」用，调用方自带防抖）。 */
    fun reverseGeocodeCity(lat: Double, lng: Double, onResult: (String?) -> Unit) {
        geocodeScope.launch {
            onResult(GeoCoderService.reverseGeocode(appContext, lat, lng)?.city)
        }
    }

    /** 城市名 → 城市中心坐标（P10 选中某市后地图切换；失败回 null 由调用方提示）。 */
    fun geocodeCity(name: String, onResult: (LatLng?) -> Unit) {
        geocodeScope.launch {
            onResult(GeoCoderService.geocode(appContext, name))
        }
    }

    private fun zoomBy(delta: Int) {
        val map = mapView?.map ?: return
        val status = map.cameraPosition
        map.animateCamera(CameraUpdateFactory.zoomTo((status.zoom + delta).coerceIn(4f, 21f)))
    }

    fun startLocate() {
        if (mapView == null) return
        if (removeGeoObserver != null) return
        // 2026-11-19 定案改版（用户定调）：平时链路（本方法/S03 蓝点）直连定位 SDK
        // TencentLocationManager（返回码+error/reason 透明）；导航期（NavGuideActivity）才用
        // navix LocationApi。链路与时序铁律见 NaviLocation 类注释。
        removeGeoObserver = NaviLocation.addObserver(appContext, 3000) { t ->
            // 无效定位（空坐标）直接跳过
            if (t.getLatitude() < 0.0001 || t.getLongitude() < 0.0001) return@addObserver
            lastFixLat = t.getLatitude()
            lastFixLng = t.getLongitude()
            lastValidFixAt = android.os.SystemClock.elapsedRealtime()
            feedMyLocation(lastFixLat, lastFixLng, t.getAccuracy())
            if (!firstFixDispatched) {
                firstFixDispatched = true
                onFirstFix?.invoke(lastFixLat, lastFixLng)
            }
            t.getCity()?.takeIf { it.isNotBlank() }?.let { onCityKnown?.invoke(it) }
        }
    }

    /**
     * 第二十四轮：把相机移到最近一次有效定位（没有定位则不动）。
     * 二次点定位用——蓝点/定位数据已在持续更新（interval=3000），点击应立即回到当前位置，
     * 而不是干等只发一次的 onFirstFix。
     */
    fun moveToLastFix(zoom: Float = 16f) {
        if (lastValidFixAt == 0L) return
        moveTo(lastFixLat, lastFixLng, zoom)
    }

    fun stopLocate() {
        removeGeoObserver?.invoke()
        removeGeoObserver = null
        firstFixDispatched = false
        lastValidFixAt = 0L
        lastFixLat = 0.0
        lastFixLng = 0.0
    }
}
