package dev.threadline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import dev.threadline.core.model.SessionErrorAction
import dev.threadline.core.model.SessionState
import dev.threadline.core.model.presentation
import dev.threadline.core.diagnostics.DiagnosticInventory
import dev.threadline.core.diagnostics.DiagnosticReportInput
import dev.threadline.core.diagnostics.diagnosticSessionSnapshot
import dev.threadline.core.diagnostics.generateDiagnosticReport
import dev.threadline.data.host.KnownHostMetadata
import dev.threadline.data.key.ImportedPrivateKeyMetadata
import dev.threadline.data.profile.SavedHostProfile
import dev.threadline.data.transcript.SavedTranscriptSession
import dev.threadline.data.transcript.SavedTranscriptSessionSummary
import dev.threadline.service.SshSessionService
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
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
    val ephemeral: Boolean,
) {
    companion object {
        fun emptyDefaults() = ConnectionFormDraft(
            displayName = "",
            hostname = "",
            port = "22",
            username = "",
            authenticationMode = AuthenticationMode.PASSWORD,
            ephemeral = false,
        )

        fun fixtureDefaults() = ConnectionFormDraft(
            displayName = "Local fixture",
            hostname = "10.0.2.2",
            port = "2222",
            username = "threadline",
            authenticationMode = AuthenticationMode.PASSWORD,
            ephemeral = false,
        )

        val Saver: Saver<ConnectionFormDraft, Any> = listSaver(
            save = {
                listOf(
                    it.displayName,
                    it.hostname,
                    it.port,
                    it.username,
                    it.authenticationMode.name,
                    it.ephemeral.toString(),
                )
            },
            restore = {
                ConnectionFormDraft(
                    displayName = it[0],
                    hostname = it[1],
                    port = it[2],
                    username = it[3],
                    authenticationMode = AuthenticationMode.valueOf(it[4]),
                    ephemeral = it[5].toBooleanStrict(),
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
    const val EPHEMERAL = "connection-ephemeral"
    const val CONNECT = "connection-connect"
    const val ACTIVE_SESSION = "connection-active-session"
    const val RETURN_TO_SESSION = "connection-return-to-session"
    const val DISCONNECT_SESSION = "connection-disconnect-session"
    const val SAVED_KEY_PREFIX = "connection-saved-key-"
    const val RENAME_KEY_PREFIX = "connection-rename-key-"
    const val DELETE_KEY_PREFIX = "connection-delete-key-"
    const val RENAME_KEY_NAME = "connection-rename-key-name"
    const val CONFIRM_RENAME_KEY = "connection-confirm-rename-key"
    const val CONFIRM_DELETE_KEY = "connection-confirm-delete-key"
    const val SAVED_PROFILE_PREFIX = "connection-saved-profile-"
    const val SAVE_PROFILE = "connection-save-profile"
    const val UPDATE_PROFILE = "connection-update-profile"
    const val USE_PROFILE_AS_NEW = "connection-use-profile-as-new"
    const val DELETE_PROFILE_PREFIX = "connection-delete-profile-"
    const val CONFIRM_DELETE_PROFILE = "connection-confirm-delete-profile"
    const val TRUSTED_HOST_PREFIX = "connection-trusted-host-"
    const val DELETE_TRUST_PREFIX = "connection-delete-trust-"
    const val CONFIRM_DELETE_TRUST = "connection-confirm-delete-trust"
    const val SESSION_ERROR = "connection-session-error"
    const val ERROR_ACTION = "connection-error-action"
    const val HELP = "connection-help"
}

@Composable
private fun ThreadlineApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = SessionRuntime.manager
    val snapshot by manager.snapshot.collectAsStateWithLifecycle()
    val importedPrivateKeys by SessionRuntime.importedPrivateKeys.keys
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val hostProfiles by SessionRuntime.hostProfiles.profiles
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val knownHosts by SessionRuntime.knownHosts.hosts
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val transcriptSessions by SessionRuntime.transcriptHistory.sessions
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val transcriptSaveFailed by manager.transcriptSaveFailed.collectAsStateWithLifecycle()
    val state = snapshot.connection
    var connectionDraft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
        mutableStateOf(ConnectionFormDraft.emptyDefaults())
    }
    val onboardingPreferences = remember(context) { OnboardingPreferences.create(context) }
    var showIntroduction by rememberSaveable {
        mutableStateOf(onboardingPreferences.shouldShowIntroduction())
    }
    var selectedHostProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var showConnectedSession by rememberSaveable { mutableStateOf(true) }
    val connectedSessionStateHolder = rememberSaveableStateHolder()
    var diagnosticGeneratedAtMillis by remember { mutableStateOf<Long?>(null) }
    val diagnosticEnvironment = remember(context) { androidDiagnosticEnvironment(context) }
    val openDiagnostics = { diagnosticGeneratedAtMillis = System.currentTimeMillis() }

    LaunchedEffect(state is SessionState.Connected) {
        if (state !is SessionState.Connected) {
            connectedSessionStateHolder.removeState("active-session")
        }
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

    val canShowIntroduction = state is SessionState.Disconnected ||
        state is SessionState.Failed ||
        state is SessionState.Connected && !showConnectedSession
    if (showIntroduction && canShowIntroduction) {
        OnboardingScreen(
            onContinue = {
                onboardingPreferences.markIntroductionComplete()
                showIntroduction = false
            },
        )
    } else if (state is SessionState.Connected && showConnectedSession) {
        connectedSessionStateHolder.SaveableStateProvider("active-session") {
            ConnectedSessionScreen(
                displayName = state.displayName,
                structuredShell = snapshot.structuredShell,
                transcript = snapshot.transcript,
                onSubmit = manager::submitCommand,
                onControlC = manager::sendControlC,
                onDisconnect = manager::disconnect,
                onOpenHome = { showConnectedSession = false },
                onOpenDiagnostics = openDiagnostics,
            )
        }
    } else when (val current = state) {
        SessionState.Disconnected,
        is SessionState.Failed,
        is SessionState.Connected,
        -> HostForm(
            draft = connectionDraft,
            onDraftChange = { connectionDraft = it },
            sessionError = (current as? SessionState.Failed)?.error,
            activeSessionDisplayName = (current as? SessionState.Connected)?.displayName,
            connectionEnabled = current !is SessionState.Connected,
            onReturnToActiveSession = { showConnectedSession = true },
            onDisconnectActiveSession = manager::disconnect,
            hostProfiles = hostProfiles,
            selectedHostProfileId = selectedHostProfileId,
            onSelectedHostProfileChange = { selectedHostProfileId = it },
            onSaveHostProfile = SessionRuntime.hostProfiles::save,
            onUpdateHostProfile = SessionRuntime.hostProfiles::update,
            onDeleteHostProfile = SessionRuntime.hostProfiles::delete,
            knownHosts = knownHosts,
            onDeleteKnownHost = SessionRuntime.knownHosts::delete,
            transcriptSessions = transcriptSessions,
            transcriptSaveFailed = transcriptSaveFailed,
            onLoadTranscript = SessionRuntime.transcriptHistory::load,
            onDeleteTranscript = SessionRuntime.transcriptHistory::delete,
            onClearTranscriptHistory = SessionRuntime.transcriptHistory::clearAll,
            importedPrivateKeys = importedPrivateKeys,
            onSavePrivateKey = SessionRuntime.importedPrivateKeys::save,
            onLoadPrivateKey = SessionRuntime.importedPrivateKeys::credential,
            onRenamePrivateKey = SessionRuntime.importedPrivateKeys::rename,
            onDeletePrivateKey = SessionRuntime.importedPrivateKeys::delete,
            onOpenIntroduction = { showIntroduction = true },
            onOpenDiagnostics = openDiagnostics,
            onOpenNotificationSettings = { openNotificationSettings(context) },
            onPrepared = prepared@{ request ->
                if (!manager.prepareConnection(request)) return@prepared false
                showConnectedSession = true

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

        is SessionState.Connecting -> ProgressScreen(
            title = current.displayName,
            status = current.stage.name.lowercase().replaceFirstChar(Char::uppercase),
            onCancel = manager::disconnect,
            onOpenDiagnostics = openDiagnostics,
        )

        is SessionState.AwaitingHostKey -> ProgressScreen(
            title = current.displayName,
            status = "Waiting for host-key confirmation",
            onCancel = manager::disconnect,
            onOpenDiagnostics = openDiagnostics,
        )

        is SessionState.Disconnecting -> ProgressScreen(
            title = current.displayName ?: "Threadline",
            status = "Disconnecting",
            onCancel = null,
            onOpenDiagnostics = openDiagnostics,
        )
    }

    if (state is SessionState.AwaitingHostKey) {
        HostKeyDialog(
            prompt = state.prompt,
            onDecision = manager::resolveHostKey,
        )
    }

    diagnosticGeneratedAtMillis?.let { generatedAtMillis ->
        DiagnosticReportDialog(
            reportFactory = { includeSensitiveDetails ->
                generateDiagnosticReport(
                    DiagnosticReportInput(
                        generatedAtMillis = generatedAtMillis,
                        environment = diagnosticEnvironment,
                        inventory = DiagnosticInventory(
                            savedProfiles = hostProfiles.size,
                            trustedServers = knownHosts.size,
                            importedPrivateKeys = importedPrivateKeys.size,
                            savedTranscriptSessions = transcriptSessions.size,
                        ),
                        session = diagnosticSessionSnapshot(
                            connection = snapshot.connection,
                            structuredShell = snapshot.structuredShell,
                            transcript = snapshot.transcript,
                            transcriptSaveFailed = transcriptSaveFailed,
                        ),
                        sensitiveDetails = if (includeSensitiveDetails) {
                            sensitiveDiagnosticDetails(
                                draft = connectionDraft,
                                structuredShell = snapshot.structuredShell,
                                transcript = snapshot.transcript,
                            )
                        } else {
                            null
                        },
                    ),
                )
            },
            onDismiss = { diagnosticGeneratedAtMillis = null },
            onShare = { shareDiagnosticReport(context, it) },
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

private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }
    context.startActivity(intent)
}

@Composable
internal fun HostForm(
    draft: ConnectionFormDraft,
    onDraftChange: (ConnectionFormDraft) -> Unit,
    sessionError: SessionError?,
    activeSessionDisplayName: String? = null,
    connectionEnabled: Boolean = true,
    onReturnToActiveSession: () -> Unit = {},
    onDisconnectActiveSession: () -> Unit = {},
    hostProfiles: List<SavedHostProfile> = emptyList(),
    selectedHostProfileId: String? = null,
    onSelectedHostProfileChange: (String?) -> Unit = {},
    onSaveHostProfile: suspend (HostProfile) -> SavedHostProfile = {
        error("Host-profile storage is unavailable.")
    },
    onUpdateHostProfile: suspend (id: String, profile: HostProfile) -> Unit = { _, _ ->
        error("Host-profile storage is unavailable.")
    },
    onDeleteHostProfile: suspend (id: String) -> Unit = {
        error("Host-profile storage is unavailable.")
    },
    knownHosts: List<KnownHostMetadata> = emptyList(),
    onDeleteKnownHost: suspend (endpointKey: String) -> Unit = {
        error("Known-host storage is unavailable.")
    },
    transcriptSessions: List<SavedTranscriptSessionSummary> = emptyList(),
    transcriptSaveFailed: Boolean = false,
    onLoadTranscript: suspend (id: String) -> SavedTranscriptSession = {
        error("Transcript history is unavailable.")
    },
    onDeleteTranscript: suspend (id: String) -> Unit = {
        error("Transcript history is unavailable.")
    },
    onClearTranscriptHistory: suspend () -> Unit = {
        error("Transcript history is unavailable.")
    },
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
    onRenamePrivateKey: suspend (
        id: String,
        displayName: String,
    ) -> Unit = { _, _ ->
        error("Encrypted private-key storage is unavailable.")
    },
    onDeletePrivateKey: suspend (id: String) -> Unit = {
        error("Encrypted private-key storage is unavailable.")
    },
    onOpenIntroduction: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
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
    var isManagingProfile by remember { mutableStateOf(false) }
    var profilePendingDeletion by remember { mutableStateOf<SavedHostProfile?>(null) }
    var isManagingKnownHost by remember { mutableStateOf(false) }
    var knownHostPendingDeletion by remember { mutableStateOf<KnownHostMetadata?>(null) }
    var isManagingKey by remember { mutableStateOf(false) }
    var keyPendingRename by remember { mutableStateOf<ImportedPrivateKeyMetadata?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var keyPendingDeletion by remember { mutableStateOf<ImportedPrivateKeyMetadata?>(null) }
    val hostnameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val keyPassphraseFocusRequester = remember { FocusRequester() }
    val selectedHostProfile = hostProfiles.firstOrNull { it.id == selectedHostProfileId }
    val isBusy = isPreparing || isManagingProfile || isManagingKnownHost || isManagingKey

    fun clearSessionCredentialInputs() {
        password = ""
        keyPassphrase = ""
        selectedKeyUri = null
        selectedSavedKeyId = null
        savePrivateKey = false
    }

    val keyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        selectedKeyUri = uri?.toString()
        if (uri != null) selectedSavedKeyId = null
        formError = null
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                Text(
                    text = "Threadline",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .semantics { heading() },
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = onOpenIntroduction,
                        modifier = Modifier.testTag(ConnectionFormTags.HELP),
                    ) {
                        Text("Help")
                    }
                    TextButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag(DiagnosticTags.OPEN),
                    ) {
                        Text("Diagnostics")
                    }
                }
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
            activeSessionDisplayName?.let { displayName ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ConnectionFormTags.ACTIVE_SESSION),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            "Active session",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "This session remains connected while you use Home.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onReturnToActiveSession,
                                modifier = Modifier.testTag(
                                    ConnectionFormTags.RETURN_TO_SESSION,
                                ),
                            ) {
                                Text("Return")
                            }
                            TextButton(
                                onClick = onDisconnectActiveSession,
                                modifier = Modifier.testTag(
                                    ConnectionFormTags.DISCONNECT_SESSION,
                                ),
                            ) {
                                Text("Disconnect")
                            }
                        }
                        Text(
                            "Disconnect this session before connecting to another server.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(
                "Connect to a server",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Threadline connects directly to the SSH endpoint you enter and verifies " +
                    "the server before signing in.",
                style = MaterialTheme.typography.bodyMedium,
            )

            sessionError?.let { error ->
                ErrorCard(
                    error = error,
                    onAction = { action ->
                        when (action) {
                            SessionErrorAction.REVIEW_SERVER ->
                                hostnameFocusRequester.requestFocus()

                            SessionErrorAction.REVIEW_CREDENTIALS -> when (
                                draft.authenticationMode
                            ) {
                                AuthenticationMode.PASSWORD ->
                                    passwordFocusRequester.requestFocus()

                                AuthenticationMode.PRIVATE_KEY ->
                                    keyPassphraseFocusRequester.requestFocus()
                            }

                            SessionErrorAction.REVIEW_PRIVATE_KEY ->
                                keyPassphraseFocusRequester.requestFocus()

                            SessionErrorAction.OPEN_NOTIFICATION_SETTINGS -> try {
                                onOpenNotificationSettings()
                            } catch (_: RuntimeException) {
                                formError = "Android notification settings could not be opened."
                            }
                        }
                    },
                )
            }
            formError?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            TranscriptHistorySection(
                sessions = transcriptSessions,
                saveFailed = transcriptSaveFailed,
                onLoad = onLoadTranscript,
                onDelete = onDeleteTranscript,
                onClearAll = onClearTranscriptHistory,
            )

            if (hostProfiles.isNotEmpty()) {
                Text("Saved profiles", style = MaterialTheme.typography.labelLarge)
                hostProfiles.forEach { profile ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilterChip(
                            selected = selectedHostProfileId == profile.id,
                            onClick = {
                                onSelectedHostProfileChange(profile.id)
                                onDraftChange(
                                    draft.copy(
                                        displayName = profile.displayName,
                                        hostname = profile.hostname,
                                        port = profile.port.toString(),
                                        username = profile.username,
                                    ),
                                )
                                clearSessionCredentialInputs()
                                formError = null
                            },
                            enabled = !isBusy,
                            label = {
                                Column {
                                    Text(profile.displayName)
                                    Text(
                                        "${profile.username}@${profile.hostname}:${profile.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(
                                    ConnectionFormTags.SAVED_PROFILE_PREFIX + profile.id,
                                ),
                        )
                        TextButton(
                            onClick = {
                                profilePendingDeletion = profile
                                formError = null
                            },
                            enabled = !isBusy,
                            modifier = Modifier.testTag(
                                ConnectionFormTags.DELETE_PROFILE_PREFIX + profile.id,
                            ),
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }

            if (knownHosts.isNotEmpty()) {
                Text("Trusted servers", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Forgetting a server removes only its saved host-key decision. " +
                        "A later connection must be verified and accepted again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                knownHosts.forEach { host ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .testTag(ConnectionFormTags.TRUSTED_HOST_PREFIX + host.endpointKey),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(12.dp),
                            ) {
                                Text(
                                    "${host.hostname}:${host.port}",
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "${host.algorithm} · ${host.fingerprint}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "First trusted ${formatKnownHostTimestamp(host.firstSeenAtMillis)}; " +
                                        "last verified ${formatKnownHostTimestamp(host.lastSeenAtMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                knownHostPendingDeletion = host
                                formError = null
                            },
                            enabled = !isBusy,
                            modifier = Modifier.testTag(
                                ConnectionFormTags.DELETE_TRUST_PREFIX + host.endpointKey,
                            ),
                        ) {
                            Text("Forget")
                        }
                    }
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
                    .focusRequester(hostnameFocusRequester)
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

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = {
                        if (isBusy) return@Button
                        val profile = draft.toHostProfileOrNull()
                        if (profile == null) {
                            formError = INVALID_HOST_PROFILE_MESSAGE
                            return@Button
                        }
                        formError = null
                        isManagingProfile = true
                        coroutineScope.launch {
                            try {
                                val selectedId = selectedHostProfile?.id
                                if (selectedId == null) {
                                    val saved = onSaveHostProfile(profile)
                                    onSelectedHostProfileChange(saved.id)
                                } else {
                                    onUpdateHostProfile(selectedId, profile)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                formError = failure.message
                                    ?: "The host profile could not be saved."
                            } finally {
                                isManagingProfile = false
                            }
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.testTag(
                        if (selectedHostProfile == null) {
                            ConnectionFormTags.SAVE_PROFILE
                        } else {
                            ConnectionFormTags.UPDATE_PROFILE
                        },
                    ),
                ) {
                    Text(if (selectedHostProfile == null) "Save profile" else "Update profile")
                }
                if (selectedHostProfile != null) {
                    TextButton(
                        onClick = {
                            onSelectedHostProfileChange(null)
                            clearSessionCredentialInputs()
                            formError = null
                        },
                        enabled = !isBusy,
                        modifier = Modifier.testTag(ConnectionFormTags.USE_PROFILE_AS_NEW),
                    ) {
                        Text("Use as new")
                    }
                }
            }
            Text(
                "Profiles save the server address and username only. Passwords and private-key " +
                    "passphrases are never saved.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "Authentication",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = draft.authenticationMode == AuthenticationMode.PASSWORD,
                    onClick = {
                        onDraftChange(
                            draft.copy(authenticationMode = AuthenticationMode.PASSWORD),
                        )
                    },
                    enabled = !isBusy,
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
                    enabled = !isBusy,
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
                        .focusRequester(passwordFocusRequester)
                        .testTag(ConnectionFormTags.PASSWORD),
                )

                AuthenticationMode.PRIVATE_KEY -> {
                    if (importedPrivateKeys.isNotEmpty()) {
                        Text("Saved keys", style = MaterialTheme.typography.labelLarge)
                        importedPrivateKeys.forEach { key ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                FilterChip(
                                    selected = selectedSavedKeyId == key.id,
                                    onClick = {
                                        selectedSavedKeyId = key.id
                                        selectedKeyUri = null
                                        savePrivateKey = false
                                        formError = null
                                    },
                                    enabled = !isBusy,
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
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag(
                                            ConnectionFormTags.SAVED_KEY_PREFIX + key.id,
                                        ),
                                )
                                Column(horizontalAlignment = Alignment.End) {
                                    TextButton(
                                        onClick = {
                                            keyPendingRename = key
                                            renameDraft = key.displayName
                                            formError = null
                                        },
                                        enabled = !isBusy,
                                        modifier = Modifier.testTag(
                                            ConnectionFormTags.RENAME_KEY_PREFIX + key.id,
                                        ),
                                    ) {
                                        Text("Rename")
                                    }
                                    TextButton(
                                        onClick = {
                                            keyPendingDeletion = key
                                            formError = null
                                        },
                                        enabled = !isBusy,
                                        modifier = Modifier.testTag(
                                            ConnectionFormTags.DELETE_KEY_PREFIX + key.id,
                                        ),
                                    ) {
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { keyPicker.launch("*/*") },
                        enabled = !isBusy,
                    ) {
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
                                enabled = !isBusy,
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
                            .focusRequester(keyPassphraseFocusRequester)
                            .testTag(ConnectionFormTags.KEY_PASSPHRASE),
                    )
                    Text(
                        "Passphrases stay in memory for this connection and are never saved.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = draft.ephemeral,
                    onCheckedChange = { onDraftChange(draft.copy(ephemeral = it)) },
                    enabled = !isBusy,
                    modifier = Modifier.testTag(ConnectionFormTags.EPHEMERAL),
                )
                Column {
                    Text("Ephemeral session")
                    Text(
                        "Do not retain commands or output after disconnect.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                "Otherwise, command text and up to 65,536 characters of output per turn " +
                    "are kept in the local, unencrypted app database. Device backups are disabled.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (isBusy) return@Button
                    formError = null
                    val profile = draft.toHostProfileOrNull()
                    if (profile == null) {
                        formError = INVALID_HOST_PROFILE_MESSAGE
                        return@Button
                    }

                    isPreparing = true
                    coroutineScope.launch {
                        try {
                            val credential = when (draft.authenticationMode) {
                                AuthenticationMode.PASSWORD -> {
                                    if (password.isEmpty()) {
                                        error("Enter the password.")
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
                                profile = profile,
                                credential = credential,
                                ephemeral = draft.ephemeral,
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
                enabled = connectionEnabled && !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConnectionFormTags.CONNECT),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text(if (isPreparing) "Preparing securely…" else "Connect securely")
            }
        }
    }

    profilePendingDeletion?.let { profile ->
        AlertDialog(
            onDismissRequest = {
                if (!isManagingProfile) profilePendingDeletion = null
            },
            title = { Text("Delete saved profile?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This removes the saved connection details for ${profile.displayName}. " +
                            "Known-host trust and saved private keys are not changed.",
                    )
                    Text(
                        "${profile.username}@${profile.hostname}:${profile.port}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isBusy) return@Button
                        isManagingProfile = true
                        coroutineScope.launch {
                            try {
                                onDeleteHostProfile(profile.id)
                                if (selectedHostProfileId == profile.id) {
                                    onSelectedHostProfileChange(null)
                                    clearSessionCredentialInputs()
                                }
                                profilePendingDeletion = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                formError = failure.message
                                    ?: "The host profile could not be deleted."
                                profilePendingDeletion = null
                            } finally {
                                isManagingProfile = false
                            }
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.testTag(ConnectionFormTags.CONFIRM_DELETE_PROFILE),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { profilePendingDeletion = null },
                    enabled = !isManagingProfile,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    knownHostPendingDeletion?.let { host ->
        AlertDialog(
            onDismissRequest = {
                if (!isManagingKnownHost) knownHostPendingDeletion = null
            },
            title = { Text("Forget trusted server?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This removes Threadline's saved host-key decision for " +
                            "${host.hostname}:${host.port}. It does not change the server. " +
                            "The next connection must be verified and accepted again.",
                    )
                    Text(
                        "${host.algorithm} · ${host.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isBusy) return@Button
                        isManagingKnownHost = true
                        coroutineScope.launch {
                            try {
                                onDeleteKnownHost(host.endpointKey)
                                knownHostPendingDeletion = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                formError = failure.message
                                    ?: "The trusted server record could not be deleted."
                                knownHostPendingDeletion = null
                            } finally {
                                isManagingKnownHost = false
                            }
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.testTag(ConnectionFormTags.CONFIRM_DELETE_TRUST),
                ) {
                    Text("Forget")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { knownHostPendingDeletion = null },
                    enabled = !isManagingKnownHost,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    keyPendingRename?.let { key ->
        AlertDialog(
            onDismissRequest = {
                if (!isManagingKey) keyPendingRename = null
            },
            title = { Text("Rename saved key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        label = { Text("Key name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ConnectionFormTags.RENAME_KEY_NAME),
                    )
                    Text(
                        "${key.keyType} · ${key.publicKeyFingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isManagingKey) return@Button
                        isManagingKey = true
                        coroutineScope.launch {
                            try {
                                onRenamePrivateKey(key.id, renameDraft)
                                keyPendingRename = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                formError = failure.message
                                    ?: "The saved private key could not be renamed."
                                keyPendingRename = null
                            } finally {
                                isManagingKey = false
                            }
                        }
                    },
                    enabled = !isManagingKey && renameDraft.isNotBlank(),
                    modifier = Modifier.testTag(ConnectionFormTags.CONFIRM_RENAME_KEY),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { keyPendingRename = null },
                    enabled = !isManagingKey,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    keyPendingDeletion?.let { key ->
        AlertDialog(
            onDismissRequest = {
                if (!isManagingKey) keyPendingDeletion = null
            },
            title = { Text("Delete saved key?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This removes the encrypted copy of ${key.displayName} from this " +
                            "device. It does not revoke the public key on any server.",
                    )
                    Text(
                        "${key.keyType} · ${key.publicKeyFingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isManagingKey) return@Button
                        isManagingKey = true
                        coroutineScope.launch {
                            try {
                                onDeletePrivateKey(key.id)
                                if (selectedSavedKeyId == key.id) {
                                    selectedSavedKeyId = null
                                    keyPassphrase = ""
                                }
                                keyPendingDeletion = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                formError = failure.message
                                    ?: "The saved private key could not be deleted."
                                keyPendingDeletion = null
                            } finally {
                                isManagingKey = false
                            }
                        }
                    },
                    enabled = !isManagingKey,
                    modifier = Modifier.testTag(ConnectionFormTags.CONFIRM_DELETE_KEY),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { keyPendingDeletion = null },
                    enabled = !isManagingKey,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ErrorCard(
    error: SessionError,
    onAction: (SessionErrorAction) -> Unit,
) {
    val presentation = error.presentation()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ConnectionFormTags.SESSION_ERROR)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Assertive
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = presentation.title,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(presentation.message)
            Text(
                text = presentation.recovery,
                style = MaterialTheme.typography.bodySmall,
            )
            if (error is SessionError.HostKeyChanged) {
                Text(
                    text = "Server: ${error.endpoint.hostname}:${error.endpoint.port}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
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
                Text(
                    "To replace this trust record, forget the matching trusted server below, " +
                        "reconnect, verify the new fingerprint through a trusted channel, " +
                        "and explicitly accept it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            presentation.action?.let { action ->
                TextButton(
                    onClick = { onAction(action) },
                    modifier = Modifier.testTag(ConnectionFormTags.ERROR_ACTION),
                ) {
                    Text(action.label())
                }
            }
        }
    }
}

private fun SessionErrorAction.label(): String = when (this) {
    SessionErrorAction.REVIEW_SERVER -> "Review server"
    SessionErrorAction.REVIEW_CREDENTIALS -> "Review credentials"
    SessionErrorAction.REVIEW_PRIVATE_KEY -> "Review private key"
    SessionErrorAction.OPEN_NOTIFICATION_SETTINGS -> "Open notification settings"
}

@Composable
private fun ProgressScreen(
    title: String,
    status: String,
    onCancel: (() -> Unit)?,
    onOpenDiagnostics: () -> Unit,
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
            CircularProgressIndicator(
                modifier = Modifier.semantics {
                    contentDescription = "Connection in progress"
                },
            )
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(status)
            onCancel?.let {
                OutlinedButton(onClick = it) { Text("Cancel") }
            }
            TextButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.testTag(DiagnosticTags.OPEN),
            ) {
                Text("Diagnostics")
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

private fun formatKnownHostTimestamp(timestampMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(
        Date(timestampMillis),
    )

private fun ConnectionFormDraft.toHostProfileOrNull(): HostProfile? {
    val parsedPort = port.toIntOrNull()
    if (
        displayName.isBlank() ||
        hostname.isBlank() ||
        username.isBlank() ||
        parsedPort == null ||
        parsedPort !in 1..65535
    ) {
        return null
    }
    return HostProfile(
        displayName = displayName.trim(),
        endpoint = HostEndpoint(hostname.trim(), parsedPort),
        username = username.trim(),
    )
}

private class ClearingByteArrayOutputStream : ByteArrayOutputStream() {
    fun clear() {
        buf.fill(0)
        reset()
    }
}

private const val MAX_PRIVATE_KEY_BYTES = 1024 * 1024
private const val INVALID_HOST_PROFILE_MESSAGE =
    "Enter a display name, host, valid port, and username."
