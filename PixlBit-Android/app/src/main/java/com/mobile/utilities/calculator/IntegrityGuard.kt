package com.mobile.utilities.calculator

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import androidx.annotation.Keep
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Leichtgewichtige App-Integritaetspruefung: erkennt angeschlossenen Debugger,
 * Frida (TracerPid + Port 27042) und Repackaging (Signatur-Abweichung) und
 * bricht still ab.
 */
@Keep
object IntegrityGuard {

    private const val FRIDA_PORT = 27042

    fun verify(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        return !(Debug.isDebuggerConnected() || Debug.waitingForDebugger() ||
            isTraced() || isFridaPortOpen() || !verifySignature(context))
    }

    private fun isTraced(): Boolean = try {
        (File("/proc/self/status").readText()
            .lineSequence()
            .firstOrNull { it.startsWith("TracerPid:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0) != 0
    } catch (e: Exception) { false }

    private fun isFridaPortOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", FRIDA_PORT), 250); true }
    } catch (e: Exception) { false }

    private fun verifySignature(context: Context): Boolean = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNATURES
            )
        }
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()
        } ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        val hex = digest.joinToString("") { "%02X".format(it) }
        val expected = BuildConfig.EXPECTED_SIGNATURE_SHA256
        if (expected.isBlank()) return true
        hex.equals(expected, ignoreCase = true)
    } catch (e: Exception) { false }
}
