package com.visualzyzy.chuxingdazi.data.tencent

import android.content.Context
import com.tencent.gaya.framework.SDKOptions
import com.tencent.map.geolocation.TencentLocationManagerOptions
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer

/**
 * 腾讯 SDK 初始化的统一入口（设计文档 §7.1 / §9 腾讯集成要点）。
 *
 * 硬约束：
 * - 隐私合规必须先行：`TencentMapInitializer.setAgreePrivacy(context, true)` +
 *   `start(context)`（6.x 两步）、`TencentLocationManager.setUserAgreePrivacy(true)`
 *   （导航自带定位栈的全局隐私开关）、导航 `NavigatorConfig.setUserAgreedPrivacy(true)`，
 *   都必须在用户于 S01 点「同意并继续」**之后**才允许调用；
 *   未同意前任何代码路径都不得初始化 SDK、不得申请定位权限。
 * - key 注入口径（2026-11-18 决策 #113/#114 后）：
 *   地图 `TencentMapOptions.setMapKey`（MapView 创建时传入，见 MapHost），
 *   导航 `NavigatorConfig.Builder.setDeveloperKey`（NaviSdk.ensureInit），
 *   检索（lbssearch）**运行时注入：全部调用点用 3 参构造器**
 *   `TencentSearch(context, apiKey, secretKey)`，key 落 apiKey 位。
 *   ⚠️ 2 参构造器 `TencentSearch(context, key)` 会把 key 传进 secretKey 位
 *   （mApiKey=null → 请求 key 回退 readMetaData），曾是 #12 的真根因。
 *   manifest **不携带任何 key**（meta-data TencentMapSDK 已删除，决策 #114），
 *   构建期 manifestPlaceholders 注入同步废除。
 */
object TencentSdk {

    @Volatile
    private var initialized = false

    /** 当前生效的 key（S02 保存后由 applyKey 更新；导航 re-init 等场景读取）。 */
    @Volatile
    var currentKey: String? = null
        private set

    /** 是否已完成初始化（含隐私同意）。 */
    val isInitialized: Boolean get() = initialized

    /**
     * S02 校验成功/保存后即时更新 key，使 lbssearch 等无需重启即可用新 key
     * （GeoCoderService 缓存实例按 key 失效重建；其余封装每请求新建）。
     */
    fun applyKey(key: String?) {
        currentKey = key
        applyLocationKey(key)
        // key 更新时若无运行中的地图（S02 无 MapView），立即预载 navix 引擎 + navicore
        // ——晚了（地图渲染后再加载）early init 会冻死地图（见 ensureInitialized 注释）
        if (!key.isNullOrBlank()) preloadNavicore()
    }

    fun ensureInitialized(context: Context, key: String?) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // 调用方保证：走到这里 = 用户已同意隐私（S01）
            TencentMapInitializer.setAgreePrivacy(context, true)
            TencentMapInitializer.start(context)
            // 定位栈隐私开关统一由 NavigatorConfig.setUserAgreedPrivacy(true)
            // 承担（NaviSdk.ensureInit；7.10.0 自带 geolocation 类无独立开关）
            // lbssearch（TencentSearch）走 gaya 框架自己的全局隐私开关：请求前会查
            // SDKInfo.isPrivacyProtection()，false 时所有请求本地直接拒——
            // 这里随地图一起显式同意（2026-11-18 #12 排查中补上，预防性）。
            SDKOptions.create().privacyPolicy(true)
            currentKey = key
            applyLocationKey(key)
            initialized = true
            // 🔴 navicore 预载（App 启动、地图创建之前——时序铁律，见 preloadNavicore）
            if (!key.isNullOrBlank()) preloadNavicore()
        }
    }

    /**
     * navix 引擎 + libnavicore 预载（2026-11-19 v4 冻图问题定案修复）。
     *
     * 🔴 时序铁律（真机实锤）：libnavicore.so 的 JNI_OnLoad 会跑一次
     * **MapJNI register_jni_opensdk（Nerd early init）**——对全局地图数据层
     * （http 对象/瓦片 URL 缓存/数据目录）做整体（重）初始化：
     * - 在**地图引擎创建之前**跑（App 启动早期）→ 与地图初始化合流，无害
     *   （08:10 正常会话实证）；
     * - 在**地图已渲染之后**跑（此前 v4 放在点定位时）→ 重置运行中的数据层
     *   → 主界面地图冻死（无法拖动/点击、蓝点消失），进出页面重建 MapView
     *   才恢复（17:02 冻结会话实证）。
     * 因此 navicore 必须在「无运行中地图」的时机预载：App 启动 key 就绪时
     * （ensureInitialized）/ S02 填 key 成功时（applyKey，S02 无 MapView）。
     * 同时 navicore 的 JNI_OnLoad 注册定位栈 native 方法（e.v 等 key 检查依赖，
     * rc=2 真根因），预载也让平时定位随时可用。
     * 幂等：System.loadLibrary 进程内第二次调用直接返回；NaviSdk.ensureInit
     * 同样幂等（inited 标志）。
     */
    private fun preloadNavicore() {
        runCatching {
            com.visualzyzy.chuxingdazi.data.navi.NaviSdk.ensureInit(
                com.visualzyzy.chuxingdazi.ChuxingdaziApp.instance ?: return,
            )
            System.loadLibrary("navicore")
        }
    }

    /**
     * 定位引擎（libtencentloc）的 key 注入（2026-11-19 定位排查轮定案）：
     * `TencentLocationManagerOptions.setKey` 是静态 key 槽，**定位栈单例
     * （TxLocMgrImpl/wb）构造时只读一次**——必须在任何定位栈初始化链
     * （NaviSdk.ensureInit 的 adapter 链 / TencentLocationManager.getInstance）
     * 之前设好，晚了引擎拿不到 key → requestLocationUpdates 返回码 2
     * （rc=2 真机实锤；此前 navix 包装把该返回码丢弃，全程静默）。
     * 在 key 落地的两处（ensureInitialized / applyKey）都注入，幂等。
     */
    private fun applyLocationKey(key: String?) {
        if (key.isNullOrBlank()) return
        runCatching { TencentLocationManagerOptions.setKey(key) }
    }
}
