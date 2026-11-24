package com.visualzyzy.chuxingdazi.data.tencent

import android.content.Context
import com.tencent.lbssearch.TencentSearch
import com.tencent.lbssearch.httpresponse.BaseObject
import com.tencent.lbssearch.httpresponse.HttpResponseListener
// ⚠️ 包段 `object` 是 Kotlin 关键字，import 必须反引号转义
import com.tencent.lbssearch.`object`.param.BicyclingParam
import com.tencent.lbssearch.`object`.param.DrivingParam
import com.tencent.lbssearch.`object`.param.RoutePlanningParam
import com.tencent.lbssearch.`object`.param.TransitParam
import com.tencent.lbssearch.`object`.param.WalkingParam
import com.tencent.lbssearch.`object`.result.BicyclingResultObject
import com.tencent.lbssearch.`object`.result.DrivingResultObject
import com.tencent.lbssearch.`object`.result.RoutePlanningObject
import com.tencent.lbssearch.`object`.result.TransitResultObject
import com.tencent.lbssearch.`object`.result.WalkingResultObject
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** 交通方式（§6 S10 悬浮胶囊四项）。 */
enum class TravelMode { DRIVING, WALKING, BIKING, TRANSIT }

/**
 * 行程链一步的类型（S30 路线预览，决策 #96–#99）。
 * 驾车/步行/骑行的每步 kind = 本方式；公交步细分 WALK/BUS/SUBWAY。
 */
enum class RouteStepKind { WALK, DRIVE, BIKE, BUS, SUBWAY }

/**
 * 一段的文字/几何信息（四方式通用；docs/路线规划优化方案.md §5）。
 * 非车段 lineTitle/directionText/stationCount/boardName/alightName 恒 null；
 * 非公交 kind（WALK/DRIVE/BIKE）带 instruction/roadName。
 */
data class RouteStepInfo(
    val kind: RouteStepKind,
    val lineTitle: String?,      // 线路名（Line.title，如「685路」）
    val directionText: String?,  // 行进方向（Line.destination.title，如「王顶堤公交站方向」）
    val stationCount: Int?,      // 乘坐站数（Line.station_count）
    val boardName: String?,      // 上车站（Line.geton.title）
    val alightName: String?,     // 下车站（Line.getoff.title）
    val boardLoc: LatLng?,       // 上车站坐标（geton.latLng，站点标记用）
    val alightLoc: LatLng?,      // 下车站坐标（getoff.latLng）
    val instruction: String?,    // 逐段指令文字（驾车/步行/骑行 step.instruction）
    val roadName: String?,       // 路名（驾车 step.road_name）
    val fareYuan: Int?,          // 该段票价（Line.price，元取整；汇总行拼「· N 元」）
    val distanceM: Int,
    val durationSec: Int,
    val points: List<LatLng>,    // 该段几何（分段画线用；可能为空 = SDK 未给几何）
)

/** 一条可选路线方案（已把 step 几何拼成完整 polyline）。 */
data class RouteScheme(
    val durationSec: Int,
    val distanceM: Int,
    val lightNum: Int?, // 仅驾车有红绿灯数
    val points: List<LatLng>,
    /** 逐段行程链（四方式全部生成；S30 路线预览消费）。 */
    val steps: List<RouteStepInfo> = emptyList(),
    /**
     * 仅驾车：全线逐小段路况级（0=无路况 1=畅通 2=缓行 3=拥堵 4=严重拥堵，
     * 腾讯 trafficSpeeds.level 直传），与 [points] 拼接后逐段对齐；
     * 空列表 = 无路况数据（退回纯色线）。
     */
    val trafficLevels: List<Int> = emptyList(),
)

/** 2026-11-23 起 sdkMsg 带回 SDK onFailure 的原因原文（如 373「起终点距离超长」），供 S10 简短弹窗。 */
class RoutePlanException(message: String, val sdkMsg: String? = null) : Exception(message)

/**
 * 腾讯路线规划封装（§6 S10「多点路线实现策略」，2026-11-17 腾讯版重写）：
 * - 驾车且途经点 ≤3 → 一次请求带 addWayPoints（腾讯 Web 服务原生支持，最准）；
 * - 驾车途经 >3、步行/骑行/公交 → **按用户给定顺序分段请求**（不重排），逐段拼接 polyline、累加时长距离；
 * - 顺序永远以调用方传入为准，分段不是排序。
 *
 * 腾讯步结构（与百度不同）：路线级 duration 单位=分钟（百度=秒，入库前 *60）；step 无独立 duration，几何以 polyline_idx（索引对）指向
 * 路线级 polyline——逐步时长按距离占比估算（仅 S30 行程链展示用）；
 * trafficSpeeds 用 polyline_idx 直接铺到逐点路况级。
 */
object RoutePlanner {

