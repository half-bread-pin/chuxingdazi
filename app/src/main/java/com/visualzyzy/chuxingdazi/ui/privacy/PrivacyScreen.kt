package com.visualzyzy.chuxingdazi.ui.privacy

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import com.visualzyzy.chuxingdazi.ui.theme.PrimaryDark
import com.visualzyzy.chuxingdazi.ui.theme.TextSecondary

// ---- 腾讯版两份协议（2026-11-17 用户给定 URL，2026-11-22 收敛为开放API+导航两份；S01 与 S13 隐私弹窗同指）----
private data class TencentAgreement(val name: String, val url: String)

private val TENCENT_AGREEMENTS = listOf(
    TencentAgreement("腾讯位置服务开放API服务协议", "https://lbs.qq.com/userAgreements/agreements/terms"),
    TencentAgreement("腾讯地图导航sdk隐私协议", "https://privacy.qq.com/document/preview/ba5687d1f4054fed8e108645255320f7"),
)

/** 开屏隐私说明文本（2026-11-17 腾讯版重写，用户给定文案；原百度内容删除）。 */
private const val TENCENT_PRIVACY_TEXT =
    "为提供定位、搜索、地图等能力，本应用使用了腾讯位置服务开放API和腾讯地图导航SDK，" +
        "服务由深圳市腾讯计算机系统有限公司提供，" +
        "将搜集并处理网络类型、设备型号、手机屏幕密度尺寸、操作系统及版本等信息，" +
        "采用SSL协议加密及HTTPS传输加密技术保障安全，采取加密、去标识化等安全措施脱敏处理。"

/** S01 隐私与启动（设计文档 §6 S01）。合规前置：同意之前不得初始化腾讯 SDK。 */
@Composable
fun PrivacyScreen(
    onAgreed: () -> Unit,
    onDeclined: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 打开协议链接（超链接点击共用；URL 为写死的 https 字面量，仅 runCatching 兜底无浏览器场景）
    val openUrl: (String) -> Unit = remember {
        { url ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                android.widget.Toast.makeText(context, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
            }
            Unit
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            // Logo：82dp 圆角 24dp，青蓝渐变 + 定位针
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .background(
                        brush = Brush.linearGradient(listOf(Primary, PrimaryDark)),
                        shape = RoundedCornerShape(24.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "📍", fontSize = 38.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "我的出行搭子", fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = "私人地点簿 · 路线规划器", fontSize = 13.5.sp, color = TextSecondary)

            Spacer(modifier = Modifier.height(32.dp))

            // 隐私说明卡：4 行（第三方地图服务行 = 腾讯开屏文案 + 两份协议超链接，2026-11-22 收敛）
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrivacyItem("位置信息", AnnotatedString("仅在您使用定位、导航功能时读取当前位置，用于把您放到地图上和规划路线"))
                    PrivacyItem("本机存储", AnnotatedString("所有点位、清单、行程仅保存在本机，不上传云端，不注册账号"))
                    PrivacyItem("第三方地图服务", AnnotatedString(TENCENT_PRIVACY_TEXT))
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        TENCENT_AGREEMENTS.forEach { agreement ->
                            val link = remember(agreement.url) {
                                buildAnnotatedString {
                                    pushStringAnnotation(tag = "URL", annotation = agreement.url)
                                    withStyle(SpanStyle(color = Primary, textDecoration = TextDecoration.Underline)) {
                                        append("《${agreement.name}》")
                                    }
                                    pop()
                                }
                            }
                            ClickableText(
                                text = link,
                                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = TextSecondary),
                                onClick = { offset ->
                                    link.getStringAnnotations("URL", offset, offset)
                                        .firstOrNull()
                                        ?.let { openUrl(it.item) }
                                },
                            )
                        }
                    }
                    PrivacyItem("通知与后台运行", AnnotatedString("仅在导航期间保持前台运行以播报转向提示，其余时间不常驻"))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 主按钮：同意并继续（44dp 触控最小尺寸以上）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Primary, RoundedCornerShape(14.dp))
                    .clickable(onClick = onAgreed),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "同意并继续",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable(onClick = onDeclined),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "不同意，退出", fontSize = 14.sp, color = TextSecondary)
            }

            Spacer(modifier = Modifier.height(12.dp))
            // 底注：四份协议名超链接（点同意 = 已阅读并同意全部四份）
            val footer = remember {
                buildAnnotatedString {
                    append("点击「同意并继续」即表示您已阅读并同意")
                    TENCENT_AGREEMENTS.forEachIndexed { i, agreement ->
                        pushStringAnnotation(tag = "URL", annotation = agreement.url)
                        withStyle(SpanStyle(color = Primary, textDecoration = TextDecoration.Underline)) {
                            append("《${agreement.name}》")
                        }
                        pop()
                        if (i == 0) append("、")
                        if (i == 2) append("和")
                    }
                    append("。")
                }
            }
            ClickableText(
                text = footer,
                style = TextStyle(fontSize = 11.sp, color = TextSecondary),
                onClick = { offset ->
                    footer.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()
                        ?.let { openUrl(it.item) }
                },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 隐私说明条目。body 带「URL」tag 注记且传入 onLinkClick 时按可点击超链接渲染
 * （与 S13 隐私弹窗同一套 ClickableText + ACTION_VIEW 模式，P64 同族）。
 */
@Composable
private fun PrivacyItem(
    title: String,
    body: AnnotatedString,
    onLinkClick: (() -> Unit)? = null,
) {
    Row {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .background(Primary, CircleShape),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Column {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (onLinkClick == null) {
                Text(text = body, fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
            } else {
                ClickableText(
                    text = body,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = TextSecondary),
                    onClick = { offset ->
                        body.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()
                            ?.let { onLinkClick() }
                    },
                )
            }
        }
    }
}
