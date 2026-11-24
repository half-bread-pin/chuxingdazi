package com.visualzyzy.chuxingdazi.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.data.tencent.SuggestionItem
import com.visualzyzy.chuxingdazi.ui.theme.Primary

/**
 * S05 搜索（§6 S05）：联想防抖 / 最近搜索 chips / 结果列表 / 小眼睛看详情。
 * P67（2026-11-04）第四轮：最近搜索行加垃圾桶（红/灰 + 确认清空）；结果页适配三键导航栏；
 * 行尾小眼睛 → 打开点位详情页（S05b）。
 *
 * 2026-11-19（用户定调）：取消多选与批量添加——结果行不再有勾选框，删除底部「已选 N 个」
 * 操作条与批量添加浮层；结果行整行点击 = 打开 S05b 详情（与行尾小眼睛同目标）。
 */
@Composable
fun SearchScreen(
    navEntry: androidx.navigation.NavBackStackEntry? = null,
    onClose: () -> Unit,
    onOpenPreview: (item: SuggestionItem) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SearchViewModel = hiltViewModel()

    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val requestRejected by viewModel.requestRejected.collectAsStateWithLifecycle()
    // 2026-11-22 修复 #5：city 流原供结果区城市标签（已删），本文件再无引用处——不再收集
    val recent by viewModel.recentSearches.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    // 第十六轮：空搜索页展示「点位库最近添加」（地图搜点同款空态不再一整页空白）
    val libraryPlaces by viewModel.places.collectAsStateWithLifecycle()

    var showClearHistoryDialog by remember { mutableStateOf(false) }

    val hasQuery = viewModel.query.isNotBlank()
    val showResults = hasQuery && (rows != null || searching || requestRejected)

    // P67（2026-11-04）第四轮：详情页 X 键回落地页 —— 详情页在 SearchScreen 的 savedStateHandle
    // 上打 clear_query 标记，这里消费后清空输入与结果状态（回到 222.jpg 的落地页形态）
    LaunchedEffect(navEntry) {
        if (navEntry == null) return@LaunchedEffect
        navEntry.savedStateHandle.getStateFlow("clear_query", false).collect { flag ->
            if (flag) {
                viewModel.clearQuery()
                navEntry.savedStateHandle["clear_query"] = false
            }
        }
    }

    // P43（2026-11-03）问题7：补不透明背景——此前根布局无背景，过渡动画期间会透出底下的
    // 主界面地图（图标残影）；配合 MainActivity 的 90ms 快速过渡，切换快而直接
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // P67（2026-11-04）第四轮：三键导航时结果列表不再延伸进系统导航栏区域
                .navigationBarsPadding(),
        ) {
            // 顶部：返回 + 搜索框（聚焦）+ 取消（§6 S05 布局 1）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onClose),
                )
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedTextField(
                    value = viewModel.query,
                    onValueChange = { viewModel.onQueryChange(it) },
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    placeholder = { Text("搜索地点、商户", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    trailingIcon = {
                        if (viewModel.query.isNotBlank()) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "清除",
                                modifier = Modifier.clickable {
                                    viewModel.onQueryChange("")
                                },
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "取消",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onClose),
                )
            }

            // 最近搜索 chips（§6 S05 布局 2，最多 5 个）
            // P67（2026-11-04）第四轮：标题行右侧加小垃圾桶——有历史=红色可点（确认后清空全部），
            // 无历史=灰色不可点；与「最近搜索」同一水平线、字号相近
            if (!hasQuery) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "最近搜索",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    val hasHistory = recent.isNotEmpty()
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = if (hasHistory) "清空搜索历史" else "暂无搜索历史",
                        tint = if (hasHistory) {
                            Color(0xFFE5484D)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(enabled = hasHistory) { showClearHistoryDialog = true },
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (recent.isNotEmpty()) {
                    Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        recent.forEach { keyword ->
                            Text(
                                text = keyword,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { viewModel.onQueryChange(keyword) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                // 第十六轮：空搜索不再一整页空白——本地点位库就在手边，展示最近添加的点位
                if (libraryPlaces.isNotEmpty()) {
                    Text(
                        text = "点位库 · 最近添加",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    libraryPlaces.take(5).forEach { place ->
                        val category = categories.firstOrNull { it.id == place.categoryId }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // 点已有点位 = 直接看它（走 S05b 详情形态没有意义——已在库）
                                    // 这里保持轻：回搜索输入其名称，用户可再点小眼睛看详情
                                    viewModel.onQueryChange(place.name)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(
                                        parseCategoryColorSafe(category?.color),
                                        RoundedCornerShape(8.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                com.visualzyzy.chuxingdazi.ui.common.CategoryIconTinted(
                                    iconKey = category?.iconKey ?: "other_point",
                                    size = 16.dp,
                                    tint = Color.White,
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = place.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight(600),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(category?.name, place.address ?: place.province).joinToString(" · "),
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 结果区（§6 S05 布局 3-4）
            if (showResults) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // 2026-11-22 修复 #5：去掉城市后缀（原用地图当前城市，联想实际是全国范围
                        // ——天津时搜景德镇结果页写「搜索结果 · 天津市」，第一次用的用户以为搜错了）
                        text = "搜索结果",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (requestRejected) {
                        Text(
                            text = "请求失败，点此重试",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.retry() },
                        )
                    }
                }

                val currentRows = rows.orEmpty()
                if (currentRows.isEmpty() && !searching && !requestRejected) {
                    // 空态（§6 S05 边界）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = "🗺️", fontSize = 40.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "没找到这个地点，试试换个关键词，\n或直接在地图上长按添加",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 20.sp,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (searching && currentRows.isEmpty()) {
                            // 简易骨架
                            items(4) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .height(52.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp)),
                                )
                            }
                        }
                        items(currentRows.size) { index ->
                            val row = currentRows[index]
                            SearchResultRowView(
                                row = row,
                                // 2026-11-19：多选取消——整行点击 = 打开 S05b 详情（与行尾小眼睛同目标）
                                onOpen = {
                                    if (row.item.lat != null && row.item.lng != null) {
                                        onOpenPreview(row.item)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // P67（2026-11-04）第四轮：清空全部搜索历史确认弹窗（红色垃圾桶 → 确认 / 取消）
        if (showClearHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showClearHistoryDialog = false },
                title = { Text(text = "清空搜索历史", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                text = { Text(text = "将删除全部最近搜索记录，确定吗？", fontSize = 13.sp) },
                confirmButton = {
                    Text(
                        text = "确认",
                        color = Color(0xFFE5484D),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable {
                                viewModel.clearRecentSearches()
                                showClearHistoryDialog = false
                            }
                            .padding(8.dp),
                    )
                },
                dismissButton = {
                    Text(
                        text = "取消",
                        modifier = Modifier.clickable { showClearHistoryDialog = false }.padding(8.dp),
                    )
                },
            )
        }
    }
}

private fun parseCategoryColorSafe(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748B")) }.getOrDefault(Color(0xFF64748B))

@Composable
private fun SearchResultRowView(
    row: com.visualzyzy.chuxingdazi.ui.search.SearchResultRow,
    onOpen: () -> Unit,
) {
    val item: SuggestionItem = row.item
    // 第十六轮：无坐标联想词行 = 灰化 + 「搜索联想词」标签，不可打开
    val suggestionOnly = !row.hasCoords
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !suggestionOnly, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 14.sp,
                fontWeight = FontWeight(600),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            if (suggestionOnly) {
                // 第十六轮：没有稳定坐标的联想词不再留一行孤立的点——明示身份
                Text(
                    text = "搜索联想词",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else if (row.nearMiss && !row.inLibrary) {
                // 第十六轮软提示：坐标很近但名称不同——提醒可能的重复点
                Text(
                    text = "附近 5 米内已有点位，可能是同一处",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tagText = listOfNotNull(item.tag, item.district).joinToString(" · ")
                    if (tagText.isNotBlank()) {
                        Text(
                            text = tagText,
                            fontSize = 10.5.sp,
                            color = Primary,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = item.address ?: item.city ?: "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (row.inLibrary) {
            // 已在库：灰字硬拦（§6 S05）+ 小眼睛仍可看详情
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已在库",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = "查看点位信息",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onOpen),
                )
            }
        } else if (!suggestionOnly) {
            // P67（2026-11-04）第四轮：行尾小眼睛 → 打开该结果的信息页（444.png 形态）
            // 第十六轮：联想词行不显示小眼睛（无坐标可看）
            Icon(
                imageVector = Icons.Filled.Visibility,
                contentDescription = "查看点位信息",
                tint = Primary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onOpen),
            )
        }
    }
}
