package com.visualzyzy.chuxingdazi.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity

/**
 * 分类横排选择（第十一轮 2026-11-11 从 S06 卡片提为公共组件）：
 * S06 添加卡片 / S05b 添加卡片 / S05 批量添加弹框共用同一形态——
 * 56dp 方块（分类图标 + 名）单行横滚 + 行末可选「＋新建」块（§6 S06，P12 排序由调用方保证）。
 * 第十六轮：wrap=true 改为可换行流式布局（S05 批量层用——默认分类「其他」不再被横滑藏到屏外）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CategorySelectorRow(
    categories: List<CategoryEntity>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onNewCategory: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    wrap: Boolean = false,
) {
    val squares = @Composable {
        categories.forEach { category ->
            CategorySquare(
                category = category,
                selected = category.id == selectedId,
                onClick = { onSelect(category.id) },
            )
        }
        if (onNewCategory != null) {
            NewCategorySquare(onClick = onNewCategory)
        }
    }
    if (wrap) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier,
        ) {
            squares()
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.horizontalScroll(scrollState),
        ) {
            squares()
        }
    }
}

/** 行末「＋新建分类」块（S06 原样式：主色描边 + 8% 浅底 + 加号圆点）。 */
@Composable
fun NewCategorySquare(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(11.dp))
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(11.dp),
            )
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "＋",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "新建",
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** 单个分类方块（56dp，图标恒按分类色 tint，选中 = 分类色 2dp 描边 + 8% 底；§6 S06 原样式）。
 *  2026-11-19 用户定调：图标线条颜色与分类颜色一致（对齐 S08 编辑点位视觉），不再只在选中时着色。 */
@Composable
fun CategorySquare(
    category: CategoryEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = parseCategoryColorSafe(category.color)
    // 第十二轮（2026-11-11）：引用已删除图标的分类（旧数据/旧备份导入）→ 回退定位针，
    // 方块形态（图标+名）与内置分类完全一致，不再塌陷成纯文字块（用户反馈 111.jpg）
    val effectiveIconKey = if (com.visualzyzy.chuxingdazi.data.model.IconCatalog.contains(category.iconKey)) {
        category.iconKey
    } else {
        "other_point"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(11.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) color else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(11.dp),
            )
            .background(if (selected) color.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        CategoryIconTinted(effectiveIconKey, 22.dp, color)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = category.name,
            fontSize = 10.5.sp,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 分类横排「滚到选中项」的目标偏移（2026-11-19 新增）：
 * 进入添加卡片时默认分类 = 其他（横排第 7 格，屏外看不见）——首次加载后把选中方块滚进视野，
 * 让「默认分类是其他」一眼可见。返回 null = 选中项不存在或已在视野起点（index 0）无需滚动。
 */
fun categoryRowScrollTargetPx(
    categories: List<CategoryEntity>,
    selectedId: String,
    density: androidx.compose.ui.unit.Density,
): Int? {
    val index = categories.indexOfFirst { it.id == selectedId }
    if (index <= 0) return null
    // 方块 56dp + 间距 8dp = 64dp 步进；目标 = 选中项前一格（选中项落在视野左缘第 2 格）
    val pitch = with(density) { (56 + 8).dp.toPx() }
    return ((index - 1) * pitch).toInt()
}
