package com.visualzyzy.chuxingdazi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * 重启跳板：跑在独立 `:phoenix` 进程里。
 * 主进程 exit 后由本进程把 MainActivity 重新拉起，系统会重新 fork 主进程，
 * 从而让 Application.onCreate 用新保存的 AK 重新初始化百度 SDK（设计文档 §9.2 降级路径）。
 */
class PhoenixActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val relaunch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (relaunch != null) {
            startActivity(relaunch)
        }
        finish()
        Process.killProcess(Process.myPid())
        System.exit(0)
    }
}
