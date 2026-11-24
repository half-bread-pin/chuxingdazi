@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.visualzyzy.chuxingdazi.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.model.ChinaRegions
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import com.visualzyzy.chuxingdazi.ui.theme.PrimaryBright

/**
 * 共享的「点位多模式搜索」组件（第九轮，2026-11-08）。
 * S29 标记管理 / S28 清单详情（含添加浮层）/ S24 新建行程选点浮层三处共用同一套交互：
 * 每栏 = 模式 chip（名称 → 地区 → 分类循环切换）+ 该模式对应的输入区；
 * 「＋」追加搜索栏（最多 3 栏），附加栏 ✕ 移除；多栏 AND 组合过滤。
 * 名称 = 文本框（名称/地址）；地区 / 分类 = 点击筛选的弹层（确认才生效，取消不记录）。
 */

enum class PlaceSearchMode { NAME, REGION, CATEGORY }

/**
 * 一条搜索栏的状态。
 * 地区条目格式："省" 或 "省|市"（竖线分隔，展示为「省-市」；省市名不含竖线）。
 */
data class PlaceSearchBar(
    val mode: PlaceSearchMode = PlaceSearchMode.NAME,
    val query: String = "",
    val categoryIds: Set<String> = emptySet(),
    val regions: List<String> = emptyList(),
) {
    val isActive: Boolean
        get() = when (mode) {
            PlaceSearchMode.NAME -> query.isNotBlank()
            PlaceSearchMode.REGION -> regions.isNotEmpty()
            PlaceSearchMode.CATEGORY -> categoryIds.isNotEmpty()
        }
}

/** 地区条目的展示文案（"北京市|保定市" → "北京市-保定市"）。 */
fun regionEntryLabel(entry: String): String = entry.replace("|", "-")

/** 点位是否命中一条地区条目（省条目=全省；市条目=省+市，直辖市 place.city 等于省名也算命中）。 */
fun regionEntryMatches(place: PlaceEntity, entry: String): Boolean {
    val parts = entry.split("|")
    val prov = parts.getOrNull(0) ?: return false
    val city = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
    val pp = place.province ?: return false
    return if (city == null) {
        pp == prov
    } else {
        val pc = place.city ?: return false
        pp == prov && (pc == city || pc == "${city}市" || pc == prov)
    }
}

/** 单栏是否命中一个点位（空条件 = 恒真）。 */
fun placeMatchesSearchBar(place: PlaceEntity, bar: PlaceSearchBar): Boolean = when (bar.mode) {
    PlaceSearchMode.NAME -> {
        val q = bar.query.trim()
        q.isEmpty() ||
            place.name.contains(q, ignoreCase = true) ||
            (place.address ?: "").contains(q, ignoreCase = true)
    }
    PlaceSearchMode.REGION -> bar.regions.isEmpty() || bar.regions.any { regionEntryMatches(place, it) }
    PlaceSearchMode.CATEGORY -> bar.categoryIds.isEmpty() || place.categoryId in bar.categoryIds
}

/** 多栏 AND 组合过滤（全部为空条件时原样返回）。 */
fun filterPlacesBySearchBars(
    places: List<PlaceEntity>,
    bars: List<PlaceSearchBar>,
): List<PlaceEntity> {
    val active = bars.filter { it.isActive }
    if (active.isEmpty()) return places
    return places.filter { place -> active.all { bar -> placeMatchesSearchBar(place, bar) } }
}

// ---------- 界面组件 ----------

private val BAR_FIELD_HEIGHT = 36.dp

/** 界面用：判断「当前实际生效」的配色是否暗色——P140 的暗色专属改色靠它，明亮模式保持原样。
 *  App 配色 = 设置页「软件显示配色」三选一（跟随系统/明亮/暗色），用户选「跟随系统」时生效主题随系统切换：
 *  所以既不能写死、也不能用 isSystemInDarkTheme()（它只看系统主题、不认用户设置），
 *  而是直接问当前 MaterialTheme——surface 是深色（亮度 < 0.5）即暗色，三种设置下判定都正确。 */
