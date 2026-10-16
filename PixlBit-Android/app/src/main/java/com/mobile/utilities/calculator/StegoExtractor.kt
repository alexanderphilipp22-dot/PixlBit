package com.mobile.utilities.calculator

import android.graphics.Bitmap
import android.util.Log
import java.util.BitSet

object StegoExtractor {

    private const val TAG = "CalculatorStego"

    private const val MAGIC_0 = 0x50
    private const val MAGIC_1 = 0x42
    private const val VERSION = 0x02
    private const val MODE_RGB = 0
    private const val MODE_GRAY = 1

    private const val HEADER_BYTES = 10
    private const val HEADER_BITS = HEADER_BYTES * 8
    private const val MAGIC_BITS = 40
    private const val LEGACY_HEADER_BITS = 32

    data class Result(
        val payloadBase64: String?,
        val mode: Int,
        val legacyFormat: Boolean,
        val crcOk: Boolean,
        val reason: String? = null,
    ) {
        val valid: Boolean get() = payloadBase64 != null
    }

    fun extract(bitmap: Bitmap): Result {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return Result(null, MODE_RGB, false, false, "Bild ohne Flaeche")

        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > Int.MAX_VALUE) {
            Log.e(TAG, "Bild zu gross fuer die Analyse: ${width}x$height")
            return Result(null, MODE_RGB, false, false, "Bild zu gross fuer die Analyse")
        }

