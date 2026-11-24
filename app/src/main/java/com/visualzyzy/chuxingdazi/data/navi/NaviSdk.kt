package com.visualzyzy.chuxingdazi.data.navi

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import com.tencent.navix.api.NavigatorZygote
import com.tencent.navix.api.NavigatorConfig
import com.tencent.navix.api.model.NavSearchPoint
import com.tencent.navix.api.model.NavTTSInfo
import com.tencent.navix.api.navigator.NavigatorDrive
import com.tencent.navix.api.navigator.NavigatorRide
import com.tencent.navix.api.navigator.NavigatorWalk
import com.tencent.navix.api.plan.RoutePlanRequester
import com.tencent.navix.api.plan.RoutePlanRequestCallback
import com.tencent.navix.api.model.NavRoutePlan
import com.tencent.navix.api.model.NavDriveRoute
import com.tencent.navix.api.model.NavNonMotorRoute
import com.tencent.navix.api.model.NavError
import com.tencent.navix.api.model.NavRouteReqParam
import com.tencent.navix.api.plan.DriveRoutePlanOptions
import com.tencent.navix.api.tts.NavigatorTTSPlayer
import com.visualzyzy.chuxingdazi.data.tencent.TravelMode
import com.visualzyzy.chuxingdazi.data.tencent.TencentSdk
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 导航 SDK 门面（腾讯 navix 版，2026-11-17 重写；决策 #35 系统语音桥接沿用）：
 * - 默认系统 TTS：实现 NavigatorTTSPlayer 把播报文本桥接到 android.speech.tts；
 * - SDK 内置 TTS（tencent-map-nav-sdk-tts）为可选升级，v1 不启用。
 *
 * 与百度版差异：
 * - init = NavigatorZygote.with(ctx).init(config).start()（6.11.0 起需显式 start）；
 * - 算路走 navigator.searchRoute（RoutePlanRequester），成功后记录 routeId，
 *   NavGuideActivity bindView 后 startNavigation(routeId)——百度版是 MSG 导航消息投递；
 * - 「引导开始」有真回调 onDidStartNavigation（第 23 轮百度版只能靠视图树轮询/播报探测的问题
 *   在 navix 上不存在，NavGuideActivity 的「等待定位」层改挂该回调）。
 */
object NaviSdk {

    enum class State { NOT_INIT, INITING, READY, FAILED }

    /**
     * 应用内导航引擎的出行方式（2026-11-20 新增步行/骑行）：
     * navix 7.10.0 原生提供 NavigatorWalk / NavigatorRide（官方文档 AndroidNavigationX
     * 步行导航/骑行导航专页 + AAR 双重印证）；公交无对应导航器 → TRANSIT 不映射。
     */
    enum class NaviTravelMode { DRIVING, WALKING, BIKING }

    /** S10 的 TravelMode → 导航引擎方式；公交返回 null（无导航器，S16 置灰口径）。 */
    fun naviTravelModeOf(mode: TravelMode): NaviTravelMode? = when (mode) {
        TravelMode.DRIVING -> NaviTravelMode.DRIVING
        TravelMode.WALKING -> NaviTravelMode.WALKING
        TravelMode.BIKING -> NaviTravelMode.BIKING
        TravelMode.TRANSIT -> null
    }

    val state = MutableStateFlow(State.NOT_INIT)
    var initMessage: String? = null
        private set

    /**
     * 最近一次导航页销毁时刻（elapsedRealtime）。原百度版：退出导航后定位管线卡死时，
     * RouteScreen 据此重启定位客户端——腾讯版保留同钩子（真机验证后若无需可移除）。
     */
    @Volatile
    var lastGuideDestroyAt: Long = 0

    /** 到达信号：onWillArriveDestination（SDK 判达终点，比百度版「导航结束」播报探测可靠）。 */
    var onGuidanceEnd: (() -> Unit)? = null

    /** 算路成功后待启动的路线 id（NavGuideActivity onCreate 里 startNavigation 用）。 */
    @Volatile
    var pendingRouteId: String? = null

