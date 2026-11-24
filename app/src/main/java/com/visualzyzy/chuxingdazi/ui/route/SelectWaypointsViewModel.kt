package com.visualzyzy.chuxingdazi.ui.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * S11 选择途经点（§6 S11）：勾选追加到末尾、长按拖拽调序、
 * 「少绕路」= 以当前顺序为初始解的 2-opt 局部改进（不重排用户顺序，只局部优化）。
 */
@HiltViewModel
class SelectWaypointsViewModel @Inject constructor(
    placeRepository: PlaceRepository,
    categoryRepository: CategoryRepository,
    private val routeTransfer: RouteTransfer,
) : ViewModel() {

    val places: StateFlow<List<PlaceEntity>> =
        placeRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 第十七轮：选择段搜索区换 ui/common 多模式点位搜索（与清单添加点位同款），需要分类列表。 */
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 已选（按顺序）。 */
    val selected = MutableStateFlow<List<PlaceEntity>>(emptyList())
    val toast = MutableStateFlow<String?>(null)

    /** 完成：写入单例回传给 S10。 */
    fun commitTransfer() {
        routeTransfer.pendingWayStops = selected.value.map {
            StopPayload(name = it.name, lat = it.lat, lng = it.lng, placeId = it.id)
        }
    }

    fun toggle(place: PlaceEntity) {
        val list = selected.value
        selected.value = if (list.any { it.id == place.id }) {
            list.filterNot { it.id == place.id }
        } else {
            list + place
        }
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        val list = selected.value.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        selected.value = list
    }

    fun removeAt(index: Int) {
        selected.value = selected.value.toMutableList().apply { removeAt(index) }
    }

    fun consumeToast() {
        toast.value = null
    }

    /** 2-opt 局部改进（点数 ≤8 时毫秒级；不改用户给的端点语义——首尾也参与交换的候选）。 */
    fun twoOpt() {
        val list = selected.value
        if (list.size < 3) {
            toast.value = "至少选 3 个点才需要优化"
            return
        }
        val pts = list.map { it.lat to it.lng }
        val before = pathLength(pts)
        var improved = true
        val cur = pts.toMutableList()
        var guard = 0
        while (improved && guard < 64) {
            improved = false
            guard++
            for (i in 0 until cur.size - 1) {
                for (j in i + 1 until cur.size) {
                    val candidate = cur.subList(0, i) + cur.subList(i, j + 1).reversed() + cur.subList(j + 1, cur.size)
                    if (pathLength(candidate) < pathLength(cur) - 1e-6) {
                        cur.clear()
                        cur.addAll(candidate)
                        improved = true
                    }
                }
            }
        }
        if (pathLength(cur) < before - 1.0) {
            val order = cur.map { p -> list.first { it.lat == p.first && it.lng == p.second } }
            selected.value = order
            toast.value = "已调整为少走一些路"
        } else {
            toast.value = "当前顺序已经比较顺了"
        }
    }

    companion object {
        fun pathLength(pts: List<Pair<Double, Double>>): Double {
            var total = 0.0
            for (i in 0 until pts.size - 1) total += haversineMeters(pts[i], pts[i + 1])
            return total
        }

        fun haversineMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(b.first - a.first)
            val dLng = Math.toRadians(b.second - a.second)
            val la = Math.toRadians(a.first)
            val lb = Math.toRadians(b.first)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(la) * cos(lb) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
