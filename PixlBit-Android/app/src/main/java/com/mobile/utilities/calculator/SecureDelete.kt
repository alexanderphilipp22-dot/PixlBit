package com.mobile.utilities.calculator

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.WebStorage
import androidx.annotation.RequiresApi
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

object SecureDelete {

    private const val TAG = "CalculatorSecureDelete"

    private const val OVERWRITE_PASSES = 3

    private const val BUFFER_SIZE = 64 * 1024

    private const val ALBUM_NAME = "Calculator"

    private const val PICTURES_DIR_NAME = "Pictures"

    private const val WORKMANAGER_DB_PREFIX = "androidx.work"

    private const val PUBLIC_ALBUM_NAME = "Calculator"
    private const val PUBLIC_RELATIVE_PATH = "Pictures/Calculator"

    private val secureRandom = SecureRandom()

    fun shredFile(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) return false

        val length = file.length()
        return try {
            if (length > 0) {
                val buffer = ByteArray(BUFFER_SIZE)
                repeat(OVERWRITE_PASSES) {
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(0)
                        var remaining = length
                        while (remaining > 0) {
                            val chunk = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                            secureRandom.nextBytes(buffer)
                            raf.write(buffer, 0, chunk)
                            remaining -= chunk
                        }
                    }
                }
            }
            RandomAccessFile(file, "rw").use { it.setLength(0) }
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Sicheres Loeschen fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    fun shredDirectory(dir: File, keep: (File) -> Boolean = { false }): Boolean {
        if (keep(dir)) return true
        if (!dir.exists()) return true
        if (!dir.isDirectory) return shredFile(dir)
        return try {
            dir.listFiles()?.forEach { child ->
                if (keep(child)) return@forEach
                if (child.isDirectory) shredDirectory(child, keep) else shredFile(child)
            }
            dir.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sicheres Loeschen des Verzeichnisses fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    fun wipePrivateImages(context: Context): Boolean {
        val internalOk = shredDirectory(File(File(context.filesDir, PICTURES_DIR_NAME), ALBUM_NAME))

        val legacyPictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val legacyOk = if (legacyPictures == null) {
            true
        } else {
            shredDirectory(File(legacyPictures, ALBUM_NAME))
        }

        return internalOk && legacyOk
    }

    fun wipePublicImages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wipePublicImagesViaMediaStore(context)
        } else {
            wipePublicImagesViaLegacyFile(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun wipePublicImagesViaMediaStore(context: Context): Boolean {
        return try {
            val args = arrayOf("$PUBLIC_RELATIVE_PATH%")

            val removedImages = context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                args
            )

            val removedBins = context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                args
            )

            Log.i(TAG, "Oeffentliches Album geleert: ${removedImages + removedBins} Eintraege")
            removedImages >= 0 && removedBins >= 0
        } catch (e: Exception) {
            Log.e(TAG, "Oeffentliches Loeschen fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun wipePublicImagesViaLegacyFile(context: Context): Boolean {
        val albumDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            PUBLIC_ALBUM_NAME
        )
        val shredded = shredDirectory(albumDir)
        try {
            MediaScannerConnection.scanFile(context, arrayOf(albumDir.absolutePath), null, null)
        } catch (e: Exception) {
        }
        return shredded
    }

    fun wipeWebStorage(context: Context): Boolean {
        val logicalOk = deleteWebStorageData()
        val filesOk = shredWebViewDataDirs(context)
        return logicalOk && filesOk
    }

    fun deleteWebStorageData(): Boolean {
        return try {
            WebStorage.getInstance().deleteAllData()
            true
        } catch (e: Exception) {
            Log.e(TAG, "WebStorage-Delete fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    fun shredWebViewDataDirs(context: Context): Boolean {
        var ok = true

        if (!shredDirectory(File(context.dataDir, "app_webview"))) ok = false

        val databases = File(context.dataDir, "databases")
        if (!shredDirectory(databases) { it.name.startsWith(WORKMANAGER_DB_PREFIX) }) ok = false

        return ok
    }

    fun wipeSharedPreferences(context: Context): Boolean {
        val prefsDir = File(context.dataDir, "shared_prefs")
        return shredDirectory(prefsDir)
    }

    fun wipeCacheDir(context: Context): Boolean =
        shredDirectory(context.cacheDir)

    fun wipeFilesOnly(context: Context): Boolean {
        val publicOk = wipePublicImages(context)
        val imagesOk = wipePrivateImages(context)
        val prefsOk = wipeSharedPreferences(context)
        val dirsOk = shredWebViewDataDirs(context)
        val cacheOk = wipeCacheDir(context)
        return publicOk && imagesOk && prefsOk && dirsOk && cacheOk
    }

    fun wipeAll(context: Context): Boolean {
        val imagesOk = wipePrivateImages(context)
        val prefsOk = wipeSharedPreferences(context)
        val webStorageOk = wipeWebStorage(context)
        val cacheOk = wipeCacheDir(context)
        return imagesOk && prefsOk && webStorageOk && cacheOk
    }
}