    /** 算路成功后的引擎方式（NavGuideActivity 据此选择视图根/导航器/observer）。 */
    @Volatile
    var pendingTravelMode: NaviTravelMode = NaviTravelMode.DRIVING

    /** 室内调试用：沿路线模拟定位（SimulateConfig.SIMULATE_LOCATIONS_ALONG_ROUTE）。 */
    @Volatile
    var simulateEnabled: Boolean = false

    @Volatile
    private var inited = false

    private var driveNavigator: NavigatorDrive? = null
    private var walkNavigator: NavigatorWalk? = null
    private var rideNavigator: NavigatorRide? = null

    fun ensureInit(context: Context, onReady: () -> Unit = {}, onFail: (String) -> Unit = {}) {
        if (inited) {
            onReady()
            return
        }
        if (state.value == State.INITING) return
        state.value = State.INITING
        val appContext = context.applicationContext
        val key = TencentSdk.currentKey
        if (key.isNullOrBlank()) {
            state.value = State.FAILED
            initMessage = "no key"
            onFail("未配置地图密钥，无法启动导航")
            return
        }
        runCatching {
            val zygote = NavigatorZygote.with(appContext)
            zygote.init(
                NavigatorConfig.builder()
                    .setUserAgreedPrivacy(true) // 6.11.0 起必须
                    .setDeveloperKey(key)
                    .setDeviceModel(Build.MODEL)
                    // E7 排查 #20（2026-11-20 晚）：对齐官方 demo NavApplication 配置——
                    // demo 全局 experiment().setUseSharedMap(false)。本 App 是 vector 地图 +
                    // 导航共存宿主（demo 是纯导航工程无 vector），共享地图分支在双 SDK 进程
                    // 内的行为是步行/骑行崩溃嫌疑面（驾车引擎不走该分支）。
                    .experiment().setUseSharedMap(false)
                    .build(),
            )
            zygote.start()
            inited = true
            state.value = State.READY
            initMessage = "initSuccess"
            onReady()
        }.onFailure { e ->
            state.value = State.FAILED
            initMessage = "initFailed=${e.message}"
            onFail("导航引擎初始化失败(${e.message ?: "未知错误"})")
        }
    }

    /** 已初始化后的方式化导航器单例（导航页 bindView / 算路共用同一实例；按方式各缓存一份）。 */
    fun navigator(context: Context, mode: NaviTravelMode = NaviTravelMode.DRIVING): com.tencent.navix.api.Navigator? {
        ensureInit(context)
        if (!inited) return null
        return runCatching {
            when (mode) {
                NaviTravelMode.DRIVING -> driveNavigator ?: NavigatorZygote.with(context.applicationContext)
                    .navigator(NavigatorDrive::class.java)!!.also { driveNavigator = it }
                NaviTravelMode.WALKING -> walkNavigator ?: NavigatorZygote.with(context.applicationContext)
                    .navigator(NavigatorWalk::class.java)!!.also { walkNavigator = it }
                NaviTravelMode.BIKING -> rideNavigator ?: NavigatorZygote.with(context.applicationContext)
                    .navigator(NavigatorRide::class.java)!!.also { rideNavigator = it }
            }
        }.getOrNull()
    }

