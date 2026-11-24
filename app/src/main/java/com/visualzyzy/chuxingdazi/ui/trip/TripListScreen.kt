package com.visualzyzy.chuxingdazi.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.data.db.entity.TripEntity
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import java.time.LocalDate
import kotlinx.coroutines.launch

/** S23 行程列表（§6 S23）：进行中 → 未来 → 未定日期 → 已结束。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TripListScreen(
    onClose: () -> Unit,
    onOpenTrip: (tripId: String) -> Unit,
    onOpenTripMap: (tripId: String) -> Unit = {},
    onNewTrip: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: TripListViewModel = hiltViewModel()
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val scheduleCounts by viewModel.scheduleCountByTrip.collectAsStateWithLifecycle()
    val placeCounts by viewModel.placeCountByTrip.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var menuTrip by remember { mutableStateOf<TripEntity?>(null) }
    var showRename by remember { mutableStateOf<TripEntity?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<TripEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }

    // 返回键守卫：长按菜单/改名/删除确认开着时先关弹层，再放行路由返回（第十五轮）
    androidx.activity.compose.BackHandler(
        enabled = menuTrip != null || showRename != null || showDeleteConfirm != null,
    ) {
        menuTrip = null
        showRename = null
        showDeleteConfirm = null
    }

    val today = TripTime.today()

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
                Text(
                    text = "行程",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(26.dp))
            }
            Text(
                text = "我的行程", // P24（2026-11-02）：原「每条行程是一条独立的时间轴，点位各排各的」
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (trips.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 120.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "📅", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "还没有行程", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "建一条行程，把点位排到具体的时间上",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    // 遗留 #1 修复（2026-11-18）：sortTrips 早已定义却从未被调用，列表一直按
                    // DB 插入序渲染——§6 S23 规定「进行中 → 未来（start 升序）→ 未定日期 →
                    // 已结束（end 降序）」，现按该排序渲染。
                    items(
                        viewModel.sortTrips(trips, today),
                        key = { it.id },
                    ) { trip ->
                        val isOngoing = viewModel.isOngoing(trip, today)
                        val schedCount = scheduleCounts[trip.id] ?: 0
                        val placeCount = placeCounts[trip.id] ?: 0
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            border = if (isOngoing) {
                                androidx.compose.foundation.BorderStroke(1.4.dp, Primary)
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenTrip(trip.id) },
                                    onLongClick = { menuTrip = trip },
                                ),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(
                                            if (isOngoing) Primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Event,
                                        contentDescription = null,
                                        tint = if (isOngoing) Primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = trip.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight(600),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (isOngoing) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "进行中",
                                                fontSize = 10.sp,
                                                color = Color.White,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xFF2E9E6B))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = viewModel.dateRangeText(trip),
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "$placeCount 个点位 · $schedCount 个已安排",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 底部固定「＋ 新建行程」
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Primary, RoundedCornerShape(14.dp))
                    .clickable(onClick = onNewTrip),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "＋ 新建行程",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // 长按菜单：在地图上查看 / 改名 / 删除（§6 S23）
        menuTrip?.let { trip ->
            AlertDialog(
                onDismissRequest = { menuTrip = null },
                title = { Text(text = trip.name, fontSize = 16.sp) },
                text = {
                    Column {
                        MenuRow("在地图上查看") {
                            menuTrip = null
                            onOpenTripMap(trip.id)
                        }
                        MenuRow("改名") {
                            menuTrip = null
                            renameInput = trip.name
                            showRename = trip
                        }
                        MenuRow("删除行程", danger = true) {
                            menuTrip = null
                            showDeleteConfirm = trip
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {},
            )
        }

        showRename?.let { trip ->
            OutlinedTextFieldCommit(
                title = "改名",
                initial = trip.name,
                // 遗留 #4 修复（2026-11-18）：行程名此前完全没挂限长（90 个半角字符可入），
                // 与 S24 新建口径（§6 S24「名称最多 20 字」）统一——同宽度语义 + Toast 提示。
                clamp = {
                    com.visualzyzy.chuxingdazi.ui.common.NameLimit.clampTo(
                        it, TRIP_NAME_MAX_WIDTH, "行程名称最多20个字",
                    )
                },
                onDismiss = { showRename = null },
                onCommit = { newName ->
                    scope.launch {
                        viewModel.rename(trip.id, newName)
                        showRename = null
                    }
                },
            )
        }

        showDeleteConfirm?.let { trip ->
            val sched = scheduleCounts[trip.id] ?: 0
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = { Text(text = "删除「${trip.name}」？") },
                text = {
                    Text(
                        text = if (sched > 0) {
                            "删除后这趟行程的 $sched 个安排会一起消失，点位本身还在。"
                        } else {
                            "行程会删除，点位本身不受影响。"
                        },
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    Text(
                        text = "删除",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            showDeleteConfirm = null
                            viewModel.delete(trip.id)
                        }.padding(8.dp),
                    )
                },
                dismissButton = {
                    Text(
                        text = "取消",
                        modifier = Modifier.clickable { showDeleteConfirm = null }.padding(8.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun MenuRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
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

/** 行程名宽度上限（§6 S24「名称最多 20 字」，半角 0.5 计 —— 与 NameLimit 同一套宽度语义）。 */
internal const val TRIP_NAME_MAX_WIDTH = 20f

@Composable
private fun OutlinedTextFieldCommit(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
    clamp: (String) -> String = { it },
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = clamp(it) },
                singleLine = true,
            )
        },
        confirmButton = {
            Text(
                text = "确定",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { if (text.isNotBlank()) onCommit(text.trim()) }.padding(8.dp),
            )
        },
        dismissButton = {
            Text(text = "取消", modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
        },
    )
}
