package com.mobile.utilities.calculator

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class ShareTargetActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CalculatorShare"
        private val BACKGROUND = 0xFF0B0F19.toInt()
        private val LIME = 0xFFA3E635.toInt()
        private val RESULT_TEXT = 0xFFE4E4E7.toInt()

        private const val INCOMING_FILE_NAME = "incoming_share.bin"
        private const val SHARE_CACHE_DIR_NAME = "share"

        private const val AUDIO_HTML_SHELL =
            "<html><body style=\"margin:0;padding:0;background:#0B0F19;\"><audio id=\"a\" controls style=\"width:100%;\"></audio></body></html>"
    }

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var resultScroll: ScrollView
    private lateinit var resultWeb: WebView

    private var extractedPayload: String? = null

    @Volatile
    private var imageTooLarge = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        buildUi()

        val uri = readStreamUri(intent)
        if (uri == null) {
            fail(getString(R.string.share_error_no_image))
            return
        }

        if (!isSupportedShare(uri)) {
            fail(getString(R.string.share_error_not_image))
            return
        }

        val localUri = copyToWorkspace(uri)
        if (localUri == null) {
            fail(getString(R.string.share_error_decode))
            return
        }

        statusText.setText(R.string.share_extracting)
        extractInBackground(localUri)
    }

    override fun onDestroy() {
        if (::resultText.isInitialized) resultText.text = ""
        if (::resultWeb.isInitialized) {
            resultWeb.stopLoading()
            resultWeb.destroy()
        }
        extractedPayload = null
        imageTooLarge = false
        super.onDestroy()
    }

    private fun isUiAlive(): Boolean = !isFinishing && !isDestroyed

    private fun buildUi() {
        val status = TextView(this).apply {
            textSize = 18f
            setTextColor(LIME)
            typeface = Typeface.DEFAULT_BOLD
        }

        val result = TextView(this).apply {
            textSize = 16f
            setTextColor(RESULT_TEXT)
            setLineSpacing(4f, 1f)
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }

        val scroll = ScrollView(this).apply {
            addView(
                result,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            setBackgroundColor(BACKGROUND)
            visibility = View.GONE
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(
                status,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            addView(
                web,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }

        statusText = status
        resultText = result
        resultScroll = scroll
        resultWeb = web
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun readStreamUri(intent: Intent?): Uri? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }

    private fun isSupportedShare(uri: Uri): Boolean {
        val mime = try {
            contentResolver.getType(uri)
        } catch (e: Exception) {
            Log.w(TAG, "MIME-Typ nicht ermittelbar: ${e.javaClass.simpleName}")
            return true
        }
        if (mime == null) return true
        val supported = mime.startsWith("image/", ignoreCase = true) ||
            mime.equals("application/octet-stream", ignoreCase = true)
        if (!supported) Log.w(TAG, "Geteilter Inhalt abgelehnt: MIME-Typ=$mime")
        return supported
    }

    private fun copyToWorkspace(source: Uri): Uri? {
        val dir = File(cacheDir, SHARE_CACHE_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Arbeitsordner nicht anlegbar")
            return null
        }
        val target = File(dir, INCOMING_FILE_NAME)
        return try {
            val input = contentResolver.openInputStream(source)
            if (input == null) {
                Log.e(TAG, "Eingehende Datei nicht lesbar (kein Stream)")
                return null
            }
            input.use { inputStream ->
                FileOutputStream(target).use { output -> inputStream.copyTo(output) }
            }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        } catch (e: Exception) {
            Log.e(TAG, "Kopieren in den Arbeitsbereich fehlgeschlagen: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun extractInBackground(uri: Uri) {
        Thread {
            var bitmap: Bitmap? = null
            val extraction = try {
                bitmap = decodeBitmap(uri)
                bitmap?.let { StegoExtractor.extract(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Extraktion fehlgeschlagen: ${e.javaClass.simpleName}")
                null
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Bild zu gross: ${e.javaClass.simpleName}")
                null
            } finally {
                bitmap?.recycle()
            }

            val payload = extraction?.payloadBase64
            val reason = extraction?.reason

            runOnUiThread {
                if (!isUiAlive()) return@runOnUiThread
                if (payload.isNullOrEmpty()) {
                    fail(
                        when {
                            imageTooLarge -> getString(R.string.share_error_too_large)
                            reason.isNullOrBlank() -> getString(R.string.share_error_no_payload)
                            else -> getString(R.string.share_error_no_payload) + "\n\nGrund: " + reason
                        }
                    )
                } else {
                    extractedPayload = payload
                    promptForPassword()
                }
            }
        }.start()
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val headerStream = contentResolver.openInputStream(uri)
        if (headerStream == null) {
            Log.e(TAG, "Bild nicht lesbar (kein Stream)")
            return null
        }
        headerStream.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(TAG, "Bildkopf nicht lesbar")
            return null
        }

        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (pixels > maxDecodablePixels()) {
            imageTooLarge = true
            Log.w(TAG, "Bild zu gross: ${bounds.outWidth}x${bounds.outHeight}")
            return null
        }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun maxDecodablePixels(): Long =
        (Runtime.getRuntime().maxMemory() / 8L / 2L).coerceAtLeast(1L)

    private fun promptForPassword() {
        if (!isUiAlive()) return

        statusText.setText(R.string.share_prompt)

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.share_password_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.share_password_title)
            .setView(input)
            .setPositiveButton(R.string.share_decrypt) { _, _ -> decrypt(input.text.toString()) }
            .setNegativeButton(R.string.share_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun decrypt(passphrase: String) {
        val payload = extractedPayload
        if (payload == null) {
            fail(getString(R.string.share_error_no_payload))
            return
        }
        if (passphrase.isEmpty()) {
            Toast.makeText(this, R.string.share_error_empty_password, Toast.LENGTH_SHORT).show()
            promptForPassword()
            return
        }

        statusText.setText(R.string.share_decrypting)
        Thread {
            val passChars = passphrase.toCharArray()
            val decrypted = try {
                AesGcmCrypto.decryptFromBase64(payload, passChars)
            } catch (e: Exception) {
                Log.e(TAG, "Entschluesselung fehlgeschlagen: ${e.javaClass.simpleName}")
                null
            } finally {
                passChars.fill('\u0000')
            }

            runOnUiThread {
                if (!isUiAlive()) return@runOnUiThread
                if (decrypted.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.share_error_wrong_password, Toast.LENGTH_SHORT).show()
                    promptForPassword()
                } else {
                    showResult(decrypted)
                }
            }
        }.start()
    }

    private fun showResult(text: String) {
        statusText.setText(R.string.share_result_title)
        if (isAudioPayload(text)) {
            showAudioResult(text.trim())
        } else {
            resultWeb.visibility = View.GONE
            resultScroll.visibility = View.VISIBLE
            resultText.text = text
            resultText.setTextIsSelectable(true)
        }
    }

    private fun fail(message: String) {
        statusText.setText(R.string.share_status_error)
        resultWeb.visibility = View.GONE
        resultScroll.visibility = View.VISIBLE
        resultText.text = message
        resultText.setTextIsSelectable(false)
    }

    private fun isAudioPayload(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("data:audio/") || t.startsWith("data:application/octet-stream")
    }

    private fun showAudioResult(dataUrl: String) {
        resultScroll.visibility = View.GONE
        resultText.text = ""
        resultWeb.visibility = View.VISIBLE
        resultWeb.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val srcJson = JSONObject.quote(dataUrl)
                resultWeb.evaluateJavascript(
                    "(function(){var a=document.getElementById('a');if(a){a.src=$srcJson;a.load();}})();",
                    null
                )
            }
        }
        resultWeb.loadDataWithBaseURL(
            null,
            AUDIO_HTML_SHELL,
            "text/html",
            "UTF-8",
            null
        )
    }
}
