package com.visualzyzy.chuxingdazi.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.dataStore by preferencesDataStore(name = "settings")

/** 偏好设置（DataStore Preferences）。key 名一旦发布不可再改（设计文档 §11.10）。 */
object Prefs {
    // 2026-11-17 腾讯版重写：key 固定为 sdk_key（开发期无外部用户，百度版 baidu_ak 已随重写作废）。
    // key 不硬编码进包：地图/定位/导航/检索四处运行时注入（设计文档 §9 腾讯集成要点）
    private val KEY_PRIVACY_AGREED = booleanPreferencesKey("privacy_agreed")
    private val KEY_SDK_KEY = stringPreferencesKey("sdk_key")

    fun privacyAgreed(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_PRIVACY_AGREED] ?: false }

    fun sdkKey(context: Context): Flow<String?> =
        context.dataStore.data.map { it[KEY_SDK_KEY] }

    suspend fun setPrivacyAgreed(context: Context, agreed: Boolean) {
        context.dataStore.edit { it[KEY_PRIVACY_AGREED] = agreed }
    }

    suspend fun setSdkKey(context: Context, key: String) {
        context.dataStore.edit { it[KEY_SDK_KEY] = key }
    }

    /** 启动时的一次性快照（Application.onCreate 里阻塞读，文件极小可接受）。 */
    data class Snapshot(val privacyAgreed: Boolean, val sdkKey: String?)

    suspend fun snapshot(context: Context): Snapshot {
        val p = context.dataStore.data.first()
        return Snapshot(
            privacyAgreed = p[KEY_PRIVACY_AGREED] ?: false,
            sdkKey = p[KEY_SDK_KEY],
        )
    }

    // 最近地图相机位置（S03 拖动时保存；S06/S03 新建 MapView 时恢复，避免跳回默认中心）
    private val KEY_LAST_LAT = doublePreferencesKey("last_camera_lat")
    private val KEY_LAST_LNG = doublePreferencesKey("last_camera_lng")
    private val KEY_LAST_ZOOM = floatPreferencesKey("last_camera_zoom")

    data class CameraSnapshot(val lat: Double, val lng: Double, val zoom: Float)

    suspend fun saveCamera(context: Context, lat: Double, lng: Double, zoom: Float) {
        context.dataStore.edit {
            it[KEY_LAST_LAT] = lat
            it[KEY_LAST_LNG] = lng
            it[KEY_LAST_ZOOM] = zoom
        }
    }

    suspend fun cameraSnapshot(context: Context): CameraSnapshot? {
        val p = context.dataStore.data.first()
        val lat = p[KEY_LAST_LAT] ?: return null
        val lng = p[KEY_LAST_LNG] ?: return null
        return CameraSnapshot(lat, lng, p[KEY_LAST_ZOOM] ?: 16f)
    }

    // 最近搜索（\u0001 分隔存储保序，最多 5 个；S05）
    private val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")

    fun recentSearches(context: Context): Flow<List<String>> =
        context.dataStore.data.map { p ->
            (p[KEY_RECENT_SEARCHES] ?: "").split('\u0001').filter { it.isNotBlank() }
        }

    /** 新关键词置顶、去重、截取 5 个（§6 S05：最近搜索 chips 最多 5 个）。 */
    suspend fun addRecentSearch(context: Context, keyword: String) {
        val k = keyword.trim()
        if (k.isBlank()) return
        context.dataStore.edit { p ->
            val old = (p[KEY_RECENT_SEARCHES] ?: "").split('\u0001').filter { it.isNotBlank() && it != k }
            p[KEY_RECENT_SEARCHES] = (listOf(k) + old).take(5).joinToString("\u0001")
        }
    }

    /** 清空全部最近搜索（P67 第四轮：搜索页小垃圾桶「确认」后调用）。 */
    suspend fun clearRecentSearches(context: Context) {
        context.dataStore.edit { it[KEY_RECENT_SEARCHES] = "" }
    }

    // 上次搜索/定位所在城市（S05 联想按城市检索；无则全国）
    private val KEY_LAST_CITY = stringPreferencesKey("last_city")

    fun lastCity(context: Context): Flow<String?> =
        context.dataStore.data.map { it[KEY_LAST_CITY] }

    suspend fun saveLastCity(context: Context, city: String) {
        if (city.isBlank()) return
        context.dataStore.edit { it[KEY_LAST_CITY] = city }
    }

    // 第八轮（2026-11-07）：RegionHealer 自愈快捷标志。
    // true = 上轮扫描已确认库内所有点位的 province/city 齐备，kick() 可直接跳过扫描；
    // 添加点位 / 导入数据时置 false（见 RegionHealer.markDirty）。纯性能优化，丢失无害
    // （false 只是多扫一轮），故放 DataStore 而非升 DB 版本。
    private val KEY_ALL_POINTS_HEALTHY = booleanPreferencesKey("all_points_healthy")

    fun allPointsHealthy(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ALL_POINTS_HEALTHY] ?: false }

    suspend fun setAllPointsHealthy(context: Context, healthy: Boolean) {
        context.dataStore.edit { it[KEY_ALL_POINTS_HEALTHY] = healthy }
    }

    // S20 导航说明「不再提示」（§6 S16 入口顺序）
    private val KEY_S20_NOTICE_SEEN = booleanPreferencesKey("s20_notice_seen")

    fun s20NoticeSeen(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_S20_NOTICE_SEEN] ?: false }

    suspend fun setS20NoticeSeen(context: Context, seen: Boolean) {
        context.dataStore.edit { it[KEY_S20_NOTICE_SEEN] = seen }
    }

    // S16 跳转丢途经点确认框「不再提示」。有途经点且点外部地图 App 时弹二次确认；
    // 仅勾选后点「仍要跳转」才置 true（点「改用应用内导航」不算，下次跳转仍提示）
    private val KEY_VIA_DROP_CONFIRM_ACK = booleanPreferencesKey("via_drop_confirm_ack")

    fun viaDropConfirmAck(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_VIA_DROP_CONFIRM_ACK] ?: false }

    suspend fun setViaDropConfirmAck(context: Context, ack: Boolean) {
        context.dataStore.edit { it[KEY_VIA_DROP_CONFIRM_ACK] = ack }
    }

    // 导航语音：恒「跟随系统」（决策 #35/#82：TTS 走系统 TTS 桥，腾讯 navix 经
    // navigator.setTTSPlayer(NavigatorTTSPlayer) 桥接；无内置 TTS 选项）
    private val KEY_NAVI_VOICE_SYSTEM = booleanPreferencesKey("navi_voice_system")

    fun naviVoiceSystem(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_NAVI_VOICE_SYSTEM] ?: true }

    suspend fun setNaviVoiceSystem(context: Context, system: Boolean) {
        context.dataStore.edit { it[KEY_NAVI_VOICE_SYSTEM] = system }
    }

    // P42（2026-11-03）软件显示配色：system / light / dark（S13「软件显示配色」行，全局生效）
    private val KEY_APP_THEME_MODE = stringPreferencesKey("app_theme_mode")

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    fun appThemeMode(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_APP_THEME_MODE] ?: THEME_SYSTEM }

    suspend fun saveAppThemeMode(context: Context, mode: String) {
        context.dataStore.edit { it[KEY_APP_THEME_MODE] = mode }
    }

    // ---- 行政区划数据（2026-11-17 腾讯版新需求）----
    // 省-市两级数据改由腾讯 getDistrictChildren 接口获取，本地 Room district 表存储，
    // 2 个月更新一次（只在应用启动时检查是否到期）。last=0 = 从未更新过。
    private val KEY_DISTRICT_LAST_UPDATED = longPreferencesKey("district_last_updated")

    fun districtLastUpdated(context: Context): Flow<Long> =
        context.dataStore.data.map { it[KEY_DISTRICT_LAST_UPDATED] ?: 0L }

    suspend fun setDistrictLastUpdated(context: Context, epochMs: Long) {
        context.dataStore.edit { it[KEY_DISTRICT_LAST_UPDATED] = epochMs }
    }
}
