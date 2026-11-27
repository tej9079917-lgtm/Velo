package com.example.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedEnvelope(
    val ciphertextBase64: String,
    val ivBase64: String
)

data class VaultInitializationBundle(
    val saltBase64: String,
    val verifierCiphertext: String,
    val verifierIv: String,
    val kdfIterations: Int,
    val keyFingerprint: String,
    val derivedKey: SecretKey
)

/**
 * Zero-knowledge End-to-End Encryption engine using PBKDF2WithHmacSHA256 + AES-256-GCM.
 * Master passphrases and derived SecretKeys exist strictly in local volatile memory and never leave the device.
 */
object E2EECryptoEngine {
    const val DEFAULT_KDF_ITERATIONS = 120_000
    const val CRYPTO_VERSION = 1
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val VERIFIER_PLAINTEXT = "VELO_E2EE_VERIFIED_V1"

    private val secureRandom = SecureRandom()

    fun createNewVault(passphrase: String): VaultInitializationBundle {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(passphrase, salt, DEFAULT_KDF_ITERATIONS)
        val verifier = encrypt(VERIFIER_PLAINTEXT, key)
        return VaultInitializationBundle(
            saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            verifierCiphertext = verifier.ciphertextBase64,
            verifierIv = verifier.ivBase64,
            kdfIterations = DEFAULT_KDF_ITERATIONS,
            keyFingerprint = computeKeyFingerprint(key),
            derivedKey = key
        )
    }

    fun unlockVault(
        passphrase: String,
        saltBase64: String,
        verifierCiphertext: String,
        verifierIv: String,
        iterations: Int
    ): SecretKey? {
        return try {
            val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
            val safeIterations = iterations.coerceIn(100_000, 500_000)
            val candidateKey = deriveKey(passphrase, salt, safeIterations)
            val decrypted = decrypt(verifierCiphertext, verifierIv, candidateKey)
            if (decrypted == VERIFIER_PLAINTEXT) candidateKey else null
        } catch (_: Exception) {
            null
        }
    }

    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = DEFAULT_KDF_ITERATIONS): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val raw = factory.generateSecret(spec).encoded
            SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plaintext: String, key: SecretKey): EncryptedEnvelope {
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedEnvelope(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String, key: SecretKey): String {
        val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun computeKeyFingerprint(key: SecretKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        val hex = digest.take(8).joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString("-")
    }

    fun generateStrongPassword(
        length: Int = 20,
        includeUppercase: Boolean = true,
        includeLowercase: Boolean = true,
        includeDigits: Boolean = true,
        includeSymbols: Boolean = true
    ): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "!@#$%^&*-_=+?"

        val pools = mutableListOf<String>()
        if (includeUppercase) pools.add(upper)
        if (includeLowercase) pools.add(lower)
        if (includeDigits) pools.add(digits)
        if (includeSymbols) pools.add(symbols)
        if (pools.isEmpty()) pools.add(lower + digits)

        val safeLength = length.coerceIn(10, 48)
        val result = CharArray(safeLength)
        var idx = 0
        // Guarantee at least one char from each selected pool
        for (pool in pools) {
            if (idx < safeLength) {
                result[idx++] = pool[secureRandom.nextInt(pool.length)]
            }
        }
        val combined = pools.joinToString("")
        while (idx < safeLength) {
            result[idx++] = combined[secureRandom.nextInt(combined.length)]
        }
        // Fisher-Yates shuffle using SecureRandom
        for (i in result.lastIndex downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val tmp = result[i]
            result[i] = result[j]
            result[j] = tmp
        }
        return String(result)
    }

    fun evaluatePassphraseStrength(passphrase: String): Pair<Int, String> {
        var score = 0
        if (passphrase.length >= 8) score += 25
        if (passphrase.length >= 14) score += 20
        if (passphrase.any { it.isUpperCase() } && passphrase.any { it.isLowerCase() }) score += 20
        if (passphrase.any { it.isDigit() }) score += 15
        if (passphrase.any { !it.isLetterOrDigit() }) score += 20
        val label = when {
            score >= 85 -> "Cryptographic Strong"
            score >= 60 -> "Strong"
            score >= 40 -> "Moderate"
            else -> "Weak (min 8 chars)"
        }
        return score.coerceAtMost(100) to label
    }
}
