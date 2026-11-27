package com.example.data.remote

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class VaultConfigDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val saltBase64: String = "",
    val verifierCiphertext: String = "",
    val verifierIv: String = "",
    val kdfIterations: Long = 120_000L,
    val keyFingerprint: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VaultItemDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val encryptedPayload: String = "",
    val iv: String = "",
    val cryptoVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ShortcutDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val encryptedPayload: String = "",
    val iv: String = "",
    val orderIndex: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ReadingPageDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val encryptedPayload: String = "",
    val iv: String = "",
    val urlHash: String = "",
    val archived: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class BrowserPreferencesDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val themeMode: String = "DARK",
    val adBlockEnabled: Boolean = true,
    val httpsOnlyEnabled: Boolean = true,
    val darkWebPagesEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val searchEngine: String = "DUCKDUCKGO",
    val trackersBlockedTotal: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
