package com.visualzyzy.chuxingdazi.ui.navi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.visualzyzy.chuxingdazi.data.tencent.TravelMode
import com.visualzyzy.chuxingdazi.ui.route.RouteViewModel
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** S16 导航方式（§6 S16）：每次重新弹，不记忆选择；无途经点限制说明见 viaCount。
 *  P68（2026-11-04）第四轮：改为底部对齐的真浮层（全屏 scrim + 底部圆角面板），
 *  面板内容 statusBarsPadding + 可滚动——三键/手势导航下都不再占用顶部状态栏区域（777.jpg 问题）。
 *  第二十三轮（2026-11-14）：应用内导航仅对「驾车」开放——导航 SDK 的车辆类型只有
 *  CAR/MOTOR/TRUCK（onsdk 9.0.0 IBNRoutePlanManager$Vehicle），公交/步行/骑行此前会被
 *  静默按驾车路线导航。travelMode 由 RouteScreen 传入。
 *  第二十八轮（2026-11-15）：外部跳转出行方式跟随所选；驾车跳百度/腾讯带途经点
 *  （上限 15），行副标题随方式/途经点如实说明；确认框只在真丢途经点的组合弹。
 *  2026-11-20：应用内导航按 navix 7.10.0 原生能力放开到**驾车/步行/骑行**（NavigatorWalk/
 *  NavigatorRide，官方文档 AndroidNavigationX 步行/骑行专页）；公交无导航器仍禁用。
 *  步行/骑行的算路只有起终点（官方无途经点参数）→ 有途经点时该行禁用并说明。
 *  2026-11-21 方案 A 落地（#20 收口）：步行/骑行 native 崩溃根因（引擎地图登记表空表）
 *  随「全 App 改用导航包内置地图库」消除，入口按上述口径恢复可用。
 */
