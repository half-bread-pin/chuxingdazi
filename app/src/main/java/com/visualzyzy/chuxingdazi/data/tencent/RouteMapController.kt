package com.visualzyzy.chuxingdazi.data.tencent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.tencent.gaya.foundation.api.interfaces.Removable
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptor
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds
import com.tencent.tencentmap.mapsdk.maps.model.Marker
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions
import com.tencent.tencentmap.mapsdk.maps.model.Polyline
import com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions

/**
 * 路线页/行程地图用的轻量地图控制器（2026-11-17 腾讯版重写）：
 * 只负责 polyline + 简单节点标记 + fitBounds，
 * 不含点位簿标记/聚类/长按逻辑（那是 MapController 的职责）。
 *
 * 腾讯画法映射：
 * - 白描边底线 = PolylineOptions.borderColor/borderWidth（或白底线 + 上层色线）；
 * - 方向箭头 = SDK 原生 arrow(true)+arrowTexture+arrowSpacing(120)（2026-11-22 用户定样
 *   白 chevron；S10/S26/S30 路线实线统一挂载，灰虚线段不挂）；
 * - 驾车五色路况 = colors(int[] 色值, int[] 百分比)（trafficLevels 逐点 → 逐段聚合）；
 * - 虚线 = 原生 pattern()（2026-11-23 改定；旧「colorTexture 纹理替代」方案作废——
 *   colorTexture 实际是「抽纹理某一行像素拉成整条线」，从没虚过）。
 */
class RouteMapController {

    private var mapView: MapView? = null
    private var overlays = mutableListOf<Removable>()
    private var markerTaps: MutableMap<Marker, Int> = mutableMapOf()
    var onMarkerTap: ((Int) -> Unit)? = null

    // S30 路线预览：纹理缓存（chevron 按色缓存；灰虚线已改 pattern() 无纹理）
    private val chevronCache = mutableMapOf<Int, BitmapDescriptor>()

    fun attach(view: MapView) {
        mapView = view
        view.map.uiSettings.setZoomControlsEnabled(false)
        // 2026-11-23 用户定调：行程地图与主地图（S03）口径一致——隐藏 3D 建筑物、平面底图
        // 2026-11-24 #5：setBuilding3dEffectEnable 只关 3D 绘制模式（拉伸/光影），建筑图层
        // 本身仍在渲染且画在 2D 覆盖物（虚线）之上——一加真机 9797 实锤虚线被建筑遮断。
        // 主修 = showBuilding(false)：官方文档（map SDK 6.15.0 TencentMap）实证「控制建筑物
        // 是否能够展示，默认 true」，与 setBuilding3dEffectEnable 语义不同、非弃用。
        // 备注：setDrawPillarWith2DStyle 官方已弃用（4.2.4 起）且语义是「3D 态楼块绘制
        // 模式」而非方案预想的立交/柱状物开关——按官方文档优先原则不默认加；真机验收
        // 若仍见遮挡按兜底阶梯逐级上（①弃用别名 setBuildingEnable ②buildingBlackList
        // ③停下回报，遮挡物可能是道路层 3D 地标）。
        runCatching { view.map.setBuilding3dEffectEnable(false) }
        runCatching { view.map.showBuilding(false) }
        view.map.setOnMarkerClickListener { marker ->
            markerTaps[marker]?.let { onMarkerTap?.invoke(it) }
            true
        }
    }

    fun detach() {
        clear()
        // 腾讯 BitmapDescriptor 无 recycle 语义，纹理缓存随 GC 回收
        chevronCache.clear()
        mapView = null
    }

    val center: LatLng?
        get() = mapView?.map?.cameraPosition?.target

    fun setOnCameraIdle(callback: (Double, Double, Float) -> Unit) {
        mapView?.map?.setOnCameraChangeListener(object : TencentMap.OnCameraChangeListener {
            override fun onCameraChange(position: com.tencent.tencentmap.mapsdk.maps.model.CameraPosition?) = Unit
            override fun onCameraChangeFinished(position: com.tencent.tencentmap.mapsdk.maps.model.CameraPosition?) {
                position?.target?.let { callback(it.latitude, it.longitude, position.zoom) }
            }
        })
    }

