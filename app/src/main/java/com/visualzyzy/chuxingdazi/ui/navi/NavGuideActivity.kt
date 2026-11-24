package com.visualzyzy.chuxingdazi.ui.navi

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.tencent.navix.api.NavigatorZygote
import com.tencent.navix.api.config.SimulatorConfig
import com.tencent.navix.api.layer.NavigatorLayerRoot
import com.tencent.navix.api.layer.NavigatorLayerRootDrive
import com.tencent.navix.api.layer.NavigatorLayerRootRide
import com.tencent.navix.api.layer.NavigatorLayerRootWalk
import com.tencent.navix.api.model.NavGpsStatusInfo
import com.tencent.navix.api.model.NavNavigationStartFail
import com.tencent.navix.api.observer.BaseNavigatorObserver
import com.tencent.navix.api.observer.SimpleNavigatorDriveObserver
import com.tencent.navix.api.observer.SimpleNavigatorRideObserver
import com.tencent.navix.api.observer.SimpleNavigatorWalkObserver
import com.visualzyzy.chuxingdazi.data.navi.NaviSdk
import com.visualzyzy.chuxingdazi.data.navi.NavixSystemTtsPlayer

/**
 * S12 导航中（§6 S12）：全屏承载腾讯导航 SDK 的导航视图。
 * 2026-11-20 起按 [NaviSdk.pendingTravelMode] 承载驾车/步行/骑行三种引擎视图
 * （NavigatorLayerRootDrive / Walk / Ride，均为 ConstraintLayout 子类，取根后按 View 使用）。
 *
 * 定位分工（2026-11-19 定案）：导航期引擎由 native startNavigation 拉起、key 由
 * setDeveloperKey 透传（navix LocationApi 链路）；平时定位走 NaviLocation 直连
 * 定位 SDK，两条链互不相干。
 *
 * - 「等待定位」提示层的撤层信号 = onDidStartNavigation（navix 真回调，三种 observer 同款）；
 *   视图树轮询兜底策略见待办册铁律 #27（室内拿不到 GPS fix 时引导可能永不开始，层常驻是预期）。
 * - 到达 = onWillArriveDestination 真回调（自动退出）。
 */
class NavGuideActivity : FragmentActivity() {

    private var naviView: android.view.View? = null
    private var layerRoot: NavigatorLayerRoot<*>? = null
    private var navigator: com.tencent.navix.api.Navigator? = null
    private var observer: BaseNavigatorObserver<*, *>? = null
    private var geoObserver: com.tencent.navix.api.location.GeoLocationObserver? = null
    private var waitingOverlay: View? = null
    private var ttsPlayer: NavixSystemTtsPlayer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 三种方式共用的事件处理（observer 薄壳各自委托到这里）。 */
    private fun onGuideStart() {
        runOnUiThread { hideWaitingOverlay() }
    }

    private fun onGuideStartFail(routeId: String?, fail: NavNavigationStartFail?) {
        runOnUiThread {
            android.widget.Toast.makeText(
                this@NavGuideActivity,
                "导航启动失败，请返回重试",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun onGuideArriving() {
        runOnUiThread {
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) exitNavi()
            }, 1500)
        }
    }

