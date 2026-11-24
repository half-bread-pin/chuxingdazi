package com.visualzyzy.chuxingdazi.ui.place

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithTripRow
import com.visualzyzy.chuxingdazi.data.model.BusinessHoursCodec
import com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec
import com.visualzyzy.chuxingdazi.data.model.FieldType
import com.visualzyzy.chuxingdazi.data.model.FieldValuesCodec
import com.visualzyzy.chuxingdazi.ui.trip.TripTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * S07 点位详情（§6 S07）。2026-11-24 实装「已安排」区块：只显示未出行的段、
 * 日期口径「月/日」、行点击进对应行程时间轴（最多 3 行）。
 * 地图 + 底部卡片；卡片向下拖关闭；长按卡片出快捷菜单；删除确认文案按影响计算。
 */
@Composable
fun PlaceDetailCard(
    place: PlaceEntity,
    category: CategoryEntity?,
    lists: List<ListEntity>,
    impactProvider: suspend (placeId: String) -> com.visualzyzy.chuxingdazi.data.repo.DeleteImpact?,
    // S07「已安排」区块（2026-11-24 实装；过滤与排序见 filterUpcomingSchedules）
    schedules: List<ScheduleWithTripRow> = emptyList(),
    onOpenTrip: (String) -> Unit = {},
    cardHeightPx: (Int) -> Unit,
    onDismiss: () -> Unit,
    onToggleVisited: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onNavigateTo: () -> Unit = {},
    // 第十六轮：叠点散开组内的「1/2」切换（groupCount<=1 时整块不显示）
    groupIndex: Int = -1,
    groupCount: Int = 0,
    onGroupMove: (delta: Int) -> Unit = {},
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var dragY by remember { mutableStateOf(0f) }

    // 返回键守卫（第十六轮，用户定调）：S07 卡片开着按返回 = 关卡片（不再直接退应用）；
    // ⋮ 菜单/删除确认开着时先关弹层（第十五轮 P141 同语义）。
    BackHandler(enabled = true) {
        when {
            showMenu -> showMenu = false
            showDeleteConfirm -> showDeleteConfirm = false
            else -> onDismiss()
        }
    }

    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragY }
            .onGloballyPositioned { cardHeightPx(it.size.height) }
            .pointerInput(place.id) {
                detectTapGestures(onLongPress = { showMenu = true })
            }
            .pointerInput(place.id) {
                detectDragGestures(
                    onDragEnd = {
                        if (dragY > 120f) onDismiss()
                        dragY = 0f
                    },
                ) { change, dragAmount ->
                    change.consume()
                    if (dragAmount.y > 0) dragY += dragAmount.y
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 拖拽条
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .background(Color(0xFFD3D9E0), RoundedCornerShape(2.dp))
                    .align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(10.dp))

            // 标题行：46dp 分类色块图标 + 名称 + 关闭（§6 S07 布局 2）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            parseComposeColor(category?.color),
                            RoundedCornerShape(13.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CategoryIcon(iconKey = category?.iconKey ?: "other_point", size = 26.dp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = place.name,
                    fontSize = 19.sp,
                    fontWeight = FontWeight(680),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { showMenu = true },
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onDismiss),
                )
            }
            // 第十六轮：散开组内左右切换（‹ 1/2 ›）——同组几乎重合的点位不用关卡片再点
            if (groupCount > 1 && groupIndex >= 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "‹",
                        fontSize = 18.sp,
                        color = if (groupCount > 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .clickable { onGroupMove(-1) }
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                    Text(
                        text = "${groupIndex + 1}/${groupCount}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        text = "›",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onGroupMove(1) }
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            // 标签行：分类 + 清单 + 已到访（可点就地切换，§6 S07）
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagChip(text = category?.name ?: "其他", color = parseComposeColor(category?.color))
                lists.forEach { list ->
                    TagChip(text = list.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (place.visited) {
                    // 第十六轮：带上到访日期（visited_at），回答「哪次去的」
                    TagChip(
                        text = place.visitedAt?.let {
                            "已到访 · " + SimpleDateFormat("M/d", Locale.CHINA).format(Date(it))
                        } ?: "已到访",
                        color = Color(0xFF2E9E6B),
                        filled = true,
                        onClick = onToggleVisited,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            // 地址行 + 复制（第十六轮：坐标不再作为主信息展示——BD09 复制到别的地图会偏；
            // 无地址时显示「省-市」区域，仍没有则「暂无地址」）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = place.address ?: regionText(place),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "复制",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        copyToClipboard(context, place.address ?: regionText(place))
                        Toast.makeText(context, "已复制地址", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            // 字段卡网格（§6 S07 边界 + 字段模板升级 §3.2）：分类模板字段（有值）→ 分隔线 →
            // 点位自定义字段（有值，带「自定义」小标题）；全部为空整块不显示
            val templateRows = buildFieldRows(place, category, customOnly = false)
            val customRows = buildFieldRows(place, category, customOnly = true)
            if (templateRows.isNotEmpty()) {
                FieldRowsGrid(templateRows)
                if (customRows.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (customRows.isNotEmpty()) {
                Text(
                    text = "自定义",
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                FieldRowsGrid(customRows)
            }
            if (templateRows.isNotEmpty() || customRows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
            }

            // S07「已安排」区块（2026-11-24 实装，§6 S07 布局 6/7 减法版）：只显示未出行的段；
            // 过滤后为空整块不显示；行点击进对应行程时间轴；最多 3 行 + 尾行计数
            val upcomingSchedules = remember(schedules) {
                val now = java.time.LocalTime.now()
                filterUpcomingSchedules(
                    schedules,
                    java.time.LocalDate.now(),
                    now.hour * 60 + now.minute,
                )
            }
            if (upcomingSchedules.isNotEmpty()) {
                Text(
                    text = "已安排 · ${upcomingSchedules.size} 段",
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    upcomingSchedules.take(3).forEach { row ->
                        val spanText = scheduleSpanText(row)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onOpenTrip(row.schedule.tripId) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (spanText != null) {
                                    Text(
                                        text = spanText,
                                        fontSize = 12.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = row.tripName,
                                    fontSize = 10.5.sp,
                                    lineHeight = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // 尾部 › 提示可点（进对应行程时间轴）
                            Text(
                                text = "›",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (upcomingSchedules.size > 3) {
                        Text(
                            text = "…还有 ${upcomingSchedules.size - 3} 段",
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 备注
            if (!place.note.isNullOrBlank()) {
                Text(
                    text = place.note!!,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 底部操作：导航前往 / 编辑（2026-11-24：删未实装占位「加入路线」按钮）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                        .clickable(onClick = onNavigateTo),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Navigation,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "导航前往",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(
                        modifier = Modifier.clickable(onClick = onEdit),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    // 长按卡片快捷菜单（§6 S07；第十六轮精简：删「复制地址」——与卡片上的「复制」重复，
    // 只留 已到访 / 删除 两项）
    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text(text = place.name, fontSize = 16.sp) },
            text = {
                Column {
                    MenuItem(if (place.visited) "取消已到访" else "标记为已到访") {
                        onToggleVisited()
                        showMenu = false
                    }
                    MenuItem("删除点位", danger = true) {
                        showMenu = false
                        showDeleteConfirm = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }

    // 删除确认：先算清影响再问（§6 S07：这条最容易漏）
    // 第十六轮：影响没算完时「删除」不可点——避免先闪现「只从地图和清单消失」被误读
    if (showDeleteConfirm) {
        var impactLoaded by remember { mutableStateOf(false) }
        var impactText by remember { mutableStateOf("正在统计影响…") }
        LaunchedEffect(place.id) {
            val impact = impactProvider(place.id)
            impactText = if (impact != null && impact.tripCount > 0) {
                "它在 ${impact.tripCount} 个行程里还有 ${impact.scheduleCount} 段安排，会一起删掉。" +
                    "行程本身和其他点位不受影响。"
            } else {
                "它会从地图和所有清单里消失。"
            }
            impactLoaded = true
        }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = "删除「${place.name}」？") },
            text = { Text(text = impactText, fontSize = 13.sp) },
            confirmButton = {
                Text(
                    text = "删除",
                    color = if (impactLoaded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(enabled = impactLoaded) {
                            showDeleteConfirm = false
                            onDelete()
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "取消",
                    modifier = Modifier
                        .clickable { showDeleteConfirm = false }
                        .padding(8.dp),
                )
            },
        )
    }
}

/** 第十六轮：无地址时的兜底文案——「省-市」区域；都没有则「暂无地址」（不再露 BD09 坐标）。
 *  遗留 #9（2026-11-18）：regionLabelOf 去重直辖市「北京市-北京市」。 */
private fun regionText(place: PlaceEntity): String =
    com.visualzyzy.chuxingdazi.ui.common.regionLabelOf(place.province, place.city) ?: "暂无地址"

/** S07「已安排」未出行过滤（2026-11-24 用户口径：只显示未出行的安排，纯函数便于单测）。
 *  把「日期 + 天内分钟」折算成同一把尺子（自纪元起的总分钟数）再比大小；
 *  endMin-1 是该段占用的最后一分钟（§5.3 跨天约定同款）——结束时刻还没过去就保留：
 *  正在进行的段保留、当天早上已结束的段剔除、纯历史行程的段整批剔除。
 *  planDate 解析失败 → 该行保留（fail-open，别让脏数据整块消失）。 */
internal fun filterUpcomingSchedules(
    rows: List<ScheduleWithTripRow>,
    today: java.time.LocalDate,
    nowMin: Int,
): List<ScheduleWithTripRow> {
    val absNow = today.toEpochDay() * 1440 + nowMin
    return rows.filter { row ->
        val planDate = runCatching { java.time.LocalDate.parse(row.schedule.planDate) }.getOrNull()
            ?: return@filter true
        val absEnd = planDate.toEpochDay() * 1440 + (row.schedule.endMin - 1)
        absEnd >= absNow
    }
}

/** S07 已安排行的时间文案（与 S14 已安排卡片同口径，复用 TripTime）：
 *  `M/d HH:mm–结束`；跨一天「次日 HH:mm」、跨两天以上「M/d HH:mm」（结束日+时刻）。
 *  planDate 解析失败 → null（行内只显行程名）。 */
internal fun scheduleSpanText(row: ScheduleWithTripRow): String? {
    val planDate = runCatching { java.time.LocalDate.parse(row.schedule.planDate) }.getOrNull()
        ?: return null
    val endMin = row.schedule.endMin
    val endText = when (val span = row.schedule.spannedDays) {
        0 -> TripTime.minutesText(endMin)
        1 -> "次日 " + TripTime.minutesText(endMin % 1440)
        else -> TripTime.dateText(planDate.plusDays(span.toLong())) + " " + TripTime.minutesText(endMin % 1440)
    }
    return "${TripTime.dateText(planDate)} ${TripTime.minutesText(row.schedule.startMin)}–$endText"
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("address", text))
}

private fun parseComposeColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748B")) }.getOrDefault(Color(0xFF64748B))

/** 字段卡数据行（badge = BUSINESS_HOURS 的营业中/已打烊徽标文本）。 */
private data class FieldRowData(val label: String, val text: String, val badge: String? = null)

/** 字段卡两列网格（模板字段与自定义字段共用同一渲染）。 */
@Composable
private fun FieldRowsGrid(rows: List<FieldRowData>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { row ->
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(
                                text = row.label,
                                fontSize = 10.5.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = row.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (row.badge != null) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = row.badge,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (row.badge == "营业中") {
                                        Color(0xFF16A34A)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** S07 字段卡数据：field_schema × field_values，只出有值的（§5.4 + §6 S07 边界）。
 *  customOnly=true 时数据源换 place.custom_schema（字段模板升级 §3.2）。 */
private fun buildFieldRows(place: PlaceEntity, category: CategoryEntity?, customOnly: Boolean): List<FieldRowData> {
    val schema = if (customOnly) {
        FieldSchemaCodec.decode(place.customSchema)
    } else {
        category?.let { FieldSchemaCodec.decode(it.fieldSchema) } ?: return emptyList()
    }
    val values = FieldValuesCodec.decode(place.fieldValues)
    val todayIdx = java.time.LocalDate.now().dayOfWeek.value % 7 // 本编码约定 0=周日..6=周六
    val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    return schema.mapNotNull { def ->
        val raw = values[def.key] ?: return@mapNotNull null
        val text = formatFieldValue(def.type, raw) ?: return@mapNotNull null
        // §4.3 营业徽标（2026-11-21）：BUSINESS_HOURS 有值行按当前星期+时刻判定
        val badge = if (def.type == FieldType.BUSINESS_HOURS) {
            val groups = BusinessHoursCodec.decode(rawFieldValueText(raw))
            when {
                groups.isEmpty() -> null
                BusinessHoursCodec.isOperatingNow(groups, todayIdx, nowMin) -> "营业中"
                else -> "已打烊"
            }
        } else {
            null
        }
        FieldRowData(def.label, text, badge)
    }
}

/** 值解码文本：JsonArray 等 → toString()（合法 JSON）；JsonPrimitive → content（无引号，旧字符串值容错）。 */
private fun rawFieldValueText(value: JsonElement): String =
    (value as? JsonPrimitive)?.content ?: value.toString()

private fun formatFieldValue(type: FieldType, value: JsonElement): String? {
    if (value is JsonNull) return null
    // BUSINESS_HOURS 的值是 JsonArray，必须先于 JsonPrimitive 转换处理（数组不是 primitive）
    if (type == FieldType.BUSINESS_HOURS) {
        return BusinessHoursCodec.summarize(BusinessHoursCodec.decode(rawFieldValueText(value)))
    }
    val primitive = value as? JsonPrimitive ?: return null
    return when (type) {
        FieldType.MONEY -> primitive.doubleOrNull?.let { "¥%.2f".format(it) }
        FieldType.NUMBER -> primitive.doubleOrNull?.toString() ?: primitive.content
        FieldType.DATETIME -> primitive.longOrNull?.let {
            // 2026-11-21 定调：展示加年份（跨年行程可辨）；`2026/10/31 周六 09:30`
            SimpleDateFormat("yyyy/MM/dd E HH:mm", Locale.CHINA).format(Date(it))
        }
        FieldType.TIME_RANGE -> primitive.content.replace("-", "–")
        else -> primitive.content.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun CategoryIcon(iconKey: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val id = remember(iconKey) {
        context.resources.getIdentifier("ic_cat_$iconKey", "drawable", context.packageName)
    }
    if (id != 0) {
        Image(
            painter = painterResource(id),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun TagChip(
    text: String,
    color: Color,
    filled: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val bg = if (filled) color else color.copy(alpha = 0.1f)
    val fg = if (filled) Color.White else color
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun MenuItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
