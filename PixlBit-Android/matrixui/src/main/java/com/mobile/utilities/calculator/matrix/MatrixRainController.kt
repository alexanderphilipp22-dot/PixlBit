package com.mobile.utilities.calculator.matrix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class DecayGlyph(
    val char: Char,
    val startX: Float,
    val startY: Float,
    val targetColumnUnit: Float,
    val driftX: Float,
    val delay: Float,
)

@Stable
class MatrixRainController internal constructor(
    private val config: MatrixRainConfig,
) {

    internal val reveal: Animatable<Float, AnimationVector1D> = Animatable(0f)

    internal val rainTimeMs = mutableFloatStateOf(0f)

    internal val quality = mutableIntStateOf(0)

    internal val launchId = mutableIntStateOf(0)

    internal var sourceText by mutableStateOf("")
        private set

    internal var sourceBounds by mutableStateOf<Rect?>(null)
        private set

    internal var decayGlyphs by mutableStateOf<List<DecayGlyph>?>(null)

    var isRunning by mutableStateOf(false)
        private set

    var phase by mutableStateOf(MatrixRainPhase.Idle)
        private set

    private var job: Job? = null

    fun launch(
        scope: CoroutineScope,
        sourceText: String = "",
        sourceBounds: Rect? = null,
        hold: Deferred<*>? = null,
        onFadeStart: (() -> Unit)? = null,
        onFinished: () -> Unit = {},
    ) {
        if (isRunning) return
        isRunning = true
        phase = MatrixRainPhase.Decaying
        this.sourceText = sourceText
        this.sourceBounds = sourceBounds
        decayGlyphs = null
        launchId.intValue = launchId.intValue + 1

        job = scope.launch {
            rainTimeMs.floatValue = 0f
            quality.intValue = 0
            reveal.snapTo(0f)
            val clock = launch { runRainClock() }
            try {
                reveal.animateTo(
                    targetValue = config.decayEndFraction,
                    animationSpec = tween(config.decayDurationMs, easing = LinearEasing),
                )
                phase = MatrixRainPhase.Raining
                reveal.animateTo(
                    targetValue = config.fadeStartFraction,
                    animationSpec = tween(config.rainDurationMs, easing = LinearEasing),
                )
                runCatching { hold?.await() }
                phase = MatrixRainPhase.Fading
                onFadeStart?.invoke()
                reveal.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(config.fadeDurationMs, easing = LinearEasing),
                )
            } finally {
                clock.cancel()
                decayGlyphs = null
                isRunning = false
                phase = MatrixRainPhase.Idle
                onFinished()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        decayGlyphs = null
        isRunning = false
        phase = MatrixRainPhase.Idle
    }

    private suspend fun runRainClock() {
        var lastNanos = 0L
        var accumulatedMs = 0f
        val frameIntervalMs = 1000f / config.targetFps

        while (currentCoroutineContext().isActive) {
            withFrameNanos { nowNanos ->
                if (lastNanos != 0L) {
                    val deltaMs = (nowNanos - lastNanos) / 1_000_000f
                    accumulatedMs += deltaMs
                    if (accumulatedMs >= frameIntervalMs) {
                        if (accumulatedMs > frameIntervalMs * 2.5f && quality.intValue < 2) {
                            quality.intValue = quality.intValue + 1
                        }
                        rainTimeMs.floatValue += accumulatedMs
                        accumulatedMs = 0f
                    }
                }
                lastNanos = nowNanos
            }
        }
    }
}

@Composable
fun rememberMatrixRainController(
    config: MatrixRainConfig = MatrixRainConfig.Default,
): MatrixRainController = remember(config) { MatrixRainController(config) }
