package com.visualzyzy.chuxingdazi.data.tencent

import android.content.Context
import android.os.Looper
import android.util.Pair
import com.tencent.map.geolocation.TencentLocation
import com.tencent.map.geolocation.TencentLocationListener
import com.tencent.map.geolocation.TencentLocationManager
import com.tencent.map.geolocation.TencentLocationManagerOptions
import com.tencent.map.geolocation.TencentLocationRequest
import com.visualzyzy.chuxingdazi.data.navi.NaviSdk

/**
 * 定位统一入口（2026-11-19 定位问题定案后的最终形态：平时链路直连定位 SDK + 进程级多播）。
 *
 * 分工（用户定调）：
 * - **平时**（S03 蓝点 / S10 起点「我的位置」/ 退出导航后的重定位）——本类，直连
 *   `TencentLocationManager`（定位 SDK 官方文档化链路）；
 * - **导航期间**（NavGuideActivity）——navix LocationApi + GeoLocationObserver，
 *   引擎由 native startNavigation 拉起、key 由 setDeveloperKey 透传，不走本类。
 *
 * 本轮定案的两个时序铁律（此前 navix 包装静默吞码掩盖，真机实锤）：
 * 1. **libnavicore.so 的 JNI_OnLoad** 注册定位栈 native 方法（wb 构造的 key 检查依赖
 *    e.v 等）——`NaviSdk.ensureInit` 只是 Java 配置阶段不加载它，必须显式
 *    `System.loadLibrary("navicore")`，否则 `requestLocationUpdates` 返回码 2
 *    （UnsatisfiedLinkError 被引擎吞掉伪装成「key 不正确」）。navicore 的预载在
 *    `TencentSdk.ensureInitialized/applyKey`（App 启动 key 就绪时 / S02 填 key 成功时，
 *    均无运行中地图）统一做——**不能等地图渲染后再加载**：JNI_OnLoad 里的
 *    MapJNI register_jni_opensdk（Nerd early init）会重置全局地图数据层，运行中加载
 *    会把地图渲染/触摸管线冻死。本类 ensureEngine 里的 loadLibrary 仅作幂等兜底。
 * 2. **单 listener 槽模型**：7.10.0 内部定位栈只支持一个 listener——第二个
 *    requestLocationUpdates 会顶掉常驻 listener 且新 listener 也拿不到回调。因此引擎
 *    永远只挂一个 core listener，app 层订阅者集合多播分发；订阅清零自动停引擎
 *    （官方「不再需要时务必停止」省电要求）。
 *
 * key 注入：`TencentLocationManagerOptions.setKey(key)` 静态槽——必须在定位栈单例
 * （TxLocMgrImpl/wb）构造**之前**调（构造时只读一次）。落地：
 * `TencentSdk.applyLocationKey`（key 落地即注入）+ 本类 ensureEngine 兜底
 * （引擎未启动才走，此时单例必未构造）。
 *
 * 隐私：nav 自带定位栈无独立 setUserAgreePrivacy，由 NaviSdk.ensureInit 的
 * NavigatorConfig.setUserAgreedPrivacy(true) 透传。
 */
object NaviLocation {

    private val lock = Any()
    /** 订阅者（app 层多播；key = 唯一订阅 id）。 */
    private val subscribers = LinkedHashMap<Int, (TencentLocation) -> Unit>()
    private var nextSubId = 1

    private var manager: TencentLocationManager? = null
    private var coreListener: CoreListener? = null
    private var engineStarted = false

    /**
     * 注册定位订阅（平时链路）。首个订阅者拉起引擎（幂等），之后全部走多播分发——
     * 订阅者清零时停引擎。
     * @return 注销函数（幂等可重复调用）
     */
    fun addObserver(
        context: Context,
        // 2026-11-19 用户定调：定位回调间隔 3000ms（原 1000ms）
        intervalMs: Int = 3000,
        onLocation: (TencentLocation) -> Unit,
    ): () -> Unit {
        ensureEngine(context.applicationContext, intervalMs.coerceAtLeast(1000))
        var subId = -1
        synchronized(lock) {
            subId = nextSubId++
            subscribers[subId] = onLocation
        }
        var removed = false
        return {
            if (!removed) {
                removed = true
                var empty = false
                synchronized(lock) {
                    subscribers.remove(subId)
                    empty = subscribers.isEmpty()
                }
                if (empty) stopEngine()
            }
        }
    }

    /** 引擎初始化（幂等）：setKey → ensureInit → loadLibrary(navicore) → 单次 requestLocationUpdates。 */
    private fun ensureEngine(appContext: Context, intervalMs: Int) {
        synchronized(lock) {
            if (engineStarted) return
            val key = TencentSdk.currentKey
            if (key.isNullOrBlank()) return
            // 时序铁律 1：setKey 先于 TencentLocationManager.getInstance（单例构造时只读一次）
            runCatching { TencentLocationManagerOptions.setKey(key) }
            // 隐私透传 + navix 原生初始化时序（幂等；定位引擎独立于 zygote，失败不阻断）
            NaviSdk.ensureInit(appContext)
            // 时序铁律 2：定位栈 native 方法（e.v 等）由 libnavicore 的 JNI_OnLoad 注册，
            // ensureInit 的 Java 配置阶段不加载它——幂等兜底（正常路径已在 TencentSdk 预载）
            runCatching { System.loadLibrary("navicore") }
            val mgr = TencentLocationManager.getInstance(
                appContext,
                Pair(com.tencent.map.fusionlocation.TencentLocationAdapter.TYPE_QIMEI, key),
            )
            val listener = coreListener ?: CoreListener().also { coreListener = it }
            val request = TencentLocationRequest.create()
                .setInterval(intervalMs.toLong())
                .setRequestLevel(TencentLocationRequest.REQUEST_LEVEL_ADMIN_AREA)
                .setAllowGPS(true)
                .setAllowCache(true)
            // 返回码：0 成功 / 1 缺硬件 / 2 key 不正确 / 3 libtencentloc 加载失败 / 4 隐私未同意
            val rc = runCatching {
                mgr.requestLocationUpdates(request, listener, Looper.getMainLooper(), 0)
            }.getOrDefault(-1)
            if (rc == 0) {
                manager = mgr
                engineStarted = true
            }
        }
    }

    /** 订阅者清零：停引擎（removeUpdates(null) 彻底停止，官方省电要求）。 */
    private fun stopEngine() {
        synchronized(lock) {
            val mgr = manager
            if (mgr != null && engineStarted) {
                runCatching { mgr.removeUpdates(null) }
            }
            engineStarted = false
            manager = null
        }
    }

    /**
     * 进程级唯一 core listener（引擎侧单槽模型的正解）：无效回调过滤 + 多播分发。
     */
    private class CoreListener : TencentLocationListener {

        override fun onLocationChanged(location: TencentLocation?, error: Int, reason: String?) {
            if (location == null || error != TencentLocation.ERROR_OK) return
            val snapshot = synchronized(lock) { subscribers.values.toList() }
            snapshot.forEach { it(location) }
        }

        override fun onStatusUpdate(name: String?, status: Int, desc: String?) = Unit

        override fun onGnssInfoChanged(info: Any?) = Unit

        override fun onNmeaMsgChanged(msg: String?) = Unit
    }
}
