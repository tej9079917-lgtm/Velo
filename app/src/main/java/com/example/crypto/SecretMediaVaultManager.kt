package com.example.crypto

import android.content.Context
import android.util.Base64
import com.example.browser.SecretMediaItem
import com.example.data.local.SecretVaultConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedVaultFileResult(
    val encryptedFileName: String,
    val ivBase64: String,
    val plaintextByteSize: Long
)

/**
 * Hardware-friendly Chunked Streaming AES-256-GCM Secret Media Vault Manager.
 *
 * Security properties:
 * - Secret passcode is NEVER stored in plaintext; verified via PBKDF2-HMAC-SHA256 salted verifier.
 * - Hidden videos, images, PDFs, and audios are encrypted in 512KB authenticated AES-256-GCM chunks
 *   with unique per-chunk derived IVs so large videos encrypt/decrypt with <2MB RAM and zero IV reuse.
 * - Encrypted .vlt files live strictly in app-private filesDir/secret_vault/ with a .nomedia marker.
 * - Temporary decrypted previews (cacheDir/vault_preview/) are deleted immediately on close or lock.
 */
class SecretMediaVaultManager(context: Context) {

    private val vaultDir = File(context.filesDir, "secret_vault").apply {
        mkdirs()
        File(this, ".nomedia").apply { if (!exists()) createNewFile() }
    }

    private val previewDir = File(context.cacheDir, "vault_preview").apply { mkdirs() }
    private val secureRandom = SecureRandom()

    fun createVaultConfigForCode(secretCode: String): Pair<SecretVaultConfig, SecretKey> {
        val bundle = E2EECryptoEngine.createNewVault(secretCode)
        val config = SecretVaultConfig(
            saltBase64 = bundle.saltBase64,
            verifierCiphertext = bundle.verifierCiphertext,
            verifierIv = bundle.verifierIv,
            iterations = bundle.kdfIterations
        )
        return config to bundle.derivedKey
    }

    fun verifyAndDeriveKey(candidateCode: String, config: SecretVaultConfig): SecretKey? {
        return E2EECryptoEngine.unlockVault(
            passphrase = candidateCode,
            saltBase64 = config.saltBase64,
            verifierCiphertext = config.verifierCiphertext,
            verifierIv = config.verifierIv,
            iterations = config.iterations
        )
    }

    suspend fun encryptStreamToVault(
        input: InputStream,
        key: SecretKey
    ): Result<EncryptedVaultFileResult> = withContext(Dispatchers.IO) {
        val fileName = "${UUID.randomUUID()}.vlt"
        val outFile = resolveConfinedVaultFile(fileName)
        val baseIv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        var totalPlaintext = 0L

        try {
            DataOutputStream(FileOutputStream(outFile).buffered()).use { dos ->
                dos.write(MAGIC_HEADER)
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                var chunkIndex = 0

                while (true) {
                    val bytesRead = readFullChunk(input, buffer)
                    if (bytesRead <= 0) break
                    totalPlaintext += bytesRead

                    val chunkIv = deriveChunkIv(baseIv, chunkIndex++)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
                    val encryptedChunk = cipher.doFinal(buffer, 0, bytesRead)

                    dos.writeInt(encryptedChunk.size)
                    dos.write(encryptedChunk)
                }
                dos.flush()
            }

            if (totalPlaintext == 0L) {
                outFile.delete()
                return@withContext Result.failure(IllegalArgumentException("Source file is empty."))
            }

            Result.success(
                EncryptedVaultFileResult(
                    encryptedFileName = fileName,
                    ivBase64 = Base64.encodeToString(baseIv, Base64.NO_WRAP),
                    plaintextByteSize = totalPlaintext
                )
            )
        } catch (e: Exception) {
            outFile.delete()
            Result.failure(e)
        }
    }