    /** 引擎侧导航会话结束（2026-11-21 观察项修复）：SDK 引导视图**内置**的退出确认面板
     *  点「退出导航」只执行引擎 stopNavigation（引导 UI 撤掉），不会替本页 finish——
     *  此前 onDidStopNavigation 接成空 Unit，SDK 侧退出后本页停在裸底图、须 BACK 兜底。
     *  现接回统一出口 [exitNavi]；App 自发退出（BACK/等待层「返回路线」/到达定时器）路径
     *  同样会触发本回调，由 [exitNavi] 的 isFinishing 守卫去重，无递归风险。 */
    private fun onGuideStopped() {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) exitNavi()
        }
    }

    /** 按方式选择 observer（官方口径：各方式经各自的 Simple*Observer 注册回调，事件面一致）。 */
    private fun makeObserver(mode: NaviSdk.NaviTravelMode): BaseNavigatorObserver<*, *> = when (mode) {
        NaviSdk.NaviTravelMode.DRIVING -> object : SimpleNavigatorDriveObserver() {
            override fun onDidStartNavigation() = onGuideStart()
            override fun onDidStartNavigationFail(routeId: String?, fail: NavNavigationStartFail?) =
                onGuideStartFail(routeId, fail)
            override fun onWillArriveDestination() = onGuideArriving()
            override fun onDidStopNavigation() = onGuideStopped()
            override fun onGPSStatusChanged(info: NavGpsStatusInfo?) = Unit
        }
        NaviSdk.NaviTravelMode.WALKING -> object : SimpleNavigatorWalkObserver() {
            override fun onDidStartNavigation() = onGuideStart()
            override fun onDidStartNavigationFail(routeId: String?, fail: NavNavigationStartFail?) =
                onGuideStartFail(routeId, fail)
            override fun onWillArriveDestination() = onGuideArriving()
            override fun onDidStopNavigation() = onGuideStopped()
            override fun onGPSStatusChanged(info: NavGpsStatusInfo?) = Unit
        }
        NaviSdk.NaviTravelMode.BIKING -> object : SimpleNavigatorRideObserver() {
            override fun onDidStartNavigation() = onGuideStart()
            override fun onDidStartNavigationFail(routeId: String?, fail: NavNavigationStartFail?) =
                onGuideStartFail(routeId, fail)
            override fun onWillArriveDestination() = onGuideArriving()
            override fun onDidStopNavigation() = onGuideStopped()
            override fun onGPSStatusChanged(info: NavGpsStatusInfo?) = Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NaviSdk.ensureInit(
            this,
            onReady = { setupNavi() },
            onFail = { msg -> runOnUiThread { showDiagnostic(RuntimeException(msg)) } },
        )
    }

    private fun setupNavi() {
        val naviMode = NaviSdk.pendingTravelMode
        val nav = NaviSdk.navigator(this, naviMode)
        if (nav == null) {
            showDiagnostic(RuntimeException("导航引擎未就绪（navigator=null）"))
            return
        }
        navigator = nav
        // 导航视图：经方式对应的 NavigatorLayerRoot* 类型取（实类是 ConstraintLayout 子类，
        // 但编译期只以接口暴露 → 取根后按 View 使用）。驾车 view() 出厂自带引导面板
        // （2026-11-18 真机实证不加 addViewLayer 也出引导 UI）；步行/骑行按官方 quick start
        // 第 3 步显式 addViewLayer 默认面板。
        // ⚠️ 遗留 #20 已构建侧关闭（2026-11-20 深夜定案）：navix 7.10.0 步行/骑行两大缺陷——
        // ① 引擎 native SIGSEGV：官方标「可选」的 tts AAR 实为 non-motor 硬依赖，已引入根治；
        // ② 引导视图 inflate NoSuchFieldError：AAR R.txt 声明的 37 个 id 无 values 实体，AGP
        //   8.3~8.5 生成 R 类时丢弃——主工程降 AGP 8.2.2（≤8.2.2 按 R.txt 全量生成）后恢复。
        // 完整排查档案：docs/步行骑行导航缺陷排查记录.md。
        val root: NavigatorLayerRoot<*>? = try {
            when (naviMode) {
                NaviSdk.NaviTravelMode.DRIVING -> NavigatorZygote.with(this).view(NavigatorLayerRootDrive::class.java)
                NaviSdk.NaviTravelMode.WALKING -> NavigatorZygote.with(this).view(NavigatorLayerRootWalk::class.java)
                    .also { it.addViewLayer(com.tencent.navix.ui.NavigatorLayerViewWalk(this)) }
                NaviSdk.NaviTravelMode.BIKING -> NavigatorZygote.with(this).view(NavigatorLayerRootRide::class.java)
                    .also { it.addViewLayer(com.tencent.navix.ui.NavigatorLayerViewRide(this)) }
            }
        } catch (e: Throwable) {
            showDiagnostic(e)
            return
        }
        if (root == null) {
            showDiagnostic(RuntimeException("SDK 未给出导航视图"))
            return
        }
        val view = root as? android.view.View ?: run {
            showDiagnostic(RuntimeException("导航视图类型异常"))
            return
        }
        layerRoot = root
        naviView = view
        setContentView(view)
        // 系统 TTS 桥（决策 #35）：播报文本桥接 android.speech.tts（三种引擎共用）。
        // （2026-11-20 深夜实验回退：setTTSPlayer 挂桥与否 non-motor tick 同点崩——
        // 与 tick 死因无关，恢复无条件挂桥的原验收形态；步行/骑行入口已置灰等 SDK 修复。）
        ttsPlayer = NavixSystemTtsPlayer(this).also { nav.setTTSPlayer(it) }
        val obs = makeObserver(naviMode)
        observer = obs
        nav.registerObserver(obs)
        // 室内调试开关：沿路线模拟定位（NaviSdk.simulateEnabled，默认关）
        if (NaviSdk.simulateEnabled) {
            runCatching {
                nav.simulator()
                    .setEnable(true)
                    .setConfig(
                        SimulatorConfig.builder(SimulatorConfig.Type.SIMULATE_LOCATIONS_ALONG_ROUTE)
                            .setSimulateSpeed(8)
                            .build(),
                    )
            }
        }
        // E2 排查 #20（2026-11-20）：注册 navix 定位 observer（官方「定位管理-定位回调」步骤，
        // §9.6 设计口径「导航期 navix LocationApi」落地；空壳 observer 建立数据管道。
        // 2026-11-20 深夜二分实测：摘除与否 non-motor tick 同点崩——非 tick 死因，恢复注册。）
        runCatching {
            val locApi = nav.locationApi()
            geoObserver = object : com.tencent.navix.api.location.GeoLocationObserver() {}
            locApi.addLocationObserver(geoObserver!!, 1000)
        }
        waitingOverlay = buildWaitingOverlay()
        runCatching {
            (findViewById<ViewGroup>(android.R.id.content))?.addView(
                waitingOverlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        // bindView 后再 startNavigation（算路在 RouteScreen 已完成，routeId 挂 NaviSdk）。
        // （2026-11-20 深夜页内算路实验已回退：序列与 non-motor tick 崩溃无关——六连
        // 二分排除；且页内序列下驾车引导 UI 不渲染=新回归，恢复跨页形态。）
        val routeId = NaviSdk.pendingRouteId
        runCatching {
            nav.bindView(root)
            if (routeId != null) {
                // E7 姿势：startNavigation 前显式关闭模拟器（非模拟路径标准动作）
                if (!NaviSdk.simulateEnabled) nav.simulator().setEnable(false)
                nav.startNavigation(routeId)
            }
        }
    }

    private fun hideWaitingOverlay() {
        val overlay = waitingOverlay ?: return
        if (overlay.visibility == View.VISIBLE) {
            overlay.visibility = View.GONE
        }
    }

    /** 等待定位提示层：半透明遮罩 + 居中深色卡片（标题/说明/返回按钮）。 */
    private fun buildWaitingOverlay(): View {
        val scrim = Color.argb(90, 0, 0, 0)
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setBackgroundColor(scrim)
            val card = LinearLayout(this@NavGuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(Color.argb(238, 0x1B, 0x1F, 0x27))
                    cornerRadius = dp(16).toFloat()
                }
                setPadding(dp(28), dp(26), dp(28), dp(22))
            }
            card.addView(
                TextView(this@NavGuideActivity).apply {
                    text = "正在等待定位"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            card.addView(
                TextView(this@NavGuideActivity).apply {
                    text = "定位成功后开始引导播报\n室内或信号弱时可能较慢"
                    setTextColor(Color.argb(255, 0xA6, 0xAF, 0xBC))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(0, dp(10), 0, dp(16))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            card.addView(
                Button(this@NavGuideActivity).apply {
                    text = "返回路线"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    isAllCaps = false
                    background = GradientDrawable().apply {
                        setColor(Color.argb(230, 0x0E, 0x74, 0x90))
                        cornerRadius = dp(13).toFloat()
                    }
                    setOnClickListener { exitNavi() }
                    setPadding(dp(40), dp(12), dp(40), dp(12))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER },
            )
        }
    }

    private fun showDiagnostic(e: Throwable) {
        // 把完整异常栈写进 logcat（取证用；SDK 内部吞栈，logcat 里搜不到）
        android.util.Log.e(
            "NaviDiag",
            "guide setup failed. init=${NaviSdk.initMessage}",
            e,
        )
        val info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(48, 140, 48, 40)
            setText(
                buildString {
                    appendLine("应用内导航暂时不可用")
                    appendLine()
                    appendLine("导航视图初始化失败，请返回后选择「腾讯地图 App」等跳转方式。")
                    appendLine()
                    appendLine("——诊断信息——")
                    appendLine("init=${NaviSdk.initMessage}")
                    appendLine("${e.javaClass.simpleName}: ${e.message}")
                },
            )
        }
        val back = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setText("返 回")
            setPadding(48, 40, 48, 40)
            setOnClickListener { finish() }
        }
        setContentView(
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(info)
                addView(back)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        runCatching { layerRoot?.onResume() }
    }

    override fun onPause() {
        runCatching { layerRoot?.onPause() }
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        runCatching { layerRoot?.onStart() }
    }

    override fun onStop() {
        runCatching { layerRoot?.onStop() }
        super.onStop()
    }

    override fun onDestroy() {
        runCatching {
            geoObserver?.let { navigator?.locationApi()?.removeLocationObserver(it) }
            observer?.let { navigator?.unregisterObserver(it) }
            runCatching { navigator?.stopNavigation() }
            layerRoot?.let { navigator?.unbindView(it) }
        }
        runCatching { layerRoot?.onDestroy() }
        naviView = null
        layerRoot = null
        navigator = null
        observer = null
        ttsPlayer = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        exitNavi()
    }

    /** 统一退出：stopNavigation（显式结束引导会话，防播报/引导残留）→ unbind → finish 回 S10。
     *  App 自发退出（BACK/等待层/到达定时器）与引擎侧退出（SDK 内置面板「退出导航」→
     *  onDidStopNavigation → onGuideStopped）都汇到这里；isFinishing 守卫保证重复进入无副作用。 */
    private fun exitNavi() {
        if (isFinishing || isDestroyed) return
        // 时间戳必须在 finish 之前记——MainActivity 的 ON_RESUME 会先于 guide onDestroy 到达，
        // RouteScreen 的重新定位观察者靠它触发
        NaviSdk.lastGuideDestroyAt = android.os.SystemClock.elapsedRealtime()
        runCatching { navigator?.stopNavigation() }
        finish()
    }
}
