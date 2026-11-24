package com.visualzyzy.chuxingdazi

import android.app.Application
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.tencent.TencentSdk
import com.visualzyzy.chuxingdazi.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class ChuxingdaziApp : Application() {

    companion object {
        /** 进程内 Application 实例（TencentSdk.preloadNavicore 等无 Context 入口的组件用）。 */
        @Volatile
        var instance: ChuxingdaziApp? = null
            private set
    }

    @Inject
    lateinit var categoryRepository: CategoryRepository

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject
    lateinit var backupManager: com.visualzyzy.chuxingdazi.data.backup.BackupManager

    @Inject
    lateinit var districtRepository: com.visualzyzy.chuxingdazi.data.region.DistrictRepository

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 备份导出/分享需要 ContentResolver 与分享用 Context（BackupManager 不持 Context）
        backupManager.resolverProvider = { contentResolver }
        backupManager.appContextForShare = this
        // 启动流程（设计文档 §7.1）：仅在用户已同意隐私后才初始化腾讯 SDK。
        // 未同意（首装）时不做任何 SDK 初始化，等 S01 同意后再调 TencentSdk.ensureInitialized。
        // ensureInitialized 内含 navicore 预载（2026-11-19 定位链路定案，时序铁律见
        // TencentSdk.preloadNavicore 注释——必须在第一个 MapView 创建之前）。
        val snapshot = runBlocking { Prefs.snapshot(this@ChuxingdaziApp) }
        if (snapshot.privacyAgreed) {
            TencentSdk.ensureInitialized(this, snapshot.sdkKey)
            // 行政区划数据：2 个月到期检查（只在启动时查一次；到期后台刷新，不影响 UI）
            appScope.launch { districtRepository.refreshIfDue() }
        }
        // 内置 7 分类种子（§5.4）：幂等，缺哪个补哪个，不覆盖用户已改的值
        appScope.launch { categoryRepository.seedBuiltins() }
    }
}
