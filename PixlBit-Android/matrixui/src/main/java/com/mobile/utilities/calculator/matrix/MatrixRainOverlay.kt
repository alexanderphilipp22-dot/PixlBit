package com.mobile.utilities.calculator.matrix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.floor

private const val SALT_SPEED = 101
private const val SALT_OFFSET = 211
private const val SALT_ROW = 307

private class GlyphAtlas(
    val layouts: List<TextLayoutResult>,
    val advance: Float,
    val lineHeight: Float,
)

@Composable
fun MatrixRainOverlay(
    controller: MatrixRainController,
    modifier: Modifier = Modifier,
    config: MatrixRainConfig = MatrixRainConfig.Default,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val glyphStyle = remember(config.fontFamily, config.fontSizeSp) {
        TextStyle(
            fontFamily = config.fontFamily,
            fontSize = config.fontSizeSp.sp,
            fontWeight = FontWeight.Normal,
        )
    }
    val atlas = remember(measurer, glyphStyle, config.charset) {
        buildGlyphAtlas(measurer, glyphStyle, config.charset)
    }

    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val launchId = controller.launchId.intValue

    LaunchedEffect(launchId, canvasSize, atlas) {
        if (launchId > 0 && canvasSize.width > 0f && canvasSize.height > 0f) {
            controller.decayGlyphs = buildDecayGlyphs(
                measurer = measurer,
                style = glyphStyle,
                sourceText = controller.sourceText,
                sourceBounds = controller.sourceBounds,
                canvasSize = canvasSize,
                densityScale = density.density,
            )
        }
    }

    if (!controller.isRunning) return

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it.toSize() },
    ) {
        val reveal = controller.reveal.value
        if (reveal <= 0f) return@Canvas
        drawMatrixRain(
            atlas = atlas,
            config = config,
            reveal = reveal,
            rainTimeMs = controller.rainTimeMs.floatValue,
            decayGlyphs = controller.decayGlyphs,
            quality = controller.quality.intValue,
        )
    }
}

