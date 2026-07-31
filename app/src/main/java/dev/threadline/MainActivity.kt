package dev.threadline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.HostKeyPrompt
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.data.key.ImportedPrivateKeyMetadata
import dev.threadline.service.SshSessionService
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionRuntime.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ThreadlineApp()
                }
            }
        }
    }
}

internal enum class AuthenticationMode {
    PASSWORD,
    PRIVATE_KEY,
}

internal data class ConnectionFormDraft(
    val displayName: String,
    val hostname: String,
    val port: String,
    val username: String,
    val authenticationMode: AuthenticationMode,
) {
    companion object {
        fun fixtureDefaults() = ConnectionFormDraft(
            displayName = "Local fixture",
            hostname = "10.0.2.2",
            port = "2222",
            username = "threadline",
            authenticationMode = AuthenticationMode.PASSWORD,
        )

        val Saver: Saver<ConnectionFormDraft, Any> = listSaver(
            save = {
                listOf(
                    it.displayName,
                    it.hostname,
                    it.port,
                    it.username,
                    it.authenticationMode.name,
                )
            },
            restore = {
                ConnectionFormDraft(
                    displayName = it[0],
                    hostname = it[1],
                    port = it[2],
                    username = it[3],
                    authenticationMode = AuthenticationMode.valueOf(it[4]),
                )
            },
        )
    }
}

internal object ConnectionFormTags {
    const val DISPLAY_NAME = "connection-display-name"
    const val HOSTNAME = "connection-hostname"
    const val PORT = "connection-port"
    const val USERNAME = "connection-username"
    const val PASSWORD_AUTH = "connection-password-auth"
    const val PRIVATE_KEY_AUTH = "connection-private-key-auth"
    const val PASSWORD = "connection-password"
    const val KEY_PASSPHRASE = "connection-key-passphrase"
    const val SAVE_PRIVATE_KEY = "connection-save-private-key"
    const val CONNECT = "connection-connect"
    const val SAVED_KEY_PREFIX = "connection-saved-key-"
}

@Composable
private fun ThreadlineApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = SessionRuntime.manager
    val snapshot by manager.snapshot.collectAsStateWithLifecycle()
    val importedPrivateKeys by SessionRuntime.importedPrivateKeys.keys
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val state = snapshot.connection
    var connectionDraft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
        mutableStateOf(ConnectionFormDraft.fixtureDefaults())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startSessionService(context)
        } else {
            manager.cancelPrepared(SessionError.NotificationPermissionRequired)
        }
    }

    when (val current = state) {
        SessionState.Disconnected,
        is SessionState.Failed,
        -> HostForm(
            draft = connectionDraft,
            onDraftChange = { connectionDraft = it },
            sessionError = (current as? SessionState.Failed)?.error,
            importedPrivateKeys = importedPrivateKeys,
            onSavePrivateKey = SessionRuntime.importedPrivateKeys::save,
            onLoadPrivateKey = SessionRuntime.importedPrivateKeys::credential,
            onPrepared = prepared@{ request ->
                if (!manager.prepareConnection(request)) return@prepared false

                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startSessionService(context)
                }
                true
            },
        )

        is SessionState.Connected -> ConnectedSessionScreen(
            displayName = current.displayName,
            structuredShell = snapshot.structuredShell,
            transcript = snapshot.transcript,
            onSubmit = manager::submitCommand,
            onControlC = manager::sendControlC,
            onDisconnect = manager::disconnect,
        )

        is SessionState.Connecting -> ProgressScreen(
            title = current.displayName,
            status = current.stage.name.lowercase().replaceFirstChar(Char::uppercase),
            onCancel = manager::disconnect,
        )

        is SessionState.AwaitingHostKey -> ProgressScreen(
            title = current.displayName,
            status = "Waiting for host-key confirmation",
            onCancel = manager::disconnect,
        )

        is SessionState.Disconnecting -> ProgressScreen(
            title = current.displayName ?: "Threadline",
            status = "Disconnecting",
            onCancel = null,
        )
    }

    if (state is SessionState.AwaitingHostKey) {
        HostKeyDialog(
            prompt = state.prompt,
            onDecision = manager::resolveHostKey,
        )
    }
}

private fun startSessionService(context: Context) {
    try {
        SshSessionService.connect(context)
    } catch (_: RuntimeException) {
        SessionRuntime.manager.cancelPrepared(SessionError.ServiceStartFailed)
    }
}