    /** 驾车一次请求可携带的途经点上限（2026-11-23 起 3→15：lbssearch AAR 字节码实锤客户端上限 30，
     *  官方文档页 10/30 两行以 30 为准；15 与 S16 外部跳转「途经点截前 15 个」口径对齐，超出走分段）。 */
    private const val MAX_PASS_BY = 15

    suspend fun plan(
        context: Context,
        mode: TravelMode,
        origin: LatLng,
        destination: LatLng,
        wayPoints: List<LatLng>,
        city: String,
    ): List<RouteScheme> {
        if (mode == TravelMode.DRIVING && wayPoints.size <= MAX_PASS_BY) {
            return planOneLeg(
                context = context,
                mode = mode,
                from = origin,
                to = destination,
                via = wayPoints,
                city = city,
            ).schemes
        }
        // 分段拼接：[origin] + wayPoints + [destination] 两两请求
        val nodes = buildList {
            add(origin)
            addAll(wayPoints)
            add(destination)
        }
        var points = mutableListOf<LatLng>()
        var duration = 0
        var distance = 0
        val allSteps = mutableListOf<RouteStepInfo>()
        val allTraffic = mutableListOf<Int>()
        var trafficComplete = true
        for (i in 0 until nodes.size - 1) {
            val leg = planOneLeg(
                context = context,
                mode = mode,
                from = nodes[i],
                to = nodes[i + 1],
                via = emptyList(),
                city = city,
            )
            val legScheme = leg.schemes.firstOrNull()
                ?: throw RoutePlanException("第 ${i + 1} 段未能规划出路线", leg.failMsg)
            legScheme.points.forEach { p ->
                if (points.isEmpty() || points.last() != p) points.add(p)
            }
            allSteps.addAll(legScheme.steps)
            // 各段内部 points/trafficLevels 已对齐（同一次拼接生成）；段边界去重不产生新段 → 直接拼接仍对齐
            if (legScheme.trafficLevels.isEmpty()) trafficComplete = false else allTraffic.addAll(legScheme.trafficLevels)
            duration += legScheme.durationSec
            distance += legScheme.distanceM
        }
        return listOf(
            RouteScheme(
                durationSec = duration,
                distanceM = distance,
                lightNum = null,
                points = points,
                steps = allSteps,
                trafficLevels = if (trafficComplete) allTraffic else emptyList(),
            ),
        )
    }

    /**
     * 单段路线查询（P7 交通腿：时间轴相邻安排间一次查询，取第一条方案）。
     * 结果按当前时刻/当前路况（与百度版「按当前时刻查 + 手填兜底」口径一致，§5.3）。
     * 无可用路线返回 null。
     */
    suspend fun planLeg(
        context: Context,
        mode: TravelMode,
        from: LatLng,
        to: LatLng,
        city: String,
    ): RouteScheme? =
        planOneLeg(context = context, mode = mode, from = from, to = to, city = city).schemes.firstOrNull()

    /** 单段请求结果体：schemes = 解析出的方案；failMsg = SDK onFailure 的原因原文（成功/发起失败为 null）。 */
    private data class LegOutcome(val schemes: List<RouteScheme>, val failMsg: String?)

    /** 单段请求（一次 search → 最多 N 条方案）。失败返回空方案 + SDK 原因（若有）。 */
    private suspend fun planOneLeg(
        context: Context,
        mode: TravelMode,
        from: LatLng,
        to: LatLng,
        via: List<LatLng> = emptyList(),
        city: String = "",
    ): LegOutcome {
        val key = TencentSdk.currentKey
        if (key.isNullOrBlank()) return LegOutcome(emptyList(), null)
        // 决策 #114：3 参构造器，key 必须落 apiKey 位（2 参构造器 key 进 secretKey 位不进请求）
        val searcher = TencentSearch(context.applicationContext, key, null)
        return suspendCancellableCoroutine { cont ->
            val reply: (LegOutcome) -> Unit = { outcome ->
                if (!cont.isCompleted) cont.resume(outcome)
            }
            val listener = object : HttpResponseListener<BaseObject> {
                override fun onSuccess(statusCode: Int, obj: BaseObject?) {
                    reply(
                        LegOutcome(
                            runCatching { parseResult(mode, obj) }.getOrDefault(emptyList()),
                            null,
                        ),
                    )
                }

                override fun onFailure(statusCode: Int, msg: String?, throwable: Throwable?) {
                    reply(LegOutcome(emptyList(), msg?.takeIf { it.isNotBlank() }))
                }
            }
            val launched = runCatching {
                when (mode) {
                    TravelMode.DRIVING -> {
                        val param = DrivingParam(from, to)
                            .policy(RoutePlanningParam.DrivingPolicy.REAL_TRAFFIC)
                            .setMultRoute(1)
                        via.forEach { param.addWayPoint(it) }
                        searcher.getRoutePlan(param, listener)
                    }
                    TravelMode.WALKING -> searcher.getRoutePlan(WalkingParam(from, to), listener)
                    TravelMode.BIKING -> searcher.getRoutePlan(BicyclingParam(from, to), listener)
                    TravelMode.TRANSIT -> {
                        val param = TransitParam(from, to)
                            .policy(RoutePlanningParam.TransitPolicy.LEAST_TIME)
                        searcher.getRoutePlan(param, listener)
                    }
                }
            }.isSuccess
            if (!launched && !cont.isCompleted) {
                cont.resume(LegOutcome(emptyList(), null))
            }
            cont.invokeOnCancellation { runCatching { searcher.cancelAll() } }
        }
    }

