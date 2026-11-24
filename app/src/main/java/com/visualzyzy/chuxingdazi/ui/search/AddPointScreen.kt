package com.visualzyzy.chuxingdazi.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec
import com.visualzyzy.chuxingdazi.data.model.FieldValuesCodec
import com.visualzyzy.chuxingdazi.data.tencent.SuggestionItem
import com.visualzyzy.chuxingdazi.ui.common.CategorySelectorRow
import com.visualzyzy.chuxingdazi.ui.common.IconPickerDialog
import com.visualzyzy.chuxingdazi.ui.common.NewCategoryDialog
import com.visualzyzy.chuxingdazi.ui.common.PlaceIconRow
import com.visualzyzy.chuxingdazi.ui.common.categoryRowScrollTargetPx
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * s05_add_point 添加点位（2026-11-19 新增独立全屏页）：
 * 原 S05b「为该位置添加标记」的屏内拖动浮层改为独立路由页（用户定调）——
 * 两条入口（S05b 底部信息卡 / 点击点位信息卡）都跳本页，元素与布局与原浮层一致：
 * 地址行 → 名称 → 分类 → 图标行 → 分类模板字段 → 备注 → 加入清单 → 取消/确认添加。
 *
 * 页面不使用系统状态栏与底部按键栏区域（statusBars/navigationBars padding）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddPointScreen(
    item: SuggestionItem,
    presetName: String,
    viewModel: SearchViewModel,
    draftColorFlow: StateFlow<String>,
    onConsumeDraftColor: () -> Unit,
    onPickDraftColor: (initialHex: String) -> Unit,
    onClose: () -> Unit,
    onAdded: (coords: DoubleArray) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val allLists by viewModel.allLists.collectAsStateWithLifecycle()

    var addCategoryId by rememberSaveable { mutableStateOf("other") }
    val addListIds = remember { mutableStateOf(setOf<String>()) }
    var addNameInput by rememberSaveable { mutableStateOf(presetName) }
    var addNoteInput by rememberSaveable { mutableStateOf("") }
    // 分类模板字段值跨 S18 调色往返恢复（Bundle 存扁平 k-v 列表）
    val addFieldInputs = rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.listSaver(
            save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
            restore = { flat ->
                val map = mutableMapOf<String, String>()
                var i = 0
                while (i + 1 < flat.size) {
                    map[flat[i]] = flat[i + 1]
                    i += 2
                }
                map
            },
        ),
    ) { mutableStateOf(mutableMapOf<String, String>()) }
    // 点位自定义字段 schema（字段模板升级 §3.2）：新建态存表单状态，随保存同事务落库（JSON 字符串可直接 saveable）
    var addCustomSchema by rememberSaveable { mutableStateOf("[]") }
    var addIconKey by remember { mutableStateOf<String?>(null) }
    var showIconPicker by remember { mutableStateOf(false) }
    var showNewCategoryDialog by rememberSaveable { mutableStateOf(false) }
    var showNewListDialog by remember { mutableStateOf(false) }

    // 切换分类 → 图标重置为跟随新分类（图标语义跟分类走，与 S06 一致）
    LaunchedEffect(addCategoryId) { addIconKey = null }
    val addTargetCategory = categories.firstOrNull { it.id == addCategoryId }

    // 分类横排（默认选中「其他」在行末屏外）→ 首次加载后把选中项滚进视野
    val categoryScrollState = rememberScrollState()
    var pendingScrollToEnd by remember { mutableStateOf(false) }
    LaunchedEffect(categories.isNotEmpty()) {
        if (categories.isNotEmpty()) {
            withFrameNanos { }
            val target = categoryRowScrollTargetPx(categories, addCategoryId, density)
            if (target != null) {
                categoryScrollState.scrollTo(target.coerceIn(0, categoryScrollState.maxValue))
            }
        }
    }
    // 页内新建分类 → 滚到行末并自动选中（与 S06 同行为）
    LaunchedEffect(categories.size, pendingScrollToEnd) {
        if (pendingScrollToEnd && categories.isNotEmpty()) {
            categoryScrollState.animateScrollTo(categoryScrollState.maxValue)
            pendingScrollToEnd = false
        }
    }

    // S18 草稿调色回传（新建颜色的色值）→ 注入 NewCategoryDialog
    var pendingCustomColor by remember { mutableStateOf<String?>(null) }
    val draftColor = draftColorFlow.collectAsStateWithLifecycle(initialValue = "").value
    LaunchedEffect(draftColor) {
        if (draftColor.isNotBlank()) {
            pendingCustomColor = draftColor
            onConsumeDraftColor()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 不使用系统状态栏 / 底部按键栏区域
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 窄顶栏：返回 + 标题（同 S30 形态）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onClose),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "添加点位",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // 地址行（与原浮层一致：目标点名称 + 地址/区划，静态）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name.ifBlank { "为该位置添加标记" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val addrLine = item.address?.takeIf { it.isNotBlank() }
                        ?: listOfNotNull(item.district, item.city)
                            .joinToString("").takeIf { it.isNotBlank() }
                    if (!addrLine.isNullOrBlank()) {
                        Text(
                            text = addrLine,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            // 名称（40 汉字上限，选填）
            OutlinedTextField(
                value = addNameInput,
                onValueChange = { addNameInput = com.visualzyzy.chuxingdazi.ui.common.NameLimit.clamp(it) },
                singleLine = true,
                placeholder = { Text("给这个地点起个名字（选填）", fontSize = 13.sp) },
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 分类横排（与 S06 卡片同一组件形态，含「＋新建」块）
            Text(text = "分类", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            CategorySelectorRow(
                categories = categories,
                selectedId = addCategoryId,
                onSelect = { addCategoryId = it },
                onNewCategory = { showNewCategoryDialog = true },
                scrollState = categoryScrollState,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 图标行（点位自定义图标，null = 跟随分类）
            PlaceIconRow(
                categoryColorHex = addTargetCategory?.color,
                categoryIconKey = addTargetCategory?.iconKey,
                customIconKey = addIconKey,
                onPick = { showIconPicker = true },
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 分类模板字段（随保存落 field_values）
            val addSchema = addTargetCategory?.let { FieldSchemaCodec.decode(it.fieldSchema) }.orEmpty()
            if (addSchema.isNotEmpty()) {
                Text(
                    text = "${addTargetCategory?.name.orEmpty()}信息（选填，也可保存后进编辑页补充）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                addSchema.chunked(2).forEach { rowDefs ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowDefs.forEach { def ->
                            com.visualzyzy.chuxingdazi.ui.common.TemplateFieldCell(
                                def = def,
                                value = addFieldInputs.value[def.key].orEmpty(),
                                onValueChange = {
                                    addFieldInputs.value = addFieldInputs.value
                                        .toMutableMap()
                                        .also { m -> m[def.key] = it }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowDefs.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // 点位自定义字段区（字段模板升级 §3.2）：新建点位也可当场加字段
            com.visualzyzy.chuxingdazi.ui.common.CustomFieldsSection(
                defs = com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.decode(addCustomSchema),
                templateLabels = addSchema.map { it.label }.toSet(),
                values = addFieldInputs.value,
                onValueChange = { key, value ->
                    addFieldInputs.value = addFieldInputs.value
                        .toMutableMap()
                        .also { m -> if (value.isBlank()) m.remove(key) else m[key] = value }
                },
                onDefsChanged = {
                    addCustomSchema = com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.encode(it)
                },
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 备注
            Text(text = "备注", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = addNoteInput,
                onValueChange = { addNoteInput = it },
                shape = RoundedCornerShape(11.dp),
                placeholder = { Text("想记点什么都可以（选填）", fontSize = 12.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 加入清单（多选 + ＋新建清单）
            Text(text = "加入清单（可多选）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allLists.forEach { list ->
                    val selected = list.id in addListIds.value
                    Text(
                        text = list.name,
                        fontSize = 12.5.sp,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .clickable {
                                addListIds.value = if (selected) {
                                    addListIds.value - list.id
                                } else {
                                    addListIds.value + list.id
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Text(
                    text = "＋ 新建清单",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable { showNewListDialog = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 取消 / 确认添加（原浮层同款按钮行）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .width(92.dp)
                        .height(46.dp)
                        .clickable(onClick = onClose),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "取消",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                        .clickable {
                            viewModel.addSingle(
                                item,
                                addCategoryId,
                                addListIds.value,
                                addNameInput,
                                addNoteInput,
                                iconKey = addIconKey,
                                // 字段模板升级 §3.1：自定义字段（p_ 前缀）的值也要编码落库
                                fieldValuesJson = FieldValuesCodec.fromTextInputs(
                                    addTargetCategory?.let { FieldSchemaCodec.decode(it.fieldSchema) }.orEmpty() +
                                        com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.decode(addCustomSchema),
                                    addFieldInputs.value,
                                ),
                                customSchemaJson = addCustomSchema,
                            ) { added, coords ->
                                if (added > 0) {
                                    Toast.makeText(context, "已添加到点位库", Toast.LENGTH_SHORT).show()
                                    onAdded(coords)
                                } else {
                                    Toast.makeText(context, "这个地点已经在库里了", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "确认添加",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    if (showIconPicker) {
        IconPickerDialog(
            currentKey = addIconKey,
            categoryIconKey = addTargetCategory?.iconKey,
            categoryColorHex = addTargetCategory?.color,
            onDismiss = { showIconPicker = false },
            onConfirm = {
                addIconKey = it
                showIconPicker = false
            },
        )
    }

    if (showNewCategoryDialog) {
        NewCategoryDialog(
            onDismiss = { showNewCategoryDialog = false },
            onCreate = { name, iconKey, color ->
                scope.launch {
                    viewModel.createCategory(name, iconKey, color)?.let { created ->
                        addCategoryId = created.id // 新分类自动选中（与 S06 同行为）
                        showNewCategoryDialog = false
                        pendingScrollToEnd = true
                    } ?: Toast.makeText(context, "已有同名分类", Toast.LENGTH_SHORT).show()
                }
            },
            onPickCustomColor = { currentHex -> onPickDraftColor(currentHex) },
            pendingCustomColor = pendingCustomColor,
            onConsumeCustomColor = { pendingCustomColor = null },
        )
    }

    if (showNewListDialog) {
        var newListName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewListDialog = false },
            title = { Text(text = "新建清单") },
            text = {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = com.visualzyzy.chuxingdazi.ui.common.NameLimit.clamp(it) },
                    singleLine = true,
                    placeholder = { Text("例：杭州必吃") },
                )
            },
            confirmButton = {
                Text(
                    text = "创建",
                    color = if (newListName.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.clickable {
                        if (newListName.isNotBlank()) {
                            scope.launch {
                                viewModel.createList(newListName)?.let { list ->
                                    addListIds.value = addListIds.value + list.id
                                }
                                showNewListDialog = false
                            }
                        }
                    }.padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "取消",
                    modifier = Modifier.clickable { showNewListDialog = false }.padding(8.dp),
                )
            },
        )
    }
}

/** 模板字段占位文案（与 PickPlaceScreen.placeholderOf 同款）。 */