@Composable
internal fun HostForm(
    draft: ConnectionFormDraft,
    onDraftChange: (ConnectionFormDraft) -> Unit,
    sessionError: SessionError?,
    importedPrivateKeys: List<ImportedPrivateKeyMetadata> = emptyList(),
    onSavePrivateKey: suspend (
        displayName: String,
        keyBytes: ByteArray,
        passphrase: CharArray?,
    ) -> ImportedPrivateKeyMetadata = { _, _, _ ->
        error("Encrypted private-key storage is unavailable.")
    },
    onLoadPrivateKey: suspend (
        id: String,
        passphrase: CharArray?,
    ) -> SessionCredential.PrivateKey = { _, _ ->
        error("Encrypted private-key storage is unavailable.")
    },
    onPrepared: (ConnectionRequest) -> Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Secrets deliberately use remember rather than rememberSaveable.
    var password by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }
    var selectedKeyUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSavedKeyId by rememberSaveable { mutableStateOf<String?>(null) }
    var savePrivateKey by rememberSaveable { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var isPreparing by remember { mutableStateOf(false) }

    val keyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        selectedKeyUri = uri?.toString()
        if (uri != null) selectedSavedKeyId = null
        formError = null
    }

    Scaffold(
        topBar = {
            Column {
                Text(
                    text = "Threadline",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                HorizontalDivider()
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Phase 4 · security and persistence",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Connect through the live PTY-backed shell with strict host verification " +
                    "and local trust persistence.",
                style = MaterialTheme.typography.bodyMedium,
            )

            sessionError?.let { ErrorCard(it) }
            formError?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            OutlinedTextField(
                value = draft.displayName,
                onValueChange = { onDraftChange(draft.copy(displayName = it)) },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConnectionFormTags.DISPLAY_NAME),
            )
            OutlinedTextField(
                value = draft.hostname,
                onValueChange = { onDraftChange(draft.copy(hostname = it)) },
                label = { Text("Hostname or IP") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConnectionFormTags.HOSTNAME),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = draft.port,
                    onValueChange = {
                        onDraftChange(draft.copy(port = it.filter(Char::isDigit)))
                    },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(0.35f)
                        .testTag(ConnectionFormTags.PORT),
                )
                OutlinedTextField(
                    value = draft.username,
                    onValueChange = { onDraftChange(draft.copy(username = it)) },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(0.65f)
                        .testTag(ConnectionFormTags.USERNAME),
                )
            }

            Text("Authentication", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = draft.authenticationMode == AuthenticationMode.PASSWORD,
                    onClick = {
                        onDraftChange(
                            draft.copy(authenticationMode = AuthenticationMode.PASSWORD),
                        )
                    },
                    label = { Text("Password") },
                    modifier = Modifier.testTag(ConnectionFormTags.PASSWORD_AUTH),
                )
                FilterChip(
                    selected = draft.authenticationMode == AuthenticationMode.PRIVATE_KEY,
                    onClick = {
                        onDraftChange(
                            draft.copy(authenticationMode = AuthenticationMode.PRIVATE_KEY),
                        )
                    },
                    label = { Text("Private key") },
                    modifier = Modifier.testTag(ConnectionFormTags.PRIVATE_KEY_AUTH),
                )
            }

            when (draft.authenticationMode) {
                AuthenticationMode.PASSWORD -> OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ConnectionFormTags.PASSWORD),
                )

                AuthenticationMode.PRIVATE_KEY -> {
                    if (importedPrivateKeys.isNotEmpty()) {
                        Text("Saved keys", style = MaterialTheme.typography.labelLarge)
                        importedPrivateKeys.forEach { key ->
                            FilterChip(
                                selected = selectedSavedKeyId == key.id,
                                onClick = {
                                    selectedSavedKeyId = key.id
                                    selectedKeyUri = null
                                    savePrivateKey = false
                                    formError = null
                                },
                                label = {
                                    Column {
                                        Text(key.displayName)
                                        Text(
                                            "${key.keyType} · ${key.publicKeyFingerprint}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                },
                                modifier = Modifier.testTag(
                                    ConnectionFormTags.SAVED_KEY_PREFIX + key.id,
                                ).fillMaxWidth(),
                            )
                        }
                    }
                    OutlinedButton(onClick = { keyPicker.launch("*/*") }) {
                        Text(
                            selectedKeyUri?.let { it.toUri().lastPathSegment }
                                ?: "Choose OpenSSH private key",
                        )
                    }
                    if (selectedKeyUri != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = savePrivateKey,
                                onCheckedChange = { savePrivateKey = it },
                                modifier = Modifier.testTag(
                                    ConnectionFormTags.SAVE_PRIVATE_KEY,
                                ),
                            )
                            Text("Save encrypted on this device")
                        }
                    }
                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        label = { Text("Key passphrase (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ConnectionFormTags.KEY_PASSPHRASE),
                    )
                    Text(
                        "Passphrases stay in memory for this connection and are never saved.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (isPreparing) return@Button
                    formError = null
                    val parsedPort = draft.port.toIntOrNull()
                    if (
                        draft.displayName.isBlank() ||
                        draft.hostname.isBlank() ||
                        draft.username.isBlank() ||
                        parsedPort == null ||
                        parsedPort !in 1..65535
                    ) {
                        formError = "Enter a display name, host, valid port, and username."
                        return@Button
                    }

                    isPreparing = true
                    coroutineScope.launch {
                        try {
                            val credential = when (draft.authenticationMode) {
                                AuthenticationMode.PASSWORD -> {
                                    if (password.isEmpty()) {
                                        error("Enter the fixture password.")
                                    }
                                    val characters = password.toCharArray()
                                    try {
                                        SessionCredential.Password.from(characters)
                                    } finally {
                                        characters.fill('\u0000')
                                    }
                                }

                                AuthenticationMode.PRIVATE_KEY -> {
                                    val passphrase = keyPassphrase
                                        .takeIf(String::isNotEmpty)
                                        ?.toCharArray()
                                    try {
                                        val savedId = selectedSavedKeyId
                                        if (savedId != null) {
                                            onLoadPrivateKey(savedId, passphrase)
                                        } else {
                                            val uri = selectedKeyUri?.toUri()
                                                ?: error("Choose a private key.")
                                            val keyBytes = withContext(Dispatchers.IO) {
                                                readPrivateKey(context, uri)
                                            }
                                            try {
                                                if (savePrivateKey) {
                                                    val saved = onSavePrivateKey(
                                                        selectedPrivateKeyName(uri),
                                                        keyBytes,
                                                        passphrase,
                                                    )
                                                    selectedSavedKeyId = saved.id
                                                    selectedKeyUri = null
                                                    savePrivateKey = false
                                                }
                                                SessionCredential.PrivateKey.from(
                                                    keyBytes = keyBytes,
                                                    passphrase = passphrase,
                                                )
                                            } finally {
                                                keyBytes.fill(0)
                                            }
                                        }
                                    } finally {
                                        passphrase?.fill('\u0000')
                                    }
                                }
                            }

                            val request = ConnectionRequest(
                                profile = HostProfile(
                                    displayName = draft.displayName.trim(),
                                    endpoint = HostEndpoint(draft.hostname.trim(), parsedPort),
                                    username = draft.username.trim(),
                                ),
                                credential = credential,
                            )
                            if (onPrepared(request)) {
                                password = ""
                                keyPassphrase = ""
                            } else {
                                formError = "Another SSH session is already active."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            formError = failure.message
                                ?: "Could not prepare the selected private key."
                        } finally {
                            isPreparing = false
                        }
                    }
                },
                enabled = !isPreparing,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConnectionFormTags.CONNECT),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text(if (isPreparing) "Preparing securely…" else "Connect securely")
            }
        }
    }
}