    /** SDK 原生白 chevron 方向箭头统一挂载（S10/S26/S30 路线实线共用；间距 120px 用户
     *  定样 2026-11-22）。alpha 烘进箭头位图跟线同步淡显；虚线段不挂——白箭头落在虚线
     *  缺口上会碎。 */
    private fun arrowOpts(opts: PolylineOptions, alpha: Int, d: Float): PolylineOptions =
        opts.arrow(true)
            .arrowTexture(directionArrow(scaleAlpha(0xFFFFFFFF.toInt(), alpha), d, chevronCache))
            .arrowSpacing(120)

    fun clear() {
        overlays.forEach { runCatching { it.remove() } }
        overlays.clear()
        markerTaps.clear()
    }

    /** 画一条路线（白描边底 + 主色层带实心白方向箭头纹理，alpha 0–100，旧线 32% 用）。 */
    fun showRoute(points: List<LatLng>, lineColorArgb: Int, alpha: Int) {
        val map = mapView?.map ?: return
        if (points.size < 2) return
        val d = density()
        // 白描边底层（腾讯 borderColor 在部分版本随纹理失效，与百度版同构画双线最稳）
        overlays.add(
            map.addPolyline(
                PolylineOptions()
                    .latLngs(points)
                    .width(9 * d)
                    .color(scaleAlpha(0xFFFFFFFF.toInt(), alpha))
                    .zIndex(0),
            ),
        )
        // 主层：纯色线 + SDK 原生方向箭头（arrow/arrowTexture/arrowSpacing，2026-11-22 用户
        // 需求：百度导航同款白 chevron；间距 120px = 首版 240px 用户要求再密一倍）
        overlays.add(
            map.addPolyline(
                arrowOpts(
                    PolylineOptions()
                        .latLngs(points)
                        .width(6 * d)
                        .color(scaleAlpha(lineColorArgb, alpha))
                        .zIndex(1),
                    alpha,
                    d,
                ),
            ),
        )
    }

    /**
     * S26 行程地图专用：单条细虚线（2026-11-23 #6 新增，**不动 showRoute**——S10/S30 共用的
     * 实线+白 chevron 是已验收样式）。原生 ARGB 虚线走 `pattern()`（2026-11-23 改定：
     * 旧注记「腾讯 polyline 无原生虚线开关」是错的——官方 javadoc 与 AAR 7.10.0 javap
     * 双证 `pattern(List<Integer>)` 存在，元素必须偶数个、每对 = 实线段长/空白段长 px；
     * `colorTexture` 的真实语义是「抽纹理第 N 像素行拉成整条线」而非沿线平铺，tile 方案
     * 必出实线，已废弃）；无箭头、无白描边底层（虚线叠白底会显脏）。
     * [alphaPercent] 0–100 烘进线色；[widthDp] 线宽（S26 全部态/选中天 ≈5，其他天曲线 2.5）。
     * [dashPattern] px 单位 [实, 空]；默认 14/10（全部态/选中天），S26 其他天淡化传 8/14（短划长空）。
     */
    fun showSchematicRoute(
        points: List<LatLng>,
        lineColorArgb: Int,
        alphaPercent: Int,
        widthDp: Float,
        dashPattern: List<Int> = listOf(14, 10),
    ) {
        val map = mapView?.map ?: return
        if (points.size < 2) return
        val d = density()
        overlays.add(
            map.addPolyline(
                PolylineOptions()
                    .latLngs(points)
                    .width(widthDp * d)
                    .colorType(PolylineOptions.ColorType.LINE_COLOR_ARGB)
                    .color(scaleAlpha(lineColorArgb, alphaPercent))
                    // 虚线节奏由 dashPattern 决定（14/10 密、8/14 稀疏）；断口密度只在这对数值上微调
                    .pattern(dashPattern)
                    .zIndex(1),
            ),
        )
    }