@Composable
private fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** 模式 chip + 对应输入区的整行（模式切换 chip 复用 S29 原样式）。 */
@Composable
private fun SearchBarRow(
    bar: PlaceSearchBar,
    categories: List<CategoryEntity>,
    markedProvinces: List<String>,
    onBarChange: (PlaceSearchBar) -> Unit,
    trailing: @Composable () -> Unit,
) {
    val dark = isDarkScheme()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when (bar.mode) {
                PlaceSearchMode.NAME -> "名称"
                PlaceSearchMode.REGION -> "地区"
                PlaceSearchMode.CATEGORY -> "分类"
            },
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            // 第十轮：暗色下深青 #0E7490 在黑底上看不清 → 白字 + 略加深的青底；明亮模式原样
            color = if (dark) Color.White else Primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (dark) PrimaryBright.copy(alpha = 0.18f) else Primary.copy(alpha = 0.08f))
                .clickable {
                    onBarChange(
                        when (bar.mode) {
                            PlaceSearchMode.NAME -> bar.copy(mode = PlaceSearchMode.REGION)
                            PlaceSearchMode.REGION -> bar.copy(mode = PlaceSearchMode.CATEGORY)
                            PlaceSearchMode.CATEGORY -> bar.copy(mode = PlaceSearchMode.NAME)
                        },
                    )
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        when (bar.mode) {
            PlaceSearchMode.NAME -> {
                CompactSearchField(
                    value = bar.query,
                    onValueChange = { onBarChange(bar.copy(query = it)) },
                    placeholder = "按名称或地址搜索",
                    modifier = Modifier.weight(1f),
                )
            }
            PlaceSearchMode.CATEGORY -> {
                var showSheet by remember { mutableStateOf(false) }
                Column(modifier = Modifier.weight(1f)) {
                    if (bar.categoryIds.isEmpty()) {
                        FilterEntryButton(text = "点此筛选分类") { showSheet = true }
                    } else {
                        // 第九轮：确认后按钮位置变上下两部分——上面选中分类小方块，下面「修改」
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            categories.filter { it.id in bar.categoryIds }.forEach { c ->
                                Text(
                                    text = c.name,
                                    fontSize = 10.5.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(parseSharedColor(c.color))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "修改",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .border(1.dp, Primary.copy(alpha = 0.5f), RoundedCornerShape(7.dp))
                                .clickable { showSheet = true }
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
                if (showSheet) {
                    CategoryFilterSheet(
                        categories = categories,
                        initialSelected = bar.categoryIds,
                        onDismiss = { showSheet = false },
                        onConfirm = { ids ->
                            onBarChange(bar.copy(categoryIds = ids))
                            showSheet = false
                        },
                    )
                }
            }
            PlaceSearchMode.REGION -> {
                var showSheet by remember { mutableStateOf(false) }
                Column(modifier = Modifier.weight(1f)) {
                    if (bar.regions.isEmpty()) {
                        FilterEntryButton(text = "点此筛选地区") { showSheet = true }
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            bar.regions.forEach { r ->
                                Text(
                                    text = regionEntryLabel(r),
                                    fontSize = 10.5.sp,
                                    // 第十轮：暗色下改实底主色 + 白字（与分类小方块同构、醒目），明亮模式原样
                                    color = if (dark) Color.White else Primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(if (dark) Primary else Primary.copy(alpha = 0.08f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "修改",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .border(1.dp, Primary.copy(alpha = 0.5f), RoundedCornerShape(7.dp))
                                .clickable { showSheet = true }
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
                if (showSheet) {
                    RegionFilterSheet(
                        initialSelected = bar.regions,
                        markedProvinces = markedProvinces,
                        onDismiss = { showSheet = false },
                        onConfirm = { regions ->
                            onBarChange(bar.copy(regions = regions))
                            showSheet = false
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        trailing()
    }
}

/** S29/S28/S24 共用的搜索栏区（多栏 + ＋ 添加 + ✕ 移除）。 */
@Composable
fun PlaceSearchBarsSection(
    bars: List<PlaceSearchBar>,
    onBarsChange: (List<PlaceSearchBar>) -> Unit,
    categories: List<CategoryEntity>,
    markedProvinces: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        bars.forEachIndexed { index, bar ->
            SearchBarRow(
                bar = bar,
                categories = categories,
                markedProvinces = markedProvinces,
                onBarChange = { nb -> onBarsChange(bars.mapIndexed { i, b -> if (i == index) nb else b }) },
                trailing = {
                    if (index > 0) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "移除该搜索栏",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onBarsChange(bars.filterIndexed { i, _ -> i != index }) },
                        )
                    } else if (bars.size < 3) {
                        Text(
                            text = "＋",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val used = bars.map { it.mode }.toSet()
                                    val nextMode = when {
                                        PlaceSearchMode.REGION !in used -> PlaceSearchMode.REGION
                                        PlaceSearchMode.CATEGORY !in used -> PlaceSearchMode.CATEGORY
                                        else -> PlaceSearchMode.NAME
                                    }
                                    onBarsChange(bars + PlaceSearchBar(mode = nextMode))
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 紧凑文本输入框（36dp 高，与左侧模式 chip 同高——第九轮：原 OutlinedTextField 太高）。 */
@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = BAR_FIELD_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(Primary),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text(text = placeholder, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}

/** 「点此筛选分类 / 地区」入口按钮（占满搜索位，与文本框同高）。 */
@Composable
private fun FilterEntryButton(text: String, onClick: () -> Unit) {
    val dark = isDarkScheme()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BAR_FIELD_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, if (dark) PrimaryBright.copy(alpha = 0.55f) else Primary.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, fontSize = 12.5.sp, color = if (dark) Color.White else Primary)
    }
}

private fun parseSharedColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748B")) }.getOrDefault(Color(0xFF64748B))

/** 通用底部弹层骨架（独立 Dialog 窗口 + scrim + 底部面板；点空白 / 系统返回 = onDismiss）。
 *  第九轮：必须用 Dialog（独立窗口）——作为搜索行 Row 的子节点会被 weight 列把宽度挤成 0
 *  （真机实测 B 弹层 Box 0x2037），Dialog 全屏窗口彻底避开嵌套布局约束。 */
@Composable
private fun BottomSheetShell(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x52000000))
                    .clickable(onClick = onDismiss),
            )
            Surface(
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            modifier = Modifier
                                .size(22.dp)
                                .clickable(onClick = onDismiss),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    content()
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

/** 勾选圆圈（弹层列表行用）。 */
@Composable
private fun CheckCircle(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(if (checked) Primary else Color.Transparent, CircleShape)
            .border(1.5.dp, if (checked) Primary else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(text = "✓", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- 分类筛选弹层（S29 分类模式） ----------

@Composable
private fun CategoryFilterSheet(
    categories: List<CategoryEntity>,
    initialSelected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val selected = remember { mutableStateListOf<String>().apply { addAll(initialSelected) } }
    BottomSheetShell(title = "筛选分类", onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "全选",
                fontSize = 12.sp,
                color = Primary,
                modifier = Modifier
                    .clickable { selected.clear(); selected.addAll(categories.map { it.id }) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                text = "清空",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { selected.clear() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            categories.forEach { c ->
                val checked = c.id in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (checked) selected.remove(c.id) else selected.add(c.id) }
                        .padding(vertical = 10.dp),
                ) {
                    CheckCircle(checked)
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(parseSharedColor(c.color)),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = c.name, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        SheetConfirmButton(text = "确认") { onConfirm(selected.toSet()) }
    }
}

// ---------- 地区筛选弹层 A（S29 地区模式） ----------

/** 搜索省份/城市 → 候选条目（省条目 "省"，市条目 "省|市"）。 */
private fun searchRegionEntries(query: String, limit: Int = 30): List<String> {
    val q = query.trim()
    val out = mutableListOf<String>()
    if (q.isEmpty()) return out
    for ((prov, cities) in ChinaRegions.provinces) {
        if (prov.contains(q) && prov !in out) out.add(prov)
        for (city in cities) {
            if (city.contains(q)) {
                val entry = if (city == prov || "${city}市" == prov) prov else "$prov|$city"
                if (entry !in out) out.add(entry)
            }
        }
        if (out.size >= limit) break
    }
    return out.take(limit)
}

@Composable
private fun RegionFilterSheet(
    initialSelected: List<String>,
    markedProvinces: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val selected = remember { mutableStateListOf<String>().apply { addAll(initialSelected) } }
    var expandedProv by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }

    BottomSheetShell(title = "筛选地区", onDismiss = onDismiss) {
        // 第十轮（2026-11-08）：文字搜索框删除——本弹层只做已选地区管理（省条目展开选城市 / × 删除）；
        // 搜索、点选加入统一走「＋」→ 弹层 B（与原搜索框相同的搜索与勾选逻辑）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "＋",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Primary)
                    .clickable { showAddSheet = true }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (selected.isEmpty()) "还没有选择地区（点 ＋ 搜索添加）" else "已选 ${selected.size} 个（点省份名可展开选城市，× 删除）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            selected.forEach { entry ->
                val prov = entry.split("|").first()
                val isProvinceEntry = "|" !in entry
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = regionEntryLabel(entry),
                            fontSize = 12.5.sp,
                            color = if (isProvinceEntry) Primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isProvinceEntry) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(enabled = isProvinceEntry) { expandedProv = if (expandedProv == prov) null else prov }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        if (isProvinceEntry) {
                            Text(
                                text = if (expandedProv == prov) "收起" else "选城市 ›",
                                fontSize = 10.5.sp,
                                color = Primary,
                                modifier = Modifier.clickable {
                                    expandedProv = if (expandedProv == prov) null else prov
                                },
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "删除该地区",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    selected.remove(entry)
                                    if (expandedProv == prov && selected.none { it.startsWith("$prov|") }) expandedProv = null
                                },
                        )
                    }
                    // 展开该省城市：可在此基础上选中一个或多个城市（"省|市" 条目）
                    if (isProvinceEntry && expandedProv == prov) {
                        val cities = ChinaRegions.provinces.firstOrNull { it.first == prov }?.second.orEmpty()
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                        ) {
                            cities.forEach { city ->
                                val cityEntry = "$prov|$city"
                                val picked = cityEntry in selected
                                Text(
                                    text = city,
                                    fontSize = 11.sp,
                                    color = if (picked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(if (picked) Primary else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { if (picked) selected.remove(cityEntry) else selected.add(cityEntry) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        SheetConfirmButton(text = "确认") { onConfirm(selected.toList()) }
    }

    // 弹层 B：从「已有标记的省份」快捷添加（可只选省，也可再选省内具体城市）
    if (showAddSheet) {
        RegionAddSheet(
            markedProvinces = markedProvinces,
            onDismiss = { showAddSheet = false },
            onConfirm = { entries ->
                entries.forEach { if (it !in selected) selected.add(it) }
                showAddSheet = false
            },
        )
    }
}

// ---------- 地区筛选弹层 B（弹层 A 的「＋」） ----------

/** 第十轮（2026-11-08）重做：搜索框改成与原弹层 A 相同的搜索与操作逻辑——
 *  输入「省份或城市」→ 出现「省（省）/ 省-市（市）」候选条目 → 点选即勾选加入；
 *  「已有标记的省份」快捷区同步改勾选制（点省 = 勾选省条目，「选城市 ›」展开省内城市多选）。
 *  确认 = 把所有勾选条目回传弹层 A 的已选区。 */
@Composable
private fun RegionAddSheet(
    markedProvinces: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var expandedProv by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onDismiss)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(onClick = onDismiss),
            )
            Surface(
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            modifier = Modifier
                                .size(22.dp)
                                .clickable(onClick = onDismiss),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        CompactSearchField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "搜索省份或城市，点选加入",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 已有标记的省份（快捷勾选；点省 = 勾选省条目，「选城市 ›」展开省内城市多选）
                        if (markedProvinces.isNotEmpty()) {
                            Text(
                                text = "已有标记的省份",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        markedProvinces.forEach { prov ->
                            val provPicked = prov in picked
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = prov,
                                    fontSize = 13.sp,
                                    color = if (provPicked) Color.White else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (provPicked) Primary else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { if (provPicked) picked.remove(prov) else picked.add(prov) }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (expandedProv == prov) "收起 ∧" else "选城市 ›",
                                    fontSize = 10.5.sp,
                                    color = Primary,
                                    modifier = Modifier.clickable {
                                        expandedProv = if (expandedProv == prov) null else prov
                                    },
                                )
                            }
                            if (expandedProv == prov) {
                                val cities = ChinaRegions.provinces.firstOrNull { it.first == prov }?.second.orEmpty()
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp),
                                ) {
                                    cities.forEach { city ->
                                        val cityEntry = "$prov|$city"
                                        val cityPicked = cityEntry in picked
                                        Text(
                                            text = city,
                                            fontSize = 11.sp,
                                            color = if (cityPicked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(if (cityPicked) Primary else MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable { if (cityPicked) picked.remove(cityEntry) else picked.add(cityEntry) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // 搜索结果：与原弹层 A 搜索框相同的逻辑——全国省份或城市条目，点选加入（勾选）
                        if (query.isNotBlank()) {
                            val hits = searchRegionEntries(query)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "搜索结果",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (hits.isEmpty()) {
                                Text(
                                    text = "没有匹配的省份或城市",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            hits.forEach { entry ->
                                val checked = entry in picked
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { if (checked) picked.remove(entry) else picked.add(entry) }
                                        .padding(vertical = 8.dp),
                                ) {
                                    CheckCircle(checked)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = regionEntryLabel(entry) + if ("|" in entry) "（市）" else "（省）",
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = if (picked.isEmpty()) "勾选省份或城市后点「确认」加入筛选" else "已勾选 ${picked.size} 个，点「确认」加入筛选",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SheetConfirmButton(
                        text = "确认",
                        enabled = picked.isNotEmpty(),
                    ) {
                        onConfirm(picked.toList())
                    }
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

/** 弹层底部的通栏确认按钮。 */
@Composable
private fun SheetConfirmButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (enabled) Primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}
