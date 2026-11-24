package com.visualzyzy.chuxingdazi.ui.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.ui.theme.Primary

/**
 * S11 选择途经点（§6 S11）：两段式（P58 问题17，2026-11-03）——
 * ① 选择点位：多模式点位搜索（第十七轮 2026-11-14 起与「清单管理-添加点位」同款
 *    ui/common/PlaceSearchBars：名称/地区/分类，可多栏 AND）+ 勾选点位库，
 *    底部「确认 · X 个点」进入排序界面；
 * ② 途径顺序：独立界面对已选点位排序（长按拖拽 / × 移除 / 少绕路 2-opt），
 *    左上返回可回①增减点位，「完成」才回传 S10（回 S10 后仍要点「确认路线」）。
 *
 * P59 问题14：暗色模式下返回箭头/标题/点位行/「途经顺序」块内文字、↓ 小箭头全部
 * 走 onBackground/onSurface 系颜色（暗色自动变白），不再用默认黑与过暗的 outlineVariant。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectWaypointsScreen(
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SelectWaypointsViewModel = hiltViewModel()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toast.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    // 第十七轮（2026-11-14）：搜索区换成与「清单管理-添加点位」（S28 添加浮层）同一套多模式点位搜索
    // ——ui/common/PlaceSearchBars：名称/地区/分类模式条，可「＋」追加多栏 AND 过滤
    var bars by remember { mutableStateOf(listOf(com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBar())) }
    val markedProvinces = remember(places) {
        places.mapNotNull { it.province?.takeIf { p -> p.isNotBlank() } }.distinct()
    }
    val filtered = remember(places, bars) {
        com.visualzyzy.chuxingdazi.ui.common.filterPlacesBySearchBars(places = places, bars = bars)
    }

    // P58 问题17：排序是否进入独立界面
    var ordering by remember { mutableStateOf(false) }

    val commitAndDone = {
        viewModel.commitTransfer()
        onDone()
    }

    // 排序界面里按系统返回 = 回选择界面（不退出整页）
    BackHandler(enabled = ordering) { ordering = false }
    // 排序界面里把点位全删光 → 自动回选择界面
    LaunchedEffect(ordering, selected.size) {
        if (ordering && selected.isEmpty()) ordering = false
    }

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    if (ordering) {
        OrderingScreen(
            selected = selected,
            onBack = { ordering = false },
            onMove = { from, to -> viewModel.move(from, to) },
            onRemoveAt = { viewModel.removeAt(it) },
            onTwoOpt = { viewModel.twoOpt() },
            onDone = commitAndDone,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // P59 问题14：箭头/标题显式走 onBackground（暗色=白），不依赖默认 LocalContentColor
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onClose),
            )
            Text(
                text = "选择途径点",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "完成",
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = selected.isNotEmpty(), onClick = commitAndDone),
            )
        }

        com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBarsSection(
            bars = bars,
            onBarsChange = { bars = it },
            categories = categories,
            markedProvinces = markedProvinces,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(10.dp))

        // P58 问题17：选择界面不再常驻「途经顺序」卡（挪进独立排序界面），
        // 只留一行已选摘要（点它也可进入排序界面）
        if (selected.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Primary.copy(alpha = 0.06f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { ordering = true },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "已选 ${selected.size} 个点位 · ${selected.joinToString("、") { it.name }}",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "调整顺序 ›",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "全部点位 · 已选 ${selected.size}",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(filtered, key = { _, p -> p.id }) { _, place ->
                val isSel = selected.any { it.id == place.id }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggle(place) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    // 勾选框 = S28 添加浮层同款圆角方框（原圆形勾圈 + 「已选」角标一并换掉）
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .border(
                                width = if (isSel) 0.dp else 1.5.dp,
                                color = if (isSel) Primary else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(5.dp),
                            )
                            .background(if (isSel) Primary else Color.Transparent, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSel) {
                            Text(text = "✓", fontSize = 13.sp, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        // P59 问题14：点位名称显式 onSurface（暗色=浅白）
                        Text(
                            text = place.name,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight(600),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 副标题 = S28 添加浮层同款「分类名 · 省-市」（缺失「暂无地址」）
                        Text(
                            text = listOfNotNull(
                                categoriesById[place.categoryId]?.name,
                                listOfNotNull(place.province, place.city).joinToString("-").ifBlank { null },
                            ).joinToString(" · ").ifBlank { "暂无地址" },
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = if (places.isEmpty()) "点位库还是空的，先回主地图添加点位" else "没有匹配的点位",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    )
                }
            }
        }

        // 底部：P58 问题17 「确认 · X 个点」→ 进入途径顺序排序界面（不再直接回传 S10）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
                .height(46.dp)
                .background(
                    if (selected.isNotEmpty()) Primary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(14.dp),
                )
                .clickable(enabled = selected.isNotEmpty()) { ordering = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "确认 · ${selected.size} 个点",
                color = if (selected.isNotEmpty()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * P58 问题17：途径顺序独立界面。长按拖拽排序（与 S10 途经行同一套手势），
 * 「少绕路」2-opt、× 移除；返回回选择界面，「完成」回传 S10。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrderingScreen(
    selected: List<com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity>,
    onBack: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onTwoOpt: () -> Unit,
    onDone: () -> Unit,
) {
    // 长按拖拽排序（点位最多 8 个，一列纵排 + 位移交换即可）
    var dragIndex by remember { mutableStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { 46.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回选择点位",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onBack),
            )
            Text(
                text = "途径顺序",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "完成",
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onDone),
            )
        }

        Text(
            text = "长按上下拖动调整顺序；点 × 可移除，需要增减点位请返回上一页",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Primary.copy(alpha = 0.06f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "途径顺序",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "少绕路",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onTwoOpt),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    selected.forEachIndexed { index, place ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .graphicsLayer {
                                    if (dragIndex == index) translationY = dragOffsetY
                                }
                                .pointerInput(selected.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragIndex = index
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, amt ->
                                            change.consume()
                                            dragOffsetY += amt.y
                                            while (dragOffsetY > rowHeightPx / 2 && dragIndex < selected.size - 1) {
                                                onMove(dragIndex, dragIndex + 1)
                                                dragIndex++
                                                dragOffsetY -= rowHeightPx
                                            }
                                            while (dragOffsetY < -rowHeightPx / 2 && dragIndex > 0) {
                                                onMove(dragIndex, dragIndex - 1)
                                                dragIndex--
                                                dragOffsetY += rowHeightPx
                                            }
                                        },
                                        onDragEnd = {
                                            dragIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            dragIndex = -1
                                            dragOffsetY = 0f
                                        },
                                    )
                                },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 10.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = place.name,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "⠿",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "×",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable { onRemoveAt(index) }
                                    .padding(start = 8.dp),
                            )
                        }
                        if (index < selected.size - 1) {
                            // P59 问题14：↓ 小箭头在暗色下也要可见（原 outlineVariant 过暗）
                            Text(
                                text = "↓",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
