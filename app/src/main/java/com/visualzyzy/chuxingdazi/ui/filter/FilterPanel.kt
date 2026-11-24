package com.visualzyzy.chuxingdazi.ui.filter

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.prefs.FilterState
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * S04 筛选面板（§6 S04）：四档双向拖拽（收起/46%/72%/全屏），
 * 面板 <100% 时地图完全可操作（无 scrim）；全屏态追加「当前显示」点位列表。
 */
object FilterPanelSnap {
    const val COLLAPSED = 0f
    const val HALF = 0.46f
    const val TALL = 0.72f
    const val FULL = 1f

    /** 松手吸附规则：向上拖过 92% 吸全屏；其余取最近档位。 */
    fun snapTarget(fraction: Float): Float = when {
        fraction >= 0.92f -> FULL
        else -> listOf(COLLAPSED, HALF, TALL).minByOrNull { abs(it - fraction) } ?: HALF
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterPanel(
    filter: FilterState,
    containerHeightPx: Int,
    visiblePlaces: List<PlaceEntity>,
    categoryInfo: Map<String, com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity>,
    placesEmpty: Boolean,
    provinceRows: List<com.visualzyzy.chuxingdazi.data.db.dao.RegionCountRow> = emptyList(),
    cityRows: List<com.visualzyzy.chuxingdazi.data.db.dao.RegionCityCountRow> = emptyList(),
    unknownRegionCount: Int = 0,
    panelHeightPx: (Int) -> Unit,
    onClose: () -> Unit,
    onPlaceClick: (PlaceEntity) -> Unit,
    onShowMarkersChange: (Boolean) -> Unit,
    onShowMarkersBlocked: () -> Unit,
    onStyleChange: (String) -> Unit,
    onShowLabelsChange: (Boolean) -> Unit,
    onClusterModeChange: (String) -> Unit,
    onUnvisitedOnlyChange: (Boolean) -> Unit,
    onMarkerSizeChange: (Int) -> Unit,
    onToggleCategoryHidden: (String) -> Unit,
    onClearHidden: () -> Unit,
    onHideAll: (List<String>) -> Unit,
    onRegionChange: (province: String?, city: String?) -> Unit = { _, _ -> },
    lists: List<com.visualzyzy.chuxingdazi.data.db.entity.ListEntity> = emptyList(),
    onListFilterChange: (enabled: Boolean, listIds: Set<String>) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fullPx = containerHeightPx.toFloat()
    val halfPx = fullPx * FilterPanelSnap.HALF
    val tallPx = fullPx * FilterPanelSnap.TALL

    // 拖拽中的实时值 + 松手后的吸附动画
    val heightAnim = remember { androidx.compose.animation.core.Animatable(halfPx) }
    var dragging by remember { mutableStateOf(false) }
    val heightPx by heightAnim.asState()
    val heightDp = with(density) { heightPx.toDp() }
    val fraction = if (fullPx > 0) heightPx / fullPx else 0f
    val isFullScreen = fraction >= 0.92f

    // 挂载/容器变化时回到半屏档
    LaunchedEffect(containerHeightPx) {
        if (!dragging) heightAnim.snapTo(halfPx)
    }

    // 高度变化回传（地图 setViewPadding 联动）
    LaunchedEffect(heightPx) {
        panelHeightPx(heightPx.roundToInt())
    }

    fun snap() {
        val target = FilterPanelSnap.snapTarget(heightAnim.value / fullPx.coerceAtLeast(1f)) * fullPx
        scope.launch {
            heightAnim.animateTo(target, tween(220, easing = FastOutSlowInEasing))
            if (target <= 1f) onClose()
        }
    }

    val categoryNames = remember(categoryInfo) { categoryInfo.mapValues { it.value.name } }
    val categoryColors = remember(categoryInfo) { categoryInfo.mapValues { it.value.color } }

    Surface(
        shape = if (isFullScreen) {
            RoundedCornerShape(0.dp)
        } else {
            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        },
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isFullScreen) {
                        Modifier.statusBarsPadding()
                    } else {
                        Modifier
                    },
                ),
        ) {
            // 拖拽条（全屏态 34dp 且位于状态栏下方；其余 22dp）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isFullScreen) Modifier.statusBarsPadding() else Modifier,
                    )
                    .height(if (isFullScreen) 34.dp else 22.dp)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                heightAnim.snapTo((heightAnim.value - delta).coerceIn(0f, fullPx))
                            }
                        },
                        onDragStarted = {
                            dragging = true
                        },
                        onDragStopped = {
                            dragging = false
                            snap()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 4.dp)
                        .background(Color(0xFFD3D9E0), RoundedCornerShape(2.dp)),
                )
            }

            if (heightPx >= 40f) {
                // 标题行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = if (isFullScreen) 2.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            // R5（第七轮 2026-11-06）：「显示点位」→「标记筛选」
                            text = "标记筛选",
                            fontSize = if (isFullScreen) 20.sp else 17.sp,
                            fontWeight = FontWeight(650),
                        )
                        Text(
                            text = if (isFullScreen) {
                                "${visiblePlaces.size} 个标记符合筛选条件 · 向下拖可回到地图"
                            } else {
                                "拖动顶部横条可调整面板高度"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                // P22（2026-11-02）：显示标记总开关 —— 关闭后地图不画任何图标，其下显示类选项置灰
                // P66（2026-11-04）第四轮：没有任何点位时显示标记恒显示为「关」，尝试打开被拦截
                // （不写偏好，回调 onShowMarkersBlocked 让主界面弹空态提示卡）
                val showMarkersDisplayed = filter.showMarkers && !placesEmpty
                val displayEnabled = showMarkersDisplayed
                SwitchRow(
                    label = "显示标记",
                    checked = showMarkersDisplayed,
                    onCheckedChange = { want ->
                        if (want && placesEmpty) {
                            onShowMarkersBlocked()
                        } else {
                            onShowMarkersChange(want)
                        }
                    },
                )
                // 标记样式三分段（§6 S04 内容 1）
                Text(
                    text = "标记样式",
                    fontSize = 12.sp,
                    color = if (displayEnabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf(
                        Triple(FilterState.STYLE_ICON, "图标标记", FilterState.STYLE_ICON),
                        Triple("dot", "圆点", FilterState.STYLE_DOT),
                        Triple("name", "仅名称", FilterState.STYLE_NAME_ONLY),
                    ).forEach { (label, text, styleValue) ->
                        val selected = filter.markerStyle == styleValue
                        // 注意：不用 border(0.dp) 关闭描边——实测会残留 1px 主色 hairline（P66 真机验证）
                        val showStyleBorder = selected && displayEnabled
                        Text(
                            text = text,
                            fontSize = 12.5.sp,
                            color = when {
                                !displayEnabled -> MaterialTheme.colorScheme.outline
                                selected -> Primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    when {
                                        !displayEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        selected -> Primary.copy(alpha = 0.06f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .then(
                                    if (showStyleBorder) {
                                        Modifier.border(
                                            width = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(9.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable(enabled = displayEnabled) { onStyleChange(styleValue) }
                                .padding(vertical = 9.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                // 开关行：显示名称标签（§6 S04 内容 2；P21：仅名称时名称是唯一显示物，强制开且不可改）
                val labelsLocked = displayEnabled && filter.markerStyle == FilterState.STYLE_NAME_ONLY
                SwitchRow(
                    label = "显示名称标签",
                    checked = if (labelsLocked) true else filter.showLabels,
                    enabled = displayEnabled && !labelsLocked,
                    onCheckedChange = onShowLabelsChange,
                )
                // 开关行：重叠点位聚合（三态：自动/开/关）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "重叠点位聚合",
                        fontSize = 14.sp,
                        color = if (displayEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.weight(1f),
                    )
                    val clusterText = when (filter.clusterMode) {
                        FilterState.CLUSTER_ON -> "开"
                        FilterState.CLUSTER_OFF -> "关"
                        else -> "自动"
                    }
                    Text(
                        text = clusterText,
                        fontSize = 12.5.sp,
                        color = if (displayEnabled) Primary else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (displayEnabled) {
                                    Primary.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                            )
                            .clickable(enabled = displayEnabled) {
                                onClusterModeChange(
                                    when (filter.clusterMode) {
                                        FilterState.CLUSTER_AUTO -> FilterState.CLUSTER_ON
                                        FilterState.CLUSTER_ON -> FilterState.CLUSTER_OFF
                                        else -> FilterState.CLUSTER_AUTO
                                    },
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                // 开关行：只看未到访（§6 S04 内容 4）
                // P66（2026-11-04）第四轮：显示标记关闭时不可操作（与按分类筛选一起锁定）
                SwitchRow(
                    label = "只看未到访",
                    checked = filter.unvisitedOnly,
                    enabled = displayEnabled,
                    onCheckedChange = onUnvisitedOnlyChange,
                )
                // 滑块行：标记大小（§6 S04 内容 5，默认 60；P23：拖动不实时刷新，松手才提交）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "标记大小",
                        fontSize = 14.sp,
                        color = if (displayEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                    var sizeDuringDrag by remember(filter.markerSizePercent) {
                        mutableFloatStateOf(filter.markerSizePercent.toFloat())
                    }
                    Slider(
                        value = sizeDuringDrag,
                        onValueChange = { sizeDuringDrag = it },
                        onValueChangeFinished = {
                            onMarkerSizeChange(sizeDuringDrag.roundToInt())
                        },
                        enabled = displayEnabled,
                        valueRange = 30f..100f,
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = if (displayEnabled) Primary else MaterialTheme.colorScheme.outline,
                            activeTrackColor = if (displayEnabled) Primary else MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    Text(
                        text = "${sizeDuringDrag.roundToInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                // 按分类筛选（§6 S04 内容 7-8）
                // P66（2026-11-04）第四轮：显示标记关闭时整段不可操作
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "按分类筛选", fontSize = 14.sp, fontWeight = FontWeight(650), modifier = Modifier.weight(1f))
                    Text(
                        text = "全选",
                        fontSize = 12.sp,
                        color = if (displayEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.clickable(enabled = displayEnabled) { onClearHidden() }.padding(4.dp),
                    )
                    Text(
                        text = "清空",
                        fontSize = 12.sp,
                        color = if (displayEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.clickable(enabled = displayEnabled) { onHideAll(categoryInfo.keys.toList()) }.padding(4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    categoryInfo.forEach { (categoryId, category) ->
                        val name = category.name
                        val hidden = categoryId in filter.hiddenCategoryIds
                        // 不用 border(0.dp) 关闭描边（会残留 hairline，P66）；只在启用且未隐藏时描边
                        val showCatBorder = displayEnabled && !hidden
                        Text(
                            text = name,
                            fontSize = 12.5.sp,
                            color = when {
                                !displayEnabled -> MaterialTheme.colorScheme.outline
                                hidden -> MaterialTheme.colorScheme.outline
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (hidden || !displayEnabled) {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (displayEnabled) 1f else 0.5f)
                                    } else {
                                        Primary.copy(alpha = 0.08f)
                                    },
                                )
                                .then(
                                    if (showCatBorder) {
                                        Modifier.border(
                                            width = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(9.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable(enabled = displayEnabled) { onToggleCategoryHidden(categoryId) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                // 按照地区筛选（R6，第七轮 2026-11-06）：可单独按省，也可省-市两级；默认全国。
                // 数据 = place.province/city 的 DB 聚合（省份表 / 省份-城市记录表）；未知区域的点位
                // 在选中任一地区时不显示（等待自愈回填）。显示标记关闭时与分类筛选一起锁定。
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "按照地区筛选", fontSize = 14.sp, fontWeight = FontWeight(650), modifier = Modifier.weight(1f))
                    if (filter.regionProvince != null) {
                        Text(
                            text = "全国",
                            fontSize = 12.sp,
                            color = if (displayEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clickable(enabled = displayEnabled) { onRegionChange(null, null) }.padding(4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RegionChip(
                        text = "全国",
                        selected = filter.regionProvince == null,
                        enabled = displayEnabled,
                        onClick = { onRegionChange(null, null) },
                    )
                    provinceRows.forEach { row ->
                        RegionChip(
                            text = "${row.name.removeSuffix("省").removeSuffix("市")} ${row.placeCount}",
                            selected = filter.regionProvince == row.name && filter.regionCity == null,
                            enabled = displayEnabled,
                            onClick = { onRegionChange(row.name, null) },
                        )
                    }
                    // 第十六轮：「地区未知」——自愈完成前 region 不全的点位也能被筛出，
                    // 不再像被删掉；数量 >0 才显示
                    if (unknownRegionCount > 0) {
                        RegionChip(
                            text = "地区未知 $unknownRegionCount",
                            selected = filter.regionProvince == FilterState.REGION_UNKNOWN,
                            enabled = displayEnabled,
                            onClick = { onRegionChange(FilterState.REGION_UNKNOWN, null) },
                        )
                    }
                }
                // 选中某省后展开该省的已用城市（省份-城市记录表），可再选市 = 省-市两级筛选
                if (filter.regionProvince != null && filter.regionProvince != FilterState.REGION_UNKNOWN) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RegionChip(
                            text = "全省",
                            selected = filter.regionCity == null,
                            enabled = displayEnabled,
                            onClick = { onRegionChange(filter.regionProvince, null) },
                        )
                        cityRows
                            .filter { it.province == filter.regionProvince }
                            .forEach { row ->
                                RegionChip(
                                    text = "${row.city.removeSuffix("市")} ${row.placeCount}",
                                    selected = filter.regionCity == row.city,
                                    enabled = displayEnabled,
                                    onClick = { onRegionChange(filter.regionProvince, row.city) },
                                )
                            }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // 按清单筛选（第十三轮 2026-11-12；2026-11-24 单选改多选）：开关开启后，
                // 点位筛选链变为 所有点位→分类→地区→清单；圆角方框复用地区筛选的
                // RegionChip 样式。多选语义 = 并集（同一点位可同时在多个清单，任一命中
                // 即显示）；「全部」与具体清单互斥（选中任一具体清单 = 非空集）。
                // 开关关闭 = 不启用该维度（不显示方框、筛选不参与，选区保留）。
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                SwitchRow(
                    label = "按清单筛选",
                    checked = filter.listFilterEnabled,
                    onCheckedChange = { enabled ->
                        onListFilterChange(enabled, filter.listIds)
                    },
                )
                if (filter.listFilterEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RegionChip(
                            text = "全部",
                            selected = filter.listIds.isEmpty(),
                            enabled = displayEnabled,
                            onClick = { onListFilterChange(true, emptySet()) },
                        )
                        lists.forEach { list ->
                            RegionChip(
                                text = list.name,
                                selected = list.id in filter.listIds,
                                enabled = displayEnabled,
                                onClick = {
                                    // toggle 语义：已选中则移除，未选中则加入
                                    val next = if (list.id in filter.listIds) {
                                        filter.listIds - list.id
                                    } else {
                                        filter.listIds + list.id
                                    }
                                    onListFilterChange(true, next)
                                },
                            )
                        }
                    }
                    if (lists.isEmpty()) {
                        Text(
                            text = "还没有清单，可在「清单管理」里创建",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // 全屏态追加「当前显示」点位列表（§6 S04 全屏布局）
                if (isFullScreen) {
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // R5（第七轮 2026-11-06）：「点位」→「标记」
                            text = "当前显示 · ${visiblePlaces.size} 个标记",
                            fontSize = 13.sp,
                            fontWeight = FontWeight(650),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "按分类排序",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    visiblePlaces.forEach { place ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlaceClick(place) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(
                                        parseCategoryColorSafe(categoryColors[place.categoryId]),
                                        RoundedCornerShape(9.dp),
                                    ),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = place.name,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight(600),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!place.address.isNullOrBlank()) {
                                    Text(
                                        text = place.address!!,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.weight(1f),
        )
        // P49（2026-11-03）问题3：开关整体缩小一号（视觉 0.8 倍；布局占位与点击热区不变，易点不误触）
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Primary,
                uncheckedTrackColor = if (enabled) {
                    SwitchDefaults.colors().uncheckedTrackColor
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            modifier = Modifier
                .graphicsLayer {
                    scaleX = 0.8f
                    scaleY = 0.8f
                },
        )
    }
}

private fun parseCategoryColorSafe(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748B")) }.getOrDefault(Color(0xFF64748B))

/** 地区筛选胶囊（第七轮 R6）：样式与分类胶囊一致。 */
@Composable
private fun RegionChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        color = when {
            !enabled -> MaterialTheme.colorScheme.outline
            selected -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    selected -> Primary.copy(alpha = 0.08f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .then(
                if (selected && enabled) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(9.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
