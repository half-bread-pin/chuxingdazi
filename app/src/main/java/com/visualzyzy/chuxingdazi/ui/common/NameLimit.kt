package com.visualzyzy.chuxingdazi.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 名称长度限制（第六轮，2026-11-06）：
 * 点位标记名称 / 分类名称 / 清单名称统一最大 **40 个中文汉字** 的宽度
 * （中文汉字 / 全角字符按 1 个汉字计，英文数字等半角字符按半个汉字计）。
 *
 * 用户输入超过上限时：输入被截断回上限内，并提示一次「名称最大长度40个汉字」。
 * 第十六轮（2026-11-14，优化建议）：提示从「3 秒全屏阻断浮层」降级为一行 Toast——
 * 输入框已经会截断，阻断层只会吃掉返回键和对话框按钮；Toast 不抢焦点、不打断操作。
 * 防重：Toast 期间重复触发不重复弹（同一次粘贴多行输入只提示一次）。
 */
object NameLimit {

    /** 名称最大宽度（单位 = 中文汉字）。 */
    const val MAX_WIDTH = 40f

    /** Toast 防重窗口（毫秒）。 */
    private const val BLOCK_MS = 3000L

    /** 由 MainActivity.onCreate 注入，供 Toast 使用。 */
    var appContext: android.content.Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hideJob: Job? = null

    /**
     * 输入钩子：宽度未超限原样返回；超限则截断回上限内并触发 3 秒全屏阻断。
     * 所有名称输入框的 onValueChange 都应经过本方法。
     */
    fun clamp(text: String): String = clampTo(text, MAX_WIDTH, "名称最大长度40个汉字")

    /**
     * 可配置上限版（遗留 #4 修复，2026-11-18）：行程名规格是「最多 20 字」（§6 S24），
     * 与点位/分类/清单的 40 汉字宽不同——同一套宽度语义（半角 0.5 计）与 Toast 提示，
     * 上限与提示文案由调用方给出。
     */
    fun clampTo(text: String, maxWidth: Float, toast: String): String {
        if (widthOf(text) <= maxWidth) return text
        triggerBlock(toast)
        return truncateTo(text, maxWidth)
    }

    /** 字符宽度（单位 = 中文汉字）：半角 0.5，中文/全角/其余字符 1。 */
    fun charWidth(c: Char): Float = when {
        c.code in 0x20..0x2E7F -> 0.5f // ASCII / 拉丁 / 半角标点
        else -> 1f                     // 中文汉字、全角符号、emoji 等
    }

    /** 文本总宽度（单位 = 中文汉字）。 */
    fun widthOf(text: String): Float {
        var w = 0f
        for (c in text) w += charWidth(c)
        return w
    }

    /** 截断到宽度 ≤ 上限（保证逐字累加不超界）。 */
    fun truncateToLimit(text: String): String = truncateTo(text, MAX_WIDTH)

    /** 截断到指定宽度上限（clampTo 用）。 */
    private fun truncateTo(text: String, maxWidth: Float): String {
        var w = 0f
        val sb = StringBuilder()
        for (c in text) {
            val cw = charWidth(c)
            if (w + cw > maxWidth) break
            w += cw
            sb.append(c)
        }
        return sb.toString()
    }

    /** 提示一次超长（Toast，不抢焦点；窗口期内不重复弹）。 */
    private fun triggerBlock(message: String) {
        if (hideJob?.isActive == true) return
        val ctx = appContext ?: return
        android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
        hideJob = scope.launch { delay(BLOCK_MS) }
    }
}