private fun buildGlyphAtlas(
    measurer: TextMeasurer,
    style: TextStyle,
    charset: String,
): GlyphAtlas {
    var advance = 0f
    var lineHeight = 0f
    val layouts = ArrayList<TextLayoutResult>(charset.length)
    charset.forEach { char ->
        val layout = measurer.measure(text = char.toString(), style = style)
        layouts += layout
        advance = maxOf(advance, layout.size.width.toFloat())
        lineHeight = maxOf(lineHeight, layout.size.height.toFloat())
    }
    return GlyphAtlas(
        layouts = layouts,
        advance = if (advance > 0f) advance else lineHeight * 0.6f,
        lineHeight = if (lineHeight > 0f) lineHeight else 16f,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMatrixRain(
    atlas: GlyphAtlas,
    config: MatrixRainConfig,
    reveal: Float,
    rainTimeMs: Float,
    decayGlyphs: List<DecayGlyph>?,
    quality: Int,
) {
    val backgroundAlpha = MatrixRainMath.backgroundAlpha(
        reveal = reveal,
        decayEnd = config.decayEndFraction,
        fadeStart = config.fadeStartFraction,
    )
    if (backgroundAlpha > 0.001f) {
        drawRect(color = config.backgroundColor, alpha = backgroundAlpha)
    }

    val fadeAlpha = 1f - MatrixRainMath.phaseProgress(reveal, config.fadeStartFraction, 1f)
    if (fadeAlpha <= 0.001f) return

    val rowHeight = atlas.lineHeight * config.lineHeightFactor
    val columnWidth = atlas.advance * config.columnSpacingFactor
    val columnCount = if (columnWidth <= 0f) {
        1
    } else {
        floor(size.width / columnWidth).toInt().coerceIn(1, config.maxColumns)
    }
    val columnStep = size.width / columnCount
    val trailRows = if (quality >= 2) maxOf(4, config.trailRows / 2) else config.trailRows
    val travel = size.height + trailRows * rowHeight
    val rainSeconds = rainTimeMs / 1000f
    val glyphTicks = MatrixRainMath.tick(rainTimeMs, config.glyphMutationMs)
    val charCount = config.charset.length
    val glowActive = config.glow && quality == 0

    for (column in 0 until columnCount) {
        val headY = columnHeadY(column, travel, size.height, rainSeconds, rowHeight, trailRows, config)
        val glyphX = column * columnStep + (columnStep - atlas.advance) / 2f
        for (row in 0 until trailRows) {
            val y = headY - row * rowHeight
            if (y < -rowHeight || y > size.height) continue
            val alpha = MatrixRainMath.trailAlpha(row, trailRows) * fadeAlpha
            if (alpha < 0.02f) continue
            val glyphIndex = glyphIndexFor(column, row, glyphTicks, charCount)
            val isHead = row == 0
            drawText(
                textLayoutResult = atlas.layouts[glyphIndex],
                color = if (isHead) config.headColor else config.bodyColor,
                topLeft = Offset(glyphX, y),
                alpha = alpha,
                shadow = if (isHead && glowActive) {
                    Shadow(color = config.headColor.copy(alpha = 0.75f), blurRadius = config.glowRadius)
                } else {
                    null
                },
            )
        }
    }

    if (!decayGlyphs.isNullOrEmpty()) {
        drawDecayGlyphs(
            atlas = atlas,
            config = config,
            reveal = reveal,
            glyphs = decayGlyphs,
            columnCount = columnCount,
            columnStep = columnStep,
            travel = travel,
            rowHeight = rowHeight,
            trailRows = trailRows,
            rainSeconds = rainSeconds,
            glyphTicks = glyphTicks,
            fadeAlpha = fadeAlpha,
        )
    }
}

private fun columnHeadY(
    column: Int,
    travel: Float,
    height: Float,
    rainSeconds: Float,
    rowHeight: Float,
    trailRows: Int,
    config: MatrixRainConfig,
): Float {
    val offsetUnit = MatrixRainMath.columnUnit(column, SALT_OFFSET)
    val speedUnit = MatrixRainMath.columnUnit(column, SALT_SPEED)
    val rowsPerSecond = config.minSpeedRowsPerSecond +
        (config.maxSpeedRowsPerSecond - config.minSpeedRowsPerSecond) * speedUnit
    val travelPosition = offsetUnit * travel + rainSeconds * rowsPerSecond * rowHeight
    return MatrixRainMath.wrap(travelPosition, travel) - trailRows * rowHeight
}

private fun glyphIndexFor(column: Int, row: Int, ticks: Int, charCount: Int): Int {
    val rowSeed = MatrixRainMath.hash(column, row, SALT_ROW)
    val tick = ticks + (rowSeed and 3)
    return MatrixRainMath.glyphIndex(MatrixRainMath.hash(column, row, tick), charCount)
}

private fun buildDecayGlyphs(
    measurer: TextMeasurer,
    style: TextStyle,
    sourceText: String,
    sourceBounds: Rect?,
    canvasSize: Size,
    densityScale: Float,
): List<DecayGlyph> {
    if (sourceText.isEmpty() || canvasSize.width <= 0f) return emptyList()
    val layout = measurer.measure(text = sourceText, style = style)
    val area = sourceBounds ?: Rect(
        left = canvasSize.width * 0.12f,
        top = canvasSize.height * 0.38f,
        right = canvasSize.width * 0.88f,
        bottom = canvasSize.height * 0.38f + layout.size.height.toFloat() * 1.6f,
    )
    val originX = area.left + (area.width - layout.size.width) / 2f
    val originY = area.top + (area.height - layout.size.height) / 2f

    val glyphs = ArrayList<DecayGlyph>(sourceText.length)
    sourceText.forEachIndexed { index, char ->
        if (char.isWhitespace()) return@forEachIndexed
        val box = try {
            layout.getBoundingBox(index)
        } catch (error: IllegalArgumentException) {
            return@forEachIndexed
        }
        val seed = MatrixRainMath.hash(index, char.code, SALT_ROW)
        val targetUnit = MatrixRainMath.glyphIndex(seed, 1000) / 1000f
        val driftUnit = (MatrixRainMath.glyphIndex(seed, 401) - 200) / 200f
        glyphs += DecayGlyph(
            char = char,
            startX = originX + box.left,
            startY = originY + box.top,
            targetColumnUnit = targetUnit,
            driftX = driftUnit * 26f * densityScale,
            delay = 0.04f + 0.32f * targetUnit,
        )
    }
    return glyphs
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDecayGlyphs(
    atlas: GlyphAtlas,
    config: MatrixRainConfig,
    reveal: Float,
    glyphs: List<DecayGlyph>,
    columnCount: Int,
    columnStep: Float,
    travel: Float,
    rowHeight: Float,
    trailRows: Int,
    rainSeconds: Float,
    glyphTicks: Int,
    fadeAlpha: Float,
) {
    val decayProgress = MatrixRainMath.phaseProgress(reveal, 0f, config.decayEndFraction)
    if (decayProgress >= 1f) return
    val charCount = config.charset.length

    glyphs.forEachIndexed { index, glyph ->
        val local = MatrixRainMath.phaseProgress(decayProgress, glyph.delay, 1f)
        if (local <= 0f) return@forEachIndexed
        val eased = MatrixRainMath.decayEase(local)

        val column = (glyph.targetColumnUnit * columnCount).toInt().coerceIn(0, columnCount - 1)
        val targetX = column * columnStep + (columnStep - atlas.advance) / 2f
        val targetY = columnHeadY(column, travel, size.height, rainSeconds, rowHeight, trailRows, config)

        val x = glyph.startX + (targetX - glyph.startX) * eased + glyph.driftX * (1f - eased)
        val y = glyph.startY + (targetY - glyph.startY) * (eased * eased)

        val glyphIndex = if (local < 0.4f) {
            val originalIndex = config.charset.indexOf(glyph.char)
            if (originalIndex >= 0) originalIndex else MatrixRainMath.glyphIndex(glyph.char.code, charCount)
        } else {
            MatrixRainMath.glyphIndex(MatrixRainMath.hash(index, glyphTicks, SALT_ROW), charCount)
        }

        drawText(
            textLayoutResult = atlas.layouts[glyphIndex],
            color = config.headColor,
            topLeft = Offset(x, y),
            alpha = (1f - 0.2f * eased) * fadeAlpha,
        )
    }
}
