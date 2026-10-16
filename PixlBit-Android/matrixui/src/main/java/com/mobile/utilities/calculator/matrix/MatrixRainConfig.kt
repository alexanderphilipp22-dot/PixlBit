package com.mobile.utilities.calculator.matrix

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

const val MATRIX_RAIN_CHARSET: String =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
        "\u00C4\u00D6\u00DC\u00E4\u00F6\u00FC\u00DF\u00C6\u00D8\u00C5" +
        "\u0410\u0411\u0412\u0413\u0414\u0415\u0401\u0416\u0417\u0418\u0419\u041A\u041B\u041C" +
        "\u041D\u041E\u041F\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042A" +
        "\u042B\u042C\u042D\u042E\u042F" +
        "\u0430\u0431\u0432\u0433\u0434\u0435\u0451\u0436\u0437\u0438\u0439\u043A\u043B\u043C" +
        "\u043D\u043E\u043F\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044A" +
        "\u044B\u044C\u044D\u044E\u044F" +
        "\u0391\u0392\u0393\u0394\u0395\u0396\u0397\u0398\u0399\u039A\u039B\u039C\u039D\u039E" +
        "\u039F\u03A0\u03A1\u03A3\u03A4\u03A5\u03A6\u03A7\u03A8\u03A9\u03E0" +
        "#\u00A7\$%&+-\u00D7\u00F7=\u2260<>[]{}(),.;:/\\|*~^\u221A\u00B1\u00B6\u2020\u2021\u25CA\u2206\u00AC\u00B7"

enum class MatrixRainPhase {
    Idle,

    Decaying,

    Raining,

    Fading,
}

@Immutable
data class MatrixRainConfig(
    val charset: String = MATRIX_RAIN_CHARSET,
    val fontFamily: FontFamily = FontFamily.Monospace,
    val fontSizeSp: Float = 16f,
    val columnSpacingFactor: Float = 0.92f,
    val lineHeightFactor: Float = 1.02f,
    val trailRows: Int = 14,
    val maxColumns: Int = 48,
    val minSpeedRowsPerSecond: Float = 9f,
    val maxSpeedRowsPerSecond: Float = 26f,
    val glyphMutationMs: Int = 90,
    val headColor: Color = Color(0xFFD9FFB0),
    val bodyColor: Color = Color(0xFFA3E635),
    val backgroundColor: Color = Color.Black,
    val glow: Boolean = true,
    val glowRadius: Float = 6f,
    val decayDurationMs: Int = 700,
    val rainDurationMs: Int = 2200,
    val fadeDurationMs: Int = 520,
    val targetFps: Int = 60,
) {
    val totalDurationMs: Int get() = decayDurationMs + rainDurationMs + fadeDurationMs

    val decayEndFraction: Float
        get() = if (totalDurationMs == 0) 0f else decayDurationMs.toFloat() / totalDurationMs

    val fadeStartFraction: Float
        get() = if (totalDurationMs == 0) 1f else (decayDurationMs + rainDurationMs).toFloat() / totalDurationMs

    companion object {
        val Default: MatrixRainConfig = MatrixRainConfig()
    }
}
