package com.visualzyzy.chuxingdazi.ui.route

import javax.inject.Inject
import javax.inject.Singleton

/**
 * S11/S21 与 S10 之间的一次性结果传递。
 * Navigation Compose 的 savedStateHandle + JSON 序列化在本工程真机上不可靠（回传丢失），
 * 改为进程内单例：S11 写入 → popBackStack → S10 重组时 consume。
 */
@Singleton
class RouteTransfer @Inject constructor() {
    var pendingWayStops: List<StopPayload>? = null
    var pendingOrigin: StopPayload? = null
    var pendingDestination: StopPayload? = null

    fun consumeWayStops(): List<StopPayload>? = pendingWayStops.also { pendingWayStops = null }
    fun consumeOrigin(): StopPayload? = pendingOrigin.also { pendingOrigin = null }
    fun consumeDestination(): StopPayload? = pendingDestination.also { pendingDestination = null }
}