@Composable
fun NaviMethodSheet(
    distanceText: String,
    viaCount: Int,
    travelMode: TravelMode,
    onStartInApp: () -> Unit,
    onJump: (app: ExternalApp) -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 2026-11-21 方案 A 落地（#20 收口）：全 App 改用导航包内置地图库（官方形态）→
    // 导航 SDK 自建地图 → 引擎「地图登记表」填充 → 步行/骑行 native 0x58 崩溃根因
    // 链消除（真机 T2/T2b 验证：引导面板/路线彩线/驾车回归全过，见
    // docs/导航SDK自建地图-MapView共存备选方案.md §九）。应用内导航恢复
    // **驾车/步行/骑行**；公交仍禁用（官方无 NavigatorBus）。步行/骑行的官方算路
    // 参数只有起终点（无途经点）→ 有途经点时该行禁用并说明。
    val inAppEnabled = travelMode != TravelMode.TRANSIT &&
        (travelMode == TravelMode.DRIVING || viaCount == 0)
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        // 全屏 scrim（点击空白处收起）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x59000000))
                .clickable(onClick = onDismiss),
        )
        // 底部对齐面板：限高 80% 屏高（顶边始终在状态栏之下），超高可滚动
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = Material3SurfaceColor(),
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            val maxH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.8f
            Column(
                modifier = Modifier
                    .heightIn(max = maxH.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(text = "怎么走？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "全程 $distanceText · 途经 $viaCount 个点",
                    fontSize = 11.sp,
                    color = Color(0xFF8A93A3),
                )
                Spacer(modifier = Modifier.height(12.dp))
                MethodRow(
                    title = "应用内导航",
                    subtitle = when {
                        // 遗留 #17（2026-11-18）：百度版文案残留改腾讯口径
                        inAppEnabled -> "腾讯导航 SDK · 语音播报 · 路口放大图"
                        travelMode == TravelMode.TRANSIT ->
                            // 公交无导航引擎（官方无 NavigatorBus），维持置灰口径
                            "公交暂不支持应用内导航 · 请用下面的地图 App"
                        else ->
                            // 步行/骑行有途经点：官方算路参数只有起终点（无途经点）
                            "步行/骑行导航不支持途经点 · 请移除途经点或改用地图 App"
                    },
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    enabled = inAppEnabled,
                ) {
                    onDismiss()
                    onStartInApp()
                }
                MethodRow(
                    title = "百度地图 App",
                    // 第二十八轮：驾车带途经点（上限 15），非驾车不带
                    subtitle = when {
                        viaCount == 0 -> installedText(context, ExternalApp.BAIDU)
                        travelMode == TravelMode.DRIVING -> "途经点会带走（最多15个）"
                        else -> "途经点不带走"
                    },
                    tint = Color(0xFF3385FF),
                    enabled = isInstalled(context, ExternalApp.BAIDU),
                ) {
                    onDismiss()
                    onJump(ExternalApp.BAIDU)
                }
                MethodRow(
                    title = "高德地图 App",
                    subtitle = if (viaCount > 0) "途经点不带走" else installedText(context, ExternalApp.AMAP),
                    tint = Color(0xFF00B578),
                    enabled = isInstalled(context, ExternalApp.AMAP),
                ) {
                    onDismiss()
                    onJump(ExternalApp.AMAP)
                }
                MethodRow(
                    title = "腾讯地图 App",
                    // 第二十八轮：驾车带途经点（上限 15），非驾车不带
                    subtitle = when {
                        viaCount == 0 -> installedText(context, ExternalApp.TENCENT)
                        travelMode == TravelMode.DRIVING -> "途经点会带走（最多15个）"
                        else -> "途经点不带走"
                    },
                    tint = Color(0xFF1485EE),
                    enabled = isInstalled(context, ExternalApp.TENCENT),
                ) {
                    onDismiss()
                    onJump(ExternalApp.TENCENT)
                }
                MethodRow(
                    title = "复制路线信息",
                    subtitle = "把起终点和途经点整理成文字",
                    tint = Color(0xFF5B8DEF),
                    enabled = true,
                ) {
                    onDismiss()
                    onCopy()
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Primary, RoundedCornerShape(13.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "开始", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun Material3SurfaceColor(): Color = androidx.compose.material3.MaterialTheme.colorScheme.surface

/** 跳转外部地图前「途经点会丢失」二次确认（§6 S16 产品规则）。
 *  「不再提示」复选框样式同 S20 导航说明；勾选后点任一按钮都回传勾选值
 *  （RouteScreen 落 Prefs），直接关闭弹窗不回传。
 *  2026-11-20：步行/骑行的应用内导航同样不支持途经点 → [inAppAvailable]=false 时
 *  不再显示「改用应用内导航」按钮（此时没有能保留途经点的去处，只确认丢失）。 */
@Composable
fun ViaDropConfirmDialog(
    viaCount: Int,
    inAppAvailable: Boolean,
    onStillJump: (dontRemind: Boolean) -> Unit,
    onUseInApp: (dontRemind: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var never by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转后只保留起点和终点", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    if (inAppAvailable) "$viaCount 个途经点会丢失。想走完整路线，可以留在应用内导航。"
                    else "$viaCount 个途经点会丢失。",
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 不再提示（自绘复选框，样式同 S20 导航说明）
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(
                                if (never) Primary else Color.Transparent,
                                RoundedCornerShape(4.dp),
                            )
                            .then(
                                if (never) {
                                    Modifier
                                } else {
                                    Modifier.border(
                                        width = 1.5.dp,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                },
                            )
                            .clickable { never = !never }
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (never) Text("✓", color = Color.White, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "不再提示",
                        fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { never = !never },
                    )
                }
            }
        },
        confirmButton = {
            if (inAppAvailable) {
                TextButton(onClick = { onDismiss(); onUseInApp(never) }) { Text("改用应用内导航", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(); onStillJump(never) }) { Text("仍要跳转", color = Color(0xFF8A93A3)) }
        },
    )
}

/**
 * S20 导航说明（视觉稿 designs/design/png/s20.png；第二十八轮 2026-11-15 文案定稿）：
 * 琥珀色信息图标 + 标题「（重要）开始规划前注意事项」（无副标题）+ 两个分区
 * （应用内导航 3 条 / 跳转地图 App 3 条，加粗关键词）+ 底部「不再提示」复选框与「知道了」实心按钮。
 */
@Composable
fun NaviNoticeDialog(
    onGotIt: (neverShow: Boolean) -> Unit,
) {
    var never by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onGotIt(false) },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = {},
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 琥珀色信息图标（S20 顶部）
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0x17F08A24), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = Color(0xFFF08A24),
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "（重要）开始规划前注意事项",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(14.dp))
                NoticeSection(Icons.Filled.Navigation, "应用内导航")
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBullet(
                    annotated(
                        // 遗留 #17（2026-11-18）：同批清理 S20 弹窗里的百度口径残留
                        "路况由腾讯 SDK 提供，" to false,
                        "车道指引、限行规避" to true,
                        "等没有专业地图 App 精准。" to false,
                    ),
                )
                NoticeBullet(
                    annotated(
                        "全程联网" to true,
                        "，隧道和地下车库容易掉线卡顿，也更耗电。" to false,
                    ),
                )
                NoticeBullet(
                    annotated(
                        "公交、步行、骑行多点时只能" to false,
                        "分段计算再拼接" to true,
                        "，不是最优路线——建议改用专业地图 App。" to false,
                    ),
                )
                Spacer(modifier = Modifier.height(14.dp))
                NoticeSection(Icons.Filled.Link, "跳转地图 App")
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBullet(
                    annotated(
                        "除「" to false,
                        "百度地图/腾讯地图 + 驾车" to true,
                        "」外，跳转到地图 App 时，途经点均会丢失。" to false,
                    ),
                )
                NoticeBullet(
                    annotated(
                        "「百度地图/腾讯地图 + 驾车」途经点" to false,
                        "最多15个" to true,
                        "。" to false,
                    ),
                )
                NoticeBullet(
                    annotated(
                        "跳转后路线由地图 App 重算，可能和这里看到的不一样。" to false,
                    ),
                )
                Spacer(modifier = Modifier.height(14.dp))
                androidx.compose.material3.HorizontalDivider(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 不再提示（自绘复选框，样式同旧版）
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(
                                if (never) Primary else Color.Transparent,
                                RoundedCornerShape(4.dp),
                            )
                            .then(
                                if (never) {
                                    Modifier
                                } else {
                                    Modifier.border(
                                        width = 1.5.dp,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                },
                            )
                            .clickable { never = !never }
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (never) Text("✓", color = Color.White, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "不再提示",
                        fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { never = !never },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .background(Primary, RoundedCornerShape(13.dp))
                            .clickable { onGotIt(never) }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "知道了",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

/** 分区标题：小图标块 + 名称 + 延伸分隔线（S20 样式）。 */
@Composable
private fun NoticeSection(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Primary.copy(alpha = 0.10f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** 圆点列表项（S20 样式：主色小圆点 + 12.5sp 正文，含加粗片段）。 */
@Composable
private fun NoticeBullet(text: androidx.compose.ui.text.AnnotatedString) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(4.dp)
                .background(Color(0xFF9AA3B2), CircleShape),
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = text,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 构造「片段 to 是否加粗」的 AnnotatedString。 */
private fun annotated(vararg parts: Pair<String, Boolean>): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        parts.forEach { (text, bold) ->
            if (bold) {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }
            } else {
                append(text)
            }
        }
    }

@Composable
private fun MethodRow(
    title: String,
    subtitle: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(tint.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(tint, CircleShape),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Material3SurfaceTextColor() else Material3SurfaceTextColor().copy(alpha = 0.42f),
            )
            Text(text = subtitle, fontSize = 11.sp, color = Color(0xFF8A93A3))
        }
        Box(
            modifier = Modifier
                .size(21.dp)
                .background(Color.Transparent, CircleShape),
        )
    }
}

@Composable
private fun Material3SurfaceTextColor(): Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface

/** 交通方式的中文名（复制路线文案/说明用）。 */
fun travelModeName(mode: TravelMode): String = when (mode) {
    TravelMode.DRIVING -> "驾车"
    TravelMode.WALKING -> "步行"
    TravelMode.BIKING -> "骑行"
    TravelMode.TRANSIT -> "公交"
}

enum class ExternalApp { IN_APP, BAIDU, AMAP, TENCENT }

/**
 * P37（2026-11-02）：已装地图 App 检测。两道探测：
 * 1) getPackageInfo（需要 manifest `<queries>` 声明包名，已加）；
 * 2) 兜底 resolveActivity 按 scheme 解析（queries 里也声明了 baidumap/amapuri/qqmap）。
 * 只做其中一道在部分 ROM 上会漏判。
 */
private fun isInstalled(context: Context, app: ExternalApp): Boolean = when (app) {
    ExternalApp.BAIDU -> pkgOrSchemeInstalled(context, "com.baidu.BaiduMap", "baidumap://map/")
    ExternalApp.AMAP -> pkgOrSchemeInstalled(context, "com.autonavi.minimap", "amapuri://route/plan/")
    ExternalApp.TENCENT -> pkgOrSchemeInstalled(context, "com.tencent.map", "qqmap://")
    else -> false
}

private fun pkgOrSchemeInstalled(context: Context, pkg: String, schemeUri: String): Boolean =
    runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false) ||
        runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(schemeUri)),
                0,
            ) != null
        }.getOrDefault(false)

