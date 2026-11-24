package com.visualzyzy.chuxingdazi.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.visualzyzy.chuxingdazi.data.tencent.RoutePlanner
import com.visualzyzy.chuxingdazi.data.tencent.TravelMode
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithPlaceRow
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TrafficLegCacheEntity
import com.visualzyzy.chuxingdazi.data.db.dao.TrafficLegDao
import com.visualzyzy.chuxingdazi.data.db.entity.TripEntity
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import com.visualzyzy.chuxingdazi.data.repo.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * S14 行程时间轴（§6 S14）。
 * 数据刷新策略：所有写操作走本 VM，写完 suspend 重查一次（schedule 表没有 observe 流，够用且省一条 DAO）。
 */
@HiltViewModel
class TripTimelineViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val placeRepository: PlaceRepository,
    private val trafficLegDao: TrafficLegDao,
    categoryRepository: CategoryRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    val trip = MutableStateFlow<TripEntity?>(null)
    val schedules = MutableStateFlow<List<ScheduleWithPlaceRow>>(emptyList())
    val pendingPlaces = MutableStateFlow<List<PlaceEntity>>(emptyList())
    val tripPlaces = MutableStateFlow<List<PlaceEntity>>(emptyList())

    /** 全部点位（S25「＋添加」候选池，屏层过滤已在行程的）。 */
    val allPlaces: StateFlow<List<PlaceEntity>> = placeRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categoriesById: StateFlow<Map<String, CategoryEntity>> = categoryRepository.observeAll()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ---------- P7 交通腿（用户点击计算，结果缓存于 traffic_leg_cache） ----------

    /** 缓存 key = "fromId_toId_MODE"。 */
    fun legKey(fromPlaceId: String, toPlaceId: String, mode: String) = "${fromPlaceId}_${toPlaceId}_$mode"

    val legCache: StateFlow<Map<String, TrafficLegCacheEntity>> = trafficLegDao.observeAll()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _computingLegs = MutableStateFlow<Set<String>>(emptySet())
    val computingLegs: StateFlow<Set<String>> = _computingLegs.asStateFlow()

    /** 一次性提示（计算失败等），屏层收了 Toast。 */
    private val _legEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val legEvents = _legEvents.asSharedFlow()

    /** 调腾讯检索按所选方式算一段（结果按当前时刻/当前路况；公交不支持传出发时间，§5.3）。 */
    fun computeLeg(from: PlaceEntity, to: PlaceEntity, mode: TravelMode) {
        val key = legKey(from.id, to.id, mode.name)
        if (key in _computingLegs.value) return
        _computingLegs.value = _computingLegs.value + key
        viewModelScope.launch {
            val scheme = runCatching {
                RoutePlanner.planLeg(
                    context = appContext,
                    mode = mode,
                    from = LatLng(from.lat, from.lng),
                    to = LatLng(to.lat, to.lng),
                    city = "全国",
                )
            }.getOrNull()
            if (scheme != null) {
                trafficLegDao.upsert(
                    TrafficLegCacheEntity(
                        id = key,
                        fromPlaceId = from.id,
                        toPlaceId = to.id,
                        mode = mode.name,
                        minutes = (scheme.durationSec / 60).coerceAtLeast(1),
                        distanceM = scheme.distanceM,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                _legEvents.tryEmit("未能计算路线，请检查网络后重试")
            }
            _computingLegs.value = _computingLegs.value - key
        }
    }

    /** 用户手动填写用时（去百度地图 App 按实际时刻查完回来填；不覆盖自动查询结果，存 MANUAL 档）。 */
    fun saveManualLeg(from: PlaceEntity, to: PlaceEntity, minutes: Int) {
        if (minutes <= 0) return
        viewModelScope.launch {
            trafficLegDao.upsert(
                TrafficLegCacheEntity(
                    id = legKey(from.id, to.id, "MANUAL"),
                    fromPlaceId = from.id,
                    toPlaceId = to.id,
                    mode = "MANUAL",
                    minutes = minutes,
                    distanceM = 0,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 清除这一对点位的全部交通腿结果（各方式 + 手填）。 */
    fun clearLeg(fromPlaceId: String, toPlaceId: String) {
        viewModelScope.launch {
            (TravelMode.entries.map { it.name } + "MANUAL").forEach { mode ->
                trafficLegDao.delete(legKey(fromPlaceId, toPlaceId, mode))
            }
        }
    }

    private var tripId: String = ""

    fun load(id: String) {
        if (tripId == id && trip.value != null) return
        tripId = id
        refresh()
    }

    fun refresh() {
        if (tripId.isBlank()) return
        viewModelScope.launch {
            trip.value = tripRepository.getById(tripId)
            schedules.value = tripRepository.schedulesForTrip(tripId)
            pendingPlaces.value = tripRepository.unscheduledPlaces(tripId)
            tripPlaces.value = tripRepository.placesForTrip(tripId)
        }
    }

    /** S25「＋添加」：把点位库里的点位加入本行程（进待安排池）。 */
    fun addPlacesToTrip(placeIds: List<String>) {
        if (placeIds.isEmpty()) return
        viewModelScope.launch {
            tripRepository.addPlaces(tripId, placeIds)
            refresh()
        }
    }

    /** 色块拖拽 → 整段平移（15 分钟吸附由 UI 算好）。跨 0 点时换算 plan_date（§5.3 存储约定）。 */
    /**
     * P4 长按菜单「上移/下移」：与同日相邻安排交换 start_min（保持各自时长，§6 S14）。
     * @param direction -1=上移（更早），+1=下移（更晚）
     */
    fun moveSchedule(scheduleId: String, direction: Int) {
        val rows = schedules.value.sortedBy {
            TripTime.absMinutes(TripTime.dayOffset(TripTime.tripStart(trip.value?.startDate), it.schedule.planDate), it.schedule.startMin)
        }
        val idx = rows.indexOfFirst { it.schedule.id == scheduleId }
        val target = idx + direction
        if (idx < 0 || target !in rows.indices) return
        val cur = rows[idx].schedule
        val other = rows[target].schedule
        // 同日才交换：把对方的开始时间借过来，各自保持原时长
        val curDuration = cur.endMin - cur.startMin
        val otherDuration = other.endMin - other.startMin
        viewModelScope.launch {
            runCatching {
                tripRepository.updateSchedule(other.copy(startMin = cur.startMin, endMin = cur.startMin + otherDuration, updatedAt = System.currentTimeMillis()))
                tripRepository.updateSchedule(cur.copy(startMin = other.startMin, endMin = other.startMin + curDuration, updatedAt = System.currentTimeMillis()))
            }
            refresh()
        }
    }

    fun shiftSchedule(scheduleId: String, deltaMin: Int) {
        if (deltaMin == 0) return
        val row = schedules.value.firstOrNull { it.schedule.id == scheduleId } ?: return
        val start = TripTime.tripStart(trip.value?.startDate)
        val dayOffset = TripTime.dayOffset(start, row.schedule.planDate)
        val newAbs = TripTime.absMinutes(dayOffset, row.schedule.startMin) + deltaMin
        if (newAbs < 0) return
        val (newDayOffset, newStartMin) = TripTime.splitAbs(newAbs)
        val newDate = start.plusDays(newDayOffset.toLong()).toString()
        viewModelScope.launch {
            tripRepository.updateSchedule(
                row.schedule.copy(
                    planDate = newDate,
                    startMin = newStartMin,
                    endMin = row.schedule.endMin + deltaMin,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            refresh()
        }
    }

    /**
     * P5 拖色块上下边缘改时间（§6 S14）：edge 0=上边缘改开始（结束保持），1=下边缘改结束（开始保持）。
     * 第九轮：最短时长放宽到 0 分钟（开始=结束，§6 S14 色块持续可为 0）；拖拽 15 分钟吸附不变。
     * 改开始跨 0 点时换算 plan_date（§5.3），改结束保持与 shiftSchedule 相同的 endMin 直加约定。
     */
    fun resizeSchedule(scheduleId: String, edge: Int, deltaMin: Int) {
        if (deltaMin == 0) return
        val row = schedules.value.firstOrNull { it.schedule.id == scheduleId } ?: return
        val sch = row.schedule
        val tripStart = TripTime.tripStart(trip.value?.startDate)
        val dayOffset = TripTime.dayOffset(tripStart, sch.planDate)
        if (edge == 0) {
            val absStart = TripTime.absMinutes(dayOffset, sch.startMin) + deltaMin
            val absEnd = TripTime.absMinutes(dayOffset, sch.endMin)
            if (absStart < 0 || absEnd - absStart < 0) return
            val (newDayOffset, newStartMin) = TripTime.splitAbs(absStart)
            viewModelScope.launch {
                tripRepository.updateSchedule(
                    sch.copy(
                        planDate = tripStart.plusDays(newDayOffset.toLong()).toString(),
                        startMin = newStartMin,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                refresh()
            }
        } else {
            val newEnd = sch.endMin + deltaMin
            if (newEnd < sch.startMin) return
            viewModelScope.launch {
                tripRepository.updateSchedule(
                    sch.copy(endMin = newEnd, updatedAt = System.currentTimeMillis()),
                )
                refresh()
            }
        }
    }

    /** S15「移出时间轴」：只删这一段，不动点位（§6 S15）。 */
    fun removeSchedule(scheduleId: String) {
        viewModelScope.launch {
            tripRepository.deleteSchedule(scheduleId)
            refresh()
        }
    }

    fun saveSchedule(
        schedule: ScheduleEntity,
        onDone: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch {
            val isNew = schedules.value.none { it.schedule.id == schedule.id }
            try {
                if (isNew) tripRepository.addSchedule(schedule) else tripRepository.updateSchedule(schedule)
                // 第十六轮（2026-11-14）：不再自动把最早安排日写进行程 start_date——
                // 未定日期行程的日期只能由用户「选定这几天」显式设置（优化建议：不悄悄填）。
                refresh()
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message ?: "保存失败")
            }
        }
    }

    /**
     * 第十六轮：「选定这几天」——为未定日期（或任意）行程显式设置起止日期。
     * 第三十二轮（2026-11-15）：区间外的安排随改日期移回待安排（repo 同事务删除），
     * removed>0 时经 onRemoved 回调给屏层弹 Toast 说明（安排静默消失会让人困惑）。
     */
    fun setTripDates(startDate: String, endDate: String, onRemoved: ((Int) -> Unit)? = null) {
        val id = tripId
        if (id.isBlank()) return
        viewModelScope.launch {
            val removed = tripRepository.updateDates(id, startDate, endDate)
            refresh()
            onRemoved?.invoke(removed)
        }
    }

    /**
     * 第十三轮（2026-11-12）：「按这天走」——某天的安排数（与 §5.3 核心查询 1 同口径：
     * plan_date ≤ d 且跨天段（end_min-1）/1440 天后仍 ≥ d 的都算覆盖到 d）。
     */
    fun schedulesOnDay(date: String): List<ScheduleWithPlaceRow> {
        val d = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return emptyList()
        return schedules.value.filter { row ->
            val pd = runCatching { java.time.LocalDate.parse(row.schedule.planDate) }.getOrNull() ?: return@filter false
            val endD = pd.plusDays(((row.schedule.endMin - 1) / 1440).toLong())
            !pd.isAfter(d) && !endD.isBefore(d)
        }
    }

    fun toggleVisited(place: PlaceEntity) {
        viewModelScope.launch {
            placeRepository.setVisited(place.id, !place.visited)
            refresh()
        }
    }

    /** S15 新建一段的默认时间（§6 S15：分类常用时段，event 读 field_values）。 */
    fun defaultTimesFor(place: PlaceEntity, category: CategoryEntity?, tripStart: LocalDate): Pair<Int, Int> {
        // 1) 活动类 field_values 里已有 startAt/endAt → 优先用（§6 S15「与分类字段的关系」）
        place.fieldValues?.let { json ->
            val parsed = parseEventTimes(json)
            if (parsed != null) return parsed
        }
        // 2) 分类常用时段
        val start = when {
            category?.name?.contains("美食") == true || category?.name?.contains("餐") == true -> 11 * 60 + 30
            category?.name?.contains("住宿") == true || category?.name?.contains("酒店") == true -> 20 * 60
            else -> 9 * 60
        }
        return Pair(start, start + 120)
    }

    private fun parseEventTimes(json: String): Pair<Int, Int>? {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
                as? kotlinx.serialization.json.JsonObject ?: return null
            val start = obj["startAt"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val end = obj["endAt"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val startMin = timeToMinutes(start) ?: return null
            val endMin = timeToMinutes(end) ?: (startMin + 120)
            Pair(startMin, maxOf(endMin, startMin + 15))
        } catch (e: Exception) {
            null
        }
    }

    private fun timeToMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val m = Regex("(\\d{1,2}):(\\d{2})").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }
}
