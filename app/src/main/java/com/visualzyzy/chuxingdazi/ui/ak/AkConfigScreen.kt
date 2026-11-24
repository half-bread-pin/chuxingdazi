package com.visualzyzy.chuxingdazi.ui.ak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.data.tencent.TencentSdk
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import com.visualzyzy.chuxingdazi.ui.theme.TextSecondary
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** S02 Key 配置（设计文档 §6 S02）。key 不硬编码进包，由用户自己填（2026-11-17 腾讯版）。 */
@Composable
fun AkConfigScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var validating by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "配置地图密钥", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "地图与导航能力由腾讯位置服务提供。用您的腾讯位置服务账号创建应用（填本应用包名并启用产品）申请一个免费 key，填到这里，一次配置长期有效。",
                fontSize = 12.5.sp,
                color = TextSecondary,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 第十六轮（优化建议）：包名 + SHA1 不再铺在首启流程里——收进「复制申请信息」
            // 一个动作（粘贴到控制台申请 key 用）
            val sha1 = remember { signatureSha1(context) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "申请 key 需要：包名 + SHA1",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "复制申请信息",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primary,
                    modifier = Modifier.clickable {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "key-info",
                                "包名：${context.packageName}\nSHA1：${sha1 ?: ""}",
                            ),
                        )
                        android.widget.Toast.makeText(context, "已复制包名与 SHA1", android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(text = "密钥（Key）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextField(
                value = key,
                onValueChange = { key = it.trim() }, // 粘贴时去首尾空格与换行
                singleLine = true,
                // 第十六轮：key 默认遮罩显示（防止旁人瞥见；粘贴不受影响）
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                placeholder = { Text("粘贴您在腾讯位置服务控制台拿到的 key", fontSize = 13.sp, color = TextSecondary) },
                shape = RoundedCornerShape(13.dp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Primary,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = Primary,
                ),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            // 校验结果条
            resultMessage?.let { msg ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(9.dp), color = if (resultOk) Color(0xFFE7F5EC) else Color(0xFFFDECEC)) {
                    Text(
                        text = msg,
                        fontSize = 12.5.sp,
                        color = if (resultOk) Color(0xFF1F7A45) else Color(0xFFB3382E),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        lineHeight = 17.sp,
                    )
                }
                if (!resultOk && key.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // 降级路径：在线校验依赖腾讯检索服务可用性，允许直接保存并重启生效
                    Text(
                        text = "确认控制台配置无误？可直接保存并重启应用生效",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            scope.launch {
                                Prefs.setSdkKey(context, key)
                                delay(300) // 确保 DataStore 落盘后再重启
                                restartApp(context)
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 主按钮：验证并进入
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        if (validating || key.isBlank()) MaterialTheme.colorScheme.surfaceVariant else Primary,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable(enabled = !validating && key.isNotBlank()) {
                        scope.launch {
                            validating = true
                            resultMessage = null
                            val res = validateKey(context, key)
                            validating = false
                            when (res) {
                                is ValidateResult.Success -> {
                                    resultOk = true
                                    resultMessage = "校验通过，正在进入…"
                                    Prefs.setSdkKey(context, key)
                                    // 决策 #114：key 即时生效（无需重启）——lbssearch/地图/导航运行时注入都读它
                                    com.visualzyzy.chuxingdazi.data.tencent.TencentSdk.applyKey(key)
                                    onDone()
                                }
                                is ValidateResult.Failure -> {
                                    resultOk = false
                                    resultMessage = res.message
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (validating) "校验中…" else "验证并进入",
                    color = if (validating || key.isBlank()) TextSecondary else Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable(onClick = onDone), // 稍后配置：进主地图（地图区显示不可用占位）
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "稍后配置", fontSize = 14.sp, color = TextSecondary)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private sealed interface ValidateResult {
    data object Success : ValidateResult
    data class Failure(val message: String) : ValidateResult
}

/**
 * 用该 key 发起一次轻量请求（腾讯 suggestion 联想搜「西湖」）验证有效性（设计文档 §6 S02）。
 * 腾讯检索（lbssearch）key 走运行时注入：必须用 3 参构造器 TencentSearch(context, apiKey, secretKey)
 * 把 key 落在 apiKey 位（决策 #114——2 参构造器 key 进 secretKey 位、不进请求，
 * 曾导致请求回退 manifest 占位符被服务端拒 311）；无需 SDK 初始化；
 * 成功标准：超时内返回联想结果；失败按错误码分档提示。
 */
private suspend fun validateKey(context: Context, key: String): ValidateResult =
    withContext(Dispatchers.Main) {
        if (!isNetworkAvailable(context)) {
            return@withContext ValidateResult.Failure("网络不可用，请检查后重试")
        }
        val suggestions: Int? = withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Int?> { cont ->
                // 决策 #114：3 参构造器，key 必须落 apiKey 位
                val search = com.tencent.lbssearch.TencentSearch(context.applicationContext, key, null)
                val launched = runCatching {
                    search.suggestion(
                        com.tencent.lbssearch.`object`.param.SuggestionParam("西湖", "杭州"),
                        object : com.tencent.lbssearch.httpresponse.HttpResponseListener<com.tencent.lbssearch.httpresponse.BaseObject> {
                            override fun onSuccess(statusCode: Int, obj: com.tencent.lbssearch.httpresponse.BaseObject?) {
                                val n = (obj as? com.tencent.lbssearch.`object`.result.SuggestionResultObject)
                                    ?.data?.size ?: -1
                                if (cont.isActive) cont.resume(n)
                            }

                            override fun onFailure(statusCode: Int, msg: String?, throwable: Throwable?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }.isSuccess
                if (!launched && cont.isActive) cont.resume(null)
            }
        }
        when {
            suggestions == null ->
                ValidateResult.Failure(
                    "key 校验失败或超时：请核对 key 逐字符一致，确认已在腾讯位置服务控制台启用「WebServiceAPI/地图SDK」等产品，" +
                        "并确认应用绑定的包名与本应用一致；若刚改过配置，等几分钟再试",
                )
            suggestions < 0 ->
                ValidateResult.Failure(
                    "key 鉴权失败：请确认 key 与「包名 + SHA1」匹配，且控制台已启用相关产品",
                )
            else -> ValidateResult.Success
        }
    }

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** 经跳板重启：主进程退出，:phoenix 进程把 MainActivity 拉起（key 需参与各 SDK 初始化）。 */
private fun restartApp(context: Context) {
    context.startActivity(
        android.content.Intent(context, com.visualzyzy.chuxingdazi.PhoenixActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    (context as? android.app.Activity)?.finish()
    Runtime.getRuntime().exit(0)
}

/**
 * 读当前安装包签名证书的 SHA1（申请 key 时要填；模拟器/调试构建 = 开发版 SHA1）。
 * 注意：这里仅按腾讯控制台要求「展示证书指纹」，非加密用途，算法由腾讯平台规定（SHA1）。
 */
private fun signatureSha1(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    val digest = MessageDigest.getInstance("SHA1").digest(info.signatures.first().toByteArray())
    digest.joinToString(":") { "%02X".format(it) }
}.getOrDefault("读取失败")
