package com.mobile.utilities.calculator

import androidx.annotation.Keep
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id-KDF (speicherhart): 64 MiB, 3 Iterationen, 4 Lanes, 256-bit Ausgabe.
 */
@Keep
object Argon2Kdf {

    private const val MEMORY_KIB = 65536   // 64 MiB
    private const val ITERATIONS = 3
    private const val PARALLELISM = 4      // Lanes
    const val OUTPUT_BYTES = 32            // 256-bit

    fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(PARALLELISM)
            .withMemoryAsKB(MEMORY_KIB)
            .withIterations(ITERATIONS)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        val output = ByteArray(OUTPUT_BYTES)
        generator.generateBytes(password, output)
        return output
    }
}