    /** 按方式解析结果对象 → 方案列表（类型不符/无数据 = 空列表）。 */
    private fun parseResult(mode: TravelMode, obj: BaseObject?): List<RouteScheme> = when (mode) {
        TravelMode.DRIVING ->
            (obj as? DrivingResultObject)?.result?.routes.orEmpty().map { buildDrivingScheme(it) }
        TravelMode.WALKING ->
            (obj as? WalkingResultObject)?.result?.routes.orEmpty().map {
                buildUniformScheme(it.distance, it.duration, it.polyline, it.steps, TravelMode.WALKING)
            }
        TravelMode.BIKING ->
            (obj as? BicyclingResultObject)?.result?.routes.orEmpty().map {
                buildUniformScheme(it.distance, it.duration, it.polyline, it.steps, TravelMode.BIKING)
            }
        TravelMode.TRANSIT ->
            (obj as? TransitResultObject)?.result?.routes.orEmpty().map { buildTransitScheme(it) }
    }

    // ---- 驾车：一条 Route → RouteScheme（总 polyline + steps + trafficLevels 同源生成）----

    private fun buildDrivingScheme(route: DrivingResultObject.Route): RouteScheme {
        val pts = route.polyline.orEmpty()
        val steps = parseUniformSteps(route.steps.orEmpty(), pts, RouteStepKind.DRIVE, route.duration)
        // trafficSpeeds.level（1 畅通 / 2 缓行 / 3 拥堵 / 4 严重拥堵；0 段位 = 无路况）
        val levels = IntArray((pts.size - 1).coerceAtLeast(0))
        var trafficComplete = pts.size >= 2
        if (trafficComplete) {
            route.trafficSpeeds.orEmpty().forEach { speed ->
                val idx = speed.polyline_idx.orEmpty()
                if (idx.size < 2) {
                    trafficComplete = false
                    return@forEach
                }
                var i = 0
                while (i + 1 < idx.size) {
                    val s = idx[i].coerceIn(0, levels.size)
                    val e = idx[i + 1].coerceIn(0, levels.size)
                    for (k in s until e) levels[k] = speed.level
                    i += 2
                }
            }
        }
        return RouteScheme(
            durationSec = (route.duration * 60).toInt(), // 腾讯 duration 单位=分钟（百度=秒），统一转秒（决策 #110）
            distanceM = route.distance.toInt(),
            lightNum = route.traffic_light_count.takeIf { it > 0 },
            points = pts,
            steps = steps,
            trafficLevels = if (trafficComplete && pts.size >= 2) levels.toList() else emptyList(),
        )
    }

    // ---- 步行 / 骑行：一条 Route → RouteScheme（step 几何从 polyline_idx 展开）----

    private fun buildUniformScheme(
        totalDistance: Float,
        totalDuration: Float,
        routePolyline: List<LatLng>?,
        rawSteps: List<RoutePlanningObject.Step>?,
        mode: TravelMode,
    ): RouteScheme {
        val kind = when (mode) {
            TravelMode.WALKING -> RouteStepKind.WALK
            TravelMode.BIKING -> RouteStepKind.BIKE
            else -> RouteStepKind.DRIVE
        }
        val pts = routePolyline.orEmpty()
        val steps = parseUniformSteps(rawSteps.orEmpty(), pts, kind, totalDuration)
        return RouteScheme(
            durationSec = (totalDuration * 60).toInt(), // 同上：分钟→秒（决策 #110）
            distanceM = totalDistance.toInt(),
            lightNum = null,
            points = pts,
            steps = steps,
        )
    }

    /** polyline_idx（索引对，[s0,e0,s1,e1…] 指向路线级 polyline）→ 该步几何点。 */
    private fun stepPoints(idx: List<Int>?, routePolyline: List<LatLng>): List<LatLng> {
        if (idx.isNullOrEmpty() || routePolyline.isEmpty()) return emptyList()
        val out = mutableListOf<LatLng>()
        var i = 0
        while (i + 1 < idx.size) {
            val s = idx[i].coerceIn(0, routePolyline.size)
            val e = idx[i + 1].coerceIn(0, routePolyline.size)
            if (e > s) out.addAll(routePolyline.subList(s, e))
            i += 2
        }
        return out
    }

