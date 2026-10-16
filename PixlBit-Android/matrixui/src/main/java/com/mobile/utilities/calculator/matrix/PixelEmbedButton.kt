package com.mobile.utilities.calculator.matrix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

enum class EmbedStatus { Idle, Working, Done }

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun PixelEmbedButton(
    modifier: Modifier = Modifier,
    label: String = "In Pixel einbetten & Sichern",
    config: MatrixRainConfig = MatrixRainConfig.Default,
    messageProvider: () -> String = { "" },
    preparePayload: suspend (message: String) -> ByteArray,
    onFinished: (ByteArray?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val controller = rememberMatrixRainController(config)
    var status by remember { mutableStateOf(EmbedStatus.Idle) }
    var buttonBounds by remember { mutableStateOf(Rect.Zero) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(config.backgroundColor)
            .onGloballyPositioned { coordinates -> hostOrigin = coordinates.positionInRoot() },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 24.dp, end = 24.dp),
        ) {
            BasicText(
                text = "Calculator",
                style = TextStyle(color = config.bodyColor, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "Schutzstatus: aktiv - Nutzlast wird vor dem Einbetten ver- und entschluesselt.",
                style = TextStyle(color = Color(0xFF9CA3AF), fontSize = 12.sp),
            )
        }

        PixelActionSurface(
            label = if (status == EmbedStatus.Working) "Verschlüsselt…" else label,
            enabled = status != EmbedStatus.Working,
            accentColor = config.bodyColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .fillMaxWidth(),
            onBoundsChanged = { bounds -> buttonBounds = bounds.translate(-hostOrigin.x, -hostOrigin.y) },
            onClick = {
                if (status == EmbedStatus.Working) return@PixelActionSurface
                status = EmbedStatus.Working
                val message = messageProvider()

                val payload = scope.async(Dispatchers.Default) { preparePayload(message) }

                controller.launch(
                    scope = scope,
                    sourceText = label,
                    sourceBounds = buttonBounds,
                    hold = payload,
                ) {
                    val bytes = runCatching { payload.getCompleted() }.getOrNull()
                    status = if (bytes != null) EmbedStatus.Done else EmbedStatus.Idle
                    onFinished(bytes)
                }
            },
        )

        MatrixRainOverlay(controller = controller, config = config)
    }
}

@Composable
private fun PixelActionSurface(
    label: String,
    enabled: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit = {},
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) accentColor else Color(0xFF3F3F46))
            .clickable(enabled = enabled) { onClick() }
            .onGloballyPositioned { coordinates -> onBoundsChanged(coordinates.boundsInRoot()) },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PixelEmbedButtonPreview() {
    PixelEmbedButton(
        messageProvider = { "Geheime Nachricht" },
        preparePayload = { raw ->
            delay(1500L)
            raw.toByteArray(Charsets.UTF_8)
        },
    )
}
