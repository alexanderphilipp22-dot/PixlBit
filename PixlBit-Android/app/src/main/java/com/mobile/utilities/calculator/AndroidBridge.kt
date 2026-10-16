package com.mobile.utilities.calculator

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import java.security.GeneralSecurityException
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

@Keep
class AndroidBridge(
    private val context: Context,
    private val onBlobDownload: ((blobUrl: String, dataUrl: String, blobMimeType: String) -> Unit)? = null,
    private val onMicrophonePermissionRequest: (() -> Unit)? = null,
    private val onNativeRecordingStart: (() -> Boolean)? = null,
    private val onNativeRecordingStop: (() -> String)? = null,
    private val onPremiumPurchaseRequest: (() -> Boolean)? = null,
    private val onPanicCodeSetupRequest: (() -> Boolean)? = null,
    private val onMainPinChangeRequest: (() -> Boolean)? = null
) {

    companion object {
        private const val TAG = "CalculatorBridge"
        private const val ALBUM_NAME = "Calculator"

        private const val PICTURES_DIR_NAME = "Pictures"

        private const val PUBLIC_ALBUM_NAME = "Calculator"
        private const val PUBLIC_RELATIVE_PATH = "Pictures/Calculator"

        private const val MIME_PNG = "image/png"

        private const val MIME_OCTET_STREAM = "application/octet-stream"

        private const val SHARE_BIN_FILE_NAME = "calc_sec_data.bin"

        private const val SHARE_CACHE_DIR_NAME = "share"

        private const val MAX_FILE_NAME_LENGTH = 120

        private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }

    @Keep
    @JavascriptInterface
    fun saveImagePrivate(base64Png: String, fileName: String): Boolean {
        val safeName = sanitizeFileName(fileName)
        if (safeName == null) {
            Log.e(TAG, "Speichern abgelehnt: ungueltiger Dateiname")
            return false
        }
        return try {
            val bytes = Base64.decode(base64Png, Base64.DEFAULT)
            try {
                if (!hasPngSignature(bytes)) {
                    Log.e(TAG, "Speichern abgelehnt: Daten sind kein PNG (verlustbehaftet?)")
                    return false
                }

                if (savePngToPublicAlbum(bytes, safeName)) {
                    Log.i(TAG, "Stego-PNG gespeichert: $PUBLIC_RELATIVE_PATH/$safeName")
                    return true
                }

                val fallback = albumFileFor(safeName)
                if (fallback != null && savePngToPrivateAlbum(fallback, bytes)) {
                    Log.w(TAG, "Oeffentliches Speichern fehlgeschlagen - intern abgelegt ($safeName)")
                    return true
                }
                false
            } finally {
                bytes.fill(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speichern fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Speichern abgebrochen (Speicher): ${e.javaClass.simpleName}")
            false
        }
    }

    private fun hasPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false
        }
        return true
    }

    private fun privateAlbumDir(): File =
        File(File(context.filesDir, PICTURES_DIR_NAME), ALBUM_NAME)

    private fun albumFileFor(fileName: String): File? {
        val albumDir = privateAlbumDir()
        val safeName = sanitizeFileName(fileName) ?: return null
        val candidate = File(albumDir, safeName)
        return try {
            val albumPath = albumDir.canonicalPath + File.separator
            if (candidate.canonicalPath.startsWith(albumPath)) candidate else null
        } catch (e: IOException) {
            Log.e(TAG, "Zielpfad nicht aufloesbar: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun sanitizeFileName(fileName: String): String? {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base == "." || base == "..") return null
        val cleaned = base.replace(ILLEGAL_FILE_NAME_CHARS, "_")
            .take(MAX_FILE_NAME_LENGTH)
            .trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
        return cleaned
    }

    private fun savePngToPublicAlbum(bytes: ByteArray, fileName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePngToPublicViaMediaStore(bytes, fileName)
        } else {
            savePngToPublicViaLegacyFile(bytes, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePngToPublicViaMediaStore(bytes: ByteArray, fileName: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
            put(MediaStore.Images.Media.RELATIVE_PATH, PUBLIC_RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.e(TAG, "MediaStore-Eintrag konnte nicht angelegt werden")
            return false
        }

        return try {
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                runCatching { resolver.delete(uri, null, null) }
                Log.e(TAG, "MediaStore-Ausgabestream nicht verfuegbar")
                return false
            }
            stream.use { out -> out.write(bytes) }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            notifyMediaScanner(uri)
            true
        } catch (e: IOException) {
            runCatching { resolver.delete(uri, null, null) }
            Log.e(TAG, "MediaStore-Schreibfehler: ${e.javaClass.simpleName}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun savePngToPublicViaLegacyFile(bytes: ByteArray, fileName: String): Boolean {
        return try {
            val albumDir = legacyPublicAlbumDir()
            if (!albumDir.exists() && !albumDir.mkdirs()) {
                Log.e(TAG, "Konnte Album-Ordner $PUBLIC_RELATIVE_PATH nicht anlegen")
                return false
            }
            val target = File(albumDir, fileName)
            FileOutputStream(target).use { it.write(bytes) }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(MIME_PNG),
                null
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Legacy-Schreibfehler: ${e.javaClass.simpleName}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyPublicAlbumDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        PUBLIC_ALBUM_NAME
    )

    private fun savePngToPrivateAlbum(file: File, bytes: ByteArray): Boolean {
        return try {
            val albumDir = file.parentFile ?: return false
            if (!albumDir.exists() && !albumDir.mkdirs()) return false
            FileOutputStream(file).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Internes Speichern fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyMediaScanner(uri: Uri) {
        try {
            val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            scanIntent.data = uri
            context.sendBroadcast(scanIntent)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun findPublicImageUri(fileName: String): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf(fileName, "$PUBLIC_RELATIVE_PATH%")
        } else {
            selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Images.Media.DATA} LIKE ?"
            selectionArgs = arrayOf(fileName, "%/$PUBLIC_ALBUM_NAME/%")
        }

        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore-Suche fehlgeschlagen: ${e.javaClass.simpleName}")
            null
        }
    }

    fun saveToPublicDownloads(bytes: ByteArray, fileName: String, mimeType: String): Boolean {
        return try {
            val finalMime = mimeType.ifBlank { guessMimeType(fileName) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToDownloadsViaMediaStore(bytes, fileName, finalMime)
            } else {
                saveToDownloadsViaLegacyFile(bytes, fileName, finalMime)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download-Speichern fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    @Keep
    @JavascriptInterface
    fun onDownloadBlob(blobUrl: String, dataUrl: String, blobMimeType: String) {
        val callback = onBlobDownload ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                callback.invoke(blobUrl, dataUrl, blobMimeType)
            } catch (e: Exception) {
                Log.e(TAG, "Blob-Download-Rueckruf fehlgeschlagen: ${e.javaClass.simpleName}")
            }
        }
    }

    @Keep
    @JavascriptInterface
    fun shareImage(fileName: String): Boolean {
        return try {
            val safeName = sanitizeFileName(fileName) ?: run {
                Log.e(TAG, "Teilen abgelehnt: ungueltiger Dateiname")
                return false
            }

            val sourceUri = findPublicImageUri(safeName) ?: internalAlbumUriFor(safeName) ?: run {
                Log.e(TAG, "Zu teilende Datei nicht gefunden")
                return false
            }

            val binFile = File(shareCacheDir(), SHARE_BIN_FILE_NAME)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(binFile).use { output -> input.copyTo(output) }
            } ?: run {
                Log.e(TAG, "Zu teilende Datei nicht lesbar")
                return false
            }

            val binUri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", binFile)
            } catch (e: Exception) {
                Log.e(TAG, "FileProvider-URI fuer .bin-Datei fehlgeschlagen: ${e.javaClass.simpleName}")
                return false
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = MIME_OCTET_STREAM
                putExtra(Intent.EXTRA_STREAM, binUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("Calculator File", binUri)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Sichere Datei teilen"))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Teilen fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun shareCacheDir(): File {
        val dir = File(context.cacheDir, SHARE_CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun internalAlbumUriFor(fileName: String): Uri? {
        val file = albumFileFor(fileName) ?: return null
        if (!file.exists()) return null
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider-URI fehlgeschlagen: ${e.javaClass.simpleName}")
            null
        }
    }

    @Keep
    @JavascriptInterface
    fun triggerIfDuress(passphrase: String): Boolean {
        return try {
            if (!PanicCodeStore.hasDecimalSeparator(passphrase)) return false
            val isDuress = PanicCodeStore.verify(context, passphrase)
            if (isDuress) {
                PanicCodeStore.clear()
                Handler(Looper.getMainLooper()).post {
                    try {
                        SecureDelete.deleteWebStorageData()

                        SecureWipeWork.enqueueOrFallback(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Duress-Wipe-Ausloesung fehlgeschlagen: ${e.javaClass.simpleName}")
                    }
                }
            }
            isDuress
        } catch (e: Exception) {
            Log.e(TAG, "Duress-Pruefung fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    @Keep
    @JavascriptInterface
    fun aesEncrypt(plaintext: String, passphrase: String): String {
        return try {
            AesGcmCrypto.encryptToBase64(plaintext, passphrase.toCharArray())
        } catch (e: Exception) {
            Log.e(TAG, "Verschluesselung fehlgeschlagen: ${e.javaClass.simpleName}")
            ""
        }
    }

    @Keep
    @JavascriptInterface
    fun aesDecrypt(payloadBase64: String, passphrase: String): String {
        if (BruteForceGuard.isLockedOut(context)) return ""
        return try {
            val plain = AesGcmCrypto.decryptFromBase64(payloadBase64, passphrase.toCharArray())
            BruteForceGuard.reset(context)
            plain
        } catch (e: GeneralSecurityException) {
            BruteForceGuard.recordFailure(context)
            ""
        } catch (e: Exception) {
            ""
        }
    }

    @Keep
    @JavascriptInterface
    fun hasMicrophonePermission(): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG, "Mikrofon-Status nicht abfragbar: ${e.javaClass.simpleName}")
            false
        }
    }

    @Keep
    @JavascriptInterface
    fun requestMicrophonePermission(): Boolean {
        return try {
            val request = onMicrophonePermissionRequest
            if (request == null) {
                Log.w(TAG, "requestMicrophonePermission ohne Handler - WebView fragt selbst nach RECORD_AUDIO")
                return false
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    request()
                } catch (e: Exception) {
                    Log.e(TAG, "Mikrofon-Anfrage fehlgeschlagen: ${e.javaClass.simpleName}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Mikrofon-Anfrage fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    @Keep
    @JavascriptInterface
    fun microphoneDiagnostics(): String {
        return try {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val webViewVersion = try {
                WebView.getCurrentWebViewPackage()?.versionName ?: "unbekannt"
            } catch (e: Exception) {
                Log.w(TAG, "WebView-Version nicht abfragbar: ${e.javaClass.name}: ${e.message}")
                "unbekannt"
            }
            JSONObject()
                .put("recordAudioGranted", granted)
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("webViewVersion", webViewVersion)
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "Mikrofon-Diagnose fehlgeschlagen: ${e.javaClass.name}: ${e.message}")
            ""
        }
    }

    @Keep
    @JavascriptInterface
    fun startNativeRecording(): Boolean {
        return try {
            val start = onNativeRecordingStart
            if (start == null) {
                Log.w(TAG, "startNativeRecording ohne Handler - JS nutzt MediaRecorder-Fallback")
                return false
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    start()
                } catch (e: Exception) {
                    Log.e(TAG, "Native Aufnahme-Start fehlgeschlagen: ${e.javaClass.simpleName}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Native Aufnahme-Start fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    @Keep
    @JavascriptInterface
    fun stopNativeRecording(): String {
        return try {
            val stop = onNativeRecordingStop ?: return ""
            val latch = CountDownLatch(1)
            val result = AtomicReference<String?>(null)
            Handler(Looper.getMainLooper()).post {
                try {
                    result.set(stop())
                } catch (e: Exception) {
                    Log.e(TAG, "Native Aufnahme-Stopp fehlgeschlagen: ${e.javaClass.simpleName}")
                    result.set("")
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(15, TimeUnit.SECONDS)) {
                Log.e(TAG, "Native Aufnahme-Stopp: Timeout beim Finalisieren")
                return ""
            }
            result.get() ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Native Aufnahme-Stopp fehlgeschlagen: ${e.javaClass.simpleName}")
            ""
        }
    }

    @Keep
    @JavascriptInterface
    fun isDebugBuild(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Keep
    @JavascriptInterface
    fun setAppLanguage(code: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                LocaleHelper.apply(code)
            } catch (e: Exception) {
                Log.e(TAG, "Sprachwechsel fehlgeschlagen: ${e.javaClass.simpleName}")
            }
        }
    }

    @Keep
    @JavascriptInterface
    fun getPremiumState(): String {
        return try {
            PremiumManager.buildStateJson(context)
        } catch (e: Exception) {
            Log.e(TAG, "Premium-Status nicht lesbar: ${e.javaClass.name}: ${e.message}", e)
            PremiumManager.buildFailClosedStateJson()
        }
    }

    @Keep
    @JavascriptInterface
    fun isPanicCodeConfigured(): Boolean {
        return try {
            PanicCodeStore.isConfigured(context)
        } catch (e: Exception) {
            Log.e(TAG, "Panik-Code-Status nicht abfragbar: ${e.javaClass.simpleName}")
            false
        }
    }

    @Keep
    @JavascriptInterface
    fun requestPanicCodeSetup(): Boolean {
        return try {
            val request = onPanicCodeSetupRequest ?: return false
            Handler(Looper.getMainLooper()).post {
                try {
                    request()
                } catch (e: Exception) {
                    Log.e(TAG, "Panik-Code-Dialog fehlgeschlagen: ${e.javaClass.simpleName}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Panik-Code-Dialog fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Prueft die Haupt-PIN (Entsperr-PIN) konstantzeit-verifiziert.
     *  Der Brute-Force-Guard greift hier NATIV: Sperre pruefen, bei Treffer
     *  zuruecksetzen, bei Fehlversuch zaehlen. So kann auch ein direkter
     *  Bridge-Aufruf (ohne den JS-Pfad der Fassade) den Lockout nicht umgehen. */
    @Keep
    @JavascriptInterface
    fun verifyMainPin(pin: String): Boolean {
        if (BruteForceGuard.isLockedOut(context)) return false
        return try {
            val ok = MainPinStore.verify(context, pin)
            if (ok) BruteForceGuard.reset(context) else BruteForceGuard.recordFailure(context)
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Haupt-PIN-Pruefung fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    /** true, wenn bereits eine Haupt-PIN hinterlegt ist (Erststart-Check). */
    @Keep
    @JavascriptInterface
    fun hasMainPin(): Boolean {
        return try {
            MainPinStore.isConfigured(context)
        } catch (e: Exception) {
            Log.e(TAG, "Haupt-PIN-Status nicht abfragbar: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Setzt die Haupt-PIN direkt (Erststart-Auto-Setup). Validiert nativ.
     *  Nur zulaessig, solange noch KEINE PIN hinterlegt ist: Ein Ueberschreiben
     *  ohne Kenntnis der aktuellen PIN wird abgelehnt. Aenderungen laufen
     *  ausschliesslich ueber den nativen Dialog (requestMainPinChange), der
     *  zuvor die aktuelle PIN verifiziert. */
    @Keep
    @JavascriptInterface
    fun setMainPin(pin: String): Boolean {
        return try {
            if (MainPinStore.isConfigured(context)) {
                Log.w(TAG, "Haupt-PIN bereits gesetzt - Erststart-Setup abgelehnt")
                return false
            }
            MainPinStore.setPin(context, pin)
        } catch (e: Exception) {
            Log.e(TAG, "Haupt-PIN konnte nicht gesetzt werden: ${e.javaClass.simpleName}")
            false
        }
    }

    /** true, wenn der Long-Press-Trigger wegen zu vieler Fehlversuche gesperrt ist. */
    @Keep
    @JavascriptInterface
    fun isBruteForceLockedOut(): Boolean {
        return try {
            BruteForceGuard.isLockedOut(context)
        } catch (e: Exception) {
            false
        }
    }

    /** Registriert einen Fehlversuch (rein ueber Zeitstempel, keine Sperr-Warteschleife). */
    @Keep
    @JavascriptInterface
    fun recordFailedPinAttempt(): Boolean {
        return try {
            BruteForceGuard.recordFailure(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Setzt den Fehlversuchs-Zaehler nach erfolgreicher Verifikation zurueck. */
    @Keep
    @JavascriptInterface
    fun resetFailedPinAttempts(): Boolean {
        return try {
            BruteForceGuard.reset(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Kopiert Text sicher in die Zwischenablage: sensibel markiert (API 33+) und
        nach 20 Sekunden automatisch geleert. */
    @Keep
    @JavascriptInterface
    fun copySensitiveText(text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Calculator", text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
            ClipboardGuard.markPending()
            Handler(Looper.getMainLooper()).postDelayed({
                ClipboardGuard.purge(context)
            }, 20_000L)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Kopieren fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Oeffnet den Dialog "PIN ändern" (nativ, wie der Panik-Code-Dialog). */
    @Keep
    @JavascriptInterface
    fun requestMainPinChange(): Boolean {
        return try {
            val request = onMainPinChangeRequest ?: return false
            Handler(Looper.getMainLooper()).post {
                try {
                    request()
                } catch (e: Exception) {
                    Log.e(TAG, "Haupt-PIN-Dialog fehlgeschlagen: ${e.javaClass.simpleName}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Haupt-PIN-Dialog fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    @Keep
    @JavascriptInterface
    fun requestPremiumPurchase(): Boolean {
        return try {
            val request = onPremiumPurchaseRequest
            if (request != null) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        request()
                    } catch (e: Exception) {
                        Log.e(TAG, "Kaufstart fehlgeschlagen: ${e.javaClass.simpleName}")
                    }
                }
                true
            } else {
                PremiumManager.openStoreListing(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kaufstart fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
            .lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsViaMediaStore(bytes: ByteArray, fileName: String, mimeType: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                ?: return false

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            try {
                val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                scanIntent.data = uri
                context.sendBroadcast(scanIntent)
            } catch (e: Exception) {
            }
            true
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "Download-MediaStore-Schreibfehler: ${e.javaClass.simpleName}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToDownloadsViaLegacyFile(bytes: ByteArray, fileName: String, mimeType: String): Boolean {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                Log.e(TAG, "Konnte Download-Ordner nicht anlegen")
                return false
            }

            val targetFile = File(downloadsDir, fileName)
            FileOutputStream(targetFile).use { it.write(bytes) }

            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            true
        } catch (e: IOException) {
            Log.e(TAG, "Download-Legacy-Schreibfehler: ${e.javaClass.simpleName}")
            false
        }
    }
}
