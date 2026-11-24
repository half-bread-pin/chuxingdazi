package com.visualzyzy.chuxingdazi.ui.place

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec
import com.visualzyzy.chuxingdazi.data.model.FieldType
import com.visualzyzy.chuxingdazi.data.model.FieldValuesCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * S08 编辑点位（§6 S08）：名称 / 分类方块 / 地址块（调整→S06）/ 分类专属字段区（数据驱动）/
 * 加入清单 chips / 备注 / 底部保存。切换分类时已填的同 key 字段值保留。
 *
 * 2026-11-19（用户定调）：分类横排换成与 s05_add_point / S06 添加卡片同一组件
 * （CategorySelectorRow：56dp 方块、分类色图标、横滚、＋新建块）——此前是 take(7) 固定排，
 * 自定义分类被截断、行末方块挤压、也没有新建入口。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditPlaceScreen(
    onClose: () -> Unit,
    onAdjustCoords: (placeId: String) -> Unit,
    // 2026-11-19 新建分类「新建颜色」：S18 草稿调色回传（savedStateHandle 链，同 S09 口径）
    draftColorFlow: kotlinx.coroutines.flow.StateFlow<String>? = null,
    onConsumeDraftColor: () -> Unit = {},
    onPickDraftColor: (initialHex: String) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: EditPlaceViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current

    val place by viewModel.place.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val allLists by viewModel.allLists.collectAsStateWithLifecycle()

    var loaded by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var noteInput by remember { mutableStateOf("") }
    val fieldInputs = remember { mutableStateMapOf<String, String>() } // key → 用户输入文本（切换分类保留）
    // 点位自定义字段定义（字段模板升级 §3.1）：schema 编辑态，保存时随 place 落库
    var customDefs by remember { mutableStateOf(listOf<com.visualzyzy.chuxingdazi.data.model.FieldDef>()) }
    var selectedListIds by remember { mutableStateOf(setOf<String>()) }
    var originalListIds by remember { mutableStateOf(setOf<String>()) }
    // S08 2026-11-21 补齐（承接原 S29 面板能力）：图标 / 已到访 / 删除
    var customIconKey by remember { mutableStateOf<String?>(null) }
    var showIconPicker by remember { mutableStateOf(false) }
    var visitedInput by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteImpactText by remember { mutableStateOf("") }
    var deleteImpactLoaded by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showNewListDialog by remember { mutableStateOf(false) }
    // 新建分类（2026-11-19 与添加卡片同操作）：rememberSaveable——去 S18 调色跨页往返恢复
    var showNewCategoryDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCustomColor by remember { mutableStateOf<String?>(null) }
    val draftColor = draftColorFlow?.collectAsStateWithLifecycle(initialValue = "")?.value
    LaunchedEffect(draftColor) {
        if (!draftColor.isNullOrBlank()) {
            pendingCustomColor = draftColor
            onConsumeDraftColor()
        }
    }

    // 分类横排滚动：进入时把选中分类滚进视野 + 新建分类后滚到行末（与 S06 同行为）
    val categoryScrollState = rememberScrollState()
    var pendingScrollToEnd by remember { mutableStateOf(false) }
    LaunchedEffect(categories.size, pendingScrollToEnd) {
        if (pendingScrollToEnd && categories.isNotEmpty()) {
            categoryScrollState.animateScrollTo(categoryScrollState.maxValue)
            pendingScrollToEnd = false
        }
    }
    var initialCategoryScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(categories.isNotEmpty(), selectedCategoryId) {
        if (!initialCategoryScrollDone && categories.isNotEmpty() && selectedCategoryId != null) {
            initialCategoryScrollDone = true
            androidx.compose.runtime.withFrameNanos { }
            val target = com.visualzyzy.chuxingdazi.ui.common.categoryRowScrollTargetPx(
                categories, selectedCategoryId!!, density,
            )
            if (target != null) {
                categoryScrollState.scrollTo(target.coerceIn(0, categoryScrollState.maxValue))
            }
        }
    }

    // 初始化一次（S06 调整模式保存回来时 place Flow 自动刷新，但不覆盖用户正在编辑的内容）
    LaunchedEffect(place?.id) {
        val p = place ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        loaded = true
        nameInput = p.name
        selectedCategoryId = p.categoryId
        noteInput = p.note.orEmpty()
        originalListIds = viewModel.memberListIds()
        selectedListIds = originalListIds
        FieldValuesCodec.decode(p.fieldValues).forEach { (key, element) ->
            fieldInputs[key] = (element as? JsonPrimitive)?.content ?: element.toString()
        }
        customDefs = com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.decode(p.customSchema)
        customIconKey = p.iconKey
        visitedInput = p.visited
    }

    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
    val dirty = nameInput != (place?.name ?: "") ||
        selectedCategoryId != place?.categoryId ||
        noteInput != (place?.note ?: "") ||
        (customIconKey ?: "") != (place?.iconKey ?: "") ||
        visitedInput != (place?.visited ?: false)

    fun requestClose() {
        if (dirty) showDiscardConfirm = true else onClose()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶部导航：取消 / 编辑点位 / 保存（§6 S08 布局 1）
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
                    .clickable { requestClose() },
            )
            Text(
                text = "编辑点位",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            // 第十六轮：顶栏「保存」删除——表单只留底部一处保存，不再两套提交方式
            Spacer(modifier = Modifier.width(26.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // 名称（§6 S08 布局 2）
            Text(text = "名称", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = com.visualzyzy.chuxingdazi.ui.common.NameLimit.clamp(it) },
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
                placeholder = { Text("地点名称（必填）", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(14.dp))

            // 分类：横排方块（2026-11-19 换与 s05_add_point/S06 同一组件——横滚 + ＋新建）
            Text(text = "分类", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            com.visualzyzy.chuxingdazi.ui.common.CategorySelectorRow(
                categories = categories,
                selectedId = selectedCategoryId.orEmpty(),
                onSelect = { selectedCategoryId = it },
                onNewCategory = { showNewCategoryDialog = true },
                scrollState = categoryScrollState,
            )
            Spacer(modifier = Modifier.height(14.dp))

            // 图标行（2026-11-21 S08 补齐，承接原 S29 面板能力）：跟随分类 / 自定义图标
            val iconCategory = selectedCategory
            com.visualzyzy.chuxingdazi.ui.common.PlaceIconRow(
                categoryColorHex = iconCategory?.color,
                categoryIconKey = iconCategory?.iconKey,
                customIconKey = customIconKey,
                onPick = { showIconPicker = true },
            )
            Spacer(modifier = Modifier.height(14.dp))

            // 地址块：浅灰 + 坐标 + 调整（§6 S08 布局 4）
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = place?.address ?: "暂无地址",
                            fontSize = 13.sp,
                            maxLines = 2,
                        )
                        place?.let {
                            // 坐标系标注：腾讯版全库 GCJ02（2026-11-19 顺手纠正残留的 BD09 文案）
                            Text(
                                text = "%.4f, %.4f · GCJ02".format(it.lat, it.lng),
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = "调整",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onAdjustCoords(viewModel.placeId) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))

            // 分类专属字段区（§6 S08 布局 5）：切换分类立即重渲染（保留同 key 已填值）
            // 第十六轮：空模板（如「其他」）不再渲染「来自分类模板」的空块（与 S06/S05b 同口径）
            if (selectedCategory != null) {
                val schema = FieldSchemaCodec.decode(selectedCategory.fieldSchema)
                if (schema.isNotEmpty()) {
                Text(
                    text = "${selectedCategory.name}信息 · 来自「${selectedCategory.name}」分类模板",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                schema.chunked(2).forEach { rowDefs ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowDefs.forEach { def ->
                            com.visualzyzy.chuxingdazi.ui.common.TemplateFieldCell(
                                def = def,
                                value = fieldInputs[def.key].orEmpty(),
                                onValueChange = { fieldInputs[def.key] = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowDefs.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // 点位自定义字段区（字段模板升级 §3.2）：分类模板字段之后、加入清单之前
            com.visualzyzy.chuxingdazi.ui.common.CustomFieldsSection(
                defs = customDefs,
                templateLabels = (selectedCategory?.let { FieldSchemaCodec.decode(it.fieldSchema) } ?: emptyList())
                    .map { it.label }
                    .toSet(),
                values = fieldInputs,
                onValueChange = { key, value ->
                    if (value.isBlank()) fieldInputs.remove(key) else fieldInputs[key] = value
                },
                onDefsChanged = { customDefs = it },
            )
            Spacer(modifier = Modifier.height(14.dp))

            // 加入清单：多选 chips + ＋新建（§6 S08 布局 6）
            Text(text = "加入清单", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allLists.forEach { list ->
                    val selected = list.id in selectedListIds
                    Text(
                        text = list.name,
                        fontSize = 12.sp,
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
                                selectedListIds = if (selected) {
                                    selectedListIds - list.id
                                } else {
                                    selectedListIds + list.id
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                Text(
                    text = "＋ 新建",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable { showNewListDialog = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            Spacer(modifier = Modifier.height(14.dp))

            // 已到访开关（2026-11-21 S08 补齐，承接原 S29 面板能力）：随「保存」统一落库
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "已到访", fontSize = 13.5.sp)
                    Text(
                        text = "勾选后可被「只看未到访」筛选排除",
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = visitedInput,
                    onCheckedChange = { visitedInput = it },
                )
            }
            Spacer(modifier = Modifier.height(14.dp))

            // 备注：74dp 多行输入（§6 S08 布局 7）
            Text(text = "备注", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp),
                placeholder = { Text("想记点什么都可以（选填）", fontSize = 13.sp) },
            )
            Spacer(modifier = Modifier.height(14.dp))

            // 删除行（2026-11-21 S08 补齐，承接原 S29 面板删除能力）：红底垃圾桶 + 影响说明
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color(0x1AB3382E), RoundedCornerShape(14.dp))
                        .clickable {
                            showDeleteConfirm = true
                            deleteImpactLoaded = false
                            deleteImpactText = "正在统计影响…"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除点位",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "删除「${nameInput.ifBlank { "此点位" }}」会把它从所有清单移除；若它已在行程里排了时间，对应安排会一起删掉。",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 底部固定「保存点位」（§6 S08 布局 8）——第十六轮：表单唯一保存入口；
        // 名称为空不可保存（优化建议：名称空着不能存成点位）
        val canSave = nameInput.isNotBlank() && place != null
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable(enabled = canSave) {
                        val p = place ?: return@clickable
                        val schema = selectedCategory?.let { FieldSchemaCodec.decode(it.fieldSchema) }.orEmpty()
                        // 字段模板升级 §3.1：自定义字段（p_ 前缀）的值也要编码落库——schema 取「模板 + 自定义」并集
                        val valuesJson = FieldValuesCodec.fromTextInputs(
                            schema + customDefs,
                            fieldInputs,
                        )
                        viewModel.save(
                            name = nameInput,
                            categoryId = selectedCategoryId ?: p.categoryId,
                            note = noteInput,
                            fieldValuesJson = valuesJson,
                            customSchemaJson = com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.encode(customDefs),
                            iconKey = customIconKey,
                            visited = visitedInput,
                            selectedListIds = selectedListIds,
                            originalListIds = originalListIds,
                            onSaved = onClose,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (nameInput.isBlank()) "填写名称后保存" else "保存点位",
                    color = if (canSave) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(text = "放弃修改？") },
            text = { Text(text = "退出后将不保存这次改动。", fontSize = 13.sp) },
            confirmButton = {
                Text(
                    text = "放弃修改",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable {
                            showDiscardConfirm = false
                            onClose()
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "继续编辑",
                    modifier = Modifier
                        .clickable { showDiscardConfirm = false }
                        .padding(8.dp),
                )
            },
        )
    }

    // 图标选择对话框（2026-11-21 S08 补齐；添加卡片同款组件）
    if (showIconPicker) {
        com.visualzyzy.chuxingdazi.ui.common.IconPickerDialog(
            currentKey = customIconKey,
            categoryIconKey = selectedCategory?.iconKey,
            categoryColorHex = selectedCategory?.color,
            onDismiss = { showIconPicker = false },
            onConfirm = { key ->
                customIconKey = key
                showIconPicker = false
            },
        )
    }

    // 删除确认（2026-11-21 S08 补齐；影响统计 = S29 面板同款：清单数 + 行程安排数）
    if (showDeleteConfirm) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val impact = viewModel.impactOfDelete()
            val listNames = viewModel.memberLists().map { it.name }
            deleteImpactText = buildString {
                append("「${place?.name.orEmpty()}」将从 ${listNames.size} 个清单移除")
                if (listNames.isNotEmpty()) append("（${listNames.joinToString("、")}）")
                if (impact.tripCount > 0) {
                    append("；并从 ${impact.tripCount} 个行程删除 ${impact.scheduleCount} 段安排")
                } else {
                    append("；它在行程里没有安排")
                }
                append("。此操作不可恢复。")
            }
            deleteImpactLoaded = true
        }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = "删除「${place?.name.orEmpty()}」？") },
            text = { Text(text = deleteImpactText, fontSize = 13.sp) },
            confirmButton = {
                Text(
                    text = "删除",
                    color = if (deleteImpactLoaded) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = deleteImpactLoaded) {
                        showDeleteConfirm = false
                        viewModel.deletePlace(onDone = onClose)
                    }.padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "取消",
                    modifier = Modifier.clickable { showDeleteConfirm = false }.padding(8.dp),
                )
            },
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        if (newListName.isNotBlank()) {
                            scope.launch {
                                // 第十六轮：重名 Toast、对话框保持打开（与 S05 一致）
                                val created = viewModel.createList(newListName)
                                if (created != null) {
                                    selectedListIds = selectedListIds + created.id
                                    showNewListDialog = false
                                } else {
                                    android.widget.Toast.makeText(context, "已有同名清单", android.widget.Toast.LENGTH_SHORT).show()
                                }
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

    // 新建分类（2026-11-19 与 s05_add_point/S06 卡片同操作）：页内创建 + 自动选中 + 滚到行末
    if (showNewCategoryDialog) {
        com.visualzyzy.chuxingdazi.ui.common.NewCategoryDialog(
            onDismiss = { showNewCategoryDialog = false },
            onCreate = { name, iconKey, color ->
                scope.launch {
                    viewModel.createCategory(name, iconKey, color)?.let { created ->
                        selectedCategoryId = created.id
                        showNewCategoryDialog = false
                        pendingScrollToEnd = true
                    } ?: android.widget.Toast.makeText(
                        context,
                        "已有同名分类",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onPickCustomColor = { currentHex -> onPickDraftColor(currentHex) },
            pendingCustomColor = pendingCustomColor,
            onConsumeCustomColor = { pendingCustomColor = null },
        )
    }
}

