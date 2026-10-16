package com.mobile.utilities.calculator

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebStorage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CalculatorMain"
    }

    private lateinit var webView: WebView
    private lateinit var androidBridge: AndroidBridge

    private lateinit var billingManager: BillingManager

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private data class PendingDownload(val contentDisposition: String?, val mimeType: String?)
    private val pendingBlobDownloads = HashMap<String, PendingDownload>()

    private var pendingPermissionRequest: PermissionRequest? = null

    private var sensorManager: SensorManager? = null
    private var faceDownLocked = false

    private val faceDownListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values[2] < -9.0f && !faceDownLocked) {
                faceDownLocked = true
                lockVaultImmediately()
                Handler(Looper.getMainLooper()).postDelayed({ faceDownLocked = false }, 3000)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        callback.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    private var microphonePermissionInFlight = false

    private var nativeRecorder: MediaRecorder? = null
    private var nativeRecordingFile: File? = null
    private var nativeRecordingMime: String = "audio/mp4"

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphonePermissionInFlight = false
        val pending = pendingPermissionRequest
        pendingPermissionRequest = null
        if (pending != null) {
            if (granted) grantAudioCapture(pending) else denyPermissionRequest(pending)
        }
        notifyMicrophonePermission(granted)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!IntegrityGuard.verify(this)) {
            android.os.Process.killProcess(android.os.Process.myPid())
            throw SecurityException("Integritaetspruefung fehlgeschlagen")
        }

        // 3-Tage-Testphase: Erststart-Zeitstempel genau einmal sichern.
        try {
            PremiumManager.ensureTrialStart(this)
        } catch (t: Throwable) {
            Log.w(TAG, "Trial-Start nicht speicherbar: ${t.javaClass.simpleName}")
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setupFaceDownLock()

        billingManager = BillingManager(
            activity = this,
            onPremiumStateChanged = { notifyPremiumStateChanged() },
            onPurchaseFailed = { reason -> notifyPurchaseFailed(reason) }
        )
        billingManager.connect()

        requestLegacyStoragePermissionIfNeeded()

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = WebView(this)
        setContentView(webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val intent = fileChooserParams.createIntent().apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    fileChooserLauncher.launch(Intent.createChooser(intent, "Datei auswählen"))
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val resources = request.resources.joinToString(",")

                if (!request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    Log.w(TAG, "WebView-Permission [$resources] abgelehnt: nur Audio wird unterstuetzt")
                    denyPermissionRequest(request)
                    return
                }

                if (isRecordAudioGranted()) {
                    grantAudioCapture(request)
                    return
                }

                pendingPermissionRequest = request
                if (microphonePermissionInFlight) {
                    return
                }
                microphonePermissionInFlight = true
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingPermissionRequest === request) {
                    pendingPermissionRequest = null
                }
            }
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(
                        TAG,
                        "WebView: ${redactConsoleText(message.message())} " +
                            "(${message.sourceId()}:${message.lineNumber()})"
                    )
                }
                return true
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            setAllowUniversalAccessFromFileURLs(false)
            setSupportZoom(false)
            builtInZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.filterTouchesWhenObscured = true

        androidBridge = AndroidBridge(
            context = this,
            onBlobDownload = { blobUrl, dataUrl, blobMimeType ->
                onBlobDownloadCompleted(blobUrl, dataUrl, blobMimeType)
            },
            onMicrophonePermissionRequest = { requestMicrophonePermissionFromUi() },
            onNativeRecordingStart = { startNativeRecording() },
            onNativeRecordingStop = { stopNativeRecordingAndRead() },
            onPremiumPurchaseRequest = {
                val started = billingManager.launchPurchaseFlow()
                if (!started) {
                    PremiumManager.openStoreListing(this)
                }
                true
            },
            onPanicCodeSetupRequest = {
                showPanicCodeSetupDialog()
                true
            },
            onMainPinChangeRequest = {
                showMainPinChangeDialog()
                true
            }
        )
        webView.addJavascriptInterface(androidBridge, "AndroidBridge")

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }

        enforcePanicCodeSetup()
    }

    override fun onResume() {
        super.onResume()
        if (::billingManager.isInitialized) billingManager.refreshPurchases()
    }

    override fun onPause() {
        super.onPause()
        wipeSensitiveData()
    }

    override fun onStop() {
        ClipboardGuard.purge(this)
        super.onStop()
        wipeSensitiveData()
    }

    override fun onTrimMemory(level: Int) {
        ClipboardGuard.purge(this)
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(faceDownListener)

        if (::billingManager.isInitialized) billingManager.endConnection()

        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        wipeSensitiveData()
        webView.destroy()
        super.onDestroy()
    }

    private fun notifyPremiumStateChanged() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "if (typeof onPremiumStateChanged === 'function') { onPremiumStateChanged(); }",
            null
        )
    }

    private fun notifyPurchaseFailed(reason: String) {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "if (typeof onPurchaseFailed === 'function') { onPurchaseFailed(${JSONObject.quote(reason)}); }",
            null
        )
    }

    private fun wipeSensitiveData() {
        releaseNativeRecorder()
        WebStorage.getInstance().deleteAllData()
        webView.evaluateJavascript(
            "if (typeof secureWipeSensitiveData === 'function') { secureWipeSensitiveData(); }",
            null
        )
    }

    private fun redactConsoleText(raw: String?): String {
        if (raw.isNullOrBlank()) return "(leere Meldung)"
        val withoutPayload = Regex("data:[^\\s\"']{16,}").replace(raw, "[Nutzdaten entfernt]")
        return withoutPayload.take(300)
    }

    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun requestMicrophonePermissionFromUi() {
        if (isRecordAudioGranted()) {
            notifyMicrophonePermission(true)
            return
        }
        if (!::webView.isInitialized || microphonePermissionInFlight) return

        microphonePermissionInFlight = true
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun isRecordAudioGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun grantAudioCapture(request: PermissionRequest) {
        try {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } catch (e: Exception) {
            Log.e(TAG, "grant() fehlgeschlagen: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    private fun denyPermissionRequest(request: PermissionRequest) {
        try {
            request.deny()
        } catch (e: Exception) {
            Log.e(TAG, "deny() fehlgeschlagen: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    private fun notifyMicrophonePermission(granted: Boolean) {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "if (typeof onMicrophonePermissionResult === 'function') { " +
                "onMicrophonePermissionResult($granted); }",
            null
        )
    }

    private fun startNativeRecording(): Boolean {
        if (nativeRecorder != null) return false
        if (!isRecordAudioGranted()) return false

        return try {
            val file = File(cacheDir, "calc_native_recording.m4a")
            if (file.exists()) file.delete()
            nativeRecordingFile = file
            nativeRecordingMime = "audio/mp4"

            var recorder = buildNativeRecorder(file, samplingRate = 24000)
            if (recorder == null) {
                recorder = buildNativeRecorder(file, samplingRate = 0)
            }
            if (recorder == null) {
                Log.e(TAG, "Native Audioaufnahme: AAC-Encoder nicht konfigurierbar")
                releaseNativeRecorder()
                return false
            }

            recorder.start()
            nativeRecorder = recorder
            true
        } catch (e: Exception) {
            Log.e(TAG, "Native Audioaufnahme-Start fehlgeschlagen: ${e.javaClass.name}: ${e.message}")
            releaseNativeRecorder()
            false
        }
    }

    private fun buildNativeRecorder(file: File, samplingRate: Int): MediaRecorder? {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            runCatching { recorder.setAudioEncodingBitRate(24000) }
            if (samplingRate > 0) runCatching { recorder.setAudioSamplingRate(samplingRate) }
            recorder.setOutputFile(file)
            recorder.prepare()
            recorder
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Native Aufnahme: Konfiguration abgelehnt (samplingRate=$samplingRate): " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            runCatching { recorder.release() }
            null
        }
    }

    private fun stopNativeRecordingAndRead(): String {
        val recorder = nativeRecorder ?: return ""
        nativeRecorder = null
        val file = nativeRecordingFile
        nativeRecordingFile = null
        return try {
            recorder.stop()
            recorder.release()
            if (file == null || !file.exists() || file.length() == 0L) {
                Log.e(TAG, "Native Audioaufnahme: keine Daten geschrieben")
                return ""
            }
            val bytes = file.readBytes()
            val byteCount = bytes.size
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            bytes.fill(0)
            Log.i(
                TAG,
                "Native Audioaufnahme beendet: $byteCount Bytes (~" +
                    String.format(Locale.US, "%.1f", byteCount / 1024.0) +
                    " KB), Data-URL ${b64.length} Zeichen"
            )
            "data:${nativeRecordingMime};base64,$b64"
        } catch (e: Exception) {
            Log.e(TAG, "Native Audioaufnahme-Stopp fehlgeschlagen: ${e.javaClass.name}: ${e.message}")
            ""
        } finally {
            file?.delete()
        }
    }

    private fun releaseNativeRecorder() {
        val recorder = nativeRecorder
        nativeRecorder = null
        if (recorder != null) {
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
        }
        val file = nativeRecordingFile
        nativeRecordingFile = null
        if (file != null) {
            try { file.delete() } catch (_: Exception) {}
        }
    }

    private var panicCodeDialogVisible = false
    private var mainPinDialogVisible = false

    private fun enforcePanicCodeSetup() {
        if (PanicCodeStore.isConfigured(this)) return
        showPanicCodeSetupDialog()
    }

    private fun showPanicCodeSetupDialog() {
        if (isFinishing || isDestroyed || panicCodeDialogVisible) return
        val isChange = PanicCodeStore.isConfigured(this)

        val infoText = TextView(this).apply {
            textSize = 14f
            text = getString(if (isChange) R.string.panic_setup_info_change else R.string.panic_setup_info_create)
            setPadding(0, 0, 0, dp(12))
        }

        val currentInput = passwordField(getString(R.string.panic_setup_current_hint))
        val newInput = passwordField(getString(R.string.panic_setup_new_hint, PanicCodeStore.MIN_CODE_LENGTH))
        val repeatInput = passwordField(getString(R.string.panic_setup_repeat_hint))
        val errorText = TextView(this).apply {
            setTextColor(0xFFEF4444.toInt())
            textSize = 13f
            visibility = View.GONE
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(infoText, matchParentWrapParams())
            if (isChange) addView(currentInput, matchParentWrapParams())
            addView(newInput, matchParentWrapParams())
            addView(repeatInput, matchParentWrapParams())
            addView(errorText, matchParentWrapParams())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(if (isChange) R.string.panic_setup_title_change else R.string.panic_setup_title_create))
            .setView(container)
            .setCancelable(isChange)
            .setPositiveButton(getString(if (isChange) R.string.panic_setup_save else R.string.panic_setup_create), null)
            .setNegativeButton(if (isChange) getString(R.string.panic_setup_cancel) else null, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.decorView?.filterTouchesWhenObscured = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = validatePanicCodeInput(isChange, currentInput, newInput, repeatInput)
                if (error != null) {
                    errorText.text = error
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val stored = PanicCodeStore.setPanicCode(this, newInput.text.toString())
                if (!stored) {
                    errorText.text = getString(R.string.panic_error_store)
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                currentInput.text?.clear()
                newInput.text?.clear()
                repeatInput.text?.clear()
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.panic_saved), Toast.LENGTH_SHORT).show()
            }
        }
        dialog.setOnDismissListener { panicCodeDialogVisible = false }
        panicCodeDialogVisible = true
        dialog.show()
    }

    private fun validatePanicCodeInput(
        isChange: Boolean,
        currentInput: EditText,
        newInput: EditText,
        repeatInput: EditText
    ): String? {
        if (isChange && !PanicCodeStore.verify(this, currentInput.text.toString())) {
            return getString(R.string.panic_error_current)
        }
        val code = newInput.text.toString()
        if (code.length < PanicCodeStore.MIN_CODE_LENGTH) {
            return getString(R.string.panic_error_min_length, PanicCodeStore.MIN_CODE_LENGTH)
        }
        if (!PanicCodeStore.hasDecimalSeparator(code)) {
            return getString(R.string.panic_error_separator)
        }
        if (!PanicCodeStore.isValidCode(code)) {
            return getString(R.string.panic_error_too_long)
        }
        if (code != repeatInput.text.toString()) {
            return getString(R.string.panic_error_mismatch)
        }
        return null
    }

    private fun showMainPinChangeDialog() {
        if (isFinishing || isDestroyed || mainPinDialogVisible) return
        val isChange = MainPinStore.isConfigured(this)

        val currentInput = passwordField(getString(R.string.main_pin_current_hint))
        val newInput = passwordField(getString(R.string.main_pin_new_hint, MainPinStore.MIN_CODE_LENGTH))
        val repeatInput = passwordField(getString(R.string.main_pin_repeat_hint))
        val errorText = TextView(this).apply {
            setTextColor(0xFFEF4444.toInt())
            textSize = 13f
            visibility = View.GONE
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            if (isChange) addView(currentInput, matchParentWrapParams())
            addView(newInput, matchParentWrapParams())
            addView(repeatInput, matchParentWrapParams())
            addView(errorText, matchParentWrapParams())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(if (isChange) R.string.main_pin_title_change else R.string.main_pin_title_create))
            .setView(container)
            .setCancelable(isChange)
            .setPositiveButton(getString(if (isChange) R.string.panic_setup_save else R.string.panic_setup_create), null)
            .setNegativeButton(if (isChange) getString(R.string.panic_setup_cancel) else null, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.decorView?.filterTouchesWhenObscured = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = validateMainPinInput(isChange, currentInput, newInput, repeatInput)
                if (error != null) {
                    errorText.text = error
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val stored = MainPinStore.setPin(this, newInput.text.toString())
                if (!stored) {
                    errorText.text = getString(R.string.panic_error_store)
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                currentInput.text?.clear()
                newInput.text?.clear()
                repeatInput.text?.clear()
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.main_pin_saved), Toast.LENGTH_SHORT).show()
            }
        }
        dialog.setOnDismissListener { mainPinDialogVisible = false }
        mainPinDialogVisible = true
        dialog.show()
    }

    private fun validateMainPinInput(
        isChange: Boolean,
        currentInput: EditText,
        newInput: EditText,
        repeatInput: EditText
    ): String? {
        if (isChange && !MainPinStore.verify(this, currentInput.text.toString())) {
            return getString(R.string.main_pin_error_current)
        }
        val pin = newInput.text.toString()
        if (pin.length < MainPinStore.MIN_CODE_LENGTH) {
            return getString(R.string.panic_error_min_length, MainPinStore.MIN_CODE_LENGTH)
        }
        if (!MainPinStore.hasDecimalSeparator(pin)) {
            return getString(R.string.panic_error_separator)
        }
        if (!MainPinStore.isValidPin(pin)) {
            return getString(R.string.panic_error_too_long)
        }
        if (pin != repeatInput.text.toString()) {
            return getString(R.string.panic_error_mismatch)
        }
        return null
    }

    private fun passwordField(hint: String): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        this.hint = hint
    }

    private fun matchParentWrapParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun handleDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.isNullOrBlank()) return

        when {
            url.startsWith("blob:") -> downloadBlob(url, contentDisposition, mimeType)
            url.startsWith("data:") -> downloadDataUrl(url, contentDisposition, mimeType)
            else -> try {
                enqueueDownloadManager(url, userAgent, contentDisposition, mimeType)
            } catch (e: SecurityException) {
                Log.w(TAG, "Download abgelehnt (Offline-Garantie): ${e.message}")
                Toast.makeText(
                    this,
                    getString(R.string.download_blocked),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Download fehlgeschlagen: ${e.javaClass.simpleName}")
                notifyDownloadFailed()
            }
        }
    }

    private fun downloadBlob(blobUrl: String, contentDisposition: String?, mimeType: String?) {
        pendingBlobDownloads[blobUrl] = PendingDownload(contentDisposition, mimeType)

        val js = "(function() {\n" +
                "  fetch(" + JSONObject.quote(blobUrl) + ")\n" +
                "    .then(function(r) { return r.blob(); })\n" +
                "    .then(function(b) {\n" +
                "      var fr = new FileReader();\n" +
                "      fr.onloadend = function() {\n" +
                "        if (window.AndroidBridge) { window.AndroidBridge.onDownloadBlob(" +
                JSONObject.quote(blobUrl) + ", fr.result, b.type || ''); }\n" +
                "      };\n" +
                "      fr.onerror = function() {\n" +
                "        if (window.AndroidBridge) { window.AndroidBridge.onDownloadBlob(" +
                JSONObject.quote(blobUrl) + ", '', ''); }\n" +
                "      };\n" +
                "      fr.readAsDataURL(b);\n" +
                "    })\n" +
                "    .catch(function() {\n" +
                "      if (window.AndroidBridge) { window.AndroidBridge.onDownloadBlob(" +
                JSONObject.quote(blobUrl) + ", '', ''); }\n" +
                "    });\n" +
                "})()"

        webView.evaluateJavascript(js, null)
    }

    private fun onBlobDownloadCompleted(blobUrl: String, dataUrl: String, blobMimeType: String) {
        val pending = pendingBlobDownloads.remove(blobUrl) ?: return
        if (dataUrl.isBlank() || !dataUrl.startsWith("data:")) {
            notifyDownloadFailed()
            return
        }
        val finalMime = pending.mimeType?.takeIf { it.isNotBlank() } ?: blobMimeType
        saveDataUrl(dataUrl, pending.contentDisposition, finalMime)
    }

    private fun downloadDataUrl(dataUrl: String, contentDisposition: String?, mimeType: String?) {
        saveDataUrl(dataUrl, contentDisposition, mimeType)
    }

    private fun saveDataUrl(dataUrl: String, contentDisposition: String?, mimeType: String?) {
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex < 0) {
            notifyDownloadFailed()
            return
        }

        val header = dataUrl.substring(0, commaIndex)
        val payload = dataUrl.substring(commaIndex + 1)
        val detectedMime = header.substringAfter("data:", "").substringBefore(';')
            .ifBlank { "application/octet-stream" }
        val isBase64 = header.substringAfter(';', "").contains("base64", ignoreCase = true)

        val bytes = try {
            if (isBase64) Base64.decode(payload, Base64.DEFAULT)
            else payload.toByteArray(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Download-Dekodierung fehlgeschlagen: ${e.javaClass.simpleName}")
            notifyDownloadFailed()
            return
        }

        val finalMime = mimeType?.takeIf { it.isNotBlank() } ?: detectedMime
        val fileName = resolveFileName(contentDisposition, finalMime, url = null)
        saveDownloadedBytes(bytes, fileName, finalMime)
    }

    private fun enqueueDownloadManager(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)

        if (scheme == "http" || scheme == "https") {
            throw SecurityException(
                "Download blockiert: http/https sind in dieser Offline-App verboten " +
                    "(Schema: $scheme)."
            )
        }

        if (scheme != "content" && scheme != "file") {
            throw SecurityException(
                "Download blockiert: nur lokale content://- und file://-URIs sind erlaubt " +
                    "(uebergebenes Schema: ${scheme ?: "keins"})."
            )
        }

        try {
            val fileName = resolveFileName(contentDisposition, mimeType, url)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                if (!userAgent.isNullOrBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookie)
                }
            }

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "DownloadManager-Fehler: ${e.javaClass.simpleName}")
            notifyDownloadFailed()
        }
    }

    private fun saveDownloadedBytes(bytes: ByteArray, fileName: String, mimeType: String) {
        try {
            val ok = androidBridge.saveToPublicDownloads(bytes, fileName, mimeType)
            if (ok) {
                Toast.makeText(this, getString(R.string.download_saved), Toast.LENGTH_SHORT).show()
            } else {
                notifyDownloadFailed()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download-Speichern fehlgeschlagen: ${e.javaClass.simpleName}")
            notifyDownloadFailed()
        } finally {
            bytes.fill(0)
        }
    }

    private fun notifyDownloadFailed() {
        Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
    }

    private fun resolveFileName(contentDisposition: String?, mimeType: String?, url: String?): String {
        if (!contentDisposition.isNullOrBlank()) {
            val name = extractFileNameFromContentDisposition(contentDisposition)
            if (!name.isNullOrBlank()) return name
        }

        if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
            val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val name = guessed.takeIf { it.isNotBlank() && it != url }?.let { sanitizeFileName(it) }
            if (!name.isNullOrBlank()) return name
        }

        val extension = mimeTypeToExtension(mimeType) ?: "bin"
        return "download_" + System.currentTimeMillis() + "." + extension
    }

    private fun extractFileNameFromContentDisposition(contentDisposition: String): String? {
        Regex("filename\\*\\s*=\\s*(UTF-8''|iso-8859-1'')?([^;]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.let { match ->
                val raw = match.groupValues[2].trim().trim('"')
                val decoded = try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (e: Exception) {
                    raw
                }
                val name = sanitizeFileName(decoded)
                if (!name.isNullOrBlank()) return name
            }

        Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.let { match ->
                val name = sanitizeFileName(match.groupValues[1].trim().trim('"', '\''))
                if (!name.isNullOrBlank()) return name
            }

        return null
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\.\\."), "_")
            .trim()
            .take(120)
    }

    private fun mimeTypeToExtension(mimeType: String?): String? {
        if (mimeType.isNullOrBlank()) return null
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: when {
                mimeType.contains("png") -> "png"
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("gif") -> "gif"
                else -> null
            }
    }

    private fun setupFaceDownLock() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(faceDownListener, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    private fun lockVaultImmediately() {
        webView.post {
            webView.evaluateJavascript(
                "if (typeof secureWipeSensitiveData === 'function') secureWipeSensitiveData();",
                null
            )
            webView.reload()
        }
    }
}
