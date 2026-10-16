package com.mobile.utilities.calculator.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixRainMathTest {

    @Test
    fun hashIstDeterministisch() {
        assertEquals(MatrixRainMath.hash(7, 13, 42), MatrixRainMath.hash(7, 13, 42))
        assertNotEquals(MatrixRainMath.hash(7, 13, 42), MatrixRainMath.hash(7, 13, 43))
        assertNotEquals(MatrixRainMath.hash(1, 2, 3), MatrixRainMath.hash(3, 2, 1))
    }

    @Test
    fun glyphIndexLiegtImZeichensatz() {
        val size = MATRIX_RAIN_CHARSET.length
        for (column in 0 until 64) {
            for (tick in 0 until 25) {
                val index = MatrixRainMath.glyphIndex(MatrixRainMath.hash(column, 5, tick), size)
                assertTrue("Index $index ausserhalb von 0..$size", index in 0 until size)
            }
        }
    }

    @Test
    fun glyphIndexStreutUeberDenZeichensatz() {
        val size = MATRIX_RAIN_CHARSET.length
        val getroffen = HashSet<Int>()
        repeat(2000) { i -> getroffen.add(MatrixRainMath.glyphIndex(MatrixRainMath.hash(i, 99, i * 7), size)) }
        assertTrue("Nur ${getroffen.size} von $size Zeichen erreicht", getroffen.size > size / 4)
    }

    @Test
    fun schweifDeckkraftFaelltMonoton() {
        val trail = 14
        assertEquals(1f, MatrixRainMath.trailAlpha(0, trail), 0.0001f)
        assertEquals(0f, MatrixRainMath.trailAlpha(trail, trail), 0.0001f)
        var previous = 2f
        for (row in 0..trail) {
            val alpha = MatrixRainMath.trailAlpha(row, trail)
            assertTrue("Deckkraft steigt bei Zeile $row", alpha <= previous)
            assertTrue(alpha in 0f..1f)
            previous = alpha
        }
    }

    @Test
    fun wrapHaeltWerteImBereich() {
        val range = 1080f
        listOf(-5000f, -1f, 0f, 1f, 1079f, 1080f, 1081f, 25000f).forEach { value ->
            val wrapped = MatrixRainMath.wrap(value, range)
            assertTrue("wrap($value) = $wrapped", wrapped >= 0f && wrapped < range)
        }
        assertEquals(0f, MatrixRainMath.wrap(2160f, range), 0.0001f)
    }

    @Test
    fun decayEaseIstSchnellUndSanft() {
        assertEquals(0f, MatrixRainMath.decayEase(0f), 0.0001f)
        assertEquals(1f, MatrixRainMath.decayEase(1f), 0.0001f)
        assertTrue(MatrixRainMath.decayEase(0.5f) > 0.8f)
        assertEquals(1f, MatrixRainMath.decayEase(2f), 0.0001f)
        assertEquals(0f, MatrixRainMath.decayEase(-2f), 0.0001f)
    }

    @Test
    fun hintergrundBlendetEinUndAus() {
        val decayEnd = 0.2f
        val fadeStart = 0.85f
        assertEquals(0f, MatrixRainMath.backgroundAlpha(0f, decayEnd, fadeStart), 0.0001f)
        assertEquals(1f, MatrixRainMath.backgroundAlpha(0.5f, decayEnd, fadeStart), 0.0001f)
        assertEquals(1f, MatrixRainMath.backgroundAlpha(fadeStart, decayEnd, fadeStart), 0.0001f)
        assertEquals(0f, MatrixRainMath.backgroundAlpha(1f, decayEnd, fadeStart), 0.0001f)
        assertTrue(MatrixRainMath.backgroundAlpha(0.95f, decayEnd, fadeStart) < 0.5f)
    }

    @Test
    fun phasenFortschrittIstBegrenzt() {
        assertEquals(0f, MatrixRainMath.phaseProgress(0.1f, 0.2f, 0.8f), 0.0001f)
        assertEquals(0.5f, MatrixRainMath.phaseProgress(0.5f, 0.2f, 0.8f), 0.0001f)
        assertEquals(1f, MatrixRainMath.phaseProgress(0.9f, 0.2f, 0.8f), 0.0001f)
        assertEquals(0f, MatrixRainMath.phaseProgress(0.5f, 0.3f, 0.3f), 0.0001f)
    }

    @Test
    fun spaltenParameterSindStabilUndVerschieden() {
        val werte = (0 until 48).map { column -> MatrixRainMath.columnUnit(column, 17) }
        werte.forEach { assertTrue(it in 0f..1f) }
        assertEquals(werte[7], MatrixRainMath.columnUnit(7, 17), 0.0f)
        assertTrue("Spalten starten identisch", werte.distinct().size > 8)
    }

    @Test
    fun tickZaehltInSchritten() {
        assertEquals(0, MatrixRainMath.tick(0f, 90))
        assertEquals(0, MatrixRainMath.tick(89f, 90))
        assertEquals(1, MatrixRainMath.tick(90f, 90))
        assertEquals(11, MatrixRainMath.tick(1000f, 90))
        assertEquals(0, MatrixRainMath.tick(1000f, 0))
    }

    @Test
    fun konfigurationHatSinnvolleZeiten() {
        val config = MatrixRainConfig.Default
        assertEquals(config.decayDurationMs + config.rainDurationMs + config.fadeDurationMs, config.totalDurationMs)
        assertTrue("Gesamtdauer ${config.totalDurationMs} ms", config.totalDurationMs in 2500..4000)
        assertTrue(config.decayEndFraction in 0.05f..0.5f)
        assertTrue(config.fadeStartFraction in config.decayEndFraction..1f)
        assertTrue(config.glyphMutationMs > 0)
        assertTrue(config.targetFps in 24..144)
        assertTrue(config.maxColumns in 16..128)
    }

    @Test
    fun zeichensatzDecktAlleGefordertenAlphabeteAb() {
        val charset = MATRIX_RAIN_CHARSET
        listOf('0', '1', 'A', 'Z', 'X', '\u00C4', '\u00DF', '\u00C6', '\u00A7', '#', '\u0416', '\u0414', '\u03E0')
            .forEach { glyph -> assertTrue("Zeichensatz enthaelt '$glyph' nicht", charset.contains(glyph)) }
    }

    @Test
    fun zeichensatzIstDichtUndOhneSurrogatPaare() {
        val charset = MATRIX_RAIN_CHARSET
        assertTrue("Zeichensatz zu klein: ${charset.length}", charset.length >= 150)
        assertEquals("Zeichensatz enthaelt Duplikate", charset.length, charset.toSet().size)
        charset.forEach { char ->
            assertTrue("Surrogat gefunden: ${char.code}", !char.isSurrogate())
        }
    }
}
