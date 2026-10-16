package com.mobile.utilities.calculator.matrix

internal object MatrixRainMath {

    fun hash(a: Int, b: Int, c: Int): Int {
        var h = a * -1640531527 // 0x9E3779B1
        h = h xor (b * -2048144789) // 0x85EBCA6B
        h = h xor (c * -1028477387) // 0xC2B2AE35
        h = h xor (h ushr 16)
        h *= 0x7FEB352D
        h = h xor (h ushr 15)
        h *= 0x846CA68B.toInt()
        h = h xor (h ushr 16)
        return h
    }

    fun glyphIndex(seed: Int, charsetSize: Int): Int {
        require(charsetSize > 0) { "Zeichensatz darf nicht leer sein." }
        return (seed and Int.MAX_VALUE) % charsetSize
    }

    fun trailAlpha(row: Int, trailRows: Int): Float {
        if (row <= 0) return 1f
        if (trailRows <= 1 || row >= trailRows) return 0f
        val life = 1f - row.toFloat() / trailRows
        return life * life * 0.85f
    }

    fun wrap(value: Float, range: Float): Float {
        if (range <= 0f) return 0f
        val v = value % range
        return if (v < 0f) v + range else v
    }

    fun decayEase(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val inv = 1f - x
        return 1f - inv * inv * inv
    }

    fun backgroundAlpha(reveal: Float, decayEnd: Float, fadeStart: Float): Float {
        val r = reveal.coerceIn(0f, 1f)
        val inAlpha = if (decayEnd <= 0f) 1f else (r / decayEnd).coerceIn(0f, 1f)
        val outAlpha = if (fadeStart >= 1f) 1f else ((1f - r) / (1f - fadeStart)).coerceIn(0f, 1f)
        return minOf(inAlpha, outAlpha)
    }

    fun phaseProgress(reveal: Float, start: Float, end: Float): Float {
        if (end <= start) return 0f
        return ((reveal - start) / (end - start)).coerceIn(0f, 1f)
    }

    fun columnUnit(column: Int, salt: Int): Float {
        return glyphIndex(hash(column, salt, column * 31), 4096) / 4095f
    }

    fun tick(elapsedMs: Float, tickMs: Int): Int {
        if (tickMs <= 0) return 0
        return (elapsedMs / tickMs).toInt()
    }
}
