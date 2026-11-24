package com.visualzyzy.chuxingdazi.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.model.ColorMath
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ColorPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val categoryRepository: CategoryRepository,
    placeRepository: PlaceRepository,
) : ViewModel() {

    val categoryId: String = savedStateHandle.get<String>("categoryId").orEmpty()

    /** 草稿模式（第七轮 R15/R16）：S09「＋新建」的未落库分类调色——保存只回传色值，不落库。 */
    val isDraft: Boolean = savedStateHandle.get<String>("draft") == "1"

    /** 草稿初始色（S09 面板当前草稿色），用于初始化 HSV。 */
    val draftInitialHex: String = savedStateHandle.get<String>("initial").orEmpty()

    val allCategories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val placeCountByCategory: StateFlow<Map<String, Int>> =
        placeRepository.observeAll()
            .map { places -> places.groupBy { it.categoryId }.mapValues { it.value.size } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    suspend fun getCategory(id: String): CategoryEntity? = categoryRepository.getById(id)

    fun saveColor(colorHex: String, iconKey: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            categoryRepository.getById(categoryId) ?: return@launch
            categoryRepository.updateAppearance(categoryId, iconKey, colorHex)
            onSaved()
        }
    }
}

/**
 * S18 选择颜色（§6 S18）。
 * 第七轮（2026-11-06）：
 * - R18：删「白色图标对比度 x.x:1，可用/偏低」行（对比度仍隐式计算，供 <2.0 提示用）。
 * - R19：户外提示只看对比度 <2.0，文案「这个颜色在户外强光下可能看不清」（删建议明度/饱和度后缀）。
 * - R21：「已被占用的颜色」→「已被使用的颜色」。
 * - R22：撞色文案 = 「与「X」分类的背景色过于接近，请换一个颜色」（不显示 ΔE 数值）；
 *   删「换一个可用颜色」按钮。
 * - R23：SV 面板与色相条按下即可拖动连续选色（原 detectTapGestures 独占事件循环，拖动从未生效）。
 * - R24：ΔE 判定 CIE76→CIEDE2000，阈值 < 4.5 拒绝（实现过 Sharma 2005 官方 34 对校验）。
 * - 草稿模式（R15/R16 配套）：draft=1 时「保存」只把色值回传 S09 草稿，不落库。
 */