    /**
     * S30 路线预览：分段分类画线（决策 #96–#99，docs/路线规划优化方案.md §6；腾讯版画法；
     * 2026-11-22 起实线段统一挂 SDK 白 chevron 方向箭头，与 S10/S26 同款）。
     * - 驾车 = 全线一条：白描边底 + colors() 五色路况段（无路况退纯主色）+ SDK 白 chevron 方向箭头；
     * - 公交/地铁 = 每步一条：步行灰虚线 pattern()（不挂箭头）、车段线路色纯色线 + SDK 白 chevron；
     *   每车段上下车站图标；
     * - 步行 = 每步一条灰虚线 pattern()（不挂箭头）；骑行 = 每步一条主色线 + SDK 白 chevron
     *   （上步末点接下步首点防断缝）；
     * - focusStepIndex 非空 = 只高亮该步（其余 32%），null = 全量 100%。
     * 调用前应 clear()（本方法只加不清）。
     */
    fun drawSchemeSteps(scheme: RouteScheme, mode: TravelMode, focusStepIndex: Int? = null) {
        val map = mapView?.map ?: return
        if (scheme.points.size < 2) return
        val d = density()
        val alphaOf: (Int) -> Int = { i -> if (focusStepIndex == null || focusStepIndex == i) 100 else 32 }

        fun solidLine(points: List<LatLng>, widthDp: Float, color: Int, alpha: Int): PolylineOptions =
            PolylineOptions().latLngs(points).width(widthDp * d).color(scaleAlpha(color, alpha))

        when (mode) {
            TravelMode.DRIVING -> {
                overlays.add(
                    map.addPolyline(
                        solidLine(scheme.points, 9f, 0xFFFFFFFF.toInt(), alphaOf(-1)).zIndex(0),
                    ),
                )
                val usable = scheme.trafficLevels.isNotEmpty() && scheme.trafficLevels.size == scheme.points.size - 1
                // 主层（五色路况线/纯色退路）统一挂 SDK 白 chevron 方向箭头（2026-11-22 S30 加箭头）
                if (usable) {
                    overlays.add(map.addPolyline(arrowOpts(trafficPolyline(scheme, alphaOf(-1), d), alphaOf(-1), d)))
                } else {
                    overlays.add(
                        map.addPolyline(
                            arrowOpts(
                                PolylineOptions()
                                    .latLngs(scheme.points)
                                    .width(6 * d)
                                    .color(scaleAlpha(ROUTE_MAIN_COLOR, alphaOf(-1)))
                                    .zIndex(1),
                                alphaOf(-1),
                                d,
                            ),
                        ),
                    )
                }
            }

            TravelMode.TRANSIT -> {
                var lastVehicleColor: Int? = null
                scheme.steps.forEachIndexed { i, step ->
                    val pts = bridged(step.points, scheme.steps, i)
                    if (pts.size < 2) return@forEachIndexed
                    if (step.kind == RouteStepKind.WALK) {
                        // 步行灰虚线（2026-11-23 改 pattern() 原生虚线；8+8 比 S26 的 14+10 更细腻）
                        overlays.add(
                            map.addPolyline(
                                PolylineOptions()
                                    .latLngs(pts)
                                    .width(6 * d)
                                    .colorType(PolylineOptions.ColorType.LINE_COLOR_ARGB)
                                    .color(scaleAlpha(WALK_GRAY, alphaOf(i)))
                                    .pattern(listOf(8, 8))
                                    .zIndex(1),
                            ),
                        )
                    } else {
                        val lineColor = RouteLineColors.colorOfAvoiding(step.lineTitle, lastVehicleColor)
                        lastVehicleColor = lineColor
                        // 白描边底 + 线路色纯色线 + SDK 白 chevron 方向箭头（失焦透明度烘进线色与箭头）
                        overlays.add(
                            map.addPolyline(
                                solidLine(pts, 10f, 0xFFFFFFFF.toInt(), alphaOf(i)).zIndex(0),
                            ),
                        )
                        overlays.add(
                            map.addPolyline(
                                arrowOpts(
                                    PolylineOptions()
                                        .latLngs(pts)
                                        .width(8 * d)
                                        .color(scaleAlpha(lineColor, alphaOf(i)))
                                        .zIndex(1),
                                    alphaOf(i),
                                    d,
                                ),
                            ),
                        )
                        // 上/下车站图标（自绘色点；坐标优先 entrance/exit 节点，缺省用段首/尾）
                        val board = step.boardLoc ?: pts.first()
                        val alight = step.alightLoc ?: pts.last()
                        val icon = stationIcon(if (step.kind == RouteStepKind.SUBWAY) 0xFF5B5BD6.toInt() else 0xFF2A7DE1.toInt())
                        map.addMarker(
                            MarkerOptions(board).icon(icon).anchor(0.5f, 0.5f).zIndex(15f),
                        )?.let { overlays.add(it) }
                        map.addMarker(
                            MarkerOptions(alight).icon(icon).anchor(0.5f, 0.5f).zIndex(15f),
                        )?.let { overlays.add(it) }
                    }
                }
            }

            TravelMode.WALKING -> {
                scheme.steps.forEachIndexed { i, step ->
                    val pts = bridged(step.points, scheme.steps, i)
                    if (pts.size < 2) return@forEachIndexed
                    // 步行灰虚线（2026-11-23 改 pattern() 原生虚线，同公交步行段）
                    overlays.add(
                        map.addPolyline(
                            PolylineOptions()
                                .latLngs(pts)
                                .width(6 * d)
                                .colorType(PolylineOptions.ColorType.LINE_COLOR_ARGB)
                                .color(scaleAlpha(WALK_GRAY, alphaOf(i)))
                                .pattern(listOf(8, 8))
                                .zIndex(1),
                        ),
                    )
                }
            }

            TravelMode.BIKING -> {
                scheme.steps.forEachIndexed { i, step ->
                    val pts = bridged(step.points, scheme.steps, i)
                    if (pts.size < 2) return@forEachIndexed
                    overlays.add(
                        map.addPolyline(
                            solidLine(pts, 9f, 0xFFFFFFFF.toInt(), alphaOf(i)).zIndex(0),
                        ),
                    )
                    overlays.add(
                        map.addPolyline(
                            arrowOpts(
                                PolylineOptions()
                                    .latLngs(pts)
                                    .width(7 * d)
                                    .color(scaleAlpha(ROUTE_MAIN_COLOR, alphaOf(i)))
                                    .zIndex(1),
                                alphaOf(i),
                                d,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 驾车五色路况线（腾讯 colors(int[], int[]) 段色制）：
     * trafficLevels（逐点，长度 = points-1）→ 相邻同色段聚合 → 色值数组 + 累计百分比数组。
     */
    private fun trafficPolyline(scheme: RouteScheme, alpha: Int, d: Float): PolylineOptions {
        val n = scheme.trafficLevels.size
        val colorValues = mutableListOf<Int>()
        val percents = mutableListOf<Int>()
        var runStart = 0
        fun colorOf(level: Int): Int = when (level) {
            1 -> 0xFF28B260.toInt() // 畅通·绿
            2 -> 0xFFF2C200.toInt() // 缓行·黄
            3 -> 0xFFE34E43.toInt() // 拥堵·红
            4 -> 0xFFA61B29.toInt() // 严重拥堵·深红
            else -> ROUTE_MAIN_COLOR // 无路况段
        }
        for (i in 1..n) {
            val prev = scheme.trafficLevels[i - 1]
            if (i == n || scheme.trafficLevels[i] != prev) {
                colorValues.add(scaleAlpha(colorOf(prev), alpha))
                percents.add((i * 100 / n).coerceIn(1, 100))
                runStart = i
            }
        }
        return PolylineOptions()
            .latLngs(scheme.points)
            .width(7 * d)
            .colors(colorValues.toIntArray(), percents.toIntArray())
            .borderColor(scaleAlpha(0xFFFFFFFF.toInt(), alpha))
            .zIndex(1)
    }

    /** 段几何补桥：上一步末点接到当前步首点（防相邻步边界不共享时断缝）。 */
    private fun bridged(points: List<LatLng>, steps: List<RouteStepInfo>, index: Int): List<LatLng> {
        if (points.isEmpty()) return points
        if (index == 0) return points
        val prevLast = steps.getOrNull(index - 1)?.points?.lastOrNull() ?: return points
        return if (prevLast != points.first()) listOf(prevLast) + points else points
    }

    /** 添加节点标记（自定义位图）。index 回传给点击回调。 */
    fun addNodeMarker(
        lat: Double,
        lng: Double,
        icon: BitmapDescriptor,
        index: Int,
        anchorX: Float = 0.5f,
        anchorY: Float = 0.5f,
    ) {
        val map = mapView?.map ?: return
        val marker = map.addMarker(
            MarkerOptions(LatLng(lat, lng))
                .icon(icon)
                .anchor(anchorX, anchorY)
                .zIndex(20f),
        ) ?: return
        overlays.add(marker)
        markerTaps[marker] = index
    }

    // 2026-11-22 修复 #7：增加底部独立 padding 重载（bottomPaddingPx 默认=四边等距，原调用方不变）
    //——S26 按天筛选时底部要给天芯片面板+提示行+导航条留位，否则当天的南端标记被面板盖住。
    fun fitTo(points: List<LatLng>, paddingPx: Int, bottomPaddingPx: Int = paddingPx) {
        val map = mapView?.map ?: return
        if (points.isEmpty()) return
        val builder = LatLngBounds.Builder()
        points.forEach { builder.include(it) }
        map.animateCamera(
            CameraUpdateFactory.newLatLngBoundsRect(builder.build(), paddingPx, paddingPx, paddingPx, bottomPaddingPx),
        )
    }

    /**
     * 连续比例缩放（第三十一轮改版，用户确认）：让线路跨度占「可见区」的 [fillRatio]，锚 = 包围盒中心。
     * 可见区 = 屏幕减去上下遮挡（[topReserveDp] 顶部卡片 / [bottomReserveDp] 底部面板+导航条），
     * zoom 用标准 Web 墨卡托换算直接算出——无档位跳变、任何长度完整入画、线始终占屏幕主体。
     * [extraZoomOutLevels]：算出的 zoom 上再放开 N 级（zoom−N = 视野扩大 N 级；腾讯与百度同为
     * zoom 数值越大越近，2026-11-16 一加真机需求：起终点圆点完整落屏）。
     */
    fun fitSpan(
        points: List<LatLng>,
        topReserveDp: Float,
        bottomReserveDp: Float,
        fillRatio: Float = 0.7f,
        sidePadDp: Float = 20f,
        extraZoomOutLevels: Float = 0f,
    ) {
        val view = mapView ?: return
        val map = view.map
        if (points.size < 2) return
        val dm = view.resources.displayMetrics
        val visW = (dm.widthPixels - 2 * sidePadDp * dm.density).coerceAtLeast(200f)
        val visH = (dm.heightPixels - (topReserveDp + bottomReserveDp) * dm.density).coerceAtLeast(200f)

        val lats = points.map { it.latitude }
        val lngs = points.map { it.longitude }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLng = lngs.min(); val maxLng = lngs.max()
        val cLat = (maxLat + minLat) / 2.0
        val cLng = (maxLng + minLng) / 2.0
        val latRad = Math.toRadians(cLat)
        val dLatM = (maxLat - minLat) * 111_320.0
        val dLngM = (maxLng - minLng) * 111_320.0 * kotlin.math.cos(latRad)
        if (dLatM <= 0.0 && dLngM <= 0.0) return

        val ln2 = kotlin.math.ln(2.0)
        val zLat = if (dLatM <= 0.0) 21.0 else kotlin.math.ln(156_543.034 * kotlin.math.cos(latRad) * visH * fillRatio / dLatM) / ln2
        val zLng = if (dLngM <= 0.0) 21.0 else kotlin.math.ln(156_543.034 * kotlin.math.cos(latRad) * visW * fillRatio / dLngM) / ln2
        val zoom = (minOf(zLat, zLng) - extraZoomOutLevels).coerceIn(6.0, 20.0).toFloat()
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(cLat, cLng), zoom))
    }

    fun moveTo(lat: Double, lng: Double, zoom: Float) {
        val map = mapView?.map ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom))
    }

    /** 无动画定位（P50 问题5：路线页新建 MapView 后恢复相机用，避免跳回默认中心）。 */
    fun moveToImmediate(lat: Double, lng: Double, zoom: Float) {
        val map = mapView?.map ?: return
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom))
    }

    fun view(): View? = mapView

    private fun density(): Float = mapView?.resources?.displayMetrics?.density ?: 2.6f

    companion object {
        /** S30 路线预览：车段默认主色（与 S10 路线页主色一致）与步行灰。 */
        private val ROUTE_MAIN_COLOR = 0xFF2A7DE1.toInt()
        private val WALK_GRAY = 0xFF8A93A3.toInt()


        /** SDK 原生方向箭头图（arrowTexture 用）：白色宽扁 chevron（^ 形、圆头粗描边），
         *  2026-11-22 用户照百度绿线截图定样（实测比例：描边≈带宽 19%、宽≈带宽 57%、
         *  高≈带宽 29%），当晚二轮描边加倍 0.75→1.5dp、位图相应加高 2.6→3.6dp 保住 ^
         *  形缺口。宽 5dp（6dp 线宽内），位图尖朝上 = 行进方向（真机实证 SDK 映射：位图
         *  顶边对行进方向正方向）。alpha 跟随线色（旧线 32% 时箭头同步变淡，从线色 alpha
         *  通道取），按线色缓存。 */
        fun directionArrow(colorArgb: Int, d: Float, cache: MutableMap<Int, BitmapDescriptor>? = null): BitmapDescriptor {
            cache?.get(colorArgb)?.let { return it }
            val w = (5 * d).toInt().coerceAtLeast(8)
            val h = (3.6f * d).toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (colorArgb and 0xFF000000.toInt()) or 0x00FFFFFF
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * d
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            // ^ 形：左下 → 顶点（朝上）→ 右下；圆头描边外扩 ≈ 半个线宽，路径各端内缩防裁边
            val inset = 0.75f * d + 0.5f
            val path = Path().apply {
                moveTo(inset, h - inset)
                lineTo(w / 2f, inset)
                lineTo(w - inset, h - inset)
            }
            canvas.drawPath(path, paint)
            val desc = BitmapDescriptorFactory.fromBitmap(bmp)
            cache?.put(colorArgb, desc)
            return desc
        }

        // dashedTexture（自绘实/透相间 tile）已于 2026-11-23 删除：官方 javadoc + AAR 7.10.0
        // javap 双证 colorTexture 的语义是「抽纹理第 color() 行像素拉成整条线」而非沿线平铺，
        // tile 方案自上线起从未真正出过虚线；虚线一律改用原生 pattern()（见 showSchematicRoute）。

        /** 公交/地铁站点图标：白描边实心圆点（原百度版用官方 PNG，腾讯版自绘）。 */
        fun stationIcon(colorArgb: Int): BitmapDescriptor {
            val size = 18
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = 0xFFFFFFFF.toInt()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, stroke)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, fill)
            return BitmapDescriptorFactory.fromBitmap(bmp)
        }

