package com.visualzyzy.chuxingdazi.ui.category

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec
import com.visualzyzy.chuxingdazi.data.model.IconCatalog
import com.visualzyzy.chuxingdazi.ui.common.IconGridSections
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * S09 分类管理（§6 S09）：列表 + 底部编辑面板（名称/40 图标网格/颜色→S18/字段只读/删除）。
 *
 * 第七轮（2026-11-06）：
 * - R12：根布局避让系统导航栏（navigationBarsPadding，跟随系统导航方式自适应）。
 * - R13：顶部说明 = 「内置分类可改图标/颜色/名称，不可删除」（原底部文案），底部说明删除。
 * - R14：面板「颜色」→「图标背景颜色」。
 * - R15/R16：「＋ 新建自定义分类」改为**草稿制**——打开与已有分类同款编辑面板（无垃圾桶、
 *   保存占整行），点「保存」才落库；X / 返回 = 丢弃草稿回 S09。不再出现「未确认也建出分类」。
 * - R17：面板弹出期间系统返回键 = 关闭面板回 S09（不再退出 S09）。
 */
@Composable
fun CategoryManageScreen(
    onClose: () -> Unit,
    onPickColor: (categoryId: String) -> Unit,
    draftColorFlow: StateFlow<String>? = null, // S18 草稿模式回传的颜色（经 savedStateHandle）
    onConsumeDraftColor: () -> Unit = {},
    onPickDraftColor: (initialHex: String) -> Unit = {}, // 草稿调色：S18 带 initial 色打开，回传不落库
) {
    val context = LocalContext.current
    val viewModel: CategoryManageViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()

    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val editingId by viewModel.editingId.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    val editing = categories.firstOrNull { it.id == editingId }
    // 面板主体：编辑已有 = categories 里那条；新建 = 未落库草稿
    val panelCat: CategoryEntity? = editing ?: draft
    val isCreating = panelCat != null && draft != null
    val inputKey = editingId ?: (draft?.let { CategoryManageViewModel.DRAFT_ID } ?: null)

    var nameInput by remember(inputKey) { mutableStateOf(panelCat?.name.orEmpty()) }
    var iconKeyInput by remember(inputKey) { mutableStateOf(panelCat?.iconKey ?: "other_point") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nameConflict by remember { mutableStateOf(false) }
    // 模板字段编辑态（字段模板升级 §3.4，2026-11-21）：面板本地保存，点「保存」统一落库；
    // 删除在落库时同事务清该分类全部点位的对应值（孤儿清理，repo.updateTemplateSchema）
    var panelSchemaJson by remember(inputKey) { mutableStateOf(panelCat?.fieldSchema ?: "[]") }
    var showAddFieldDialog by remember { mutableStateOf(false) }
    var pendingDeleteField by remember { mutableStateOf<com.visualzyzy.chuxingdazi.data.model.FieldDef?>(null) }

    // S18 草稿模式回传的颜色 → 写回草稿（不落库，直到点「保存」）
    val draftColor = draftColorFlow?.collectAsStateWithLifecycle(initialValue = "")?.value
    LaunchedEffect(draftColor) {
        if (!draftColor.isNullOrBlank()) {
            viewModel.updateDraftColor(draftColor)
            onConsumeDraftColor()
        }
    }

    // 名称实时查重（§6 S09：编辑时排除自己；新建草稿不排除）
    LaunchedEffect(nameInput, editingId, isCreating) {
        val cat = panelCat
        if (cat == null || nameInput.isBlank()) {
            nameConflict = false
        } else if (!isCreating && nameInput.trim() == cat.name) {
            nameConflict = false
        } else {
            nameConflict = viewModel.isNameTaken(nameInput, editingId)
        }
    }

    // R17（第七轮）：面板弹出期间系统返回 = 关闭面板，回 S09（不退出界面）
    androidx.activity.compose.BackHandler(enabled = panelCat != null) {
        viewModel.openEditor(null)
        viewModel.cancelCreating()
        nameConflict = false
    }

    // BoxWithConstraints（2026-11-19）：编辑面板限高 = 容器高 − 状态栏——98 图标网格全展开时
    // 面板内容比屏幕还高，底锚 + 无限高的旧实现会把面板顶进（甚至顶出）状态栏区域；
    // 限高后超出的部分走面板自身 verticalScroll。
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        val statusInsetPx = androidx.compose.foundation.layout.WindowInsets.statusBars
            .getTop(androidx.compose.ui.platform.LocalDensity.current)
        val panelMaxHeightDp = with(androidx.compose.ui.platform.LocalDensity.current) {
            maxHeight - (statusInsetPx.toDp())
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // R12（第七轮）：避让系统导航栏（三键/手势自适应——WindowInsets.navigationBars）
                .navigationBarsPadding(),
        ) {
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
                    text = "分类管理",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(26.dp))
            }
            // R13（第七轮）：说明文案移到顶部（原底部文案），底部说明删除
            Text(
                text = "内置分类可以改图标/颜色/名称，不可删除",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))

            // 分类列表卡片（§6 S09 布局 3）
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(categories, key = { it.id }) { category ->
                    val count = places.count { it.categoryId == category.id }
                    val fieldCount = FieldSchemaCodec.decode(category.fieldSchema).size
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.openEditor(category.id)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(parseCategoryColorSafe(category.color)),
                                contentAlignment = Alignment.Center,
                            ) {
                                CategoryIcon(category.iconKey, 22.dp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = category.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight(600),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (category.isBuiltin) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "内置",
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(horizontal = 5.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = "$count 个点位 · $fieldCount 个字段",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                // ＋ 新建自定义分类（§6 S09 布局 4）
                // R15/R16（第七轮）：只打开未落库草稿面板；点「保存」才创建（R15）
                item {
                    androidx.compose.material3.TextButton(onClick = { viewModel.startCreating() }) {
                        Text(text = "＋ 新建自定义分类", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // 底部编辑面板（§6 S09 布局 5）：编辑已有 与 新建草稿 共用同一形态
        // R16：新建时无垃圾桶，保存占一整行
        if (panelCat != null) {
            val cat = panelCat
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 2026-11-19：顶边永不进入系统状态栏区域（用户定调）；超高内容面板内滚动
                    .heightIn(max = panelMaxHeightDp),
            ) {
                val panelScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .verticalScroll(panelScrollState)
                        .padding(16.dp),
                ) {
                    // 名称 + 关闭
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = com.visualzyzy.chuxingdazi.ui.common.NameLimit.clamp(it) },
                            singleLine = true,
                            // 2026-11-22 修复 #8：空框无提示看不出是名称框——补 placeholder
                            //（S05c/S06/S08 的 NewCategoryDialog 已有「例：咖啡馆」，本面板是唯一漏网）
                            placeholder = { Text("分类名称", fontSize = 13.sp) },
                            shape = RoundedCornerShape(11.dp),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            modifier = Modifier
                                .size(24.dp)
                                .clickable {
                                    // R15：X = 取消面板（草稿丢弃，不创建）
                                    viewModel.openEditor(null)
                                    viewModel.cancelCreating()
                                    nameConflict = false
                                },
                        )
                    }
                    if (nameConflict) {
                        Text(
                            text = "已有同名分类",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // 图标网格：40 个（§6 S09：6 列，选中青蓝描边）
                    Text(
                        text = "图标与颜色 · 选一个一眼能认出来的图标",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // 第十一轮（2026-11-11）：98 图标按前缀分组显示（组标题 + 6 列网格，
                    // 与图标选择对话框共用同一组件）；整块随面板自身的 verticalScroll 滚动
                    IconGridSections(
                        selectedKey = iconKeyInput,
                        onSelect = { iconKeyInput = it },
                        scrollState = panelScrollState, // 第十六轮：组名胶囊锚点滚动
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 颜色行（§6 S09：26dp 色圆 + Hex + 调整›）
                    // R14（第七轮）：「颜色」→「图标背景颜色」
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "图标背景颜色", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(parseCategoryColorSafe(cat.color))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = cat.color,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "调整 ›",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                if (isCreating) {
                                    onPickDraftColor(cat.color)
                                } else {
                                    onPickColor(cat.id)
                                }
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // 模板字段增删（字段模板升级 §3.4，2026-11-21）：内置与自定义分类同等开放
                    //（seed 幂等规则「分类行存在永不碰 schema」保证删掉的内置字段不会被加回）。
                    // 原「v1 只读」展示块废除。
                    val fields = remember(panelSchemaJson) { FieldSchemaCodec.decode(panelSchemaJson) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "模板字段（${fields.size} 个）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "＋ 添加字段",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { showAddFieldDialog = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    fields.forEach { def ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = def.label, fontSize = 13.sp)
                                Text(
                                    text = "${fieldTypeLabel(def.type)} · 非必填",
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "删除",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clickable { pendingDeleteField = def }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    // 保存 + 删除（仅编辑已有自定义分类；新建草稿 = 保存占整行，R16）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isCreating && !cat.isBuiltin) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color(0x1AB3382E), RoundedCornerShape(14.dp))
                                    .clickable { showDeleteConfirm = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "删除分类",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .background(
                                    if (nameConflict || nameInput.isBlank()) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable(enabled = !nameConflict && nameInput.isNotBlank()) {
                                    if (isCreating) {
                                        // R15：只有点「保存」才真正创建（模板字段随创建落库，§3.4）
                                        viewModel.saveDraft(
                                            name = nameInput.trim(),
                                            iconKey = iconKeyInput,
                                            schemaJson = panelSchemaJson,
                                            onNameConflict = {
                                                nameConflict = true
                                            },
                                            onDone = {
                                                viewModel.cancelCreating()
                                                nameConflict = false
                                            },
                                        )
                                    } else {
                                        viewModel.saveAppearance(
                                            id = cat.id,
                                            name = nameInput,
                                            iconKey = iconKeyInput,
                                            color = cat.color,
                                            onDone = {
                                                // 模板字段与名称/图标/颜色同一次「保存」落库（§3.4）
                                                viewModel.saveTemplateFields(cat.id, panelSchemaJson) {
                                                    viewModel.openEditor(null)
                                                }
                                            },
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "保存",
                                color = if (nameConflict || nameInput.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    Color.White
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }

        // 添加模板字段对话框（字段模板升级 §3.4；与点位自定义字段共用同一组件）
        if (showAddFieldDialog) {
            com.visualzyzy.chuxingdazi.ui.common.AddCustomFieldDialog(
                existingLabels = FieldSchemaCodec.decode(panelSchemaJson).map { it.label }.toSet(),
                onDismiss = { showAddFieldDialog = false },
                onConfirm = { def ->
                    panelSchemaJson = FieldSchemaCodec.encode(FieldSchemaCodec.decode(panelSchemaJson) + def)
                    showAddFieldDialog = false
                },
            )
        }

        // 删除模板字段确认（§3.4：落库时同事务清该分类全部点位的对应值）
        pendingDeleteField?.let { def ->
            AlertDialog(
                onDismissRequest = { pendingDeleteField = null },
                title = { Text(text = "删除字段「${def.label}」？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        text = "点「保存」后生效：该分类所有点位里已填的这个字段值会一并删除。",
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        panelSchemaJson = FieldSchemaCodec.encode(
                            FieldSchemaCodec.decode(panelSchemaJson).filter { it.key != def.key },
                        )
                        pendingDeleteField = null
                    }) { Text(text = "删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { pendingDeleteField = null }) { Text("取消") } },
            )
        }

        // 删除确认（§6 S09：点位置回其他 + 二次确认）
        if (showDeleteConfirm && editing != null) {
            val count = places.count { it.categoryId == editing.id }
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(text = "删除「${editing.name}」？") },
                text = {
                    Text(
                        text = if (count > 0) {
                            "它名下的 $count 个点位会回落到「其他」，并清理该分类模板的字段值。"
                        } else {
                            "删除后其颜色会被释放。"
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
                            showDeleteConfirm = false
                            val id = editing.id
                            viewModel.openEditor(null)
                            viewModel.deleteCustom(id) {}
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
    }
}

/** 草稿模式的 S18 入口（由 MainActivity 提供：带 initial 色 + 回传 savedStateHandle）。 */

/** 列表行 / 面板色块图标（按分类色 tint，浅背景也可见）。 */
@Composable
private fun CategoryIcon(iconKey: String, size: androidx.compose.ui.unit.Dp) {
    CategoryIconTinted(iconKey, size, null)
}

@Composable
private fun CategoryIconTinted(iconKey: String, size: androidx.compose.ui.unit.Dp, tint: Color?) {
    val context = LocalContext.current
    val id = remember(iconKey) {
        context.resources.getIdentifier("ic_cat_$iconKey", "drawable", context.packageName)
    }
    if (id != 0) {
        Image(
            painter = painterResource(id),
            contentDescription = null,
            colorFilter = tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it) },
            modifier = Modifier.size(size),
        )
    }
}

private fun parseCategoryColorSafe(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748B")) }.getOrDefault(Color(0xFF64748B))

/** 模板字段类型中文名（S09 面板字段行副标题用；与添加对话框的 9 项白名单一致）。 */
private fun fieldTypeLabel(type: com.visualzyzy.chuxingdazi.data.model.FieldType): String =
    when (type) {
        com.visualzyzy.chuxingdazi.data.model.FieldType.TEXT -> "文本"
        com.visualzyzy.chuxingdazi.data.model.FieldType.LONG_TEXT -> "长文本"
        com.visualzyzy.chuxingdazi.data.model.FieldType.NUMBER -> "数字"
        com.visualzyzy.chuxingdazi.data.model.FieldType.MONEY -> "金额"
        com.visualzyzy.chuxingdazi.data.model.FieldType.TEL -> "电话"
        com.visualzyzy.chuxingdazi.data.model.FieldType.URL -> "链接"
        com.visualzyzy.chuxingdazi.data.model.FieldType.DATETIME -> "日期时间"
        com.visualzyzy.chuxingdazi.data.model.FieldType.TIME_RANGE -> "时间段"
        com.visualzyzy.chuxingdazi.data.model.FieldType.BUSINESS_HOURS -> "营业时间"
    }