private fun installedText(context: Context, app: ExternalApp): String =
    if (isInstalled(context, app)) "已安装" else "未下载"

/**
 * GCJ02 → BD09（2026-11-17 腾讯版：库内坐标已是 GCJ02，只有跳百度需要逆变换；标准公开公式）。
 * 原百度版是反向（库内 BD09 → 高德/腾讯 GCJ02），随换 SDK 语义反转：现在仅百度一行做转换。
 * 2026-11-18 修复双重偏移（待办册遗留 #11）：偏移曾在输入端（x/y 进 z/θ）与输出端各加一次，
 * 与标准公式差 ~868 米——标准 gcj02tobd09 的 z/θ 用原始坐标、偏移仅在输出端加一次。
 */
fun gcj02ToBd09(lat: Double, lng: Double): Pair<Double, Double> {
    val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * Math.PI * 3000 / 180)
    val theta = kotlin.math.atan2(lat, lng) + 0.000003 * cos(lng * Math.PI * 3000 / 180)
    return Pair(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
}

/**
 * BD09 → GCJ02（遗留 #10，2026-11-18）：百度版存量点位坐标换算成腾讯版库内口径。
 * 标准 bd09togcj02（gcj02ToBd09 的逆变换，同 tools_seed/backup_bd09_to_gcj02.py）；
 * 单测锚点见 Bd09ConversionTest。设备端存量由 DB v7→v8 迁移批量调用（见 ChuxingdaziDatabase）。
 */
