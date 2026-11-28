package com.example.crypto

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
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
 * Zero-knowledge Multi-Layer End-to-End Encryption engine.
 *
 * Password Vault records use a 3-Layer Defense-in-Depth Cryptographic Envelope ("MLV2:"):
 *   - Layer 1 (Inner AEAD + Length Obfuscation): Random 256-bit per-record Ephemeral DEK encrypts
 *     length-padded plaintext via AES-256-GCM (96-bit IV, 128-bit auth tag, AAD bound).
 *   - Layer 2 (Cascaded Cipher Wrap + Encrypt-then-MAC): Domain-separated HKDF-SHA256 sub-keys
 *     wrap the DEK + Layer-1 payload using AES-256-CBC + constant-time HMAC-SHA256 authentication.
 *   - Layer 3 (Outer Master AEAD Seal): Domain-separated HKDF-SHA256 outer seal key wraps the
 *     entire Layer-2 authenticated bundle with a second independent AES-256-GCM pass.
 *
 * Master passphrases and derived SecretKeys exist strictly in volatile RAM and never leave the device.
 */
object E2EECryptoEngine {
    const val DEFAULT_KDF_ITERATIONS = 210_000
    const val CRYPTO_VERSION = 2
    private const val MULTI_LAYER_PREFIX = "MLV2:"
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_IV_BYTES = 12
    private const val CBC_IV_BYTES = 16
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val RECORD_SALT_BYTES = 32
    private const val DEK_BYTES = 32
    private const val VERIFIER_PLAINTEXT = "VELO_E2EE_VERIFIED_V1"

    private val AAD_LAYER1 = "VELO_L1_CRED_V2".toByteArray(Charsets.UTF_8)
    private val AAD_LAYER3 = "VELO_L3_SEAL_V2".toByteArray(Charsets.UTF_8)

    private val secureRandom = SecureRandom()

