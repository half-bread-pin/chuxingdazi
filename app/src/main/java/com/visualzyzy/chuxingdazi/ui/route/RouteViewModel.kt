package com.visualzyzy.chuxingdazi.ui.route

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.visualzyzy.chuxingdazi.data.tencent.RoutePlanException
import com.visualzyzy.chuxingdazi.data.tencent.RoutePlanner
import com.visualzyzy.chuxingdazi.data.tencent.RouteScheme
import com.visualzyzy.chuxingdazi.data.tencent.TravelMode
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import com.visualzyzy.chuxingdazi.data.repo.TripRepository
import com.visualzyzy.chuxingdazi.ui.trip.TripTime
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/** S11 回传的途经点载荷（经 savedStateHandle JSON 传递）。 */
@kotlinx.serialization.Serializable
data class StopPayload(
    val name: String,
    val lat: Double,
    val lng: Double,
    val placeId: String? = null,
)

/** S10 路线规划（§6 S10）：待确认/已规划两态，路线只在用户点「确认路线」时计算一次。 */
@HiltViewModel
class RouteViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val placeRepository: PlaceRepository,
    private val tripRepository: TripRepository,
    private val routeTransfer: RouteTransfer,
    categoryRepository: CategoryRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    enum class Phase { PENDING, PLANNED }

    /** 路线上的一个点（点位库里来的带 placeId；我的位置/搜点无）。 */
    data class Stop(
        val key: String = UUID.randomUUID().toString(),
        val placeId: String?,
        val name: String,
        val lat: Double,
        val lng: Double,
        val isMyLocation: Boolean = false,
    )

    val origin = MutableStateFlow<Stop?>(null)
    val destination = MutableStateFlow<Stop?>(null)
    val wayStops = MutableStateFlow<List<Stop>>(emptyList())
    val mode = MutableStateFlow(TravelMode.DRIVING)
    val phase = MutableStateFlow(Phase.PENDING)
    val schemes = MutableStateFlow<List<RouteScheme>>(emptyList())
    val selectedScheme = MutableStateFlow(0)
    /** 已规划后改了端点/顺序：旧线保留但 32% 透明（§6 S10 状态机）。 */
    val staleLine = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val errorMsg = MutableStateFlow<String?>(null)

    /** 2026-11-23：SDK 明说原因（如 373「起终点距离超长」）→ 一次性简短弹窗态；null = 无弹窗。 */
    val planFailDialogMsg = MutableStateFlow<String?>(null)
    /** 「来自时间轴 · 10/31 周五」标注。 */
    val timelineBadge = MutableStateFlow<String?>(null)
    /** 从时间轴进来后手动改过顺序。 */
    val orderEdited = MutableStateFlow(false)
    /** 我的位置状态：null=获取中，Double=失败占位 NaN。 */
    val myLocation = MutableStateFlow<DoubleArray?>(null)
    val myLocationFailed = MutableStateFlow(false)

    var city: String = "全国"

    // ---- P50（2026-11-03）问题5：路线页相机恢复 ----
    // 进入路线页：新建 MapView 先恢复「本页上次相机」（子页返回时保留用户拖动位置）；
    // 没有则恢复主界面快照（进入路线页时地图不变化）。路线页的相机改动只写 lastCamera，
    // 不写主界面的 Prefs 相机快照 → 退出路线页时主界面地图不变化。
    var lastCamera: com.visualzyzy.chuxingdazi.data.prefs.Prefs.CameraSnapshot? = null

    /** 首次拿到终点时做一次「起点+终点」视野适配（点位详情入口可见终点）；只做一次。 */
    var mapInitDone = false

    /** 首个算路方案到达时做一次全览适配；只做一次。 */
    var schemeFitted = false

    /**
     * P68（2026-11-04）第四轮：S20 导航说明在本条路线页生命周期内只弹一次。
     * ViewModel 与 s10_route 返回栈条目同生命周期——从 S11/S21 子页返回时不会重弹；
     * 重新从主界面进入路线页（新条目、新实例）才会再弹。
     */
    var s20NoticeShown = false

    val categoriesById: StateFlow<Map<String, CategoryEntity>> = categoryRepository.observeAll()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** S30：路线页返回栈条目销毁（popBackStack）时清预览快照——route_preview 生命周期内 S10 条目仍在栈上（VM 存活）。 */
    override fun onCleared() {
        super.onCleared()
        RoutePreviewStore.snapshot = null
    }

    /** 第十七轮：起点/终点「从点位选」浮层换 ui/common 多模式点位搜索（与清单添加点位同款），需要分类列表。 */
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 起点/终点「从点位选」用的全量点位。 */
    val places: StateFlow<List<com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity>> =
        placeRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var fromTimeline = false

    /** 时间轴进入：等定位回来再自动算一次（「按这天走」本身即确认，§7.4）。 */
    private var pendingAutoConfirm = false

    init {
        val tripId = savedStateHandle.get<String>("fromTripId")
        val date = savedStateHandle.get<String>("date")
        // 第九轮（2026-11-08）：「从第一个安排的点位出发」——起点 = 当天时间最早的安排，不用我的位置
        val originFromSchedule = savedStateHandle.get<String>("originFromSchedule") == "true"
        if (tripId != null && date != null) {
            initFromTimeline(tripId, date, originFromSchedule)
        }
        // 从点位详情「导航前往」进：预置终点，起点仍为我的位置（待确认态，不请求）
        savedStateHandle.get<String>("destPlaceId")?.takeIf { it.isNotBlank() }?.let { destId ->
            viewModelScope.launch {
                placeRepository.getById(destId)?.let { place ->
                    setDestination(Stop(placeId = place.id, name = place.name, lat = place.lat, lng = place.lng))
                }
            }
        }
        // 独立进入：起点等屏幕定位后 setMyLocation
    }

    /** S11/S21 回传（RouteScreen 重组时调用；单例直传，绕开 savedStateHandle）。 */
    fun consumeTransfers() {
        routeTransfer.consumeWayStops()?.let { payloads ->
            addWayStops(payloads.map { Stop(placeId = it.placeId, name = it.name, lat = it.lat, lng = it.lng) })
        }
        routeTransfer.consumeOrigin()?.let { setOrigin(Stop(placeId = it.placeId, name = it.name, lat = it.lat, lng = it.lng)) }
        routeTransfer.consumeDestination()?.let {
            setDestination(Stop(placeId = it.placeId, name = it.name, lat = it.lat, lng = it.lng))
        }
    }

    /** 独立进入（S03「路线」/详情「导航前往」）：只设我的位置，不请求。 */
    fun setMyLocation(lat: Double, lng: Double) {
        val current = origin.value
        if (current == null || current.isMyLocation) {
            origin.value = Stop(placeId = null, name = "我的位置", lat = lat, lng = lng, isMyLocation = true)
        }
        myLocation.value = doubleArrayOf(lat, lng)
        if (errorMsg.value == "正在定位我的位置…") errorMsg.value = null
        if (pendingAutoConfirm) {
            pendingAutoConfirm = false
            confirmRoute()
        }
    }

    fun markMyLocationFailed() {
        myLocationFailed.value = true
        if (errorMsg.value == "正在定位我的位置…") errorMsg.value = null
        if (pendingAutoConfirm) {
            pendingAutoConfirm = false
            errorMsg.value = "未获取到我的位置，点起点行「改为点位」后再确认路线"
        }
    }

    /** P35（2026-11-02）：起点区定位小图标 → 起点强制变为实时我的位置（覆盖已选点位）。 */
    fun forceOriginMyLocation(lat: Double, lng: Double) {
        origin.value = Stop(placeId = null, name = "我的位置", lat = lat, lng = lng, isMyLocation = true)
        myLocation.value = doubleArrayOf(lat, lng)
        myLocationFailed.value = false
        if (errorMsg.value == "正在定位我的位置…") errorMsg.value = null
    }

    /** §7.4：时间轴「按这天走」→ 按时间顺序填充并**直接算一次**。
     *  第九轮：originFromSchedule=true 时起点 = 时间最早的那个安排（不用我的位置），
     *  途经/终点仍按时间先后排布；起点不依赖定位，填充完直接确认算路。 */
    private fun initFromTimeline(tripId: String, date: String, originFromSchedule: Boolean = false) {
        fromTimeline = true
        viewModelScope.launch {
            val trip = tripRepository.getById(tripId)
            val rows = tripRepository.schedulesForDay(tripId, date).sortedBy { it.schedule.startMin }
            val start = TripTime.tripStart(trip?.startDate)
            val d = runCatching { LocalDate.parse(date) }.getOrElse { TripTime.today() }
            timelineBadge.value = "来自时间轴 · ${TripTime.dateText(d)} ${TripTime.weekdayText(d)}"
            // 第十三轮（2026-11-12）：只安排了一个点位时不再拦截——
            // 「从第一个安排的点位出发」= 起点为该点位、终点保持尚未设置（不自动算路）；
            // 「从我的位置出发」= 起点=我的位置（待定位回填）、终点=该点位。
            val stops = mutableListOf<Stop>()
            for (row in rows) {
                val place = placeRepository.getById(row.schedule.placeId) ?: continue
                stops.add(Stop(placeId = place.id, name = place.name, lat = place.lat, lng = place.lng))
            }
            if (rows.isEmpty() || stops.isEmpty()) {
                errorMsg.value = "这天还没有安排点位"
                return@launch
            }
            if (rows.size == 1 || stops.size == 1) {
                if (originFromSchedule) {
                    origin.value = stops.first()
                    destination.value = null
                    wayStops.value = emptyList()
                    pendingAutoConfirm = false
                } else {
                    origin.value = null // 我的位置（定位回来回填）
                    destination.value = stops.first()
                    wayStops.value = emptyList()
                    pendingAutoConfirm = true
                }
                return@launch
            }
            if (originFromSchedule) {
                origin.value = stops.first()
                destination.value = stops.last()
                wayStops.value = stops.drop(1).dropLast(1)
                pendingAutoConfirm = false
                confirmRoute()
            } else {
                // 起点 = 我的位置占位（定位回来会覆盖坐标），终点 = 最后一项，途经 = 中间项（§7.4）
                destination.value = stops.last()
                wayStops.value = stops.dropLast(1)
                pendingAutoConfirm = true
            }
        }
    }

    /** 任何输入变更：已规划态 → 打回待确认，旧线保留降透明。 */
    private fun markStale() {
        if (phase.value == Phase.PLANNED) {
            staleLine.value = true
            phase.value = Phase.PENDING
        }
        errorMsg.value = null
    }

    fun setMode(m: TravelMode) {
        if (mode.value == m) return
        mode.value = m
        // 切方式：不同方式的线不对应 → 直接清空旧线（§6 S10）
        schemes.value = emptyList()
        staleLine.value = false
        phase.value = Phase.PENDING
        errorMsg.value = null
    }

    fun swapEnds() {
        val o = origin.value ?: return
        val d = destination.value ?: return
        origin.value = d
        destination.value = o
        markStale()
    }

    fun setOrigin(stop: Stop) {
        origin.value = stop
        markStale()
    }

    fun setDestination(stop: Stop) {
        destination.value = stop
        markStale()
    }

    fun addWayStops(stops: List<Stop>) {
        if (stops.isEmpty()) return
        val existing = wayStops.value
        val merged = (existing + stops).distinctBy { it.placeId ?: it.key }
        if (merged.size > 8) {
            errorMsg.value = "途经点最多 8 个"
            return
        }
        wayStops.value = merged
        if (fromTimeline && orderEdited.value.not() && existing.isNotEmpty()) orderEdited.value = true
        markStale()
    }

    fun removeWay(index: Int) {
        wayStops.value = wayStops.value.toMutableList().apply { removeAt(index) }
        if (fromTimeline) orderEdited.value = true
        markStale()
    }

    /** 拖拽实时排序（只在途经点之间换位）。 */
    fun reorderWay(from: Int, to: Int) {
        if (from == to) return
        val list = wayStops.value.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        wayStops.value = list
    }

    /** 拖拽松手：只在此刻标记「改过顺序」。 */
    fun onReorderFinished() {
        if (fromTimeline) orderEdited.value = true
        markStale()
    }

    fun selectScheme(index: Int) {
        selectedScheme.value = index
    }

    /** 算路失败弹窗「知道了」/返回键/点外部关闭 = 清一次性弹窗态。 */
    fun clearPlanFailDialog() {
        planFailDialogMsg.value = null
    }

    /** 「确认路线」：只发一次请求（§6 S10）。 */
    fun confirmRoute() {
        val o = origin.value
        val d = destination.value
        when {
            o == null -> {
                errorMsg.value = if (myLocationFailed.value) "未获取到我的位置，点起点行改为点位或地图搜点" else "正在定位我的位置…"
                return
            }
            d == null -> {
                errorMsg.value = "请先设置终点"
                return
            }
            o.lat == d.lat && o.lng == d.lng -> {
                errorMsg.value = "起点和终点相同"
                return
            }
        }
        loading.value = true
        errorMsg.value = null
        viewModelScope.launch {
            try {
                val result = RoutePlanner.plan(
                    context = appContext,
                    mode = mode.value,
                    origin = LatLng(o.lat, o.lng),
                    destination = LatLng(d.lat, d.lng),
                    wayPoints = wayStops.value.map { LatLng(it.lat, it.lng) },
                    city = city,
                )
                if (result.isEmpty()) {
                    errorMsg.value = "未能规划出路线，可能是距离过远或该方式不支持此路线"
                } else {
                    schemes.value = result
                    selectedScheme.value = 0
                    phase.value = Phase.PLANNED
                    staleLine.value = false
                    // 遗留 #16（2026-11-18）：算路成功即预热系统 TTS——用户下一步大概率点
                    // 「开始导航 → 应用内导航」，引擎 init 提前跑完，首条播报不再撞冷启窗被 skip
                    //（2026-11-20 起应用内导航支持驾车/步行/骑行，公交仍不可用不预热）。
                    if (mode.value != TravelMode.TRANSIT) {
                        com.visualzyzy.chuxingdazi.data.navi.SystemTtsBridge.preheat(appContext)
                    }
                }
            } catch (e: RoutePlanException) {
                if (e.sdkMsg != null) {
                    // SDK 明说原因（373「起终点距离超长」等）→ 简短弹窗提示；不再红字双报
                    planFailDialogMsg.value = e.sdkMsg
                } else {
                    errorMsg.value = e.message
                }
            } catch (e: Exception) {
                errorMsg.value = "规划失败：${e.message ?: "未知错误"}"
            }
            loading.value = false
        }
    }
}
