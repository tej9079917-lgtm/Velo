package com.example.data.remote

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class VeloSyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(expectedUserId: String? = null): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing synced data.")
        if (expectedUserId != null) {
            check(uid == expectedUserId) { "Signed-in user changed; refusing cross-user operation." }
        }
        return uid
    }

    // --- VAULT CONFIG ---

    fun observeVaultConfig(userId: String): Flow<VaultConfigDoc?> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/vaultConfig/primary"
        emitAll(
            db.collection("users").document(uid)
                .collection("vaultConfig").document("primary")
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(VaultConfigDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else {
                        null
                    }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    suspend fun createVaultConfig(
        userId: String,
        saltBase64: String,
        verifierCiphertext: String,
        verifierIv: String,
        kdfIterations: Int,
        keyFingerprint: String
    ): Result<Unit> {
        val path = "users/$userId/vaultConfig/primary"
        return try {
            val uid = requireUserId(userId)
            val payload = mapOf(
                "userId" to uid,
                "saltBase64" to saltBase64,
                "verifierCiphertext" to verifierCiphertext,
                "verifierIv" to verifierIv,
                "kdfIterations" to kdfIterations.toLong(),
                "keyFingerprint" to keyFingerprint,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("users").document(uid)
                .collection("vaultConfig").document("primary")
                .set(payload)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    // --- VAULT ITEMS (E2EE PASSWORD MANAGER) ---

    fun observeVaultItems(userId: String): Flow<List<VaultItemDoc>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/vaultItems"
        emitAll(
            db.collection("users").document(uid)
                .collection("vaultItems")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(VaultItemDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    suspend fun addVaultItem(
        userId: String,
        encryptedPayload: String,
        iv: String,
        cryptoVersion: Int = 1
    ): Result<String> {
        var path = "users/$userId/vaultItems"
        return try {
            val uid = requireUserId(userId)
            require(encryptedPayload.length in 16..8192) { "Encrypted payload out of allowed size bounds." }
            require(iv.length in 12..64) { "Invalid IV length." }
            val docRef = db.collection("users").document(uid).collection("vaultItems").document()
            path = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "encryptedPayload" to encryptedPayload,
                "iv" to iv,
                "cryptoVersion" to cryptoVersion.toLong(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateVaultItem(
        userId: String,
        itemId: String,
        encryptedPayload: String,
        iv: String,
        cryptoVersion: Int = 1
    ): Result<Unit> {
        val path = "users/$userId/vaultItems/$itemId"
        return try {
            val uid = requireUserId(userId)
            require(itemId.isNotBlank()) { "Item ID must not be blank." }
            require(encryptedPayload.length in 16..8192) { "Encrypted payload out of allowed size bounds." }
            db.collection("users").document(uid)
                .collection("vaultItems").document(itemId)
                .update(
                    mapOf(
                        "encryptedPayload" to encryptedPayload,
                        "iv" to iv,
                        "cryptoVersion" to cryptoVersion.toLong(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteVaultItem(userId: String, itemId: String): Result<Unit> {
        val path = "users/$userId/vaultItems/$itemId"
        return try {
            val uid = requireUserId(userId)
            require(itemId.isNotBlank()) { "Item ID must not be blank." }
            db.collection("users").document(uid)
                .collection("vaultItems").document(itemId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- HOMEPAGE SHORTCUTS (E2EE) ---

    fun observeShortcuts(userId: String): Flow<List<ShortcutDoc>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/shortcuts"
        emitAll(
            db.collection("users").document(uid)
                .collection("shortcuts")
                .orderBy("orderIndex", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(ShortcutDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    suspend fun addShortcut(
        userId: String,
        encryptedPayload: String,
        iv: String,
        orderIndex: Int
    ): Result<String> {
        var path = "users/$userId/shortcuts"
        return try {
            val uid = requireUserId(userId)
            require(encryptedPayload.length in 16..4096) { "Shortcut payload out of size bounds." }
            val safeOrder = orderIndex.coerceIn(0, 1000).toLong()
            val docRef = db.collection("users").document(uid).collection("shortcuts").document()
            path = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "encryptedPayload" to encryptedPayload,
                "iv" to iv,
                "orderIndex" to safeOrder,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteShortcut(userId: String, shortcutId: String): Result<Unit> {
        val path = "users/$userId/shortcuts/$shortcutId"
        return try {
            val uid = requireUserId(userId)
            require(shortcutId.isNotBlank()) { "Shortcut ID must not be blank." }
            db.collection("users").document(uid)
                .collection("shortcuts").document(shortcutId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- OFFLINE READING LIST (E2EE) ---

    fun observeReadingList(userId: String): Flow<List<ReadingPageDoc>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/readingList"
        emitAll(
            db.collection("users").document(uid)
                .collection("readingList")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(ReadingPageDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    suspend fun upsertReadingPage(
        userId: String,
        urlHash: String,
        encryptedPayload: String,
        iv: String
    ): Result<Unit> {
        val docId = urlHash.take(40)
        val path = "users/$userId/readingList/$docId"
        return try {
            val uid = requireUserId(userId)
            require(encryptedPayload.length in 16..16384) { "Reading metadata out of size bounds." }
            val docRef = db.collection("users").document(uid)
                .collection("readingList").document(docId)
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "encryptedPayload" to encryptedPayload,
                        "iv" to iv,
                        "archived" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "encryptedPayload" to encryptedPayload,
                        "iv" to iv,
                        "urlHash" to urlHash,
                        "archived" to false,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun toggleReadingArchive(userId: String, pageId: String, archived: Boolean): Result<Unit> {
        val path = "users/$userId/readingList/$pageId"
        return try {
            val uid = requireUserId(userId)
            require(pageId.isNotBlank()) { "Page ID must not be blank." }
            db.collection("users").document(uid)
                .collection("readingList").document(pageId)
                .update(
                    mapOf(
                        "archived" to archived,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteReadingPage(userId: String, pageId: String): Result<Unit> {
        val path = "users/$userId/readingList/$pageId"
        return try {
            val uid = requireUserId(userId)
            require(pageId.isNotBlank()) { "Page ID must not be blank." }
            db.collection("users").document(uid)
                .collection("readingList").document(pageId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- BROWSER PREFERENCES ---

    fun observePreferences(userId: String): Flow<BrowserPreferencesDoc?> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/preferences/browser"
        emitAll(
            db.collection("users").document(uid)
                .collection("preferences").document("browser")
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(BrowserPreferencesDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else {
                        null
                    }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    suspend fun ensurePreferencesExist(userId: String): Result<Unit> {
        val path = "users/$userId/preferences/browser"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid)
                .collection("preferences").document("browser")
            if (!docRef.get().await().exists()) {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "themeMode" to "DARK",
                        "adBlockEnabled" to true,
                        "httpsOnlyEnabled" to true,
                        "darkWebPagesEnabled" to false,
                        "notificationsEnabled" to true,
                        "searchEngine" to "DUCKDUCKGO",
                        "trackersBlockedTotal" to 0L,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updatePreferenceField(userId: String, field: String, value: Any): Result<Unit> {
        val path = "users/$userId/preferences/browser"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid)
                .collection("preferences").document("browser")
            docRef.update(
                mapOf(
                    field to value,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun incrementTrackersBlocked(userId: String, delta: Long): Result<Unit> {
        if (delta <= 0L) return Result.success(Unit)
        val path = "users/$userId/preferences/browser"
        return try {
            val uid = requireUserId(userId)
            db.collection("users").document(uid)
                .collection("preferences").document("browser")
                .update(
                    mapOf(
                        "trackersBlockedTotal" to FieldValue.increment(delta),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    private fun logStreamErrorIfActive(
        error: Throwable,
        op: OperationType,
        path: String,
        uid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, op, path)
        }
    }
}