@Composable
private fun ErrorCard(error: SessionError) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = error.userMessage,
                color = MaterialTheme.colorScheme.error,
            )
            if (error is SessionError.HostKeyChanged) {
                Text(
                    text = "Saved: ${error.previousFingerprint}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Presented: ${error.presentedFingerprint}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProgressScreen(
    title: String,
    status: String,
    onCancel: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(status)
            onCancel?.let {
                OutlinedButton(onClick = it) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun HostKeyDialog(
    prompt: HostKeyPrompt,
    onDecision: (HostKeyDecision) -> Boolean,
) {
    AlertDialog(
        onDismissRequest = { onDecision(HostKeyDecision.REJECT) },
        title = { Text("Unknown server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "No saved host key exists for " +
                        "${prompt.endpoint.hostname}:${prompt.endpoint.port}.",
                )
                Text("Verify this fingerprint through a trusted channel:")
                Text(
                    text = "${prompt.algorithm}\n${prompt.fingerprint}",
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onDecision(HostKeyDecision.ACCEPT_AND_SAVE) }) {
                Text("Accept and save")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(HostKeyDecision.REJECT) }) {
                Text("Reject")
            }
        },
    )
}

private fun readPrivateKey(
    context: Context,
    uri: Uri,
): ByteArray {
    val input = context.contentResolver.openInputStream(uri)
        ?: error("The selected private key could not be opened.")
    input.use { stream ->
        val output = ClearingByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_PRIVATE_KEY_BYTES) {
                    "Private keys larger than 1 MiB are not accepted."
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
            output.clear()
        }
    }
}

private fun selectedPrivateKeyName(uri: Uri): String =
    uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank)
        ?: "Imported private key"

private class ClearingByteArrayOutputStream : ByteArrayOutputStream() {
    fun clear() {
        buf.fill(0)
        reset()
    }
}

private const val MAX_PRIVATE_KEY_BYTES = 1024 * 1024
