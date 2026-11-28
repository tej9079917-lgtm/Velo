package com.example.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.browser.VaultCredential
import com.example.crypto.E2EECryptoEngine
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ShieldEmerald
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PasswordVaultSheet(
    credentials: List<VaultCredential>,
    keyFingerprint: String,
    activePageUrl: String,
    onSaveCredential: (id: String?, siteName: String, siteUrl: String, username: String, password: String, notes: String) -> Unit,
    onDeleteCredential: (String) -> Unit,
    onAutofillCredential: (VaultCredential) -> Unit,
    onCopiedNotice: (String) -> Unit,
    onLockVault: () -> Unit = {},
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var editingCredential by remember { mutableStateOf<VaultCredential?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val filtered = remember(credentials, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) credentials
        else credentials.filter {
            it.siteName.lowercase().contains(q) ||
                it.siteUrl.lowercase().contains(q) ||
                it.username.lowercase().contains(q)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Password Vault",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.EnhancedEncryption,
                            contentDescription = null,
                            tint = ShieldEmerald,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "3-LAYER E2EE • AES-GCM + HKDF + HMAC • $keyFingerprint",
                            style = MaterialTheme.typography.labelSmall,
                            color = ShieldEmerald,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onLockVault,
                        modifier = Modifier.testTag("lock_password_vault_button")
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Lock password vault",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_vault_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close password vault")
                    }
                }
            }

            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search encrypted logins...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("vault_search_input")
            )

            // Vault List
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = if (credentials.isEmpty()) "Your Encrypted Vault is Empty"
                            else "No Matching Logins",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "All credentials are encrypted on-device with AES-256-GCM before syncing across your devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filtered, key = { it.id }) { cred ->
                        CredentialCard(
                            credential = cred,
                            canAutofillOnPage = activePageUrl.startsWith("http"),
                            onCopyUsername = {
                                copyTextSecurely(context, scope, "Username", cred.username, sensitive = false)
                                onCopiedNotice("Username copied.")
                            },
                            onCopyPassword = {
                                copyTextSecurely(context, scope, "Password", cred.password, sensitive = true)
                                onCopiedNotice("Password copied (auto-clears clipboard in 30s).")
                            },
                            onAutofill = { onAutofillCredential(cred) },
                            onEdit = { editingCredential = cred },
                            onDelete = { onDeleteCredential(cred.id) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp)
                .testTag("add_vault_credential_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_credential_action))
        }
    }

    if (showCreateDialog || editingCredential != null) {
        CredentialEditorDialog(
            existing = editingCredential,
            suggestedUrl = if (editingCredential == null) activePageUrl else editingCredential!!.siteUrl,
            onDismiss = {
                showCreateDialog = false
                editingCredential = null
            },
            onSave = { siteName, siteUrl, username, password, notes ->
                onSaveCredential(editingCredential?.id, siteName, siteUrl, username, password, notes)
                showCreateDialog = false
                editingCredential = null
            }
        )
    }
}

@Composable
private fun CredentialCard(
    credential: VaultCredential,
    canAutofillOnPage: Boolean,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onAutofill: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var revealPassword by remember { mutableStateOf(false) }

    // Auto re-mask revealed password after 10 seconds to prevent shoulder-surfing
    LaunchedEffect(revealPassword) {
        if (revealPassword) {
            delay(10_000L)
            revealPassword = false
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = credential.siteName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (credential.siteUrl.isNotBlank()) {
                        Text(
                            text = credential.siteUrl.removePrefix("https://").removePrefix("http://"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit credential", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete credential",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Username Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = credential.username,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onCopyUsername, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy username", modifier = Modifier.size(16.dp))
                }
            }

            // Password Row
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (revealPassword) credential.password else "•".repeat(14),
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { revealPassword = !revealPassword }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = if (revealPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealPassword) "Hide password" else "Reveal password",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onCopyPassword, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy password", modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (canAutofillOnPage) {
                FilledTonalButton(
                    onClick = onAutofill,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Icon(Icons.Default.Password, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.fill_on_page_action))
                }
            }
        }
    }
}

@Composable
private fun CredentialEditorDialog(
    existing: VaultCredential?,
    suggestedUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit
) {
    var siteName by remember { mutableStateOf(existing?.siteName ?: "") }
    var siteUrl by remember { mutableStateOf(existing?.siteUrl ?: suggestedUrl) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var genLength by remember { mutableFloatStateOf(20f) }
    var useSymbols by remember { mutableStateOf(true) }
    var useDigits by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Encrypted Login" else "Edit Encrypted Login") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = siteName,
                    onValueChange = { siteName = it },
                    label = { Text("Site / Service Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cred_site_name_input")
                )
                OutlinedTextField(
                    value = siteUrl,
                    onValueChange = { siteUrl = it },
                    label = { Text("Website URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cred_site_url_input")
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username / Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cred_username_input")
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cred_password_input")
                )

                // Built-in Password Generator Panel
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Generator (${genLength.roundToInt()} chars)",
                                style = MaterialTheme.typography.labelMedium
                            )
                            TextButton(
                                onClick = {
                                    password = E2EECryptoEngine.generateStrongPassword(
                                        length = genLength.roundToInt(),
                                        includeDigits = useDigits,
                                        includeSymbols = useSymbols
                                    )
                                },
                                modifier = Modifier.testTag("generate_password_button")
                            ) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Generate")
                            }
                        }
                        Slider(
                            value = genLength,
                            onValueChange = { genLength = it },
                            valueRange = 12f..32f
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = useDigits,
                                onClick = { useDigits = !useDigits },
                                label = { Text("0-9") }
                            )
                            FilterChip(
                                selected = useSymbols,
                                onClick = { useSymbols = !useSymbols },
                                label = { Text("!@#\$") }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Encrypted Notes (optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(siteName, siteUrl, username, password, notes) },
                enabled = siteName.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.testTag("save_credential_confirm")
            ) {
                Text("Encrypt & Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun copyTextSecurely(
    context: Context,
    scope: CoroutineScope,
    label: String,
    value: String,
    sensitive: Boolean
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, value)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    if (sensitive) {
        scope.launch {
            delay(30_000L)
            try {
                val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == value) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard.clearPrimaryClip()
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
