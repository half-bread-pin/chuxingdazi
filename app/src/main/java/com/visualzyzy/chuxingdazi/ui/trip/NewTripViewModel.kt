package com.visualzyzy.chuxingdazi.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import com.visualzyzy.chuxingdazi.data.repo.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NewTripViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    placeRepository: PlaceRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val places: StateFlow<List<PlaceEntity>> =
        placeRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 第九轮：S24 选点浮层的搜索需要分类（多模式搜索的「分类」模式）。 */
    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(
        name: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        placeIds: Set<String>,
        placeTimes: Map<String, Pair<Int, Int>> = emptyMap(),
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val trip = tripRepository.create(
                name = name,
                startDate = startDate?.toString(),
                endDate = endDate?.toString(),
                initialPlaceIds = placeIds.toList(),
            )
            // P31（2026-11-02）：创建时已设置完整时间的点位直接落成安排（startMin/endMin 由 UI 算好）；
            // 结束跨天按 §5.3 约定存 end_min > 1440。没给时间的点位留在待安排池。
            if (startDate != null && placeTimes.isNotEmpty()) {
                val now = System.currentTimeMillis()
                placeTimes.forEach { (placeId, range) ->
                    val (startMin, endMin) = range
                    // 第九轮：0 分钟安排（开始=结束）允许；只有 end<start 才视为无效
                    if (endMin < startMin) return@forEach
                    runCatching {
                        tripRepository.addSchedule(
                            com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                tripId = trip.id,
                                placeId = placeId,
                                planDate = startDate.toString(),
                                startMin = startMin,
                                endMin = endMin,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                    }
                }
            }
            onCreated(trip.id)
        }
    }
}