    /**
     * 发起应用内导航算路（§6 S12；驾车专用——步行/骑行 S16 置灰等 SDK 修复）。
     * 成功 = [pendingRouteId] + [pendingTravelMode] 已记录 + onResult(true)，
     * 调用方随即启动 NavGuideActivity；失败给原因。
     * 官方文档口径：步行/骑行算路只有起点/终点（无途经点参数）——途经点仅驾车可用。
     */
    fun routePlanToNavi(
        context: Context,
        start: Pair<Double, Double>,
        via: List<Pair<Double, Double>>,
        end: Pair<Double, Double>,
        travelMode: TravelMode,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val naviMode = naviTravelModeOf(travelMode)
        if (naviMode == null) {
            onResult(false, "应用内导航暂不支持公交")
            return
        }
        val navigator = navigator(context, naviMode)
        if (navigator == null) {
            onResult(false, "导航引擎未就绪")
            return
        }
        fun point(lat: Double, lng: Double) = NavSearchPoint(lat, lng)
        val requester = when (naviMode) {
            NaviTravelMode.DRIVING -> RoutePlanRequester.Companion.newBuilder(NavRouteReqParam.TravelMode.TravelModeDriving)
                .start(point(start.first, start.second))
                .wayPoints(via.map { point(it.first, it.second) })
                .end(point(end.first, end.second))
                .options(DriveRoutePlanOptions.Companion.newBuilder().build())
                .build()
            // 步行/骑行走官方 quick start 裸请求形态（不传 options）——7.10.0 的
            // Walk/RideRoutePlanOptions 曾在真机 startNavigation 崩溃排查中作为变量消除
            NaviTravelMode.WALKING -> RoutePlanRequester.Companion.newBuilder(NavRouteReqParam.TravelMode.TravelModeWalking)
                .start(point(start.first, start.second))
                .end(point(end.first, end.second))
                .build()
            NaviTravelMode.BIKING -> RoutePlanRequester.Companion.newBuilder(NavRouteReqParam.TravelMode.TravelModeRiding)
                .start(point(start.first, start.second))
                .end(point(end.first, end.second))
                .build()
        }
        if (naviMode == NaviTravelMode.DRIVING) {
            val callback = object : RoutePlanRequestCallback<com.tencent.navix.api.model.NavRoute> {
                override fun onResultCallback(plan: NavRoutePlan<com.tencent.navix.api.model.NavRoute>?, error: NavError?) {
                    val route = plan?.routes?.firstOrNull() as? NavDriveRoute
                    if (route?.routeId != null) {
                        pendingRouteId = route.routeId
                        pendingTravelMode = NaviTravelMode.DRIVING
                        onResult(true, null)
                    } else {
                        onResult(false, "未能算出导航路线")
                    }
                }
            }
            val ok = runCatching { navigator.searchRoute(requester, callback) }.isSuccess
            if (!ok) onResult(false, "发起算路失败")
        } else {
            // 步行/骑行：算路结果 = NavRoutePlan<NavNonMotorRoute>（官方 walk/ride_route_plan 页）
            val callback = object : RoutePlanRequestCallback<NavNonMotorRoute> {
                override fun onResultCallback(plan: NavRoutePlan<NavNonMotorRoute>?, error: NavError?) {
                    val route = plan?.routes?.firstOrNull() as? NavNonMotorRoute
                    if (!route?.routeId.isNullOrEmpty()) {
                        pendingRouteId = route!!.routeId
                        pendingTravelMode = naviMode
                        onResult(true, null)
                    } else {
                        onResult(false, "未能算出导航路线")
                    }
                }
            }
            val ok = runCatching { navigator.searchRoute(requester, callback) }.isSuccess
            if (!ok) onResult(false, "发起算路失败")
        }
    }
}

/**
 * android.speech.tts 桥（系统默认 TTS 引擎；决策 #35）。
 * 遗留 #16 修复（2026-11-18）三件套：
 * 1. **进程级共享单例**——旧实现每次进导航页都随 NavixSystemTtsPlayer 新建一个
 *    TextToSpeech（引擎重复冷启 + 旧实例泄漏不 shutdown）；现在整进程只建一份、保持温热，
 *    第二次进导航不再冷启。
 * 2. **预热**——[preheat] 在算路成功/选「应用内导航」时提前拉起引擎（首条播报
 *    「准备出发，全程 N 公里」此前总撞上 ~9s 引擎冷启窗被 skip）。
 * 3. **未就绪缓存补播**——引擎未 ready 时到达的播报先进待播队列（上限 4 条，溢出丢最旧），
 *    onInit 成功即按序补播，首条播报不再丢。
 */
class SystemTtsBridge private constructor(context: Context) {

    companion object {
        /** 引擎未就绪期间的待播队列容量（导航播报时序敏感，溢出丢最旧）。 */
        private const val PENDING_MAX = 4

        @Volatile
        private var sharedInstance: SystemTtsBridge? = null

        /** 进程级共享实例（导航页/预热共用同一份引擎）。 */
        fun shared(context: Context): SystemTtsBridge =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: SystemTtsBridge(context.applicationContext).also { sharedInstance = it }
            }