    /** 驾车/步行/骑行：每步 kind = 本方式，带指令文字；驾车另带路名；时长按距离占比估算。 */
    private fun parseUniformSteps(
        rawSteps: List<RoutePlanningObject.Step>,
        routePolyline: List<LatLng>,
        kind: RouteStepKind,
        totalDurationSec: Float,
    ): List<RouteStepInfo> {
        val totalDistanceM = rawSteps.sumOf { it.distance.toDouble() }.coerceAtLeast(1.0)
        return rawSteps.mapNotNull { raw ->
            val points = stepPoints(raw.polyline_idx, routePolyline)
            // 腾讯 step 无独立时长：按该步距离占总距离比例摊（展示用，误差可接受）
            val durationSec = if (totalDurationSec > 0f) {
                (totalDurationSec * (raw.distance / totalDistanceM).toFloat()).toInt().coerceAtLeast(0)
            } else {
                0
            }
            RouteStepInfo(
                kind = kind,
                lineTitle = null,
                directionText = null,
                stationCount = null,
                boardName = null,
                alightName = null,
                boardLoc = null,
                alightLoc = null,
                instruction = raw.instruction?.takeIf { it.isNotBlank() },
                roadName = raw.road_name?.takeIf { it.isNotBlank() },
                fareYuan = null,
                distanceM = raw.distance.toInt(),
                durationSec = durationSec,
                points = points,
            )
        }
    }

    // ---- 公交：一条 Route → RouteScheme（WALK 段 + TRANSIT.lines 逐条线）----

    private fun buildTransitScheme(route: TransitResultObject.Route): RouteScheme {
        val merged = mutableListOf<RouteStepInfo>()
        route.steps.orEmpty().forEach { segment ->
            val isWalking = segment.mode.equals("WALKING", ignoreCase = true)
            if (isWalking) {
                val walk = segment as? TransitResultObject.Walking
                if (walk != null) {
                    merged.add(
                        RouteStepInfo(
                            kind = RouteStepKind.WALK,
                            lineTitle = null,
                            directionText = null,
                            stationCount = null,
                            boardName = null,
                            alightName = null,
                            boardLoc = null,
                            alightLoc = null,
                            instruction = walk.steps?.firstOrNull()?.instruction?.takeIf { it.isNotBlank() },
                            roadName = null,
                            fareYuan = null,
                            distanceM = walk.distance.toInt(),
                            durationSec = (walk.duration * 60).toInt(), // 分钟→秒（决策 #110）
                            points = walk.polyline.orEmpty(),
                        ),
                    )
                }
            } else if (segment is TransitResultObject.Transit) {
                segment.lines.orEmpty().forEach { line ->
                    val kind = when (line.vehicle?.uppercase()) {
                        "SUBWAY" -> RouteStepKind.SUBWAY
                        else -> RouteStepKind.BUS
                    }
                    merged.add(
                        RouteStepInfo(
                            kind = kind,
                            lineTitle = line.title?.takeIf { it.isNotBlank() },
                            directionText = line.destination?.title?.takeIf { it.isNotBlank() },
                            stationCount = line.station_count.takeIf { it > 0 },
                            boardName = line.geton?.title?.takeIf { it.isNotBlank() },
                            alightName = line.getoff?.title?.takeIf { it.isNotBlank() },
                            boardLoc = line.geton?.latLng,
                            alightLoc = line.getoff?.latLng,
                            instruction = null,
                            roadName = null,
                            fareYuan = line.price.takeIf { it > 0.0 }?.toInt(),
                            distanceM = line.distance.toInt(),
                            durationSec = (line.duration * 60).toInt(), // 分钟→秒（决策 #110）
                            points = line.polyline.orEmpty(),
                        ),
                    )
                }
            }
        }
        // 相邻步行合并（距离/时长累加、几何衔接）
        val final = mutableListOf<RouteStepInfo>()
        for (info in merged) {
            val last = final.lastOrNull()
            if (info.kind == RouteStepKind.WALK && last?.kind == RouteStepKind.WALK) {
                final[final.size - 1] = last.copy(
                    distanceM = last.distanceM + info.distanceM,
                    durationSec = last.durationSec + info.durationSec,
                    points = last.points + info.points,
                )
            } else {
                final.add(info)
            }
        }
        return RouteScheme(
            durationSec = (route.duration * 60).toInt(), // 腾讯 duration 单位=分钟（百度=秒），统一转秒（决策 #110）
            distanceM = route.distance.toInt(),
            lightNum = null,
            points = merged.flatMap { it.points },
            steps = final,
        )
    }
}
