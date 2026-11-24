package com.visualzyzy.chuxingdazi.data.tencent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.visualzyzy.chuxingdazi.data.prefs.FilterState
import kotlin.math.roundToInt

/**
 * 地图标记位图渲染（§6 S03 标记渲染 + §8.7.6 + §6 S04 标记样式）。
 * SDK 无关（纯 Bitmap 绘制），自百度版原样迁移，仅包名随目录调整。
 *
 * 三种样式（S04 三分段）：
 * - ICON：水滴 pin（宽 26dp 基准 × 大小百分比），分类色填充 + 2dp 白描边 + 白色分类图标
 * - DOT：圆形（直径 16dp 基准 × 百分比），分类色填充 + 白描边
 * - NAME_ONLY：仅名称标签（无图形）
 * 选中态：放大 1.15 倍 + 外圈光晕；名称标签白底 94% / 5dp 圆角 / 10sp 深灰 + 浅灰描边。
 * 缓存 key 含样式/尺寸/选中/标签（§8.7.6 三个易踩点），LruCache 上限 80。
 *
 * 第六轮（2026-11-06）：
 * - 标记图形与名称标签的间距由 14dp 缩短 2/3 → ≈4.7dp（用户反馈 111.jpg）
 * - 名称标签文本框最大显示宽度 = 12 个中文汉字（120dp）；文本更长时在框内滚动显示
 *   （滚动偏移量由 [MapController] 的跑马灯定时器驱动，经 [getMarkerBitmap] 的
 *   labelScrollPx 参数重渲染对应标记）
 */
class MarkerRenderer(context: Context) {

    private val appContext = context.applicationContext
    private val density = appContext.resources.displayMetrics.density

    private val iconCache = mutableMapOf<String, android.graphics.drawable.Drawable?>()
    private val bitmapCache = object : LruCache<String, MarkerBitmap>(80) {}