fun bd09ToGcJ02(bdLat: Double, bdLng: Double): Pair<Double, Double> {
    val x = bdLng - 0.0065
    val y = bdLat - 0.006
    val z = sqrt(x * x + y * y) - 0.00002 * sin(y * Math.PI * 3000 / 180)
    val theta = kotlin.math.atan2(y, x) - 0.000003 * cos(x * Math.PI * 3000 / 180)
    return Pair(z * sin(theta), z * cos(theta))
}

/**
 * 跳转途经点上限（2026-11-15 真机实测：百度 direction 的 viaPoints 传 16/20 个只收
 * 前 15 个，UI 显示「途经15地」，与鸿蒙端文档骑行导航页「最多支持15个点」一致；
 * Android 官方文档对 direction 未写明数量上限）。腾讯 passes 官方文档写明最多 15 个。
 * 用户规划超过 15 个时只传前 15 个。
 */
private const val JUMP_VIA_LIMIT = 15

/** 该跳转组合是否会丢掉全部途经点：非驾车一律不带；驾车时仅高德不带（官方 URI 无途经点参数）。 */
fun jumpDropsWaypoints(app: ExternalApp, mode: TravelMode): Boolean =
    mode != TravelMode.DRIVING || app == ExternalApp.AMAP

/** 跳转外部地图 App（§6 S16 技术要点；2026-11-17 腾讯版：库内 GCJ02 直传，仅百度做 GCJ02→BD09）。
 *  第二十八轮（2026-11-15）：出行方式跟随所选（百度 mode=driving/walking/riding/transit、
 *  高德 t=0/1/2/3、腾讯 type=drive/bus/walk/bike）；驾车时百度带 viaPoints、腾讯带 passes
 *  （均截前 [JUMP_VIA_LIMIT] 个），高德与非驾车只传起终点。 */