    fun createNewVault(passphrase: String): VaultInitializationBundle {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(passphrase, salt, DEFAULT_KDF_ITERATIONS)
        val verifier = encryptMultiLayer(VERIFIER_PLAINTEXT, key)
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
            val safeIterations = iterations.coerceIn(100_000, 600_000)
            val candidateKey = deriveKey(passphrase, salt, safeIterations)
            val decrypted = decryptAuto(verifierCiphertext, verifierIv, candidateKey)
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

    /**
     * 3-Layer Defense-in-Depth encryption for stored passwords & high-sensitivity vault secrets.
     * Each record gets its own random 32-byte salt, random 256-bit DEK, random length padding,
     * independent HKDF-SHA256 sub-keys, AES-256-GCM + AES-256-CBC/HMAC-SHA256 + AES-256-GCM.
     */
    fun encryptMultiLayer(plaintext: String, masterKey: SecretKey): EncryptedEnvelope {
        val recordSalt = ByteArray(RECORD_SALT_BYTES).also { secureRandom.nextBytes(it) }
        val dekBytes = ByteArray(DEK_BYTES).also { secureRandom.nextBytes(it) }

        try {
            // --- LAYER 1: Length-obfuscated plaintext + Ephemeral DEK AES-256-GCM ---
            val rawPlain = plaintext.toByteArray(Charsets.UTF_8)
            val padLen = 16 + secureRandom.nextInt(48) // Random 16..63 bytes hides exact password length
            val padBytes = ByteArray(padLen).also { secureRandom.nextBytes(it) }
            val paddedPlain = ByteArrayOutputStream().use { baos ->
                DataOutputStream(baos).use { dos ->
                    dos.writeInt(rawPlain.size)
                    dos.write(rawPlain)
                    dos.write(padBytes)
                }
                baos.toByteArray()
            }

            val l1Iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
            val dekKey = SecretKeySpec(dekBytes, "AES")
            val l1Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            l1Cipher.init(Cipher.ENCRYPT_MODE, dekKey, GCMParameterSpec(GCM_TAG_BITS, l1Iv))
            l1Cipher.updateAAD(AAD_LAYER1)
            val l1Ciphertext = l1Cipher.doFinal(paddedPlain)

            // Pack Layer 1 payload + Ephemeral DEK
            val layer1Bundle = ByteArrayOutputStream().use { baos ->
                DataOutputStream(baos).use { dos ->
                    dos.writeInt(dekBytes.size)
                    dos.write(dekBytes)
                    dos.writeInt(l1Iv.size)
                    dos.write(l1Iv)
                    dos.writeInt(l1Ciphertext.size)
                    dos.write(l1Ciphertext)
                }
                baos.toByteArray()
            }

            // --- LAYER 2: HKDF Sub-Keys + AES-256-CBC + HMAC-SHA256 (Encrypt-then-MAC) ---
            val wrapEncKey = SecretKeySpec(hkdfSha256(masterKey.encoded, recordSalt, "VELO_WRAP_ENC_V2", 32), "AES")
            val wrapMacKey = hkdfSha256(masterKey.encoded, recordSalt, "VELO_WRAP_MAC_V2", 32)
            val l2Iv = ByteArray(CBC_IV_BYTES).also { secureRandom.nextBytes(it) }
            val l2Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            l2Cipher.init(Cipher.ENCRYPT_MODE, wrapEncKey, IvParameterSpec(l2Iv))
            val l2Ciphertext = l2Cipher.doFinal(layer1Bundle)

            val macInput = ByteArrayOutputStream().use { baos ->
                baos.write(recordSalt)
                baos.write(l2Iv)
                baos.write(l2Ciphertext)
                baos.toByteArray()
            }
            val l2Hmac = computeHmacSha256(wrapMacKey, macInput)

            val layer2Bundle = ByteArrayOutputStream().use { baos ->
                DataOutputStream(baos).use { dos ->
                    dos.writeInt(recordSalt.size)
                    dos.write(recordSalt)
                    dos.writeInt(l2Iv.size)
                    dos.write(l2Iv)
                    dos.writeInt(l2Hmac.size)
                    dos.write(l2Hmac)
                    dos.writeInt(l2Ciphertext.size)
                    dos.write(l2Ciphertext)
                }
                baos.toByteArray()
            }

            // --- LAYER 3: Outer Master AEAD Seal (AES-256-GCM with domain-separated key) ---
            val outerSealKey = SecretKeySpec(hkdfSha256(masterKey.encoded, l2Iv, "VELO_OUTER_SEAL_V2", 32), "AES")
            val l3Iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
            val l3Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            l3Cipher.init(Cipher.ENCRYPT_MODE, outerSealKey, GCMParameterSpec(GCM_TAG_BITS, l3Iv))
            l3Cipher.updateAAD(AAD_LAYER3)
            val l3Ciphertext = l3Cipher.doFinal(layer2Bundle)

            // Store l2Iv prefix inside payload so outerSealKey can be re-derived before decrypting Layer 3
            val finalPacket = ByteArrayOutputStream().use { baos ->
                DataOutputStream(baos).use { dos ->
                    dos.writeInt(l2Iv.size)
                    dos.write(l2Iv)
                    dos.writeInt(l3Ciphertext.size)
                    dos.write(l3Ciphertext)
                }
                baos.toByteArray()
            }

            return EncryptedEnvelope(
                ciphertextBase64 = MULTI_LAYER_PREFIX + Base64.encodeToString(finalPacket, Base64.NO_WRAP),
                ivBase64 = Base64.encodeToString(l3Iv, Base64.NO_WRAP)
            )
        } finally {
            dekBytes.fill(0)
        }
    }

    /**
     * Transparently decrypts both 3-Layer ("MLV2:") envelopes and legacy single-layer v1 envelopes.
     */
    fun decryptAuto(ciphertextPayload: String, ivBase64: String, masterKey: SecretKey): String {
        return if (ciphertextPayload.startsWith(MULTI_LAYER_PREFIX)) {
            decryptMultiLayer(ciphertextPayload.removePrefix(MULTI_LAYER_PREFIX), ivBase64, masterKey)
        } else {
            decrypt(ciphertextPayload, ivBase64, masterKey)
        }
    }

    fun isMultiLayerEnvelope(ciphertextPayload: String): Boolean =
        ciphertextPayload.startsWith(MULTI_LAYER_PREFIX)

    private fun decryptMultiLayer(
        encodedPacket: String,
        l3IvBase64: String,
        masterKey: SecretKey
    ): String {
        val packetBytes = Base64.decode(encodedPacket, Base64.NO_WRAP)
        val l3Iv = Base64.decode(l3IvBase64, Base64.NO_WRAP)

        // Unpack outer container
        var l2IvHeader: ByteArray
        var l3Ciphertext: ByteArray
        DataInputStream(ByteArrayInputStream(packetBytes)).use { dis ->
            val l2IvLen = dis.readInt()
            require(l2IvLen == CBC_IV_BYTES) { "Invalid outer IV length." }
            l2IvHeader = ByteArray(l2IvLen).also { dis.readFully(it) }
            val l3Len = dis.readInt()
            require(l3Len in 17..(2 * 1024 * 1024)) { "Invalid Layer 3 payload length." }
            l3Ciphertext = ByteArray(l3Len).also { dis.readFully(it) }
        }

        // --- UNWRAP LAYER 3: Outer Master AEAD Seal ---
        val outerSealKey = SecretKeySpec(hkdfSha256(masterKey.encoded, l2IvHeader, "VELO_OUTER_SEAL_V2", 32), "AES")
        val l3Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        l3Cipher.init(Cipher.DECRYPT_MODE, outerSealKey, GCMParameterSpec(GCM_TAG_BITS, l3Iv))
        l3Cipher.updateAAD(AAD_LAYER3)
        val layer2Bundle = l3Cipher.doFinal(l3Ciphertext)

        // --- UNWRAP LAYER 2: Verify HMAC-SHA256 in constant time BEFORE CBC decryption ---
        var recordSalt: ByteArray
        var l2Iv: ByteArray
        var expectedHmac: ByteArray
        var l2Ciphertext: ByteArray
        DataInputStream(ByteArrayInputStream(layer2Bundle)).use { dis ->
            val saltLen = dis.readInt()
            require(saltLen == RECORD_SALT_BYTES) { "Invalid record salt length." }
            recordSalt = ByteArray(saltLen).also { dis.readFully(it) }
            val ivLen = dis.readInt()
            require(ivLen == CBC_IV_BYTES) { "Invalid Layer 2 IV length." }
            l2Iv = ByteArray(ivLen).also { dis.readFully(it) }
            val hmacLen = dis.readInt()
            require(hmacLen == 32) { "Invalid HMAC length." }
            expectedHmac = ByteArray(hmacLen).also { dis.readFully(it) }
            val cLen = dis.readInt()
            require(cLen in 16..(2 * 1024 * 1024)) { "Invalid Layer 2 ciphertext length." }
            l2Ciphertext = ByteArray(cLen).also { dis.readFully(it) }
        }

        val wrapMacKey = hkdfSha256(masterKey.encoded, recordSalt, "VELO_WRAP_MAC_V2", 32)
        val macInput = ByteArrayOutputStream().use { baos ->
            baos.write(recordSalt)
            baos.write(l2Iv)
            baos.write(l2Ciphertext)
            baos.toByteArray()
        }
        val computedHmac = computeHmacSha256(wrapMacKey, macInput)
        require(MessageDigest.isEqual(expectedHmac, computedHmac)) {
            "Layer 2 authentication check failed."
        }

        val wrapEncKey = SecretKeySpec(hkdfSha256(masterKey.encoded, recordSalt, "VELO_WRAP_ENC_V2", 32), "AES")
        val l2Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        l2Cipher.init(Cipher.DECRYPT_MODE, wrapEncKey, IvParameterSpec(l2Iv))
        val layer1Bundle = l2Cipher.doFinal(l2Ciphertext)

        // --- UNWRAP LAYER 1: Ephemeral DEK AES-256-GCM + Remove Length Padding ---
        val dekBytes: ByteArray
        val l1Iv: ByteArray
        val l1Ciphertext: ByteArray
        DataInputStream(ByteArrayInputStream(layer1Bundle)).use { dis ->
            val dekLen = dis.readInt()
            require(dekLen == DEK_BYTES) { "Invalid DEK length." }
            dekBytes = ByteArray(dekLen).also { dis.readFully(it) }
            val ivLen = dis.readInt()
            require(ivLen == GCM_IV_BYTES) { "Invalid Layer 1 IV length." }
            l1Iv = ByteArray(ivLen).also { dis.readFully(it) }
            val cLen = dis.readInt()
            require(cLen in 17..(2 * 1024 * 1024)) { "Invalid Layer 1 ciphertext length." }
            l1Ciphertext = ByteArray(cLen).also { dis.readFully(it) }
        }

        try {
            val dekKey = SecretKeySpec(dekBytes, "AES")
            val l1Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            l1Cipher.init(Cipher.DECRYPT_MODE, dekKey, GCMParameterSpec(GCM_TAG_BITS, l1Iv))
            l1Cipher.updateAAD(AAD_LAYER1)
            val paddedPlain = l1Cipher.doFinal(l1Ciphertext)

            DataInputStream(ByteArrayInputStream(paddedPlain)).use { dis ->
                val actualLen = dis.readInt()
                require(actualLen in 0..(paddedPlain.size - 4)) { "Invalid padded plaintext length." }
                val rawPlain = ByteArray(actualLen).also { dis.readFully(it) }
                return String(rawPlain, Charsets.UTF_8)
            }
        } finally {
            dekBytes.fill(0)
        }
    }

    /**
     * RFC 5869 HKDF-SHA256 Extract + Expand for domain-separated sub-key derivation.
     */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: String, outputLength: Int): ByteArray {
        val prk = computeHmacSha256(salt, ikm)
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val okm = ByteArray(outputLength)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < outputLength) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(infoBytes)
            mac.update(counter)
            t = mac.doFinal()
            val copyLen = minOf(t.size, outputLength - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
            counter++
        }
        return okm
    }

    private fun computeHmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
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
        for (pool in pools) {
            if (idx < safeLength) {
                result[idx++] = pool[secureRandom.nextInt(pool.length)]
            }
        }
        val combined = pools.joinToString("")
        while (idx < safeLength) {
            result[idx++] = combined[secureRandom.nextInt(combined.length)]
        }
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