    suspend fun decryptVaultFileToBytes(
        encryptedFileName: String,
        ivBase64: String,
        key: SecretKey
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val baos = ByteArrayOutputStream()
            decryptToStreamInternal(encryptedFileName, ivBase64, key, baos)
            Result.success(baos.toByteArray())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun decryptVaultFileToTempPreview(
        encryptedFileName: String,
        ivBase64: String,
        extension: String,
        key: SecretKey
    ): Result<File> = withContext(Dispatchers.IO) {
        val safeExt = extension.replace(Regex("[^a-zA-Z0-9]"), "").ifBlank { "bin" }
        val tempFile = resolveConfinedPreviewFile("preview_${UUID.randomUUID().toString().take(8)}.$safeExt")
        try {
            FileOutputStream(tempFile).buffered().use { out ->
                decryptToStreamInternal(encryptedFileName, ivBase64, key, out)
                out.flush()
            }
            Result.success(tempFile)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    suspend fun decryptVaultFileToStream(
        encryptedFileName: String,
        ivBase64: String,
        key: SecretKey,
        output: OutputStream
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            decryptToStreamInternal(encryptedFileName, ivBase64, key, output)
            output.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Re-encrypts all vault media files when the user changes their Secret Code.
     * Returns updated (itemId -> Pair(newEncryptedFileName, newIvBase64)) map.
     */
    suspend fun reEncryptAllFiles(
        items: List<SecretMediaItem>,
        oldKey: SecretKey,
        newKey: SecretKey
    ): Result<Map<String, Pair<String, String>>> = withContext(Dispatchers.IO) {
        val updatedMap = mutableMapOf<String, Pair<String, String>>()
        val oldFilesToDelete = mutableListOf<File>()
        try {
            for (item in items) {
                val tempPlain = resolveConfinedPreviewFile("rekey_${UUID.randomUUID().toString().take(8)}.tmp")
                try {
                    FileOutputStream(tempPlain).buffered().use { out ->
                        decryptToStreamInternal(item.encryptedFileName, item.ivBase64, oldKey, out)
                    }
                    val encrypted = FileInputStream(tempPlain).buffered().use { input ->
                        encryptStreamToVault(input, newKey).getOrThrow()
                    }
                    updatedMap[item.id] = encrypted.encryptedFileName to encrypted.ivBase64
                    oldFilesToDelete.add(resolveConfinedVaultFile(item.encryptedFileName))
                } finally {
                    tempPlain.delete()
                }
            }
            oldFilesToDelete.forEach { it.delete() }
            Result.success(updatedMap)
        } catch (e: Exception) {
            // Roll back any newly written files if re-keying failed halfway
            updatedMap.values.forEach { (newName, _) ->
                try {
                    resolveConfinedVaultFile(newName).delete()
                } catch (_: Exception) {
                }
            }
            Result.failure(e)
        }
    }

    fun deleteVaultFile(encryptedFileName: String): Boolean {
        return try {
            val file = resolveConfinedVaultFile(encryptedFileName)
            if (file.exists()) file.delete() else false
        } catch (_: Exception) {
            false
        }
    }

    fun wipeAllTempPreviews() {
        try {
            previewDir.listFiles()?.forEach { file ->
                if (file.isFile) file.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun decryptToStreamInternal(
        encryptedFileName: String,
        ivBase64: String,
        key: SecretKey,
        output: OutputStream
    ) {
        val inFile = resolveConfinedVaultFile(encryptedFileName)
        require(inFile.exists()) { "Encrypted vault file not found." }
        val baseIv = Base64.decode(ivBase64, Base64.NO_WRAP)

        DataInputStream(FileInputStream(inFile).buffered()).use { dis ->
            val header = ByteArray(MAGIC_HEADER.size)
            dis.readFully(header)
            require(header.contentEquals(MAGIC_HEADER)) { "Corrupted or unsupported vault file format." }

            var chunkIndex = 0
            while (dis.available() > 0) {
                val encLen = try {
                    dis.readInt()
                } catch (_: java.io.EOFException) {
                    break
                }
                require(encLen in 17..(CHUNK_SIZE_BYTES + 64)) { "Invalid encrypted chunk length." }
                val encBytes = ByteArray(encLen)
                dis.readFully(encBytes)

                val chunkIv = deriveChunkIv(baseIv, chunkIndex++)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
                val plainBytes = cipher.doFinal(encBytes)
                output.write(plainBytes)
            }
        }
    }

    private fun readFullChunk(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    private fun deriveChunkIv(baseIv: ByteArray, chunkIndex: Int): ByteArray {
        val iv = baseIv.copyOf()
        iv[8] = (iv[8].toInt() xor ((chunkIndex ushr 24) and 0xFF)).toByte()
        iv[9] = (iv[9].toInt() xor ((chunkIndex ushr 16) and 0xFF)).toByte()
        iv[10] = (iv[10].toInt() xor ((chunkIndex ushr 8) and 0xFF)).toByte()
        iv[11] = (iv[11].toInt() xor (chunkIndex and 0xFF)).toByte()
        return iv
    }

    private fun resolveConfinedVaultFile(fileName: String): File {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val file = File(vaultDir, safeName)
        val baseCanonical = vaultDir.canonicalPath + File.separator
        require(file.canonicalPath.startsWith(baseCanonical)) { "Path traversal rejected." }
        return file
    }

    private fun resolveConfinedPreviewFile(fileName: String): File {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val file = File(previewDir, safeName)
        val baseCanonical = previewDir.canonicalPath + File.separator
        require(file.canonicalPath.startsWith(baseCanonical)) { "Preview path traversal rejected." }
        return file
    }

    companion object {
        const val DEFAULT_SETUP_CODE = "#0000"
        private const val CHUNK_SIZE_BYTES = 512 * 1024 // 512 KB streaming chunks
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val MAGIC_HEADER = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
    }
}
