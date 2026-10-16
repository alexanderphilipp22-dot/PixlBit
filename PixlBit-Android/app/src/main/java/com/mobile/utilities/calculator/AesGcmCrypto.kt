package com.mobile.utilities.calculator

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesGcmCrypto {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private const val SALT_LENGTH = 16
    private const val GCM_NONCE_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    private val secureRandom = SecureRandom()

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val nonce = ByteArray(GCM_NONCE_LENGTH).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        )
        val ciphertext = cipher.doFinal(plaintext)

        return salt + nonce + ciphertext
    }

    fun decrypt(payload: ByteArray, passphrase: CharArray): ByteArray {
        val minLength = SALT_LENGTH + GCM_NONCE_LENGTH + (GCM_TAG_LENGTH_BITS / 8)
        require(payload.size >= minLength) {
            "Payload zu kurz (${payload.size} Bytes). Mindestens $minLength Bytes erwartet."
        }

        val salt = payload.copyOfRange(0, SALT_LENGTH)
        val nonce = payload.copyOfRange(SALT_LENGTH, SALT_LENGTH + GCM_NONCE_LENGTH)
        val ciphertext = payload.copyOfRange(SALT_LENGTH + GCM_NONCE_LENGTH, payload.size)

        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        )
        return cipher.doFinal(ciphertext)
    }

    fun normalizePassphrase(passphrase: CharArray): CharArray =
        Normalizer.normalize(String(passphrase), Normalizer.Form.NFC).toCharArray()

    fun encryptToBase64(plaintext: String, passphrase: CharArray): String {
        val normalized = normalizePassphrase(passphrase)
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        try {
            val encrypted = encrypt(plainBytes, normalized)
            return Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } finally {
            // Plaintext sofort aus dem RAM nullen (Memory Wipe).
            plainBytes.fill(0)
            normalized.fill('\u0000')
            System.gc()
        }
    }

    fun decryptFromBase64(payloadBase64: String, passphrase: CharArray): String {
        val payload = Base64.decode(payloadBase64, Base64.NO_WRAP)
        val normalized = normalizePassphrase(passphrase)
        val sameAsRaw = normalized.contentEquals(passphrase)
        var decrypted: ByteArray? = null
        try {
            decrypted = decrypt(payload, normalized)
            return decrypted.toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            if (sameAsRaw) throw e
            decrypted?.fill(0)
            decrypted = decrypt(payload, passphrase)
            return decrypted.toString(Charsets.UTF_8)
        } finally {
            // Klartext + Roheingabe sofort aus dem RAM nullen.
            decrypted?.fill(0)
            payload.fill(0)
            normalized.fill('\u0000')
            System.gc()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val keyBytes = Argon2Kdf.derive(passphrase, salt)
        return try {
            SecretKeySpec(keyBytes, "AES")
        } finally {
            keyBytes.fill(0)
            System.gc()
        }
    }
}
