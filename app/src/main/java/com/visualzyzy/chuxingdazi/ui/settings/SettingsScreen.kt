package com.visualzyzy.chuxingdazi.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import kotlinx.coroutines.launch

/** S13 设置（§6 S13）：4 组卡片；AK 入口从这里进 S02。 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenAk: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenMarkers: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = hiltViewModel()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showBackupSheet by remember { mutableStateOf(false) }

    val akConfigured by viewModel.akConfigured.collectAsStateWithLifecycle()
    val locPerm by viewModel.locPerm.collectAsStateWithLifecycle()
    val noticeEnabled by viewModel.noticeEnabled.collectAsStateWithLifecycle()
    val naviVoiceSystem by viewModel.naviVoiceSystem.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val exportDone by viewModel.exportDone.collectAsStateWithLifecycle()
    val importDone by viewModel.importDone.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showAbout by remember { mutableStateOf(false) }
    var showCertDialog by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    // P61（2026-11-03）问题22：软件显示配色（明亮/暗色/跟随系统）
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    // P76（2026-11-05）：标记管理入口的点位总数角标
    val markerCount by viewModel.placeCount.collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearInput by remember { mutableStateOf("") }

    // 权限状态从系统设置页返回时必须重读（§6 S13）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.refresh(context) }

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(exportDone) {
        exportDone?.let { result ->
            android.widget.Toast.makeText(context, "已备份 ${result.count} 个点位 → ${result.path}", android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                val intent = viewModelShareIntent(context)
                context.startActivity(Intent.createChooser(intent, "分享备份"))
            }
            viewModel.consumeExport()
        }
    }
    LaunchedEffect(importDone) {
        importDone?.let { r ->
            android.widget.Toast.makeText(
                context,
                "导入完成：点位 ${r.places} · 清单 ${r.lists} · 行程 ${r.trips} · 安排 ${r.schedules}" +
                    if (r.skippedSchedules > 0) "（跳过无效安排 ${r.skippedSchedules}）" else "",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            viewModel.consumeImport()
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            // 第十六轮：选文件后先解析冲突预览，确认对话框列出增/覆盖/保留/本地独有
            viewModel.prepareImport(context, it)
        }
    }
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    if (importPreview != null) {
        val pv = importPreview!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("导入预览", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "备份内容：点位 ${pv.backupPlaces} · 清单 ${pv.backupLists} · 行程 ${pv.backupTrips} · 安排 ${pv.backupSchedules}",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight(600),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("将新增：点位 ${pv.newPlaces} · 清单 ${pv.newLists} · 行程 ${pv.newTrips} · 安排 ${pv.newSchedules}", fontSize = 12.5.sp, lineHeight = 18.sp)
                    if (pv.hasConflicts) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "同 ID 冲突：点位 ${pv.conflictPlaces} · 清单 ${pv.conflictLists} · 行程 ${pv.conflictTrips} · 安排 ${pv.conflictSchedules}\n" +
                                "「覆盖」= 更新这些条目；「保留两者」= 改存副本。",
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = com.visualzyzy.chuxingdazi.ui.theme.warningAmber(androidx.compose.foundation.isSystemInDarkTheme()),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "本地独有（不在备份里）：点位 ${pv.localOnlyPlaces} · 清单 ${pv.localOnlyLists} · 行程 ${pv.localOnlyTrips}\n导入只增改、不删除，以上数据保持不动。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            // 第三十三轮（2026-11-15，用户需求）：按钮随冲突状态切换——
            // 无同 ID 冲突 = 取消/确认（此时 keepBoth 两个取值行为完全等价：同 id 冲突分支根本
            // 不进，名称撞车的 "(2)" 后缀在无冲突分支与模式无关，传 false 即可）；
            // 有冲突 = 取消/保留两者/覆盖，「覆盖」恒为主色（原冲突态的琥珀色用户不要）。
            confirmButton = {
                if (pv.hasConflicts) {
                    Row {
                        TextButton(onClick = { viewModel.confirmImport(keepBoth = true) }) {
                            Text("保留两者", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = { viewModel.confirmImport(keepBoth = false) }) {
                            Text("覆盖", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    TextButton(onClick = { viewModel.confirmImport(keepBoth = false) }) {
                        Text("确认", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.cancelImport() }) { Text("取消") } },
        )
    }

    if (showClearConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空所有数据？", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    Text("将删除所有点位、清单、行程与安排，且不可恢复。输入「清空」以确认：", fontSize = 12.5.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = clearInput,
                        onValueChange = { clearInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = clearInput.trim() == "清空" && !busy,
                    onClick = {
                        showClearConfirm = false
                        clearInput = ""
                        viewModel.clearAll()
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
        )
    }

    if (showAbout) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于我的出行搭子", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                val version = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "1.0"
                Text(
                    "版本 $version\n\n本地优先的私人地点簿与出行规划器。所有数据只保存在本机；联网仅用于地图底图、检索与路线计算。",
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("好的") } },
        )
    }

    if (showCertDialog) {
        val sha1 = remember { SettingsViewModel.certSha1(context) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCertDialog = false },
            title = { Text("包名 / SHA1", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("包名：${context.packageName}", fontSize = 12.5.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("SHA1：${sha1 ?: "读取失败"}", fontSize = 11.sp, lineHeight = 16.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "ak-info",
                            "包名：${context.packageName}\nSHA1：${sha1 ?: ""}",
                        ),
                    )
                    android.widget.Toast.makeText(context, "已复制申请信息", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("复制申请信息", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { showCertDialog = false }) { Text("关闭") } },
        )
    }

    if (showPrivacy) {
        // P64（2026-11-03）问题18 / 2026-11-17 腾讯版改版 / 2026-11-22 收敛为两份：腾讯协议可点击超链接
        // （与 S01 同一组 URL；点击用系统浏览器打开）
        val linkColor = MaterialTheme.colorScheme.primary
        val underline = androidx.compose.ui.text.style.TextDecoration.Underline
        val privacyText = androidx.compose.ui.text.buildAnnotatedString {
            append(
                "本应用不收集、不上传任何个人数据。为提供定位、搜索、地图等能力，本应用使用了腾讯位置服务开放API和腾讯地图导航SDK，" +
                    "服务由深圳市腾讯计算机系统有限公司提供，" +
                    "将搜集并处理网络类型、设备型号、手机屏幕密度尺寸、操作系统及版本等信息，" +
                    "采用SSL协议加密及HTTPS传输加密技术保障安全，采取加密、去标识化等安全措施脱敏处理。\n",
            )
            listOf(
                "腾讯位置服务开放API服务协议" to "https://lbs.qq.com/userAgreements/agreements/terms",
                "腾讯地图导航sdk隐私协议" to "https://privacy.qq.com/document/preview/ba5687d1f4054fed8e108645255320f7",
            ).forEach { (name, url) ->
                append("\n")
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(androidx.compose.ui.text.SpanStyle(color = linkColor, textDecoration = underline)) {
                    append("《$name》")
                }
                pop()
            }
            append("\n\n其余数据全部保存在本机数据库与偏好文件中。")
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("隐私政策与第三方 SDK", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                androidx.compose.foundation.text.ClickableText(
                    text = privacyText,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    onClick = { offset ->
                        privacyText.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()
                            ?.let { annotation ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item)),
                                    )
                                }.onFailure {
                                    android.widget.Toast.makeText(context, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                    },
                )
            },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("好的") } },
        )
    }

    // 2026-11-17 腾讯版新需求：「更新行政区划数据」三态弹窗（确认 → 更新中（实时进度）→ 完成 2 秒自关）
    val districtStep by viewModel.districtDialogStep.collectAsStateWithLifecycle()
    val districtProgress by viewModel.districtProgress.collectAsStateWithLifecycle()
    if (districtStep == 1) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissDistrictDialogs() },
            title = { Text("更新行政区划数据", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("是否更新省级-市级行政数据？", fontSize = 13.5.sp) },
            // 确认/取消放右下角（AlertDialog 默认动作区）
            confirmButton = { TextButton(onClick = { viewModel.confirmDistrictUpdate() }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissDistrictDialogs() }) { Text("取消") } },
        )
    }
    if (districtStep == 2) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { /* 更新中不允许关（确认/取消只在完成后给） */ },
            title = { Text("更新中...", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val done = districtProgress?.doneNames.orEmpty()
                    Text(
                        text = if (done.isEmpty()) "已更新 —" else "已更新" + done.joinToString("、"),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "正在更新${districtProgress?.currentName.orEmpty()}",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }
    if (districtStep == 3) {
        // 「更新完成，下次自动更新为2个月后」：2 秒后自动消失，期间用户无法操作
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            viewModel.finishDistrictFlow()
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.finishDistrictFlow() },
            title = { Text("更新完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("更新完成，下次自动更新为2个月后", fontSize = 13.5.sp) },
            confirmButton = {},
            dismissButton = {},
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            // P62（2026-11-03）问题19：内容避开系统导航栏（三键导航时不被底栏盖住）
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // P63（2026-11-03）问题21：暗色模式下标题与返回箭头走 onBackground（=浅白），
            // 不再依赖默认 LocalContentColor（暗色下是黑色，看不见）
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onClose),
            )
            Text(
                text = "设置",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(26.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard("地图与定位") {
                SettingsRow(
                    title = "地图密钥（Key）",
                    value = if (akConfigured) "已配置" else "未配置",
                    valueColor = if (akConfigured) Color(0xFF1B7F3B) else com.visualzyzy.chuxingdazi.ui.theme.warningAmber(androidx.compose.foundation.isSystemInDarkTheme()),
                ) { onOpenAk() }
                Divider()
                // 2026-11-17 腾讯版新需求：手动更新省-市行政区划数据（自动每 2 个月一次，启动时检查）
                SettingsRow(title = "更新行政区划数据", value = "") {
                    viewModel.showDistrictConfirm()
                }
                Divider()
                // 第十六轮（优化建议）：定位权限有自己的标题行——「未授权」不再被误读成密钥未授权
                SettingsRow(title = "定位权限", value = "") {}
                val (label, labelColor) = when (locPerm) {
                    SettingsViewModel.LocPerm.OK_FOREGROUND -> "仅使用期间 · 正常" to Color(0xFF1B7F3B)
                    SettingsViewModel.LocPerm.OK_ALWAYS -> "始终允许 · 正常" to Color(0xFF1B7F3B)
                    SettingsViewModel.LocPerm.ONESHOT -> "仅本次 · 重启后失效" to com.visualzyzy.chuxingdazi.ui.theme.warningAmber(androidx.compose.foundation.isSystemInDarkTheme())
                    SettingsViewModel.LocPerm.SERVICE_OFF -> "定位服务已关闭" to MaterialTheme.colorScheme.error
                    SettingsViewModel.LocPerm.DENIED -> "未授权 · 点此开启" to MaterialTheme.colorScheme.error
                }
                val dotColor = when (locPerm) {
                    SettingsViewModel.LocPerm.OK_FOREGROUND, SettingsViewModel.LocPerm.OK_ALWAYS -> Color(0xFF1B7F3B)
                    SettingsViewModel.LocPerm.ONESHOT -> Color(0xFFE8A800)
                    else -> MaterialTheme.colorScheme.error
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.parse("package:${context.packageName}")),
                                )
                            }
                        }
                        .padding(vertical = 11.dp),
                ) {
                    Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = label, fontSize = 13.5.sp, color = labelColor, modifier = Modifier.weight(1f))
                    Text(text = "›", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            // R10（第七轮 2026-11-06）：「点位显示」→「管理」
            SettingsCard("管理") {
                SettingsRow(title = "清单管理", value = "") { onOpenLists() }
                Divider()
                SettingsRow(title = "分类管理", value = "") { onOpenCategories() }
                Divider()
                // P76（2026-11-05）：新增「标记管理」——查看/改名/换分类/清单归属/到访/删除（不含新增标记）
                SettingsRow(
                    title = "标记管理",
                    value = "共 $markerCount 个",
                    valueColor = MaterialTheme.colorScheme.primary,
                ) { onOpenMarkers() }
            }

            // P61（2026-11-03）问题22：软件显示配色 —— 明亮模式 / 暗色模式 / 跟随系统，全局立即生效
            val themeLabel = when (themeMode) {
                Prefs.THEME_LIGHT -> "明亮模式"
                Prefs.THEME_DARK -> "暗色模式"
                else -> "跟随系统"
            }
            SettingsCard("通用") {
                SettingsRow(title = "软件显示配色", value = themeLabel, valueColor = MaterialTheme.colorScheme.primary) {
                    showThemeDialog = true
                }
                Divider()
                // P76（2026-11-05）：导航说明/导航语音从「点位显示」移入「通用」
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "导航说明", fontSize = 13.5.sp)
                        // P38（2026-11-02）：文案改为用户指定表述
                        Text(
                            text = "导航界面是否提示本软件导航可能有误差",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = noticeEnabled,
                        onCheckedChange = { viewModel.setNotice(context, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Primary),
                    )
                }
                Divider()
                // P60（2026-11-03）问题20：百度语音 4 项参数的填写入口——
                // 第十六轮（优化建议）：内置 TTS 引擎未接入（决策 #35，实际播报恒为系统语音），
                // 决策 #82/#35：导航语音恒「跟随系统」单一文案（腾讯 navix 经 setTTSPlayer 桥接系统语音，
                // 无内置 TTS 选项）；引擎接入（V1.1）后再评估恢复参数表单。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "导航语音", fontSize = 13.5.sp)
                        Text(
                            text = "跟随系统（默认）",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "跟随系统",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsCard("数据") {
                SettingsRow(title = "备份与恢复", value = if (busy) "处理中…" else "导出到 Download / 从文件导入") {
                    // 弹出二选一
                    showBackupSheet = true
                }
                Divider()
                Text(
                    text = "清空所有数据",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showClearConfirm = true }
                        .padding(vertical = 11.dp),
                )
            }

            SettingsCard("关于") {
                SettingsRow(title = "关于我的出行搭子", value = "") { showAbout = true }
                Divider()
                SettingsRow(title = "包名 / SHA1", value = "") { showCertDialog = true }
                Divider()
                SettingsRow(title = "隐私政策与第三方 SDK", value = "") { showPrivacy = true }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    // P61（2026-11-03）问题22：配色三选一弹窗
    if (showThemeDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("软件显示配色", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    listOf(
                        Prefs.THEME_SYSTEM to "跟随系统",
                        Prefs.THEME_LIGHT to "明亮模式",
                        Prefs.THEME_DARK to "暗色模式",
                    ).forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setThemeMode(context, mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(
                                        if (themeMode == mode) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        CircleShape,
                                    )
                                    .border(
                                        width = 1.5.dp,
                                        color = if (themeMode == mode) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (themeMode == mode) {
                                    Text(text = "✓", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = label, fontSize = 13.5.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("取消") }
            },
        )
    }

    // 备份/导入选择浮层
    if (showBackupSheet) {
        val backups = remember { viewModel.listBackups() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBackupSheet = false },
            title = { Text("备份与恢复", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("导出全部点位/清单/行程到 Download；下方为历史备份（点按导入）。", fontSize = 12.sp, lineHeight = 16.sp)
                    if (backups.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        backups.take(3).forEach { (uri, name) ->
                            Text(
                                text = name,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showBackupSheet = false
                                        viewModel.prepareImport(context, uri)
                                    }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackupSheet = false
                    viewModel.export(context)
                }) { Text("导出备份", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackupSheet = false
                    importPicker.launch(arrayOf("application/json", "text/*", "*/*"))
                }) { Text("从文件选择…", color = MaterialTheme.colorScheme.primary) }
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
    ) {
        Text(text = title, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) {
            Text(text = value, fontSize = 11.5.sp, color = valueColor)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "›", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
        } else {
            Text(text = "›", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

private fun viewModelShareIntent(context: android.content.Context): Intent {
    // 由屏层直接构造分享意图（BackupManager.shareIntent 需要 FileProvider）
    val dir = java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "",
    )
    val latest = dir.listFiles { f -> f.name.startsWith("我的出行搭子备份-") }?.maxByOrNull { it.lastModified() }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        latest ?: throw IllegalStateException("未找到备份文件"),
    )
    return Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
