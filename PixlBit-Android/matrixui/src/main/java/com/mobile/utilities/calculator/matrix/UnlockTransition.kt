package com.mobile.utilities.calculator.matrix

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val GATE_MARGIN_MS = 60

@Composable
fun MatrixUnlockTransition(
    unlocked: Boolean,
    modifier: Modifier = Modifier,
    config: UnlockTransitionConfig = UnlockTransitionConfig.Default,
    matrixOverlay: (@Composable () -> Unit)? = null,
    camouflage: @Composable BoxScope.() -> Unit,
    dashboard: @Composable BoxScope.() -> Unit,
) {
    val transition = updateTransition(targetState = unlocked, label = "MatrixUnlockTransition")

    val camouflageAlpha: State<Float> = transition.animateFloat(
        transitionSpec = { tween(config.camouflageFadeDurationMs, easing = LinearEasing) },
        label = "camouflageAlpha",
    ) { target -> if (target) 0f else 1f }

    val matrixAlpha: State<Float> = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(
                    durationMillis = config.matrixFadeDurationMs,
                    delayMillis = config.matrixFadeDelayMs,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(config.camouflageFadeDurationMs, easing = LinearEasing)
            }
        },
        label = "matrixAlpha",
    ) { target -> if (target) 0f else 1f }

    val matrixBlurDp: State<Float> = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(
                    durationMillis = config.matrixFadeDurationMs / 2,
                    delayMillis = config.matrixFadeDelayMs,
                    easing = FastOutSlowInEasing,
                )
            } else {
                tween(config.camouflageFadeDurationMs, easing = LinearEasing)
            }
        },
        label = "matrixBlurDp",
    ) { target -> if (target) config.matrixBlurRadiusDp else 0f }

    val dashboardAlpha: State<Float> = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(
                    durationMillis = config.dashboardFadeDurationMs,
                    delayMillis = config.dashboardFadeDelayMs,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(config.camouflageFadeDurationMs, easing = LinearEasing)
            }
        },
        label = "dashboardAlpha",
    ) { target -> if (target) 1f else 0f }

    val dashboardScale: State<Float> = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(
                    durationMillis = config.dashboardFadeDurationMs,
                    delayMillis = config.dashboardFadeDelayMs,
                    easing = FastOutSlowInEasing,
                )
            } else {
                tween(config.camouflageFadeDurationMs, easing = LinearEasing)
            }
        },
        label = "dashboardScale",
    ) { target -> if (target) 1f else config.dashboardScaleFrom }

    var camouflageComposed by remember { mutableStateOf(!unlocked) }
    var dashboardComposed by remember { mutableStateOf(unlocked) }

    LaunchedEffect(unlocked) {
        val gateDelayMs = (config.totalDurationMs + GATE_MARGIN_MS).toLong()
        if (unlocked) {
            dashboardComposed = true
            delay(gateDelayMs)
            camouflageComposed = false
        } else {
            camouflageComposed = true
            delay(gateDelayMs)
            dashboardComposed = false
        }
    }

    val density = LocalDensity.current.density
    val blurCache = remember { BlurEffectCache() }

    Box(modifier = modifier) {
        if (camouflageComposed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = camouflageAlpha.value },
                content = camouflage,
            )
        }

        if (dashboardComposed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = dashboardAlpha.value
                        val scale = dashboardScale.value
                        scaleX = scale
                        scaleY = scale
                    },
                content = dashboard,
            )
        }

        if (matrixOverlay != null) {
            // Lokale Kopie: macht den Smart-Cast auch in der Lambda klar.
            val overlay = matrixOverlay
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = matrixAlpha.value
                        renderEffect = if (config.blurEnabled) {
                            blurCache.effectFor(matrixBlurDp.value * density)
                        } else {
                            null
                        }
                    },
                content = { overlay() },
            )
        }
    }
}

private class BlurEffectCache {
    private var cachedRadiusPx = -1f
    private var cachedEffect: RenderEffect? = null

    fun effectFor(radiusPx: Float): RenderEffect? {
        val quantized = UnlockTransitionMath.quantizeBlurRadius(radiusPx)
        if (quantized <= 0f) return null
        if (cachedEffect == null || quantized != cachedRadiusPx) {
            cachedRadiusPx = quantized
            cachedEffect = BlurEffect(quantized, quantized, TileMode.Clamp)
        }
        return cachedEffect
    }
}

@Composable
fun MatrixRainUnlockTransition(
    unlocked: Boolean,
    controller: MatrixRainController,
    modifier: Modifier = Modifier,
    config: UnlockTransitionConfig = UnlockTransitionConfig.Default,
    rainConfig: MatrixRainConfig = MatrixRainConfig.Default,
    camouflage: @Composable BoxScope.() -> Unit,
    dashboard: @Composable BoxScope.() -> Unit,
) {
    MatrixUnlockTransition(
        unlocked = unlocked,
        modifier = modifier,
        config = config,
        matrixOverlay = { MatrixRainOverlay(controller = controller, config = rainConfig) },
        camouflage = camouflage,
        dashboard = dashboard,
    )
}

@Composable
private fun PreviewCamouflage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1C1E)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            BasicText(
                text = "0",
                style = TextStyle(color = Color.White, fontSize = 44.sp),
            )
            Spacer(Modifier.height(24.dp))
            listOf("AC  ±  %  ÷", "7  8  9  ×", "4  5  6  −", "1  2  3  +").forEach { row ->
                BasicText(
                    text = row,
                    style = TextStyle(color = Color(0xFFD4D4D8), fontSize = 18.sp),
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun PreviewDashboard() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = "Calculator",
                style = TextStyle(
                    color = Color(0xFFA3E635),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "Hauptmenü · Verschlüsseln / Entschlüsseln",
                style = TextStyle(color = Color(0xFF9CA3AF), fontSize = 13.sp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun MatrixRainUnlockTransitionPreview() {
    val controller = rememberMatrixRainController()
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(600)
        controller.launch(
            scope = scope,
            sourceText = "√  =  0",
            onFadeStart = { unlocked = true },
        )
    }

    MatrixRainUnlockTransition(
        unlocked = unlocked,
        controller = controller,
        camouflage = { PreviewCamouflage() },
        dashboard = { PreviewDashboard() },
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun MatrixUnlockTransitionDashboardPreview() {
    MatrixUnlockTransition(
        unlocked = true,
        camouflage = { PreviewCamouflage() },
        dashboard = { PreviewDashboard() },
    )
}
