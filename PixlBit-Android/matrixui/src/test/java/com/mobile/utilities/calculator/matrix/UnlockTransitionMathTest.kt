package com.mobile.utilities.calculator.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockTransitionMathTest {

    @Test
    fun ueberlappungIstNieNegativ() {
        assertEquals(240, UnlockTransitionMath.overlapMs(matrixFadeEndMs = 460, dashboardStartMs = 220))
        assertEquals(0, UnlockTransitionMath.overlapMs(matrixFadeEndMs = 460, dashboardStartMs = 520))
    }

    @Test
    fun ueberlappungsAnteilLiegtZwischenNullUndEins() {
        assertEquals(
            0.5f,
            UnlockTransitionMath.overlapFraction(matrixFadeStartMs = 0, matrixFadeEndMs = 400, dashboardStartMs = 200),
            0.0001f,
        )
        assertEquals(0f, UnlockTransitionMath.overlapFraction(300, 300, 200), 0.0001f)
        assertEquals(1f, UnlockTransitionMath.overlapFraction(0, 400, 0), 0.0001f)
    }

    @Test
    fun blurRadiusWirdQuantisiertUndBegrenzt() {
        assertEquals(0f, UnlockTransitionMath.quantizeBlurRadius(0f), 0.0001f)
        assertEquals(0f, UnlockTransitionMath.quantizeBlurRadius(0.4f), 0.0001f)
        assertEquals(0f, UnlockTransitionMath.quantizeBlurRadius(Float.NaN), 0.0001f)
        assertEquals(0.5f, UnlockTransitionMath.quantizeBlurRadius(0.5f), 0.0001f)
        assertEquals(0.5f, UnlockTransitionMath.quantizeBlurRadius(0.6f), 0.0001f)
        assertEquals(12f, UnlockTransitionMath.quantizeBlurRadius(12.3f), 0.0001f)
        assertEquals(12.5f, UnlockTransitionMath.quantizeBlurRadius(12.9f), 0.0001f)
    }

    @Test
    fun blurRadiusSteigtMonotonUndBleibtUnterDemEingabewert() {
        var previous = -1f
        for (step in 0..60) {
            val raw = step * 0.5f
            val quantized = UnlockTransitionMath.quantizeBlurRadius(raw)
            assertTrue("Radius faellt: $quantized nach $previous", quantized >= previous)
            assertTrue("Radius $quantized groesser als Eingabe $raw", quantized <= raw)
            previous = quantized
        }
    }

    @Test
    fun deckkraftRampLaeuftVonNullAufEins() {
        assertEquals(0f, UnlockTransitionMath.linearAlpha(0f, 220f, 480f), 0.0001f)
        assertEquals(0f, UnlockTransitionMath.linearAlpha(219f, 220f, 480f), 0.0001f)
        assertEquals(0.5f, UnlockTransitionMath.linearAlpha(460f, 220f, 480f), 0.0001f)
        assertEquals(1f, UnlockTransitionMath.linearAlpha(700f, 220f, 480f), 0.0001f)
        assertEquals(1f, UnlockTransitionMath.linearAlpha(5000f, 220f, 480f), 0.0001f)
        assertEquals(0f, UnlockTransitionMath.linearAlpha(100f, 200f, 0f), 0.0001f)
        assertEquals(1f, UnlockTransitionMath.linearAlpha(300f, 200f, 0f), 0.0001f)
    }

    @Test
    fun skalierungWaechstAufEinhundertProzent() {
        assertEquals(0.92f, UnlockTransitionMath.scaleAt(0.92f, 0f), 0.0001f)
        assertEquals(0.96f, UnlockTransitionMath.scaleAt(0.92f, 0.5f), 0.0001f)
        assertEquals(1f, UnlockTransitionMath.scaleAt(0.92f, 1f), 0.0001f)
        assertEquals(0.92f, UnlockTransitionMath.scaleAt(0.92f, -3f), 0.0001f)
        assertEquals(1f, UnlockTransitionMath.scaleAt(0.92f, 4f), 0.0001f)
    }

    @Test
    fun standardKonfigurationErfuelltDieAnforderungen() {
        val config = UnlockTransitionConfig.Default

        assertTrue("Gesamtdauer ${config.totalDurationMs} ms", config.totalDurationMs in 600..800)
        assertEquals(
            "Gesamtdauer ist nicht der Endpunkt aller Ebenen",
            config.dashboardEndMs,
            config.totalDurationMs,
        )

        assertTrue("Ueberlappung ${config.overlapMs} ms", config.overlapMs in 120..400)
        assertTrue("Anteil ${config.overlapFraction}", config.overlapFraction in 0.3f..0.8f)
        assertTrue(UnlockTransitionMath.hasCinematicOverlap(config))

        assertTrue(config.matrixFadeDelayMs < config.dashboardFadeDelayMs)
        assertTrue(config.matrixFadeEndMs > config.dashboardFadeDelayMs)
        assertTrue(config.matrixBlurRadiusDp in 4f..20f)
        assertTrue(config.dashboardScaleFrom in 0.85f..0.98f)
        assertTrue(config.camouflageFadeDurationMs <= config.matrixFadeEndMs)
    }

    @Test
    fun konfigurationOhneUeberlappungWirdErkannt() {
        val harterCut = UnlockTransitionConfig(
            matrixFadeDelayMs = 0,
            matrixFadeDurationMs = 300,
            dashboardFadeDelayMs = 300,
            dashboardFadeDurationMs = 300,
        )
        assertFalse(UnlockTransitionMath.hasCinematicOverlap(harterCut))
        assertEquals(0, harterCut.overlapMs)
        assertTrue(UnlockTransitionMath.hasCinematicOverlap(UnlockTransitionConfig.Default))
    }
}
