package com.mobile.utilities.calculator.matrix

import androidx.compose.runtime.Immutable

@Immutable
data class UnlockTransitionConfig(
    val camouflageFadeDurationMs: Int = 280,
    val matrixFadeDelayMs: Int = 0,
    val matrixFadeDurationMs: Int = 460,
    val matrixBlurRadiusDp: Float = 14f,
    val blurEnabled: Boolean = true,
    val dashboardFadeDelayMs: Int = 220,
    val dashboardFadeDurationMs: Int = 480,
    val dashboardScaleFrom: Float = 0.92f,
) {
    val matrixFadeEndMs: Int get() = matrixFadeDelayMs + matrixFadeDurationMs

    val dashboardEndMs: Int get() = dashboardFadeDelayMs + dashboardFadeDurationMs

    val totalDurationMs: Int
        get() = maxOf(camouflageFadeDurationMs, matrixFadeEndMs, dashboardEndMs)

    val overlapMs: Int
        get() = UnlockTransitionMath.overlapMs(matrixFadeEndMs, dashboardFadeDelayMs)

    val overlapFraction: Float
        get() = UnlockTransitionMath.overlapFraction(
            matrixFadeStartMs = matrixFadeDelayMs,
            matrixFadeEndMs = matrixFadeEndMs,
            dashboardStartMs = dashboardFadeDelayMs,
        )

    companion object {
        val Default: UnlockTransitionConfig = UnlockTransitionConfig()
    }
}
