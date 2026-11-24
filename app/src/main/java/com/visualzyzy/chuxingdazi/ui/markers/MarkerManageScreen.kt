package com.visualzyzy.chuxingdazi.ui.markers

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.ui.theme.Primary

/**
 * P76（2026-11-05）S29 标记管理：设置 → 点位显示 → 标记管理。
 * 全库标记列表（按分类图标/颜色渲染 + 搜索过滤）。**不含新增标记**。
 *
 * 字段模板升级（2026-11-21 用户定调）：点标记行**不再弹底部编辑面板**，改为进入 S08
 * 编辑点位页（onEditPlace → 路由 s08?placeId=…）。原面板的改名/换分类/图标/清单/已到访/
 * 删除能力全部由 S08 承接（S08 已补图标行/已到访开关/删除行），本页回归纯列表。
 */
@Composable
fun MarkerManageScreen(
    onClose: () -> Unit,
    onEditPlace: (placeId: String) -> Unit,
    viewModel: MarkerManageViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val places by viewModel.places.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // R9（第七轮 2026-11-06）：多搜索栏，每栏可切换模式（名称/地区/分类），多栏 AND 组合。
    // 「＋」追加搜索栏（最多 3 栏 = 三种模式各一），附加栏可用 ✕ 移除。
    // 第九轮（2026-11-08）：地区/分类模式改为点击筛选弹层（确认生效、取消不记录），共用
    // ui/common/PlaceSearchBars 组件（S28/S24 同源）；文本框高度与模式 chip 一致。
    var searchBars by remember {
        mutableStateOf(listOf(com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBar()))
    }

    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    val filtered = remember(places, categories, searchBars) {
        com.visualzyzy.chuxingdazi.ui.common.filterPlacesBySearchBars(
            places = places,
            bars = searchBars,
        )
    }
    // 弹层 B「已有标记的省份」：库里已有的省份（按出现顺序去重）
    val markedProvinces = remember(places) {
        places.mapNotNull { it.province?.takeIf { p -> p.isNotBlank() } }.distinct()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onClose),
                )
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "标记管理", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "共 ${places.size} 个标记 · 删除会同时从清单与行程移除",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(26.dp))
            }
            Text(
                text = "点标记进入编辑页（改名称/分类/图标/清单/到访/删除）。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBarsSection(
                bars = searchBars,
                onBarsChange = { searchBars = it },
                categories = categories,
                markedProvinces = markedProvinces,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(filtered, key = { it.id }) { place ->
                    val category = categoriesById[place.categoryId]
                    Surface(
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditPlace(place.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(parseMarkerColor(category?.color)),
                                contentAlignment = Alignment.Center,
                            ) {
                                MarkerCategoryIcon(category?.iconKey, 20.dp)
                            }
                            Spacer(modifier = Modifier.width(11.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = place.name,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight(600),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (place.visited) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "已到访",
                                            fontSize = 9.5.sp,
                                            color = Primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Primary.copy(alpha = 0.1f))
                                                .padding(horizontal = 5.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                Text(
                                    // R8（第七轮 2026-11-06）：「分类·坐标」→「分类·省份-城市」
                                    // （直辖市省=市去重；region 还没自愈到时回退显示坐标）
                                    text = listOfNotNull(
                                        category?.name ?: "未知分类",
                                        regionLabel(place),
                                    ).joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(18.dp)
                                    .rotate(180f),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        val anyQuery = searchBars.any { it.isActive }
                        Text(
                            text = if (places.isEmpty()) {
                                "还没有标记。回到主地图用「＋」添加。"
                            } else if (anyQuery) {
                                "没有匹配当前搜索条件的标记"
                            } else {
                                "没有标记"
                            },
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

private fun parseMarkerColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748B")) }.getOrDefault(Color(0xFF64748B))

/** R8（第七轮）：省份-城市标签（直辖市省=市时只显示一个；第十六轮：缺失显示「地区未知」不回退坐标）。
 *  遗留 #9（2026-11-18）：去重逻辑统一收敛到 ui/common/RegionLabel.kt 的 regionLabelOf。 */
private fun regionLabel(place: PlaceEntity): String =
    com.visualzyzy.chuxingdazi.ui.common.regionLabelOf(place.province, place.city) ?: "地区未知"

/** 分类矢量图标（与 S09 分类管理同源：ic_cat_<key> drawable，缺失时不渲染）。 */
@Composable
private fun MarkerCategoryIcon(iconKey: String?, size: androidx.compose.ui.unit.Dp) {
    if (iconKey.isNullOrBlank()) return
    val context = LocalContext.current
    val id = remember(iconKey) {
        context.resources.getIdentifier("ic_cat_$iconKey", "drawable", context.packageName)
    }
    if (id != 0) {
        Icon(
            painter = painterResource(id),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    }
}
