package com.visualzyzy.chuxingdazi.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.content.pm.Signature
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.backup.BackupManager
import com.visualzyzy.chuxingdazi.data.backup.ExportResult
import com.visualzyzy.chuxingdazi.data.backup.ImportReport
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** S13 设置（§6 S13）。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backupManager: BackupManager,
    private val placeRepository: PlaceRepository,
    /** 省-市行政区划（2026-11-17 腾讯版新需求：「更新行政区划数据」按钮 + 进度弹窗）。 */
    val districtRepository: com.visualzyzy.chuxingdazi.data.region.DistrictRepository,
) : ViewModel() {

    enum class LocPerm { OK_FOREGROUND, OK_ALWAYS, ONESHOT, DENIED, SERVICE_OFF }

    val akConfigured = MutableStateFlow(false)
    val locPerm = MutableStateFlow(LocPerm.DENIED)
    val noticeEnabled = MutableStateFlow(true)
    val naviVoiceSystem = MutableStateFlow(true)
    // P61（2026-11-03）问题22：软件显示配色（Prefs.THEME_SYSTEM / THEME_LIGHT / THEME_DARK）
    val themeMode = MutableStateFlow(Prefs.THEME_SYSTEM)
    // P76（2026-11-05）：「标记管理」行的点位总数角标
    val placeCount = placeRepository.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val busy = MutableStateFlow(false)
    val exportDone = MutableStateFlow<ExportResult?>(null)
    val importDone = MutableStateFlow<ImportReport?>(null)
    val message = MutableStateFlow<String?>(null)

    /** 「更新行政区划数据」状态机（2026-11-17）：0 空闲 → 1 确认框 → 2 更新中 → 3 完成（2 秒自关）。 */
    val districtDialogStep = MutableStateFlow(0)
    val districtProgress = districtRepository.progress
    val districtFinished = districtRepository.justFinished
    val districtError = districtRepository.lastError

    fun showDistrictConfirm() {
        districtError.value = null
        districtDialogStep.value = 1
    }

    fun dismissDistrictDialogs() {
        districtDialogStep.value = 0
    }

    /** 点「确认」：弹「更新中...」进度框并开始刷新（repository 内部串行、重复调用直接忽略）。 */
    fun confirmDistrictUpdate() {
        districtDialogStep.value = 2
        viewModelScope.launch {
            val ok = districtRepository.refresh()
            if (ok) {
                districtDialogStep.value = 3 // 「更新完成」弹窗，UI 2 秒后关
            } else {
                districtDialogStep.value = 0
                message.value = "行政区划更新失败：${districtError.value ?: "网络异常"}"
            }
        }
    }

    /** 「更新完成」弹窗关闭（2 秒超时或用户操作后）。 */
    fun finishDistrictFlow() {
        districtRepository.acknowledgeFinished()
        districtDialogStep.value = 0
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            val key = Prefs.sdkKey(app).firstOrNull()
            akConfigured.value = !key.isNullOrBlank()
            noticeEnabled.value = !(Prefs.s20NoticeSeen(app).firstOrNull() ?: false)
            naviVoiceSystem.value = Prefs.naviVoiceSystem(app).firstOrNull() ?: true
            themeMode.value = Prefs.appThemeMode(app).firstOrNull() ?: Prefs.THEME_SYSTEM
        }
        locPerm.value = readLocPerm(app)
    }

    fun readLocPerm(context: Context): LocPerm {
        val app = context.applicationContext
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                app, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return LocPerm.DENIED
        // P82（2026-11-05）：isProviderEnabled 在 LineageOS 等 ROM 上 location_mode=0 时
        // NETWORK_PROVIDER 仍返回 true → SERVICE_OFF 判不出来。改读 LOCATION_MODE（跨 ROM 一致）。
        val locationMode = try {
            android.provider.Settings.Secure.getInt(
                app.contentResolver, android.provider.Settings.Secure.LOCATION_MODE,
            )
        } catch (e: Exception) {
            android.provider.Settings.Secure.LOCATION_MODE_OFF
        }
        if (locationMode == android.provider.Settings.Secure.LOCATION_MODE_OFF) return LocPerm.SERVICE_OFF
        // v1 无法稳定区分「始终」与「仅使用期间」（Q+ 后台定位是另一权限），两者都视为正常
        return LocPerm.OK_FOREGROUND
    }

    fun setNotice(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            Prefs.setS20NoticeSeen(context.applicationContext, !enabled)
            noticeEnabled.value = enabled
        }
    }

    /** P61（2026-11-03）问题22：软件显示配色（system/light/dark）。 */
    fun setThemeMode(context: Context, mode: String) {
        themeMode.value = mode
        viewModelScope.launch {
            Prefs.saveAppThemeMode(context.applicationContext, mode)
        }
    }

    fun export(context: Context) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { backupManager.export() }
                exportDone.value = result
            } catch (e: Exception) {
                message.value = "备份失败：${e.message ?: "未知错误"}"
            }
            busy.value = false
        }
    }

    // ---- 第十六轮：导入冲突预览（确认前列出将新增 / 同 ID 冲突 / 本地独有）----

    val importPreview = MutableStateFlow<com.visualzyzy.chuxingdazi.data.backup.ImportPreview?>(null)
    private var pendingImportText: String? = null

    /** 选择备份文件后先解析出冲突预览；解析失败（格式不对）走 message。 */
    fun prepareImport(context: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("读取文件失败")
                }
                val preview = withContext(Dispatchers.IO) { backupManager.previewImport(text) }
                pendingImportText = text
                importPreview.value = preview
            } catch (e: Exception) {
                message.value = "导入失败：${e.message ?: "文件格式不正确"}"
            }
        }
    }

    /** 预览确认后真正导入（文本已在内存，不再二次读文件）。 */
    fun confirmImport(keepBoth: Boolean) {
        val text = pendingImportText ?: return
        if (busy.value) return
        busy.value = true
        importPreview.value = null
        pendingImportText = null
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { backupManager.import(text, keepBoth) }
                importDone.value = report
            } catch (e: Exception) {
                message.value = "导入失败：${e.message ?: "文件格式不正确"}"
            }
            busy.value = false
        }
    }

    fun cancelImport() {
        importPreview.value = null
        pendingImportText = null
    }

    /** Download 里的历史备份（设置页直接列，绕开系统选择器）。 */
    fun listBackups(): List<Pair<android.net.Uri, String>> = backupManager.listBackups()

    fun clearAll() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                backupManager.clearAll()
                message.value = "已清空所有点位、清单与行程"
            } catch (e: Exception) {
                message.value = "清空失败：${e.message ?: "未知错误"}"
            }
            busy.value = false
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    fun consumeExport() {
        exportDone.value = null
    }

    fun consumeImport() {
        importDone.value = null
    }

    companion object {
        /**
         * 当前签名证书 SHA1（S13「包名/SHA1」展示用）。
         * 注意：这是读取证书指纹用于百度 AK 绑定信息的展示/复制，非加密用途。
         */
        fun certSha1(context: Context): String? = runCatching {
            val pkg = context.packageName
            @Suppress("DEPRECATION")
            val signatures: Array<Signature> = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners ?: return null
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
            val md = MessageDigest.getInstance("SHA1")
            val digest = md.digest(signatures.first().toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        }.getOrNull()
    }
}
