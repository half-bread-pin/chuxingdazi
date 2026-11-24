package com.visualzyzy.chuxingdazi.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.repo.ListRepository
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** S27 清单色板（与分类色板同源的常用色）。 */
private val LIST_COLORS = listOf(
    "#F97316", "#2E9E6B", "#3B82F6", "#8B5CF6", "#EC4899",
    "#E5484D", "#0E7490", "#9A6700", "#64748B", "#14171C",
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val listRepository: ListRepository,
) : ViewModel() {

    data class Row(val list: ListEntity, val placeCount: Int)

    val rows: StateFlow<List<Row>> = listRepository.observeAll()
        .map { lists -> lists.map { Row(it, listRepository.countInListSync(it.id)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 遗留 #2 补完（2026-11-19）：S27 新建清单重名此前**未捕获 NameConflictException 直接崩进程**
     *（真机实锤：logcat「[Crash] uncaught in main: 已有同名清单」→ 退桌面）。改为可失败回传：
     * @return false = 已有同名清单（界面 Toast 提示、对话框保持打开，与 S05 十六轮同语义） */
    suspend fun create(name: String): Boolean = try {
        listRepository.create(name, null)
        true
    } catch (e: com.visualzyzy.chuxingdazi.data.repo.NameConflictException) {
        false
    }

    /** @return false = 已有同名清单（改名撞名同样 Toast + 对话框保持打开） */
    suspend fun rename(id: String, name: String): Boolean = try {
        listRepository.rename(id, name)
        true
    } catch (e: com.visualzyzy.chuxingdazi.data.repo.NameConflictException) {
        false
    }

    fun setColor(id: String, color: String?) {
        viewModelScope.launch { listRepository.setColor(id, color) }
    }

    fun delete(id: String) {
        viewModelScope.launch { listRepository.delete(id) }
    }

    /** 第十六轮：S27 清单排序（-1 上移 / +1 下移）。 */
    fun move(id: String, direction: Int) {
        viewModelScope.launch { listRepository.move(id, direction) }
    }
}

/** S27 清单列表（§6 S27）：总览入口 + 新建/长按改名/换色/删除；点卡片进 S28 管理点位。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListsScreen(
    onClose: () -> Unit,
    onOpenList: (listId: String) -> Unit = {}, // R25（第七轮）：点清单 → S28 清单详情
) {
    val viewModel: ListsViewModel = hiltViewModel()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    // 遗留 #2 补完（2026-11-19）：重名回传 Toast 用
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var showCreate by remember { mutableStateOf(false) }
    var createInput by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ListEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ListEntity?>(null) }

    // 返回键守卫：弹层（新建/改名换色删除/子确认）开着时先关弹层，再放行路由返回
    // （此前返回穿透是靠对话框内聚焦输入框「侥幸」吃掉的，第十五轮统一补齐）
    androidx.activity.compose.BackHandler(
        enabled = showCreate || renameTarget != null || deleteTarget != null,
    ) {
        showCreate = false
        renameTarget = null
        deleteTarget = null
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
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onClose),
            )
            Text(
                text = "清单",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(26.dp))
        }
        Text(
            text = "长按清单可改名或删除 · 点进去管理点位（在点位编辑里勾选）",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(11.dp),
        ) {
            items(rows, key = { it.list.id }) { row ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            // R25（第七轮）：点卡片 → S28 清单详情（此前点击无动作）
                            onClick = { onOpenList(row.list.id) },
                            onLongClick = {
                                renameTarget = row.list
                                renameInput = row.list.name
                            },
                        ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    row.list.color?.let { c -> runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrDefault(Color(0xFFB9C2CE)) }
                                        ?: Color(0xFFB9C2CE),
                                    CircleShape,
                                ),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = row.list.name, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${row.placeCount} 个点位",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(text = "›", fontSize = 15.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }

        // 新建清单（S27 底部主按钮）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
                .height(46.dp)
                .background(Primary, RoundedCornerShape(14.dp))
                .clickable { createInput = ""; showCreate = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "＋ 新建清单", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建清单", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = createInput,
                    onValueChange = { createInput = com.visualzyzy.chuxingdazi.ui.common.NameLimit.clamp(it) },
                    singleLine = true,
                    placeholder = { Text("例：杭州必吃", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = createInput.isNotBlank(),
                    onClick = {
                        // 遗留 #2 补完（2026-11-19）：重名 Toast + 对话框保持打开（此前静默关闭；
                        // 更早版本未捕获直接崩进程），与 S05 新建清单十六轮修复同语义
                        scope.launch {
                            if (viewModel.create(createInput.trim())) {
                                showCreate = false
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "已有同名清单",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) { Text("创建", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    renameTarget?.let { list ->
        // P3 小瑕疵修复：setColor 后 rows 已更新，但捕获的 list 是旧对象；从 rows 里取最新值刷新描边
        val current = rows.firstOrNull { it.list.id == list.id }?.list ?: list
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("改名 / 换色 / 排序 / 删除", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = com.visualzyzy.chuxingdazi.ui.common.NameLimit.clamp(it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "颜色", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        LIST_COLORS.forEach { hex ->
                            val c = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
                            val isCurrent = current.color == hex
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(c, CircleShape)
                                    .border(
                                        width = if (isCurrent) 2.5.dp else 1.dp,
                                        color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    )
                                    .clickable { viewModel.setColor(list.id, hex) }
                                    .semantics { contentDescription = "清单颜色 $hex" },
                            )
                        }
                        // 无色（默认灰）
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .border(
                                    width = if (current.color == null) 2.5.dp else 1.dp,
                                    color = if (current.color == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable { viewModel.setColor(list.id, null) }
                                .semantics { contentDescription = "清单颜色 无" },
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    // 第十六轮：排序（上移/下移）——sort_index 真正生效
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        val idx = rows.indexOfFirst { it.list.id == list.id }
                        Text(
                            text = "↑ 上移",
                            fontSize = 13.sp,
                            color = if (idx > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .clickable(enabled = idx > 0) {
                                    renameTarget = null
                                    viewModel.move(list.id, -1)
                                }
                                .padding(vertical = 6.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "↓ 下移",
                            fontSize = 13.sp,
                            color = if (idx >= 0 && idx < rows.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .clickable(enabled = idx >= 0 && idx < rows.lastIndex) {
                                    renameTarget = null
                                    viewModel.move(list.id, +1)
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "删除清单（点位保留）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable {
                                deleteTarget = list
                                renameTarget = null
                            }
                            .padding(vertical = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        // 遗留 #2 补完（2026-11-19）：改名撞名同样 Toast + 对话框保持打开
                        scope.launch {
                            if (viewModel.rename(list.id, renameInput.trim())) {
                                renameTarget = null
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "已有同名清单",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) { Text("保存", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { list ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${list.name}」？", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("清单里的点位不会被删除，只是从这份清单移除。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(list.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}
