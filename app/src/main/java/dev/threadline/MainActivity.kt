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
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.threadline.service.SshSessionService
import org.connectbot.terminal.Terminal
import java.io.ByteArrayOutputStream

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

private enum class AuthenticationMode {
    PASSWORD,
    PRIVATE_KEY,
}

@Composable
private fun ThreadlineApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = SessionRuntime.manager
    val state by manager.state.collectAsStateWithLifecycle()

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
            sessionError = (current as? SessionState.Failed)?.error,
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

        is SessionState.Connected -> TerminalScreen(
            displayName = current.displayName,
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
            prompt = (state as SessionState.AwaitingHostKey).prompt,
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
private fun HostForm(
    sessionError: SessionError?,
    onPrepared: (ConnectionRequest) -> Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var displayName by rememberSaveable { mutableStateOf("Local fixture") }
    var hostname by rememberSaveable { mutableStateOf("10.0.2.2") }
    var port by rememberSaveable { mutableStateOf("2222") }
    var username by rememberSaveable { mutableStateOf("threadline") }
    var authenticationMode by rememberSaveable {
        mutableStateOf(AuthenticationMode.PASSWORD)
    }
    // Secrets deliberately use remember rather than rememberSaveable.
    var password by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }
    var selectedKeyUri by rememberSaveable { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    val keyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        selectedKeyUri = uri?.toString()
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
                "Phase 0 · raw SSH dependency spike",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Connect to the local fixture, verify its host key, and use one live PTY-backed shell.",
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
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text("Hostname or IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(0.35f),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.weight(0.65f),
                )
            }

            Text("Authentication", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = authenticationMode == AuthenticationMode.PASSWORD,
                    onClick = { authenticationMode = AuthenticationMode.PASSWORD },
                    label = { Text("Password") },
                )
                FilterChip(
                    selected = authenticationMode == AuthenticationMode.PRIVATE_KEY,
                    onClick = { authenticationMode = AuthenticationMode.PRIVATE_KEY },
                    label = { Text("Private key") },
                )
            }

            when (authenticationMode) {
                AuthenticationMode.PASSWORD -> OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                AuthenticationMode.PRIVATE_KEY -> {
                    OutlinedButton(onClick = { keyPicker.launch("*/*") }) {
                        Text(
                            selectedKeyUri?.let { it.toUri().lastPathSegment }
                                ?: "Choose OpenSSH private key",
                        )
                    }
                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        label = { Text("Key passphrase (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    formError = null
                    val parsedPort = port.toIntOrNull()
                    if (
                        displayName.isBlank() ||
                        hostname.isBlank() ||
                        username.isBlank() ||
                        parsedPort == null ||
                        parsedPort !in 1..65535
                    ) {
                        formError = "Enter a display name, host, valid port, and username."
                        return@Button
                    }

                    val credential = runCatching {
                        when (authenticationMode) {
                            AuthenticationMode.PASSWORD -> {
                                if (password.isEmpty()) {
                                    error("Enter the fixture password.")
                                }
                                SessionCredential.Password.from(password.toCharArray())
                            }

                            AuthenticationMode.PRIVATE_KEY -> {
                                val uri = selectedKeyUri?.toUri()
                                    ?: error("Choose a private key.")
                                val keyBytes = readPrivateKey(context, uri)
                                try {
                                    SessionCredential.PrivateKey.from(
                                        keyBytes = keyBytes,
                                        passphrase = keyPassphrase
                                            .takeIf(String::isNotEmpty)
                                            ?.toCharArray(),
                                    )
                                } finally {
                                    keyBytes.fill(0)
                                }
                            }
                        }
                    }.getOrElse {
                        formError = it.message ?: "Could not read the selected private key."
                        return@Button
                    }

                    val request = ConnectionRequest(
                        profile = HostProfile(
                            displayName = displayName.trim(),
                            endpoint = HostEndpoint(hostname.trim(), parsedPort),
                            username = username.trim(),
                        ),
                        credential = credential,
                    )
                    if (onPrepared(request)) {
                        password = ""
                        keyPassphrase = ""
                    } else {
                        formError = "Another SSH session is already active."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text("Connect securely")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreen(
    displayName: String,
    onControlC: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayName)
                        Text(
                            "Raw terminal · same PTY",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onControlC) { Text("Ctrl-C") }
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Terminal(
                terminalEmulator = SessionRuntime.terminal.emulator,
                keyboardEnabled = true,
                showSoftKeyboard = true,
                onHyperlinkClick = {},
                modifier = Modifier.fillMaxSize(),
            )
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
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
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
    }
}

private const val MAX_PRIVATE_KEY_BYTES = 1024 * 1024
