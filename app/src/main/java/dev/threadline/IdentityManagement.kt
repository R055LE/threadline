package dev.threadline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.threadline.data.identity.IdentityAuthenticationMethod
import dev.threadline.data.identity.SshIdentity
import dev.threadline.data.key.ImportedPrivateKeyMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class SshIdentityDraft(
    val id: String?,
    val label: String,
    val username: String,
    val authenticationMethod: IdentityAuthenticationMethod,
    val importedPrivateKeyId: String?,
)

@Composable
internal fun PreferredIdentitySelector(
    identities: List<SshIdentity>,
    selectedIdentityId: String?,
    enabled: Boolean,
    onSelect: (SshIdentity?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedIdentity = identities.firstOrNull { it.id == selectedIdentityId }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Preferred identity", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConnectionFormTags.PREFERRED_IDENTITY),
            ) {
                Text(
                    selectedIdentity?.let { "${it.label} · ${it.username}" }
                        ?: "Choose later",
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("No preferred identity") },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
                identities.forEach { identity ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(identity.label)
                                Text(
                                    "${identity.username} · ${identity.authenticationMethod.label()}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(identity)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SshIdentityManagementContent(
    identities: List<SshIdentity>,
    importedPrivateKeys: List<ImportedPrivateKeyMetadata>,
    enabled: Boolean,
    onSaveIdentity: suspend (SshIdentityDraft) -> Unit,
    onDeleteIdentity: suspend (String) -> Unit,
) {
    var editing by remember { mutableStateOf<SshIdentityDraft?>(null) }
    var deleting by remember { mutableStateOf<SshIdentity?>(null) }
    var managing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "SSH identities",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(IdentityTags.HEADING),
        )
        Text(
            "An identity holds a username and authentication choice. Passwords and key " +
                "passphrases are entered for each connection.",
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        identities.forEach { identity ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(identity.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${identity.username} · ${identity.authenticationMethod.label()}" +
                            if (
                                identity.authenticationMethod ==
                                    IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY
                            ) {
                                importedPrivateKeys.firstOrNull {
                                    it.id == identity.importedPrivateKeyId
                                }?.let { " · ${it.displayName}" } ?: " · Choose a key"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                error = null
                                editorError = null
                                editing = identity.toDraft()
                            },
                            enabled = enabled && !managing,
                            modifier = Modifier.testTag(IdentityTags.EDIT_PREFIX + identity.id),
                        ) {
                            Text("Edit")
                        }
                        TextButton(
                            onClick = {
                                error = null
                                deleting = identity
                            },
                            enabled = enabled && !managing,
                            modifier = Modifier.testTag(IdentityTags.DELETE_PREFIX + identity.id),
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
        if (identities.isEmpty()) Text("No saved SSH identities.")
        OutlinedButton(
            onClick = {
                error = null
                editorError = null
                editing = SshIdentityDraft(
                    id = null,
                    label = "",
                    username = "",
                    authenticationMethod = IdentityAuthenticationMethod.UNCONFIGURED,
                    importedPrivateKeyId = null,
                )
            },
            enabled = enabled && !managing,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(IdentityTags.ADD),
        ) {
            Text("Add identity")
        }
    }

    editing?.let { draft ->
        IdentityEditorDialog(
            initial = draft,
            importedPrivateKeys = importedPrivateKeys,
            enabled = enabled && !managing,
            onDismiss = { if (!managing) editing = null },
            error = editorError,
            onSave = { updated ->
                managing = true
                error = null
                editorError = null
                coroutineScope.launch {
                    try {
                        onSaveIdentity(updated)
                        editing = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        editorError = failure.message ?: "The SSH identity could not be saved."
                    } finally {
                        managing = false
                    }
                }
            },
        )
    }

    deleting?.let { identity ->
        AlertDialog(
            onDismissRequest = { if (!managing) deleting = null },
            title = { Text("Delete SSH identity?") },
            text = {
                Text(
                    "Host profiles using ${identity.label} will need another identity. " +
                        "The saved private key, if any, stays on this device.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (managing) return@Button
                        managing = true
                        error = null
                        coroutineScope.launch {
                            try {
                                onDeleteIdentity(identity.id)
                                deleting = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "The SSH identity could not be deleted."
                                deleting = null
                            } finally {
                                managing = false
                            }
                        }
                    },
                    enabled = enabled && !managing,
                    modifier = Modifier.testTag(IdentityTags.CONFIRM_DELETE),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleting = null },
                    enabled = !managing,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun IdentityEditorDialog(
    initial: SshIdentityDraft,
    importedPrivateKeys: List<ImportedPrivateKeyMetadata>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    error: String?,
    onSave: (SshIdentityDraft) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var keyMenuExpanded by remember { mutableStateOf(false) }
    val selectedKey = importedPrivateKeys.firstOrNull {
        it.id == draft.importedPrivateKeyId
    }
    val canSave = draft.label.isNotBlank() && draft.username.isNotBlank() &&
        (draft.authenticationMethod != IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY ||
            draft.importedPrivateKeyId != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "Add SSH identity" else "Edit SSH identity") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { draft = draft.copy(label = it) },
                    label = { Text("Identity name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(IdentityTags.LABEL),
                )
                OutlinedTextField(
                    value = draft.username,
                    onValueChange = { draft = draft.copy(username = it) },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(IdentityTags.USERNAME),
                )
                Text("Authentication", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    IdentityAuthenticationMethod.values().forEach { method ->
                        FilterChip(
                            selected = draft.authenticationMethod == method,
                            onClick = {
                                draft = draft.copy(
                                    authenticationMethod = method,
                                    importedPrivateKeyId = if (
                                        method == IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY
                                    ) {
                                        draft.importedPrivateKeyId
                                    } else {
                                        null
                                    },
                                )
                            },
                            label = { Text(method.label()) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(IdentityTags.method(method)),
                        )
                    }
                }
                if (
                    draft.authenticationMethod ==
                    IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY
                ) {
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(
                            onClick = { keyMenuExpanded = true },
                            enabled = enabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(IdentityTags.PRIVATE_KEY),
                        ) {
                            Text(selectedKey?.displayName ?: "Choose saved private key")
                        }
                        DropdownMenu(
                            expanded = keyMenuExpanded,
                            onDismissRequest = { keyMenuExpanded = false },
                        ) {
                            importedPrivateKeys.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.displayName) },
                                    onClick = {
                                        draft = draft.copy(importedPrivateKeyId = key.id)
                                        keyMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    if (importedPrivateKeys.isEmpty()) {
                        Text("Import a private key before choosing this method.")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = enabled && canSave,
                modifier = Modifier.testTag(IdentityTags.SAVE),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) {
                Text("Cancel")
            }
        },
    )
}

internal object IdentityTags {
    const val HEADING = "identity-heading"
    const val ADD = "identity-add"
    const val LABEL = "identity-label"
    const val USERNAME = "identity-username"
    const val PRIVATE_KEY = "identity-private-key"
    const val SAVE = "identity-save"
    const val CONFIRM_DELETE = "identity-confirm-delete"
    const val UNCONFIGURED = "identity-auth-unconfigured"
    const val PASSWORD = "identity-auth-password"
    const val IMPORTED_KEY = "identity-auth-imported-key"
    const val EDIT_PREFIX = "identity-edit-"
    const val DELETE_PREFIX = "identity-delete-"

    fun method(method: IdentityAuthenticationMethod): String = when (method) {
        IdentityAuthenticationMethod.UNCONFIGURED -> UNCONFIGURED
        IdentityAuthenticationMethod.PASSWORD -> PASSWORD
        IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY -> IMPORTED_KEY
    }
}

private fun SshIdentity.toDraft() = SshIdentityDraft(
    id = id,
    label = label,
    username = username,
    authenticationMethod = authenticationMethod,
    importedPrivateKeyId = importedPrivateKeyId,
)

private fun IdentityAuthenticationMethod.label(): String = when (this) {
    IdentityAuthenticationMethod.UNCONFIGURED -> "Choose when connecting"
    IdentityAuthenticationMethod.PASSWORD -> "Password"
    IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY -> "Private key"
}
