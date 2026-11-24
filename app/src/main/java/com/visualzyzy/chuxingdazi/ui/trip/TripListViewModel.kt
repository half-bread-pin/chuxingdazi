package com.visualzyzy.chuxingdazi.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripEntity
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import com.visualzyzy.chuxingdazi.data.repo.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@HiltViewModel
class TripListViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val scheduleRepository: com.visualzyzy.chuxingdazi.data.repo.ScheduleRepository,
    private val placeRepository: PlaceRepository,
) : ViewModel() {

    val trips: StateFlow<List<TripEntity>> =
        tripRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scheduleCountByTrip: StateFlow<Map<String, Int>> =
        scheduleRepository.countByTrip()
            .map { list -> list.associate { it.tripId to it.cnt } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val placeCountByTrip: StateFlow<Map<String, Int>> =
        tripRepository.observePlaceCountByTrip()
            .map { list -> list.associate { it.tripId to it.cnt } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun isOngoing(trip: TripEntity, today: LocalDate): Boolean {
        val s = TripTime.parse(trip.startDate) ?: return false
        val e = TripTime.parse(trip.endDate) ?: return false
        return !today.isBefore(s) && !today.isAfter(e)
    }

    /** §6 S23 排序：进行中 → 未来（start 升序）→ 未定日期 → 已结束（end 降序）。 */
    fun dateRangeText(trip: TripEntity): String {
        val s = TripTime.parse(trip.startDate)
        val e = TripTime.parse(trip.endDate)
        return when {
            s == null -> "未定日期"
            e == null -> "${TripTime.dateText(s)} 起"
            else -> "${TripTime.dateText(s)} – ${TripTime.dateText(e)} · ${
                java.time.temporal.ChronoUnit.DAYS.between(s, e).toInt() + 1
            } 天"
        }
    }

    fun sortTrips(trips: List<TripEntity>, today: LocalDate): List<TripEntity> = trips.sortedWith(
        compareBy(
            { trip ->
                val s = TripTime.parse(trip.startDate)
                val e = TripTime.parse(trip.endDate)
                when {
                    s != null && e != null && !today.isBefore(s) && !today.isAfter(e) -> 0 // 进行中
                    s != null && s.isAfter(today) -> 1 // 未来
                    s == null -> 2 // 未定日期
                    else -> 3 // 已结束
                }
            },
            { trip ->
                val s = TripTime.parse(trip.startDate)
                if (s != null && !s.isBefore(today)) {
                    java.time.temporal.ChronoUnit.DAYS.between(today, s)
                } else {
                    Long.MAX_VALUE / 2
                }
            },
            { trip ->
                val e = TripTime.parse(trip.endDate)
                e?.let { -java.time.temporal.ChronoUnit.DAYS.between(today, it) } ?: Long.MIN_VALUE
            },
        ),
    )

    fun rename(id: String, newName: String) {
        viewModelScope.launch { tripRepository.rename(id, newName) }
    }

    fun delete(id: String) {
        viewModelScope.launch { tripRepository.delete(id) }
    }
}

/** S05/S24/S23 共用的勾选列表数据。 */
data class SelectablePlace(val place: com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity, val selected: Boolean)

/** 简易时间日期存储（S24 临时选择）。 */
data class DateRange(val start: LocalDate?, val end: LocalDate?)