        val count = pixelCount.toInt()
        val pixels = try {
            IntArray(count)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Kein Speicher fuer die Pixelkopie: ${width}x$height")
            return Result(null, MODE_RGB, false, false, "Bild zu gross fuer den Arbeitsspeicher")
        }

        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return try {
            readNewFormat(pixels, count) ?: readLegacyFormat(pixels, count)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Zu wenig Arbeitsspeicher beim Auslesen: ${width}x$height")
            Result(null, MODE_RGB, false, false, "Bild zu gross fuer den Arbeitsspeicher")
        } finally {
            pixels.fill(0)
        }
    }

    fun extractPayloadBase64(bitmap: Bitmap): String? = extract(bitmap).payloadBase64

    private fun readNewFormat(pixels: IntArray, pixelCount: Int): Result? {
        val mode = detectMode(pixels, pixelCount) ?: return null
        val capacityBits = capacity(pixelCount, mode)

        val header = bytesFromBits(extractBits(pixels, pixelCount, mode, HEADER_BITS), 0, HEADER_BYTES)
        val payloadByteLength = ((header[4].toInt() and 0xFF) shl 24) or
            ((header[5].toInt() and 0xFF) shl 16) or
            ((header[6].toInt() and 0xFF) shl 8) or
            (header[7].toInt() and 0xFF)
        val storedCrc = ((header[8].toInt() and 0xFF) shl 8) or (header[9].toInt() and 0xFF)
        val availableBytes = (capacityBits - HEADER_BITS) / 8

        if (payloadByteLength <= 0 || payloadByteLength > availableBytes) {
            Log.w(TAG, "Kopf gefunden, Laenge unplausibel: $payloadByteLength")
            return Result(null, mode, false, false, "Laenge unplausibel")
        }

        val allBits = extractBits(pixels, pixelCount, mode, HEADER_BITS + payloadByteLength * 8)
        val payloadBytes = bytesFromBits(allBits, HEADER_BITS, payloadByteLength)
        val crc = crc16(header[2].toInt() and 0xFF, mode, payloadByteLength, payloadBytes)
        if (crc != storedCrc) {
            Log.w(TAG, "CRC16 stimmt nicht - Pixel-Bett beschaedigt")
            payloadBytes.fill(0)
            return Result(null, mode, false, false, "Pixel-Bett beschaedigt (CRC16 falsch)")
        }

        val payload = String(payloadBytes, Charsets.US_ASCII)
        payloadBytes.fill(0)
        return Result(payload, mode, false, true)
    }

    private fun readLegacyFormat(pixels: IntArray, pixelCount: Int): Result {
        val mode = legacyModeGuess(pixels, pixelCount)
        val capacityBits = capacity(pixelCount, mode)
        if (capacityBits <= LEGACY_HEADER_BITS) {
            return Result(null, mode, true, false, "Bild zu klein")
        }

        val head = extractBits(pixels, pixelCount, mode, LEGACY_HEADER_BITS + 8)
        var payloadBits = 0L
        for (bit in 0 until LEGACY_HEADER_BITS) {
            payloadBits = (payloadBits shl 1) or (if (head[bit]) 1L else 0L)
        }
        if (payloadBits <= 0L ||
            payloadBits > (capacityBits - LEGACY_HEADER_BITS).toLong() ||
            payloadBits % 8L != 0L
        ) {
            Log.w(TAG, "Kein gueltiges Pixel-Bett (Altformat)")
            return Result(null, mode, true, false, "Kein gueltiges Pixel-Bett")
        }

        val all = extractBits(pixels, pixelCount, mode, LEGACY_HEADER_BITS + payloadBits.toInt())
        val byteCount = (payloadBits / 8L).toInt()
        val payloadBytes = ByteArray(byteCount)
        for (b in 0 until byteCount) {
            var code = 0
            for (j in 0 until 8) {
                val bit = LEGACY_HEADER_BITS + b * 8 + j
                code = (code shl 1) or (if (bit < all.size() && all[bit]) 1 else 0)
            }
            payloadBytes[b] = code.toByte()
        }
        val payload = String(payloadBytes, Charsets.US_ASCII)
        payloadBytes.fill(0)
        return Result(payload, mode, true, false)
    }

    private fun capacity(pixelCount: Int, mode: Int): Int =
        if (mode == MODE_RGB) pixelCount * 3 else pixelCount

    private fun detectMode(pixels: IntArray, pixelCount: Int): Int? {
        if (readMagic(pixels, pixelCount, MODE_RGB)) return MODE_RGB
        if (readMagic(pixels, pixelCount, MODE_GRAY)) return MODE_GRAY
        return null
    }

    private fun readMagic(pixels: IntArray, pixelCount: Int, mode: Int): Boolean {
        val bytes = bytesFromBits(extractBits(pixels, pixelCount, mode, MAGIC_BITS), 0, 5)
        return (bytes[0].toInt() and 0xFF) == MAGIC_0 && (bytes[1].toInt() and 0xFF) == MAGIC_1
    }

    private fun legacyModeGuess(pixels: IntArray, pixelCount: Int): Int {
        var px = 0
        while (px < pixelCount && px < 100) {
            val p = pixels[px]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (Math.abs(r - g) > 1 || Math.abs(g - b) > 1) return MODE_RGB
            px++
        }
        return MODE_GRAY
    }

    private fun extractBits(pixels: IntArray, pixelCount: Int, mode: Int, bitCount: Int): BitSet {
        val bits = BitSet(bitCount)
        var bitIndex = 0
        var px = 0
        if (mode == MODE_RGB) {
            while (px < pixelCount && bitIndex < bitCount) {
                val p = pixels[px]
                bits.set(bitIndex++, lsb((p shr 16) and 0xFF))
                if (bitIndex < bitCount) bits.set(bitIndex++, lsb((p shr 8) and 0xFF))
                if (bitIndex < bitCount) bits.set(bitIndex++, lsb(p and 0xFF))
                px++
            }
        } else {
            while (px < pixelCount && bitIndex < bitCount) {
                bits.set(bitIndex++, lsb((pixels[px] shr 16) and 0xFF))
                px++
            }
        }
        return bits
    }

    private fun bytesFromBits(bits: BitSet, offset: Int, byteCount: Int): ByteArray {
        val bytes = ByteArray(byteCount)
        for (b in 0 until byteCount) {
            var code = 0
            for (j in 0 until 8) {
                code = (code shl 1) or (if (bits.get(offset + b * 8 + j)) 1 else 0)
            }
            bytes[b] = code.toByte()
        }
        return bytes
    }

    private fun crc16(version: Int, mode: Int, payloadByteLength: Int, payload: ByteArray): Int {
        val meta = byteArrayOf(
            (version and 0xFF).toByte(),
            (mode and 0xFF).toByte(),
            ((payloadByteLength ushr 24) and 0xFF).toByte(),
            ((payloadByteLength ushr 16) and 0xFF).toByte(),
            ((payloadByteLength ushr 8) and 0xFF).toByte(),
            (payloadByteLength and 0xFF).toByte(),
        )
        var crc = 0xFFFF
        crc = crc16Update(crc, meta, 0, meta.size)
        return crc16Update(crc, payload, 0, payload.size)
    }

    private fun crc16Update(crc: Int, bytes: ByteArray, start: Int, end: Int): Int {
        var value = crc and 0xFFFF
        for (i in start until end) {
            value = value xor ((bytes[i].toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                value = if ((value and 0x8000) != 0) {
                    ((value shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (value shl 1) and 0xFFFF
                }
            }
        }
        return value and 0xFFFF
    }

    private fun lsb(channel: Int): Boolean = (channel and 1) != 0
}
