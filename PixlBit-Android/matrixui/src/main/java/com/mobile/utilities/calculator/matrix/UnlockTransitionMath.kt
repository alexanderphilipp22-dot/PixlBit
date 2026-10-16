package com.mobile.utilities.calculator.matrix

internal object UnlockTransitionMath {

    const val BLUR_MIN_PX: Float = 0.5f

    private const val BLUR_QUANTIZATION_STEPS_PER_PX: Float = 2f

    fun overlapMs(matrixFadeEndMs: Int, dashboardStartMs: Int): Int =
        (matrixFadeEndMs - dashboardStartMs).coerceAtLeast(0)

    fun overlapFraction(matrixFadeStartMs: Int, matrixFadeEndMs: Int, dashboardStartMs: Int): Float {
        val fadeLength = matrixFadeEndMs - matrixFadeStartMs
        if (fadeLength <= 0) return 0f
        return (overlapMs(matrixFadeEndMs, dashboardStartMs).toFloat() / fadeLength).coerceIn(0f, 1f)
    }

    fun quantizeBlurRadius(radiusPx: Float): Float {
        if (radiusPx.isNaN() || radiusPx < BLUR_MIN_PX) return 0f
        val quantized = (radiusPx * BLUR_QUANTIZATION_STEPS_PER_PX).toInt() / BLUR_QUANTIZATION_STEPS_PER_PX
        return if (quantized < BLUR_MIN_PX) 0f else quantized
    }

    fun linearAlpha(elapsedMs: Float, startMs: Float, durationMs: Float): Float {
        if (durationMs <= 0f) return if (elapsedMs >= startMs) 1f else 0f
        return ((elapsedMs - startMs) / durationMs).coerceIn(0f, 1f)
    }

    fun scaleAt(from: Float, progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return from + (1f - from) * t
    }

    fun hasCinematicOverlap(config: UnlockTransitionConfig): Boolean =
        overlapFraction(
            matrixFadeStartMs = config.matrixFadeDelayMs,
            matrixFadeEndMs = config.matrixFadeEndMs,
            dashboardStartMs = config.dashboardFadeDelayMs,
        ) >= MIN_OVERLAP_FRACTION

    const val MIN_OVERLAP_FRACTION: Float = 0.25f
}
