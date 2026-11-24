package com.visualzyzy.chuxingdazi.ui.common

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.data.model.FieldDef
import com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec
import com.visualzyzy.chuxingdazi.data.model.FieldType

/**
 * 点位自定义字段区（字段模板升级 §3.2/§3.3，2026-11-21）：S08（编辑，主入口）与
 * S05c/S06（添加，schema 存表单状态、随保存落库）共用同一组件。
 *
 * 结构：标题行（「自定义字段」＋「＋ 添加字段」）→ 已有字段行（TemplateFieldCell 结构化
 * 输入 + 行尾「删除」）。「添加字段」对话框 = 名称（NameLimit 40 汉字宽）+ 类型单选 chips
 * （9 项白名单，恒非必填无 required）。删除带确认框（文案含「已填的值会一并删除」）。
 *
 * 查重：名称与已有自定义字段及分类模板字段 label 重复 → Toast「已有同名字段」+ 对话框
 * 保持打开（对齐 S27 重名语义，B.25 铁律）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomFieldsSection(
    defs: List<FieldDef>,
    templateLabels: Set<String>,
    values: Map<String, String>,
    onValueChange: (key: String, value: String) -> Unit,
    onDefsChanged: (List<FieldDef>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FieldDef?>(null) }

    if (defs.isNotEmpty() || showAddDialog) {
        // 空列表也留标题行：有添加入口才可发现（S08 主入口）；全空时由调用方决定是否渲染本组件
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "自定义字段",
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "＋ 添加字段",
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { showAddDialog = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (defs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            defs.forEach { def ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TemplateFieldCell(
                        def = def,
                        value = values[def.key].orEmpty(),
                        onValueChange = { onValueChange(def.key, it) },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "删除",
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { pendingDelete = def }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showAddDialog) {
        AddCustomFieldDialog(
            existingLabels = defs.map { it.label }.toSet() + templateLabels,
            onDismiss = { showAddDialog = false },
            onConfirm = { def ->
                onDefsChanged(defs + def)
                showAddDialog = false
            },
        )
    }

    pendingDelete?.let { def ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = "删除字段「${def.label}」？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(text = "该字段的定义和已填的值会一并删除（仅影响当前点位）。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onDefsChanged(defs.filter { it.key != def.key })
                    onValueChange(def.key, "")
                    pendingDelete = null
                }) { Text(text = "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/** 9 种可选类型（§3.2 白名单；点位自定义字段与分类模板字段恒非必填，无 required 语义）。 */
private val CUSTOM_FIELD_TYPES = listOf(
    FieldType.TEXT to "文本",
    FieldType.LONG_TEXT to "长文本",
    FieldType.NUMBER to "数字",
    FieldType.MONEY to "金额",
    FieldType.TEL to "电话",
    FieldType.URL to "链接",
    FieldType.DATETIME to "日期时间",
    FieldType.TIME_RANGE to "时间段",
    FieldType.BUSINESS_HOURS to "营业时间",
)

/** 「添加字段」对话框（点位自定义字段与 §3.4 分类模板字段共用）：名称 + 类型单选 chips。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddCustomFieldDialog(
    existingLabels: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (FieldDef) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(FieldType.TEXT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "添加自定义字段", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = NameLimit.clamp(it) },
                    singleLine = true,
                    placeholder = { Text("字段名称（如：奥特曼）", fontSize = 13.sp) },
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "值类型",
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CUSTOM_FIELD_TYPES.forEach { (type, label) ->
                        val on = type == selectedType
                        Text(
                            text = label,
                            fontSize = 12.5.sp,
                            lineHeight = 16.sp,
                            color = if (on) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (on) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable { selectedType = type }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val label = name.trim()
                    if (label in existingLabels) {
                        // §3.2：重名 → Toast + 对话框保持打开（B.25 铁律语义）
                        Toast.makeText(context, "已有同名字段", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    onConfirm(
                        FieldDef(
                            key = FieldSchemaCodec.newCustomFieldKey(),
                            label = label,
                            type = selectedType,
                            required = false,
                        ),
                    )
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
