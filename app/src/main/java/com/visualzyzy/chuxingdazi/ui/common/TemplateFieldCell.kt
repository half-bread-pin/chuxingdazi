package com.visualzyzy.chuxingdazi.ui.common

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.data.model.FieldDef
import com.visualzyzy.chuxingdazi.data.model.FieldType
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * 模板/自定义字段「格子」（字段模板升级 2026-11-21）：label + 按 type 分发的输入控件。
 * 分类模板字段（S08/S05c/S06）与点位自定义字段（§3）共用同一渲染器——表单是数据驱动的。
 *
 * 输入方式（§4，全部点选不手输）：
 * - DATETIME：点击 → SimpleDatePickerDialog 选日期 → 自动续弹 TimeInput 选时刻（两连弹，§4.1）；
 *   展示态 yyyy-MM-dd HH:mm；毫秒数旧值原样展示不丢（保存往返靠编码兜底）。
 * - TIME_RANGE：点击 → TimeInput 选「开始」→ 续弹「结束」，校验起<止（§4.2）。
 * - TEXT/LONG_TEXT：长度上限 200/2000（§2.1），达到上限不再接受输入 + Toast；LONG_TEXT
 *   接近上限显示 n/2000 计数。
 * 其余类型：普通文本框（NUMBER/MONEY 数字键盘、TEL 电话键盘）。
 *
 * 实现注记：DATETIME/TIME_RANGE 的 readOnly 字段上盖一层透明点击层（matchParentSize）——
 * M3 OutlinedTextField 的 interactionSource 在部分版本不吐 press 交互（真机/模拟器实测，
 * 2026-11-21），点击层是版本无关的必达通道，同时避免 readOnly 字段聚焦弹 IME。
 */
@Composable
fun TemplateFieldCell(
    def: FieldDef,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = def.label,
            fontSize = 11.5.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        when (def.type) {
            FieldType.DATETIME -> DatetimeCell(value, onValueChange)
            FieldType.TIME_RANGE -> TimeRangeCell(value, onValueChange)
            FieldType.BUSINESS_HOURS -> BusinessHoursCell(value, onValueChange)
            else -> TextCell(def, value, onValueChange)
        }
    }
}

