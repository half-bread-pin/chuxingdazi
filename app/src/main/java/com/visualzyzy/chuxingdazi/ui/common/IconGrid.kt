package com.visualzyzy.chuxingdazi.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.visualzyzy.chuxingdazi.data.model.IconCatalog
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 分组图标网格（S09 面板与图标选择对话框共用，第十一轮 2026-11-11）：
 * 同前缀的图标集中显示——组标题（中文标签）+ 该组 6 列网格，组序 = IconCatalog.groups。
 * 选中态：主色描边 + 8% 浅底（与 S09 原网格一致）。
 *
 * 第十六轮（优化建议：98 个图标组和图标挤一长页）：
 * - 顶部一排**组名胶囊**（不筛选，只作锚点）——点「交通」滚到交通那一节；
 *   滚到哪节对应胶囊亮起（按滚动位置估算）。
 * - 组标题可点折叠/再展开，折叠只影响这一组；默认全部展开。
 * - [scrollState] 传入外层纵向 scroll（对话框/面板传入），锚点滚动才可用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconGridSections(
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState? = null,
) {
    val columns = 6
    val scope = rememberCoroutineScope()
    val groupLabels = remember { IconCatalog.groups.map { it.second }.distinct() }
    // 折叠状态（只影响对应组；默认全展开）
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    // 各组的纵向内容偏移（锚点滚动用；组内容高度随折叠变化 → 每次布局刷新）
    val sectionTops = remember { mutableMapOf<String, Int>() }
    // 当前滚到的组（胶囊高亮）：由「最后一个 top ≤ scroll 值 + 阈值」的组推出
    val activeGroup = scrollState?.let { st ->
        derivedStateOf {
            sectionTops.entries
                .filter { it.value <= st.value + 120 }
                .maxByOrNull { it.value }?.key ?: groupLabels.firstOrNull()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 组名胶囊行（锚点，不筛选）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            groupLabels.forEach { label ->
                val active = activeGroup?.value == label
                Text(
                    text = label,
                    fontSize = 11.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            collapsed = collapsed - label // 锚点跳转前先展开该组
                            val top = sectionTops[label]
                            if (top != null && scrollState != null) {
                                scope.launch {
                                    scrollState.animateScrollTo(top.coerceAtMost(scrollState.maxValue))
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))

        IconCatalog.groups.forEach { (_, label) ->
            val defs = IconCatalog.all.filter { it.group == label }
            if (defs.isEmpty()) return@forEach
            val isCollapsed = label in collapsed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        sectionTops[label] = coords.positionInParent().y.roundToInt()
                    }
                    .clickable {
                        collapsed = if (isCollapsed) collapsed - label else collapsed + label
                    }
                    .padding(top = 4.dp, bottom = 2.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${defs.size} · ${if (isCollapsed) "展开" else "收起"}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!isCollapsed) {
                // 2026-11-19 修复（用户反馈 111.jpg）：固定 6 列 chunked Row 在窄容器（新建分类
                // AlertDialog）里第 6 列被压成细条——改 FlowRow：放不下的图标自然换到下一行，
                // 宽容器（S09 面板/图标选择对话框）仍保持每行 6 个，视觉不变。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    maxItemsInEachRow = columns,
                ) {
                    defs.forEach { def ->
                        val selected = def.key == selectedKey
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .border(
                                    width = if (selected) 1.5.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(9.dp),
                                )
                                .clickable { onSelect(def.key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            CategoryIconTinted(
                                def.key,
                                22.dp,
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 图标选择对话框（独立 Dialog 窗口——第九轮经验：Compose 弹层不可作 Row 子节点）。
 * 顶部「跟随分类默认」选项：选中后确定回传 null = 清除点位自定义图标（跟随分类）。
 */
@Composable
fun IconPickerDialog(
    currentKey: String?,
    categoryIconKey: String?,
    categoryColorHex: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var pending by remember { mutableStateOf(currentKey) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x59000000))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "选择图标",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "取消",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    // 「跟随分类默认」选项：null = 点位不设自定义图标，地图标记用所属分类的图标
                    val followSelected = pending == null
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (followSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .border(
                                width = if (followSelected) 1.5.dp else 0.dp,
                                color = if (followSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(11.dp),
                            )
                            .clickable { pending = null }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(parseCategoryColorSafe(categoryColorHex)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CategoryIconTinted(categoryIconKey, 18.dp, Color.White)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = "跟随分类默认", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        if (followSelected) {
                            Text(
                                text = "✓",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    val gridScroll = rememberScrollState()
                    IconGridSections(
                        selectedKey = pending,
                        onSelect = { pending = it },
                        scrollState = gridScroll,
                        modifier = Modifier
                            .height(380.dp)
                            .verticalScroll(gridScroll),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                            .clickable { onConfirm(pending) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "确定",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

/**
 * 「图标」行（S06 卡片 / S05b 添加卡片 / S29 编辑面板共用，第十一轮）：
 * 当前生效图标预览（自定义 ?? 分类图标，叠分类色底）+ 状态文字 + 点击整行打开选择对话框。
 */
@Composable
fun PlaceIconRow(
    categoryColorHex: String?,
    categoryIconKey: String?,
    customIconKey: String?,
    onPick: () -> Unit,
) {
    Text(text = "图标", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(parseCategoryColorSafe(categoryColorHex)),
            contentAlignment = Alignment.Center,
        ) {
            CategoryIconTinted(customIconKey ?: categoryIconKey, 19.dp, Color.White)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (customIconKey == null) "跟随分类" else "已自定义",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "选择 ›",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(4.dp))
    }
}