        fun scaleAlpha(argb: Int, alphaPercent: Int): Int {
            val a = ((argb ushr 24) * alphaPercent / 100).coerceIn(0, 255)
            return (a shl 24) or (argb and 0x00FFFFFF)
        }

        /** 起点：蓝色圆点（12dp 实心 + 2dp 白描边）。 */
        fun originDot(context: Context): BitmapDescriptor = circleBitmap(context, 0xFF1B6EF3.toInt(), dp(context, 6f), dp(context, 2f))

        /** 终点：红色圆点（P32，2026-11-02：与起点同尺寸的圆点）。 */
        fun destinationDot(context: Context): BitmapDescriptor =
            circleBitmap(context, 0xFFE5484D.toInt(), dp(context, 6f), dp(context, 2f))

        /** 行程地图节点：12.5dp 实心圆 + 2.6dp 白描边 + 白色序号。 */
        fun numberedNode(context: Context, number: Int, colorArgb: Int, alphaPercent: Int = 100): BitmapDescriptor {
            val radius = dp(context, 9f)
            val size = (radius * 2 + dp(context, 2f)).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = scaleAlpha(colorArgb, alphaPercent)
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(context, 2.6f)
                color = scaleAlpha(0xFFFFFFFF.toInt(), alphaPercent)
            }
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, stroke)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = scaleAlpha(0xFFFFFFFF.toInt(), alphaPercent)
                textSize = dp(context, 9f)
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val y = cy - (text.descent() + text.ascent()) / 2f
            canvas.drawText(number.toString(), cx, y, text)
            return BitmapDescriptorFactory.fromBitmap(bmp)
        }

        /**
         * 行程地图其他天淡化端点（2026-11-24 #6 终版方案 C）：空心化——
         * 天色描边圆环 + 天色编号，中间透底，无实心填充。几何与 [numberedNode]
         * 完全一致（radius 9dp、size 同式），只差三处：① 省掉实心 fill 那笔；
         * ② 描边 paint 天色（旧函数是白描边）；③ 数字 paint 天色（旧函数是白）。
         * 满色天色全保留在环与数字上——空心本身就是淡化，这是「对得上芯片」的最强保证。
         */
        fun numberedNodeHollow(context: Context, number: Int, colorArgb: Int): BitmapDescriptor {
            val radius = dp(context, 9f)
            val size = (radius * 2 + dp(context, 2f)).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(context, 2.6f)
                color = colorArgb
            }
            canvas.drawCircle(cx, cy, radius, stroke)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorArgb
                textSize = dp(context, 9f)
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val y = cy - (text.descent() + text.ascent()) / 2f
            canvas.drawText(number.toString(), cx, y, text)
            return BitmapDescriptorFactory.fromBitmap(bmp)
        }

        /** 途经/点位 pin（简化水滴：圆头 + 尖底），分类色。 */
        fun pin(context: Context, colorArgb: Int): BitmapDescriptor {
            val w = dp(context, 13f)
            val h = dp(context, 17f)
            val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(context, 2f)
                color = 0xFFFFFFFF.toInt()
            }
            val cx = w / 2f
            val headR = dp(context, 6.5f)
            val headCy = dp(context, 6.5f)
            val path = Path()
            path.moveTo(cx, h - dp(context, 1f))
            path.lineTo(cx - headR * 0.866f, headCy + headR * 0.5f)
            path.addCircle(cx, headCy, headR, Path.Direction.CCW)
            path.close()
            canvas.drawPath(path, stroke)
            canvas.drawPath(path, paint)
            canvas.drawCircle(cx, headCy, dp(context, 2.2f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
            return BitmapDescriptorFactory.fromBitmap(bmp)
        }

        private fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

        private fun circleBitmap(context: Context, colorArgb: Int, radiusPx: Float, strokePx: Float): BitmapDescriptor {
            val size = ((radiusPx + strokePx) * 2).toInt() + 2
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = size / 2f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                color = 0xFFFFFFFF.toInt()
            }
            canvas.drawCircle(cx, cx, radiusPx, stroke)
            canvas.drawCircle(cx, cx, radiusPx, fill)
            return BitmapDescriptorFactory.fromBitmap(bmp)
        }
    }
}