@Composable
private fun TextCell(def: FieldDef, value: String, onValueChange: (String) -> Unit) {
    val context = LocalContext.current
    val maxLen = when (def.type) {
        FieldType.TEXT -> 200
        FieldType.LONG_TEXT -> 2000
        else -> Int.MAX_VALUE
    }
    val counter = if (def.type == FieldType.LONG_TEXT && value.length >= 1800) {
        "${value.length}/2000"
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = { new ->
            if (new.length > maxLen && value.length <= maxLen) {
                Toast.makeText(
                    context,
                    if (def.type == FieldType.LONG_TEXT) "最多 2000 字" else "最多 200 字",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onValueChange(if (new.length > maxLen) new.take(maxLen) else new)
        },
        singleLine = def.type != FieldType.LONG_TEXT,
        placeholder = {
            Text(
                text = fieldPlaceholder(def.type),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = when (def.type) {
                FieldType.NUMBER, FieldType.MONEY -> KeyboardType.Decimal
                FieldType.TEL -> KeyboardType.Phone
                else -> KeyboardType.Text
            },
        ),
        supportingText = if (counter != null) {
            { Text(text = counter, fontSize = 10.5.sp, lineHeight = 13.sp) }
        } else {
            null
        },
        shape = RoundedCornerShape(11.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** DATETIME / TIME_RANGE 共用的「假输入框」：readOnly 字段做外观，透明层收点击，× 清空。 */
@Composable
internal fun PickFieldBox(
    display: String,
    placeholder: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            placeholder = {
                Text(
                    text = placeholder,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            },
            shape = RoundedCornerShape(11.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick),
        )
        if (display.isNotEmpty()) {
            Text(
                text = "×",
                fontSize = 16.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clickable(onClick = onClear)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatetimeCell(value: String, onValueChange: (String) -> Unit) {
    val display = datetimeDisplayText(value)
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    val (initDate, initMinutes) = parseDatetimeDisplay(display)

    PickFieldBox(
        display = display,
        placeholder = fieldPlaceholder(FieldType.DATETIME),
        onClick = { showDate = true },
        onClear = { onValueChange("") },
    )

    if (showDate) {
        SimpleDatePickerDialog(
            title = "选择日期",
            initialSelected = initDate,
            onDismiss = { showDate = false },
            onConfirm = { d ->
                pendingDate = d
                showDate = false
                showTime = true // 两连弹：日期确认后自动续弹时刻（§4.1）
            },
        )
    }
    if (showTime) {
        val state = rememberTimePickerState(
            initialHour = (initMinutes ?: 9 * 60) / 60,
            initialMinute = (initMinutes ?: 9 * 60) % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = pendingDate ?: return@TextButton
                    onValueChange("%s %02d:%02d".format(date.toString(), state.hour, state.minute))
                    showTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("取消") } },
            text = { TimeInput(state = state) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRangeCell(value: String, onValueChange: (String) -> Unit) {
    val context = LocalContext.current
    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    var startMin by remember { mutableStateOf(-1) }
    val (initStart, initEnd) = parseRange(value)

    PickFieldBox(
        display = value,
        placeholder = fieldPlaceholder(FieldType.TIME_RANGE),
        onClick = { showStart = true },
        onClear = { onValueChange("") },
    )

    if (showStart) {
        TimeInputDialog(
            initialMinutes = initStart ?: 9 * 60,
            onDismiss = { showStart = false },
            onConfirm = { m ->
                startMin = m
                showStart = false
                showEnd = true // 选完开始自动续弹结束（§4.2）
            },
        )
    }
    if (showEnd) {
        TimeInputDialog(
            initialMinutes = initEnd ?: 10 * 60,
            onDismiss = { showEnd = false },
            onConfirm = { m ->
                if (m <= startMin) {
                    // 校验起<止：不关弹窗，保持等待重选（§4.2）
                    Toast.makeText(context, "结束时间需晚于开始时间", Toast.LENGTH_SHORT).show()
                } else {
                    onValueChange(
                        "%02d:%02d-%02d:%02d".format(startMin / 60, startMin % 60, m / 60, m % 60),
                    )
                    showEnd = false
                }
            },
        )
    }
}

/** M3 TimeInput 单弹（S15 同款：AlertDialog + TimeInput，24h 制）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeInputDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = { TimeInput(state = state) },
    )
}

/** 占位文案（三屏原 placeholderOf/placeholderOfAddPoint 收编于此）。 */
fun fieldPlaceholder(type: FieldType): String = when (type) {
    FieldType.MONEY -> "0.00"
    FieldType.TIME_RANGE -> "11:00-21:00"
    FieldType.DATETIME -> "选择日期与时间"
    FieldType.TEL -> "057187912345"
    FieldType.URL -> "https://…"
    FieldType.LONG_TEXT -> "多行描述"
    FieldType.BUSINESS_HOURS -> "设置每周营业时段"
    else -> "选填"
}

private val datetimeDisplayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

/**
 * DATETIME 展示态：毫秒数（编码/旧值）→ "yyyy-MM-dd HH:mm"；其他文本原样（脏值不吞）。
 * ≥12 位纯数字才按毫秒解释，避免把普通短数字误转 1970 年。
 */
fun datetimeDisplayText(value: String): String {
    val t = value.trim()
    if (!t.matches(Regex("\\d{12,}"))) return t
    val millis = t.toLongOrNull() ?: return t
    return datetimeDisplayFormat.format(Date(millis))
}

/** "yyyy-MM-dd HH:mm" → (LocalDate, 当天分钟数)；解析不了返回 (null, null)。 */
private fun parseDatetimeDisplay(display: String): Pair<LocalDate?, Int?> {
    val parts = display.trim().split(" ")
    val date = parts.getOrNull(0)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val minutes = parts.getOrNull(1).hmToMinutes()
    return date to minutes
}

/** "HH:mm-HH:mm" → (开始分钟, 结束分钟)；解析不了返回 (null, null)。 */
private fun parseRange(value: String): Pair<Int?, Int?> {
    val parts = value.trim().split("-")
    if (parts.size != 2) return null to null
    return parts[0].hmToMinutes() to parts[1].hmToMinutes()
}

private fun String?.hmToMinutes(): Int? {
    if (this == null) return null
    val p = split(":")
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}