    /** 标签文本画笔（与 getMarkerBitmap 共用同一 metrics，保证量宽一致）。 */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = 0xFF3D4451.toInt()
    }

    /** 名称标签框最大显示宽度 = 12 个中文汉字（第六轮，2026-11-06）。 */
    val labelMaxWidthPx: Float
        get() = 12f * 10f * density

    /** 标签超出最大宽度时需要滚动的总距离（px）；≤0 表示无需滚动。 */
    fun labelScrollRange(label: String): Float {
        if (label.isBlank()) return 0f
        return (textPaint.measureText(label) - labelMaxWidthPx).coerceAtLeast(0f)
    }

    fun getMarkerBitmap(
        colorHex: String,
        iconKey: String?,
        label: String,
        sizeDp: Int,
        selected: Boolean,
        markerStyle: String = FilterState.STYLE_ICON,
        labelScrollPx: Float = 0f,
    ): MarkerBitmap {
        val sizePx = (sizeDp * density).roundToInt()
        val argb = parseColorSafe(colorHex)
        val stroke = (2 * density).roundToInt().coerceAtLeast(2)
        val labelPad = (4 * density).roundToInt()
        // 第六轮（2026-11-06）：图形与标签的间距缩短 2/3（原 14dp → ≈4.7dp，用户反馈 111.jpg）
        val labelGap = (14f / 3f * density).roundToInt()

        val showLabel = label.isNotBlank()
        val textW = if (showLabel) textPaint.measureText(label) else 0f
        val overLimit = showLabel && textW > labelMaxWidthPx + 0.5f
        val scrollPx = if (overLimit) {
            labelScrollPx.coerceIn(0f, textW - labelMaxWidthPx)
        } else {
            0f
        }
        val visibleLabelW = if (showLabel) kotlin.math.min(textW, labelMaxWidthPx) else 0f
        val labelW = if (showLabel) visibleLabelW.roundToInt() + labelPad * 2 else 0
        val labelH = if (showLabel) (textPaint.textSize + labelPad * 1.4f).roundToInt() else 0

        val sizePxKeyed = (sizeDp * density).roundToInt()
        val key = "$markerStyle${colorHex}_${iconKey}_${sizePxKeyed}_${selected}_$label" +
            if (overLimit) "_s${scrollPx.roundToInt()}" else ""

        bitmapCache.get(key)?.let { return it }

        val graphicW: Int
        val graphicH: Int
        val anchorRatio: Float
        when (markerStyle) {
            FilterState.STYLE_DOT -> {
                graphicW = sizePx + stroke * 2
                graphicH = sizePx + stroke * 2
                anchorRatio = 0.5f
            }
            FilterState.STYLE_NAME_ONLY -> {
                graphicW = 0
                graphicH = 0
                anchorRatio = 0.85f
            }
            else -> { // ICON
                graphicW = sizePx
                graphicH = (sizePx * 34f / 26f).roundToInt()
                anchorRatio = graphicH.toFloat() / (graphicH + (if (showLabel) labelGap + labelH else 0)).coerceAtLeast(1)
            }
        }

        val bitmapW = maxOf(graphicW, labelW) + stroke * 2
        val bitmapH = graphicH + stroke * 2 + (if (showLabel) labelGap + labelH else 0)
        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = bitmapW / 2f
        val top = stroke.toFloat()

        when (markerStyle) {
            FilterState.STYLE_DOT -> {
                val cy = top + sizePx / 2f
                if (selected) {
                    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                    haloPaint.setColor((argb and 0x00FFFFFF) or 0x40000000)
                    canvas.drawCircle(cx, cy, sizePx / 2f + 8.5f * density, haloPaint)
                }
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.FILL_AND_STROKE
                    setColor(argb)
                    strokeWidth = stroke.toFloat()
                }
                canvas.drawCircle(cx, cy, sizePx / 2f, dotPaint)
            }
            FilterState.STYLE_NAME_ONLY -> Unit // 无图形
            else -> { // ICON 水滴
                val headR = sizePx / 2f - stroke
                val headCy = top + graphicH * 0.42f
                if (selected) {
                    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                    haloPaint.setColor((argb and 0x00FFFFFF) or 0x40000000)
                    canvas.drawCircle(cx, headCy, headR + 8.5f * density, haloPaint)
                }
                val path = Path()
                val tipY = top + graphicH
                val headRect = RectF(cx - headR, headCy - headR, cx + headR, headCy + headR)
                path.moveTo(cx, tipY)
                path.lineTo(cx - headR * 0.866f, headCy + headR * 0.5f)
                path.arcTo(headRect, 150f, 240f, false)
                path.close()
                val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.FILL_AND_STROKE
                    setColor(argb)
                    strokeWidth = stroke.toFloat()
                }
                pinPaint.setShadowLayer(2 * density, 0f, 2 * density, 0x33000000)
                canvas.drawPath(path, pinPaint)
                pinPaint.setShadowLayer(0f, 0f, 0f, 0)
                iconKey?.let { key0 ->
                    iconDrawable(key0)?.let { drawable ->
                        val iconSize = (sizePx * 0.42f).roundToInt()
                        val left = (cx - iconSize / 2f).roundToInt()
                        val iconTop = (headCy - iconSize / 2f).roundToInt()
                        drawable.setBounds(left, iconTop, left + iconSize, iconTop + iconSize)
                        drawable.draw(canvas)
                    }
                }
            }
        }

        if (showLabel) {
            val labelTop = when (markerStyle) {
                FilterState.STYLE_DOT -> top + graphicH + labelGap
                FilterState.STYLE_NAME_ONLY -> top
                else -> top + graphicH + labelGap
            }
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0FFFFFF.toInt() }
            val rect = RectF(cx - labelW / 2f, labelTop, cx + labelW / 2f, labelTop + labelH)
            canvas.drawRoundRect(rect, 5 * density, 5 * density, bgPaint)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
                color = 0xFFB9C2CE.toInt()
            }
            canvas.drawRoundRect(rect, 5 * density, 5 * density, borderPaint)
            val textY = labelTop + labelH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            if (overLimit) {
                // 文本超宽：裁剪到标签框内、按 scrollPx 水平偏移绘制（跑马灯，第六轮 2026-11-06）
                val saveCount = canvas.save()
                canvas.clipRect(rect)
                // 文本居中于 cx；要让文本起点在滚动 0 时对齐框左内缘 → 平移量 = textW/2 - visibleW/2 - scroll
                canvas.translate(textW / 2f - visibleLabelW / 2f - scrollPx, 0f)
                canvas.drawText(label, cx, textY, textPaint)
                canvas.restoreToCount(saveCount)
            } else {
                canvas.drawText(label, cx, textY, textPaint)
            }
        }

        val result = MarkerBitmap(bitmap, anchorRatio)
        bitmapCache.put(key, result)
        return result
    }

    /** 聚合数字气泡（§6 S04）：36dp 主色圆 + 白描边 + 白色粗体数量。 */
    fun getClusterBitmap(count: Int): Bitmap {
        val key = "cluster_$count"
        bitmapCache.get(key)?.let { return it.bitmap }
        val sizePx = (36 * density).roundToInt()
        val bitmap = Bitmap.createBitmap(sizePx + 8, sizePx + 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = sizePx / 2f + 4
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL_AND_STROKE
            setColor(0xFF0E7490.toInt())
            strokeWidth = 2.5f * density
        }
        canvas.drawCircle(c, c, sizePx / 2f, fill)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 13 * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textY = c - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(count.toString(), c, textY, textPaint)
        val result = MarkerBitmap(bitmap, 0.5f)
        bitmapCache.put(key, result)
        return result.bitmap
    }

    private fun iconDrawable(iconKey: String): android.graphics.drawable.Drawable? {
        if (iconCache.containsKey(iconKey)) return iconCache[iconKey]
        val id = appContext.resources.getIdentifier("ic_cat_$iconKey", "drawable", appContext.packageName)
        // 第十二轮（2026-11-11）：key 失效（旧数据/旧备份导入）→ 回退定位针，标记不出现"无图标"形态
        val resolvedId = if (id != 0) id
        else appContext.resources.getIdentifier("ic_cat_other_point", "drawable", appContext.packageName)
        val drawable = if (resolvedId != 0) ContextCompat.getDrawable(appContext, resolvedId) else null
        iconCache[iconKey] = drawable
        return drawable
    }

    private fun parseColorSafe(hex: String): Int =
        runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(0xFF64748B.toInt())

    data class MarkerBitmap(val bitmap: Bitmap, val anchorRatio: Float)
}