@Composable
fun ColorPickerScreen(
    onClose: () -> Unit,
    onDraftColor: (String) -> Unit = {},
) {
    val viewModel: ColorPickerViewModel = hiltViewModel()
    val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()

    val self = allCategories.firstOrNull { it.id == viewModel.categoryId }
    var hue by remember { mutableStateOf(0f) }
    var sat by remember { mutableStateOf(0.75f) }
    var value by remember { mutableStateOf(0.8f) }
    var initialized by remember { mutableStateOf(false) }

    // 从分类现有颜色（或草稿初始色）初始化 HSV
    LaunchedEffect(self?.id, viewModel.draftInitialHex) {
        val initHex = when {
            self != null -> self.color
            viewModel.isDraft && viewModel.draftInitialHex.isNotBlank() -> viewModel.draftInitialHex
            else -> null
        }
        if (initHex != null) {
            ColorMath.parseOrNull(initHex)?.let { argb ->
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(argb, hsv)
                hue = hsv[0]
                sat = hsv[1]
                value = hsv[2]
            }
        }
        initialized = true
    }

    val currentColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    val currentHex = ColorMath.hexOf(currentColor.toArgb())

    // ΔE 冲突检测（CIEDE2000 < 4.5 拒绝，R24）：排除自身，与其余全部分类比（§6 S18 唯一性规则）
    val conflict = allCategories.firstOrNull { other ->
        other.id != viewModel.categoryId &&
            ColorMath.parseOrNull(other.color)?.let {
                ColorMath.deltaE00(currentColor.toArgb(), it) < 4.5
            } ?: false
    }
    // R18/R19：对比度只做隐式计算，<2.0 才提示（不管明度饱和度）
    val contrast = ColorMath.whiteContrastRatio(currentColor.toArgb())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // 导航：取消 / 选择颜色 / 保存
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
                text = "选择颜色",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "保存",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                // 第十六轮（优化建议）：撞色改为警告——ΔE00<4.5 只提示不禁止保存
                //（分类超过十来个后硬规则会让新建分类无法保存）
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    if (viewModel.isDraft) {
                        // 草稿：色值回传 S09（savedStateHandle），不落库
                        onDraftColor(currentHex)
                    } else {
                        viewModel.saveColor(currentHex, self?.iconKey ?: "other_point") { onClose() }
                    }
                },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // 预览区（§6 S18 布局 2；R18：对比度文字行已删，计算保留）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(currentColor),
                    contentAlignment = Alignment.Center,
                ) {
                    CategoryIconTinted(self?.iconKey ?: "other_point", Color.White, 32.dp)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(text = currentHex, fontSize = 17.sp, fontWeight = FontWeight(650))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // SV 面板（§6 S18 布局 3；R23：按下即可拖动连续选色）
            SvPanel(
                hue = hue,
                sat = sat,
                value = value,
                onSatValChange = { s, v ->
                    sat = s
                    value = v
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .clip(RoundedCornerShape(13.dp)),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 色相条（§6 S18 布局 4；R23：同上）
            HueBar(
                hue = hue,
                onHueChange = { hue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(9.dp)),
            )
            Spacer(modifier = Modifier.height(14.dp))

            // RGB 三格（§6 S18 布局 5）。
            // 每框「聚焦时的本地文本态」：允许空串/中间态（退格删空不回弹——真机实测旧实现
            // 空串被 toIntOrNull 拒绝后 state 回弹，删除无效）；失焦或未聚焦时恒显示 HSV 派生值。
            val r = (currentColor.red * 255).roundToInt()
            val g = (currentColor.green * 255).roundToInt()
            val b = (currentColor.blue * 255).roundToInt()

            @Composable
            fun RgbBox(label: String, component: Int, onComponent: (Int) -> Unit) {
                var localText: String? by remember { mutableStateOf<String?>(null) }
                var focused: Boolean by remember { mutableStateOf(false) }
                val shown: String = if (focused) (localText ?: component.toString()) else component.toString()
                OutlinedTextField(
                    value = shown,
                    onValueChange = { input ->
                        localText = input
                        input.toIntOrNull()?.coerceIn(0, 255)?.let { nv ->
                            onComponent(nv)
                            localText = null // 合法输入已被 HSV 采纳，显示回到派生值
                        }
                    },
                    label = { Text(label, fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RgbBox(label = "R", component = r) { nv ->
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV(nv, g, b, hsv)
                    hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                }
                RgbBox(label = "G", component = g) { nv ->
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV(r, nv, b, hsv)
                    hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                }
                RgbBox(label = "B", component = b) { nv ->
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV(r, g, nv, hsv)
                    hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // R19（第七轮）：对比度 < 2.0 才提示（位于 RGB 行下方），建议非硬拦（§6 S18 可用色域）
            if (contrast < 2.0) {
                Text(
                    text = "这个颜色在户外强光下可能看不清",
                    fontSize = 12.sp,
                    color = Color(0xFF9A6700),
                    lineHeight = 17.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 撞色警告（§6 S18 布局 6；R22 固定文案不显示 ΔE 数值）
            // 第十六轮：从「拒绝保存」降级为警告——与某分类色差低于阈值时提示，仍可直接保存
            if (conflict != null) {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = Color(0x1A9A6700),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "与「${conflict.name}」分类的背景色过于接近，建议换一个颜色（仍可保存）",
                        fontSize = 12.5.sp,
                        color = Color(0xFF9A6700),
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 已被使用的颜色（§6 S18 布局 8；R21：占用→使用）
            Text(text = "已被使用的颜色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                allCategories.filter { it.id != viewModel.categoryId }.forEach { other ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(parseCategoryColorSafe(other.color))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "分类颜色会用于地图标记、筛选面板和表单高亮",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 二维 SV 面板：横轴饱和度 0→1，纵轴明度 1→0。 */
@Composable
private fun SvPanel(
    hue: Float,
    sat: Float,
    value: Float,
    onSatValChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var panelWidth by remember { mutableStateOf(1f) }
    var panelHeight by remember { mutableStateOf(1f) }

    fun colorAt(s: Float, v: Float): Color =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v)))

        Box(
            modifier = modifier
                .onGloballyPositioned {
                    panelWidth = it.size.width.toFloat().coerceAtLeast(1f)
                    panelHeight = it.size.height.toFloat().coerceAtLeast(1f)
                }
                .background(
                    Brush.horizontalGradient(
                        List(13) { i -> colorAt(i / 12f, 1f) },
                    ),
                ),
    ) {
        // 明度渐变（黑 0→顶 1）覆盖
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.White, Color.Black),
                        startY = 0f,
                        endY = panelHeight,
                    ),
                ),
        )
        // 当前点
        Box(
            modifier = Modifier
                .size(18.dp)
                .offset(x = with(LocalDensity.current) { (sat * panelWidth).toDp() - 9.dp })
                .offset(y = with(LocalDensity.current) { ((1 - value) * panelHeight).toDp() - 9.dp })
                .border(2.5.dp, Color.White, CircleShape)
                .border(0.5.dp, Color(0x88000000), CircleShape),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // R23（第七轮）：点击定位 + 按住拖动连续选色。tap 与 drag 必须放在
                // 两个独立 pointerInput 里（同一 pointerInput 内 tap 检测器独占事件循环，
                // drag 永远不注册——这是旧实现拖动无效的根因；而只有 drag 时纯点击又
                // 因 touch slop 不触发 onDragStart，t25 真机实测踩坑）
                .pointerInput(hue) {
                    detectTapGestures { pos ->
                        val s = (pos.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                        onSatValChange(s, v)
                    }
                }
                .pointerInput(hue) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val s = (pos.x / size.width).coerceIn(0f, 1f)
                            val v = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                            onSatValChange(s, v)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val s = (change.position.x / size.width).coerceIn(0f, 1f)
                            val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            onSatValChange(s, v)
                        },
                    )
                },
        )
    }
}

/** 色相条：0–360° 七段渐变 + 当前色相游标。 */
@Composable
private fun HueBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var barWidth by remember { mutableStateOf(1f) }
    Box(
        modifier = modifier
            .onGloballyPositioned { barWidth = it.size.width.toFloat().coerceAtLeast(1f) }
            .background(
                Brush.horizontalGradient(
                    List(13) { i ->
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(i / 12f * 360f, 1f, 1f)))
                    },
                ),
            )
            .pointerInput(Unit) {
                // R23（第七轮）：点击定位（与 SvPanel 同理，tap/drag 分两个 pointerInput）
                detectTapGestures { pos ->
                    onHueChange((pos.x / size.width * 360f).coerceIn(0f, 360f))
                }
            }
            .pointerInput(Unit) {
                // R23（第七轮）：按住拖动连续调色相
                detectDragGestures(
                    onDragStart = { pos -> onHueChange((pos.x / size.width * 360f).coerceIn(0f, 360f)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onHueChange((change.position.x / size.width * 360f).coerceIn(0f, 360f))
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .size(width = 8.dp, height = 26.dp)
                .offset(x = with(LocalDensity.current) { (hue / 360f * barWidth).toDp() - 4.dp })
                .offset(y = with(LocalDensity.current) { ((-4).dp) })
                .clip(RoundedCornerShape(2.dp))
                .border(2.dp, Color.White, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun CategoryIconTinted(iconKey: String, tint: Color, size: androidx.compose.ui.unit.Dp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val id = remember(iconKey) {
        context.resources.getIdentifier("ic_cat_$iconKey", "drawable", context.packageName)
    }
    if (id != 0) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = Modifier.size(size),
        )
    }
}

private fun parseCategoryColorSafe(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748B")) }.getOrDefault(Color(0xFF64748B))
