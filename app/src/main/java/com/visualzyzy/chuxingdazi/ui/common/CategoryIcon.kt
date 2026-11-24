package com.visualzyzy.chuxingdazi.ui.common

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

/**
 * 分类图标（第十一轮 2026-11-11 提为公共组件）：
 * 按 key 动态加载 `ic_cat_{key}` VectorDrawable（白色单色，叠分类色块用）。
 * tint 非空时着色（浅背景可见）；key 失效（图标已删除）时整格不画（P109 既定行为）。
 */
@Composable
fun CategoryIconTinted(iconKey: String?, size: Dp, tint: Color?) {
    val context = LocalContext.current
    val id = remember(iconKey) {
        iconKey?.let { context.resources.getIdentifier("ic_cat_$it", "drawable", context.packageName) } ?: 0
    }
    if (id != 0) {
        Image(
            painter = painterResource(id),
            contentDescription = null,
            colorFilter = tint?.let { ColorFilter.tint(it) },
            modifier = Modifier.size(size),
        )
    }
}

/** 分类色解析（共享；非法/空值回退默认灰 #64748B）。 */
fun parseCategoryColorSafe(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748B")) }
        .getOrDefault(Color(0xFF64748B))