fun jumpToExternal(
    context: Context,
    app: ExternalApp,
    stop: RouteViewModel.Stop,
    end: RouteViewModel.Stop,
    via: List<RouteViewModel.Stop>,
    mode: TravelMode,
) {
    val intent = when (app) {
        ExternalApp.BAIDU -> {
            val baiduMode = when (mode) {
                TravelMode.DRIVING -> "driving"
                TravelMode.WALKING -> "walking"
                TravelMode.BIKING -> "riding"
                TravelMode.TRANSIT -> "transit"
            }
            // 库内 GCJ02 → BD09（百度 URI 按 coord_type=bd09ll 解析）。
            // 途经点仅驾车有效（官方文档/JSAPI 同源说明）；JSON 需整体 encodeURIComponent。
            // org.json 构造保证名称里的引号等被正确转义。
            val (blat, blng) = gcj02ToBd09(stop.lat, stop.lng)
            val (dBlat, dBlng) = gcj02ToBd09(end.lat, end.lng)
            val viaParam = if (mode == TravelMode.DRIVING && via.isNotEmpty()) {
                val arr = org.json.JSONArray()
                via.take(JUMP_VIA_LIMIT).forEach { p ->
                    val (glat, glng) = gcj02ToBd09(p.lat, p.lng)
                    arr.put(
                        org.json.JSONObject()
                            .put("name", p.name)
                            .put("lat", glat)
                            .put("lng", glng),
                    )
                }
                "&viaPoints=" + Uri.encode(org.json.JSONObject().put("viaPoints", arr).toString())
            } else ""
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "baidumap://map/direction?origin=latlng:$blat,$blng|name:${Uri.encode(stop.name)}" +
                        "&destination=latlng:$dBlat,$dBlng|name:${Uri.encode(end.name)}" +
                        "&mode=$baiduMode&coord_type=bd09ll$viaParam&src=android.com.visualzyzy.chuxingdazi",
                ),
            )
        }
        ExternalApp.AMAP -> {
            // 库内即 GCJ02，免转换（高德坐标系一致）
            val t = when (mode) {
                TravelMode.DRIVING -> 0
                TravelMode.TRANSIT -> 1
                TravelMode.WALKING -> 2
                TravelMode.BIKING -> 3
            }
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "amapuri://route/plan/?slat=${stop.lat}&slon=${stop.lng}&sname=${Uri.encode(stop.name)}" +
                        "&dlat=${end.lat}&dlon=${end.lng}&dname=${Uri.encode(end.name)}&dev=0&t=$t",
                ),
            )
        }
        ExternalApp.TENCENT -> {
            // 库内即 GCJ02，免转换
            val type = when (mode) {
                TravelMode.DRIVING -> "drive"
                TravelMode.TRANSIT -> "bus"
                TravelMode.WALKING -> "walk"
                TravelMode.BIKING -> "bike"
            }
            // passes 格式见官方 URI API 文档：name:{名称};coord:{纬度},{经度}; ，点间用 | 分隔；
            // 文档未限定 type 但示例为驾车，仅在驾车时拼（与百度同上限 15，超出截前 15 个）。
            val passes = if (mode == TravelMode.DRIVING && via.isNotEmpty()) {
                "&passes=" + via.take(JUMP_VIA_LIMIT).joinToString("|") { p ->
                    "name:${Uri.encode(p.name)};coord:${p.lat},${p.lng};"
                } + "|"
            } else ""
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "qqmap://map/routeplan?type=$type&from=${Uri.encode(stop.name)}&fromcoord=${stop.lat},${stop.lng}" +
                        "&to=${Uri.encode(end.name)}&tocoord=${end.lat},${end.lng}$passes&referer=出行搭子",
                ),
            )
        }
        else -> return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "未找到该地图 App", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 复制路线文字（兜底出口；2026-11-20 起方式名随所选）。 */
fun copyRouteText(
    context: Context,
    stop: RouteViewModel.Stop,
    end: RouteViewModel.Stop,
    via: List<RouteViewModel.Stop>,
    mode: TravelMode = TravelMode.DRIVING,
) {
    val sb = StringBuilder()
    sb.append("【出行搭子】${travelModeName(mode)}路线\n起点：${stop.name}(${stop.lat},${stop.lng})\n")
    via.forEachIndexed { i, v -> sb.append("途经${i + 1}：${v.name}(${v.lat},${v.lng})\n") }
    sb.append("终点：${end.name}(${end.lat},${end.lng})")
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("route", sb.toString()))
    android.widget.Toast.makeText(context, "已复制路线信息", android.widget.Toast.LENGTH_SHORT).show()
}
