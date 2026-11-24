package com.visualzyzy.chuxingdazi.ui.trip

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.db.entity.TripEntity
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import com.visualzyzy.chuxingdazi.data.repo.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * S26 行程地图节点（§6 S26）：已排时间的点位按绝对时间排序，全程连续编号。
 * 2026-11-23 #6 三次改定：`dayOffset` → `dayOffsets`（所属天集合）；
 * 「过夜段 + 次日早段同点位」合并为**一个节点一个号**（边界号被相邻两天共享）——
 * 只改本视图模型，schedule 表 / S14 时间轴 / 备份导出零改动。
 */
data class TripMapNode(
    val scheduleId: String, // 代表段 id（合并点 = 次日早段 B 的 id）
    val placeId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** 所属天集合（时间序）；普通节点单元素，合并节点 = {过夜段起始天, 次日早段所属天}。 */
    val dayOffsets: List<Int>,
    /** 节点卡口径 = 出发时刻（合并点取次日早段 B 的 startMin）。 */
    val startMin: Int,
    val colorHex: String?,
    /** 全程排序锚（合并点取过夜段 A 的绝对开始分钟）。 */
    val startAbs: Int,
    /** 合并点的过夜段（A）起止绝对分钟；普通节点为 null。节点卡「过夜 M/d HH:mm – M/d HH:mm」用。 */
    val overnightStartAbs: Int? = null,
    val overnightEndAbs: Int? = null,
)

@HiltViewModel
class TripMapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tripRepository: TripRepository,
    private val placeRepository: PlaceRepository,
) : ViewModel() {

    val trip = MutableStateFlow<TripEntity?>(null)
    val nodes = MutableStateFlow<List<TripMapNode>>(emptyList())
    val unscheduledCount = MutableStateFlow(0)

    init {
        val tripId = savedStateHandle.get<String>("tripId").orEmpty()
        if (tripId.isNotBlank()) load(tripId)
    }

    private fun load(tripId: String) {
        viewModelScope.launch {
            trip.value = tripRepository.getById(tripId)
            val rows = tripRepository.schedulesForTrip(tripId)
            val start = TripTime.tripStart(trip.value?.startDate)
            // 2026-11-23 #6：先按绝对时间排序，再逐对检查「过夜段 + 次日早段同点位」合并。
            // 合并三条件：A.placeId == B.placeId；A 跨天（endMin ≥ 1440）；B 的 planDate 恰为
            // A 的结束日。合并后一个节点一个号，坐标取该点位，所属天 = {A 起始天, B 所属天}；
            // 连住多晚每晚各合一次（两枚合并点 = 到达点 + 出发点，正确语义）。
            val resolved = rows.mapNotNull { row ->
                placeRepository.getById(row.schedule.placeId)?.let { row to it }
            }.sortedBy { (row, _) ->
                TripTime.absMinutes(TripTime.dayOffset(start, row.schedule.planDate), row.schedule.startMin)
            }
            val built = mutableListOf<TripMapNode>()
            var i = 0
            while (i < resolved.size) {
                val (a, placeA) = resolved[i]
                val aDay = TripTime.dayOffset(start, a.schedule.planDate)
                val aStartAbs = TripTime.absMinutes(aDay, a.schedule.startMin)
                val next = resolved.getOrNull(i + 1)
                val mergesWithNext = next != null &&
                    next.first.schedule.placeId == placeA.id &&
                    a.schedule.endMin >= 1440 &&
                    next.first.schedule.planDate ==
                    LocalDate.parse(a.schedule.planDate).plusDays(a.schedule.spannedDays.toLong()).toString()
                if (mergesWithNext && next != null) {
                    val (b, _) = next
                    val bDay = TripTime.dayOffset(start, b.schedule.planDate)
                    built.add(
                        TripMapNode(
                            scheduleId = b.schedule.id,
                            placeId = placeA.id,
                            name = placeA.name,
                            lat = placeA.lat,
                            lng = placeA.lng,
                            dayOffsets = listOf(aDay, bDay).distinct().sorted(),
                            startMin = b.schedule.startMin,
                            colorHex = null, // 屏幕层按天配色，不用分类色
                            startAbs = aStartAbs,
                            overnightStartAbs = aStartAbs,
                            overnightEndAbs = aDay * 1440 + a.schedule.endMin,
                        ),
                    )
                    i += 2
                } else {
                    built.add(
                        TripMapNode(
                            scheduleId = a.schedule.id,
                            placeId = placeA.id,
                            name = placeA.name,
                            lat = placeA.lat,
                            lng = placeA.lng,
                            dayOffsets = listOf(aDay),
                            startMin = a.schedule.startMin,
                            colorHex = null,
                            startAbs = aStartAbs,
                        ),
                    )
                    i += 1
                }
            }
            nodes.value = built.sortedBy { it.startAbs }
            unscheduledCount.value = tripRepository.unscheduledPlaces(tripId).size
        }
    }
}