        /** 预热：提前创建引擎开始异步 init（幂等；算路成功/选应用内导航时调用）。 */
        fun preheat(context: Context) {
            shared(context).ensure()
        }
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var triedEngine: String? = null

    /** 引擎未就绪期间的待播队列（导航播报是时序敏感的，容量小、溢出丢最旧）。 */
    private val pending = ArrayDeque<String>()

    fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status -> onInitResult(status, "default") }
    }

    private fun onInitResult(status: Int, engineLabel: String) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            // setLanguage 返回码：0 可用 / -1 缺数据 / -2 不支持（无中文语音包时播报静音的直接证据）
            val langResult = runCatching { tts?.setLanguage(Locale.CHINA) }.getOrNull()
            flushPending()
            return
        }
        // 2026-11-16 一加/ColorOS 实测：系统默认引擎 init 立即失败（status=-1）。
        // 枚举本机已装 TTS 引擎 + 读默认引擎设置取证，换一个非默认引擎显式重试一次。
        val engines = runCatching {
            appContext.packageManager.queryIntentServices(
                android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                0,
            )
        }.getOrDefault(emptyList())
        val names = engines.mapNotNull { it.serviceInfo?.packageName }
        val defaultEngine = runCatching {
            android.provider.Settings.Secure.getString(appContext.contentResolver, "tts_default_synth")
        }.getOrNull()
        val next = names.firstOrNull { it != triedEngine && it != defaultEngine }
            ?: names.firstOrNull { it != triedEngine }
        if (next == null) {
            dropPending()
            return
        }
        triedEngine = next
        runCatching { tts?.shutdown() }
        tts = TextToSpeech(appContext, { s -> onInitResult(s, next) }, next)
    }

    private fun doSpeak(text: String) {
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "navi_${System.currentTimeMillis()}")
        }
    }

    fun speak(text: String) {
        ensure()
        if (ready) {
            doSpeak(text)
        } else {
            // 引擎未就绪：缓存待 ready 补播（旧实现直接 skip，首条播报丢失的根因）
            synchronized(pending) {
                if (pending.size >= PENDING_MAX) pending.removeFirst()
                pending.addLast(text)
            }
        }
    }

    private fun flushPending() {
        val texts = synchronized(pending) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        texts.forEach { doSpeak(it) }
    }

    private fun dropPending() {
        synchronized(pending) { pending.clear() }
    }

    fun pause() = runCatching { tts?.stop() }.isSuccess
    fun resume() = Unit
    fun stop() = runCatching { tts?.stop() }
    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        dropPending()
    }
}

/**
 * navix TTS 播放器实现（桥接系统语音）。挂到 navigator.setTTSPlayer(...)。
 * broadcast 返回 true = 已接播；导航结束不再靠播报探测（onWillArriveDestination 真回调）。
 * 遗留 #16：桥改用进程级共享实例——导航页反复进出不再重复冷启引擎。
 */
class NavixSystemTtsPlayer(context: Context) : NavigatorTTSPlayer {

    private val bridge = SystemTtsBridge.shared(context)
    private val mutedListeners = mutableListOf<NavigatorTTSPlayer.MutedStatusChangeListener>()

    override fun broadcast(ttsInfo: NavTTSInfo?): Boolean {
        val text = ttsInfo?.content ?: return false
        if (isMuted()) return true
        bridge.speak(text)
        return true
    }

    override fun isMuted(): Boolean = false

    override fun setMuted(muted: Boolean) = Unit

    override fun addMutedStatusChangeListener(listener: NavigatorTTSPlayer.MutedStatusChangeListener?) {
        listener?.let { mutedListeners.add(it) }
    }

    override fun removeMutedStatusChangeListener(listener: NavigatorTTSPlayer.MutedStatusChangeListener?) {
        listener?.let { mutedListeners.remove(it) }
    }
}
