package com.visualzyzy.chuxingdazi.data.tencent

import android.content.Context
import android.os.Handler
import android.os.Looper

/** 一次性定位（S10 起点「我的位置」；超时返回 null,null）。GCJ02 直出。
 *  定位链路见 [NaviLocation]（进程级多播，engine 只挂一个 core listener）。 */
object OneShotLocator {

    /**
     * 首个有效回调即注销并回传；超时未得则返回 null,null。
     */
    fun locate(context: Context, timeoutMs: Long = 5000L, onResult: (Double?, Double?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var done = false
        var remove: (() -> Unit)? = null
        val finish = { lat: Double?, lng: Double? ->
            if (!done) {
                done = true
                handler.removeCallbacksAndMessages(null)
                remove?.invoke()
                onResult(lat, lng)
            }
        }
        // 2026-11-19 用户定调：定位回调间隔 3000ms（与 NaviLocation 默认一致）
        remove = NaviLocation.addObserver(context, 3000) { t ->
            if (t.getLatitude() == 0.0 && t.getLongitude() == 0.0) return@addObserver
            finish(t.getLatitude(), t.getLongitude())
        }
        handler.postDelayed({
            finish(null, null)
        }, timeoutMs)
    }
}
