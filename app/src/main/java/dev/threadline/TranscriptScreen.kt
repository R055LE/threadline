package dev.threadline

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.threadline.core.shell.CommandSubmissionRejection
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.terminal.TerminalKey
import dev.threadline.core.terminal.TerminalModifiers
import dev.threadline.core.transcript.AnsiColor
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTranscriptState
import dev.threadline.core.transcript.CommandTurn
import dev.threadline.core.transcript.TranscriptLinkDetector
import dev.threadline.core.transcript.TranscriptStyle
import kotlinx.coroutines.delay
import org.connectbot.terminal.Terminal

internal object TranscriptTags {
    const val TRANSCRIPT = "session-transcript"
    const val COMPOSER = "command-composer"
    const val SEND = "command-send"
    const val HISTORY_OLDER = "command-history-older"
    const val HISTORY_NEWER = "command-history-newer"
    const val MODE_SWITCH = "session-mode-switch"
    const val INTERACTIVE_OPEN = "interactive-open-terminal"
    const val TERMINAL_CONTROL = "terminal-control"
    const val TERMINAL_ALT = "terminal-alt"
    const val SUBMISSION_ERROR = "command-submission-error"
    const val SESSION_ACTIONS = "session-actions"
    private const val TERMINAL_KEY_PREFIX = "terminal-key-"
    private const val OUTPUT_PREFIX = "command-output-"

    fun output(commandId: String): String = "$OUTPUT_PREFIX$commandId"

    fun terminalKey(key: TerminalKey): String = "$TERMINAL_KEY_PREFIX${key.name}"
}

private data class TerminalExtraKey(
    val key: TerminalKey,
    val label: String,
    val accessibilityLabel: String,
)

private val terminalExtraKeys = listOf(
    TerminalExtraKey(TerminalKey.ESCAPE, "Esc", "Escape"),
    TerminalExtraKey(TerminalKey.TAB, "Tab", "Tab"),
    TerminalExtraKey(TerminalKey.ARROW_UP, "↑", "Arrow up"),
    TerminalExtraKey(TerminalKey.ARROW_DOWN, "↓", "Arrow down"),
    TerminalExtraKey(TerminalKey.ARROW_LEFT, "←", "Arrow left"),
    TerminalExtraKey(TerminalKey.ARROW_RIGHT, "→", "Arrow right"),
    TerminalExtraKey(TerminalKey.HOME, "Home", "Home"),
    TerminalExtraKey(TerminalKey.END, "End", "End"),
    TerminalExtraKey(TerminalKey.PAGE_UP, "PgUp", "Page up"),
    TerminalExtraKey(TerminalKey.PAGE_DOWN, "PgDn", "Page down"),
    TerminalExtraKey(TerminalKey.DELETE, "Del", "Delete"),
)

@Composable
internal fun ConnectedSessionScreen(
    displayName: String,
    structuredShell: StructuredShellState,
    transcript: CommandTranscriptState,
    onSubmit: (String) -> CommandSubmissionResult,
    onControlC: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    rawTerminal: @Composable (Modifier) -> Unit = { RawTerminal(it) },
) {
    var rawModeRequested by rememberSaveable { mutableStateOf(false) }
    val transcriptStateHolder = rememberSaveableStateHolder()
    val rawModeRequired = structuredShell is StructuredShellState.Unavailable
    val showingRawTerminal = rawModeRequested

    LaunchedEffect(rawModeRequired) {
        if (rawModeRequired) rawModeRequested = true
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        structuredShell.statusLabel(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp)
                        .testTag(TranscriptTags.SESSION_ACTIONS),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!rawModeRequired || transcript.turns.isNotEmpty()) {
                        TextButton(
                            onClick = { rawModeRequested = !rawModeRequested },
                            modifier = Modifier.testTag(TranscriptTags.MODE_SWITCH),
                        ) {
                            Text(if (showingRawTerminal) "Transcript" else "Terminal")
                        }
                    }
                    if (showingRawTerminal) {
                        TextButton(onClick = onControlC) { Text("Ctrl-C") }
                    }
                    TextButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.testTag(DiagnosticTags.OPEN),
                    ) {
                        Text("Diagnostics")
                    }
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                HorizontalDivider()
            }
        },
    ) { contentPadding ->
        if (showingRawTerminal) {
            rawTerminal(
                Modifier
                    .padding(contentPadding)
                    .fillMaxSize(),
            )
        } else {
            transcriptStateHolder.SaveableStateProvider("transcript") {
                TranscriptSurface(
                    structuredShell = structuredShell,
                    transcript = transcript,
                    onSubmit = onSubmit,
                    onStop = onControlC,
                    onDisconnect = onDisconnect,
                    onOpenTerminal = { rawModeRequested = true },
                    modifier = Modifier
                        .padding(contentPadding)
                        .fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun RawTerminal(modifier: Modifier = Modifier) {
    val bridge = SessionRuntime.terminal
    val modifiers by bridge.modifiers.collectAsState()
    DisposableEffect(bridge) {
        bridge.clearModifiers()
        onDispose(bridge::clearModifiers)
    }

    Column(
        modifier = modifier
            .imePadding()
            .background(Color.Black),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Terminal(
                terminalEmulator = bridge.emulator,
                keyboardEnabled = true,
                showSoftKeyboard = true,
                onHyperlinkClick = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        HorizontalDivider()
        TerminalExtraKeyRow(
            modifiers = modifiers,
            onToggleControl = bridge::toggleControl,
            onToggleAlt = bridge::toggleAlt,
            onKey = bridge::sendKey,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun TerminalExtraKeyRow(
    modifiers: TerminalModifiers,
    onToggleControl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (TerminalKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = modifiers.control,
            onClick = onToggleControl,
            label = { Text("Ctrl") },
            modifier = Modifier.testTag(TranscriptTags.TERMINAL_CONTROL),
        )
        FilterChip(
            selected = modifiers.alt,
            onClick = onToggleAlt,
            label = { Text("Alt") },
            modifier = Modifier.testTag(TranscriptTags.TERMINAL_ALT),
        )
        terminalExtraKeys.forEach { extraKey ->
            TextButton(
                onClick = { onKey(extraKey.key) },
                modifier = Modifier
                    .testTag(TranscriptTags.terminalKey(extraKey.key))
                    .semantics {
                        contentDescription = extraKey.accessibilityLabel
                    },
            ) {
                Text(extraKey.label)
            }
        }
    }
}

@Composable
internal fun TranscriptSurface(
    structuredShell: StructuredShellState,
    transcript: CommandTranscriptState,
    onSubmit: (String) -> CommandSubmissionResult,
    onStop: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    modifier: Modifier = Modifier,
    clockMillis: () -> Long = System::currentTimeMillis,
    listState: LazyListState = rememberLazyListState(),
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val openUrl = onOpenUrl ?: uriHandler::openUri
    var composer by rememberSaveable { mutableStateOf("") }
    var historyIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var historyDraft by rememberSaveable { mutableStateOf("") }
    var submissionError by remember { mutableStateOf<String?>(null) }
    var followOutput by remember { mutableStateOf(true) }
    val historyCommands = transcript.turns.map(CommandTurn::command)
    val atBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount == 0 ||
                layout.visibleItemsInfo.lastOrNull()?.index == layout.totalItemsCount - 1
        }
    }
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    val lastTurn = transcript.turns.lastOrNull()
    val outputRevision = lastTurn?.let {
        "${it.id.value}:${it.status}:${it.output.plainText.length}"
    }

    LaunchedEffect(userDragging, atBottom) {
        if (userDragging || atBottom) {
            followOutput = atBottom
        }
    }
    LaunchedEffect(transcript.turns.size, outputRevision) {
        if (followOutput && transcript.turns.isNotEmpty()) {
            listState.scrollToItem(transcript.turns.size)
        }
    }

    fun submit(command: String, clearComposer: Boolean) {
        submissionError = null
        when (val result = onSubmit(command)) {
            is CommandSubmissionResult.Accepted -> {
                if (clearComposer) {
                    composer = ""
                }
                historyIndex = null
                historyDraft = composer
                followOutput = true
            }

            is CommandSubmissionResult.Rejected -> {
                submissionError = result.reason.userMessage()
            }
        }
    }

    fun showOlderCommand() {
        if (historyCommands.isEmpty()) return
        if (historyIndex == null) {
            historyDraft = composer
        }
        val olderIndex = historyIndex
            ?.minus(1)
            ?.coerceAtLeast(0)
            ?: historyCommands.lastIndex
        historyIndex = olderIndex
        composer = historyCommands[olderIndex]
        submissionError = null
    }

    fun showNewerCommand() {
        val currentIndex = historyIndex ?: return
        if (currentIndex < historyCommands.lastIndex) {
            val newerIndex = currentIndex + 1
            historyIndex = newerIndex
            composer = historyCommands[newerIndex]
        } else {
            historyIndex = null
            composer = historyDraft
        }
        submissionError = null
    }

    Column(
        modifier = modifier
            .imePadding()
            .testTag(TranscriptTags.TRANSCRIPT),
    ) {
        if (structuredShell !is StructuredShellState.Ready &&
            structuredShell !is StructuredShellState.Running
        ) {
            Text(
                text = structuredShell.statusLabel(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = transcript.turns,
                key = { it.id.value },
            ) { turn ->
                CommandCard(
                    turn = turn,
                    canSubmit = structuredShell is StructuredShellState.Ready,
                    onStop = onStop,
                    onDisconnect = onDisconnect,
                    onEdit = {
                        composer = turn.command
                        historyIndex = null
                        historyDraft = turn.command
                        submissionError = null
                    },
                    onRerun = { submit(turn.command, clearComposer = false) },
                    onOpenUrl = openUrl,
                    onOpenTerminal = onOpenTerminal,
                    clockMillis = clockMillis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item(key = "transcript-tail") {
                Spacer(Modifier.height(1.dp))
            }
        }

        HorizontalDivider()
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            submissionError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .testTag(TranscriptTags.SUBMISSION_ERROR)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = composer,
                    onValueChange = {
                        composer = it
                        historyIndex = null
                        historyDraft = it
                        submissionError = null
                    },
                    label = { Text("Command") },
                    minLines = 1,
                    maxLines = 5,
                    enabled = structuredShell is StructuredShellState.Ready ||
                        structuredShell is StructuredShellState.Running,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TranscriptTags.COMPOSER),
                )
                Button(
                    onClick = { submit(composer, clearComposer = true) },
                    enabled = structuredShell is StructuredShellState.Ready &&
                        composer.isNotEmpty(),
                    modifier = Modifier.testTag(TranscriptTags.SEND),
                ) {
                    Text("Send")
                }
            }
            if (historyCommands.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = ::showOlderCommand,
                        enabled = structuredShell is StructuredShellState.Ready &&
                            (historyIndex == null || historyIndex != 0),
                        modifier = Modifier.testTag(TranscriptTags.HISTORY_OLDER),
                    ) {
                        Text("Older")
                    }
                    TextButton(
                        onClick = ::showNewerCommand,
                        enabled = structuredShell is StructuredShellState.Ready &&
                            historyIndex != null,
                        modifier = Modifier.testTag(TranscriptTags.HISTORY_NEWER),
                    ) {
                        Text("Newer")
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandCard(
    turn: CommandTurn,
    canSubmit: Boolean,
    onStop: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onRerun: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenTerminal: () -> Unit,
    clockMillis: () -> Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(turn.id.value) { mutableStateOf(false) }
    var pendingUrl by rememberSaveable(turn.id.value) { mutableStateOf<String?>(null) }
    var linkOpenFailed by rememberSaveable(turn.id.value) { mutableStateOf(false) }
    val nowMillis = rememberTurnTime(turn, clockMillis)
    val outputStart = if (
        !expanded &&
        turn.output.plainText.length > COLLAPSED_OUTPUT_CHARACTERS
    ) {
        turn.output.plainText.length - COLLAPSED_OUTPUT_CHARACTERS
    } else {
        0
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer {
                Text(
                    text = turn.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = turn.metadataLabel(nowMillis),
                style = MaterialTheme.typography.labelSmall,
            )

            if (outputStart > 0) {
                Text(
                    text = "Showing the latest $COLLAPSED_OUTPUT_CHARACTERS characters",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (turn.output.plainText.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text = turn.output.toAnnotatedString(
                            start = outputStart,
                            onLinkClick = { pendingUrl = it },
                        ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(TranscriptTags.output(turn.id.value)),
                    )
                }
            }
            if (turn.output.truncated) {
                Text(
                    "Earlier output was truncated locally.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (turn.output.approximate) {
                Text(
                    "Transcript rendering is approximate; open Terminal for the exact view.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (turn.status.isActive() && turn.output.interactiveHint != null) {
                Text(
                    "This command may need interactive input.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Terminal keeps using this live SSH session.",
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(
                    onClick = onOpenTerminal,
                    modifier = Modifier.testTag(TranscriptTags.INTERACTIVE_OPEN),
                ) {
                    Text("Open terminal")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (turn.output.plainText.length > COLLAPSED_OUTPUT_CHARACTERS) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Collapse" else "Show all")
                    }
                }
                when (turn.status) {
                    CommandStatus.SUBMITTED,
                    CommandStatus.RUNNING,
                    -> TextButton(onClick = onStop) { Text("Stop") }

                    CommandStatus.STOPPING -> {
                        if (turn.canDisconnectAfterStop(nowMillis)) {
                            TextButton(onClick = onDisconnect) {
                                Text("Disconnect session")
                            }
                        } else {
                            Text(
                                text = "Interrupt sent",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                            )
                        }
                    }

                    else -> {
                        TextButton(onClick = onEdit) { Text("Edit") }
                        TextButton(onClick = onRerun, enabled = canSubmit) { Text("Rerun") }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        context.copyText("Threadline command", turn.command)
                    },
                ) {
                    Text("Copy command")
                }
                if (turn.output.plainText.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            context.copyText("Threadline output", turn.output.plainText)
                        },
                    ) {
                        Text("Copy output")
                    }
                }
            }
        }
    }

    pendingUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingUrl = null },
            title = { Text("Open external link?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Threadline detected this web address in untrusted command output:")
                    SelectionContainer {
                        Text(
                            text = url,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUrl = null
                        try {
                            onOpenUrl(url)
                        } catch (_: RuntimeException) {
                            linkOpenFailed = true
                        }
                    },
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUrl = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (linkOpenFailed) {
        AlertDialog(
            onDismissRequest = { linkOpenFailed = false },
            title = { Text("Could not open link") },
            text = { Text("No installed app accepted this web address.") },
            confirmButton = {
                TextButton(onClick = { linkOpenFailed = false }) {
                    Text("OK")
                }
            },
        )
    }
}

private fun StructuredShellState.statusLabel(): String = when (this) {
    StructuredShellState.Inactive -> "Raw terminal · same PTY"
    is StructuredShellState.Bootstrapping -> "Setting up transcript · terminal available"
    is StructuredShellState.Ready -> "Transcript ready · $currentDirectory"
    is StructuredShellState.Running -> "Command running · terminal available"
    is StructuredShellState.Unavailable -> "Transcript unavailable · terminal available"
}

private fun CommandSubmissionRejection.userMessage(): String = when (this) {
    CommandSubmissionRejection.NOT_READY -> "The structured shell is not ready."
    CommandSubmissionRejection.COMMAND_ALREADY_RUNNING ->
        "Wait for the active command to finish or stop it."

    CommandSubmissionRejection.INVALID_COMMAND -> "Commands cannot contain a NUL character."
    CommandSubmissionRejection.INPUT_BACKPRESSURE ->
        "The session input queue is full. Try again."
}

@Composable
private fun rememberTurnTime(
    turn: CommandTurn,
    clockMillis: () -> Long,
): Long {
    val currentClockMillis by rememberUpdatedState(clockMillis)
    var nowMillis by remember(turn.id.value) {
        mutableStateOf(currentClockMillis())
    }
    LaunchedEffect(turn.id.value, turn.status) {
        nowMillis = currentClockMillis()
        while (turn.status.isActive()) {
            delay(ACTIVE_DURATION_UPDATE_MILLIS)
            nowMillis = currentClockMillis()
        }
    }
    return nowMillis
}

private fun CommandTurn.metadataLabel(nowMillis: Long): String = buildList {
    add(status.label)
    directoryAtStart?.let(::add)
    durationMillis(nowMillis)?.let { add(formatDuration(it)) }
    exitStatus?.let { add("exit $it") }
}.joinToString(" · ")

private fun CommandTurn.durationMillis(nowMillis: Long): Long? {
    val start = startedAtMillis ?: submittedAtMillis
    val end = completedAtMillis ?: nowMillis.takeIf { status.isActive() } ?: return null
    return (end - start).coerceAtLeast(0)
}

private fun CommandTurn.canDisconnectAfterStop(nowMillis: Long): Boolean {
    val requestedAt = stopRequestedAtMillis ?: return false
    return nowMillis - requestedAt >= STOP_DISCONNECT_DELAY_MILLIS
}

private fun formatDuration(milliseconds: Long): String =
    if (milliseconds < 1_000) {
        "$milliseconds ms"
    } else {
        String.format(java.util.Locale.ROOT, "%.1f s", milliseconds / 1_000.0)
    }

private val CommandStatus.label: String
    get() = when (this) {
        CommandStatus.SUBMITTED -> "Submitted"
        CommandStatus.RUNNING -> "Running"
        CommandStatus.STOPPING -> "Stopping"
        CommandStatus.SUCCEEDED -> "Succeeded"
        CommandStatus.FAILED -> "Failed"
        CommandStatus.INTERRUPTED -> "Interrupted"
        CommandStatus.DISCONNECTED -> "Disconnected"
        CommandStatus.UNKNOWN -> "Status unknown"
    }

private fun CommandStatus.isActive(): Boolean =
    this == CommandStatus.SUBMITTED ||
        this == CommandStatus.RUNNING ||
        this == CommandStatus.STOPPING

@Composable
private fun CommandOutput.toAnnotatedString(
    start: Int,
    onLinkClick: (String) -> Unit,
): AnnotatedString {
    val defaultForeground = MaterialTheme.colorScheme.onSurface
    val defaultBackground = MaterialTheme.colorScheme.surface
    return buildAnnotatedString {
        append(plainText.substring(start))
        styledRuns.forEach { run ->
            val clippedStart = maxOf(run.start, start)
            val clippedEnd = minOf(run.endExclusive, plainText.length)
            if (clippedStart < clippedEnd) {
                addStyle(
                    style = run.style.toSpanStyle(defaultForeground, defaultBackground),
                    start = clippedStart - start,
                    end = clippedEnd - start,
                )
            }
        }
        TranscriptLinkDetector.detect(plainText).forEach { link ->
            if (link.start >= start) {
                addLink(
                    url = LinkAnnotation.Url(
                        url = link.url,
                        styles = TextLinkStyles(
                            style = SpanStyle(textDecoration = TextDecoration.Underline),
                        ),
                        linkInteractionListener = {
                            onLinkClick(link.url)
                        },
                    ),
                    start = link.start - start,
                    end = link.endExclusive - start,
                )
            }
        }
    }
}

private fun TranscriptStyle.toSpanStyle(
    defaultForeground: Color,
    defaultBackground: Color,
): SpanStyle {
    val resolvedForeground = foreground?.toColor()
    val resolvedBackground = background?.toColor()
    val foregroundColor = if (inverse) {
        resolvedBackground ?: defaultBackground
    } else {
        resolvedForeground ?: Color.Unspecified
    }
    val backgroundColor = if (inverse) {
        resolvedForeground ?: defaultForeground
    } else {
        resolvedBackground ?: Color.Unspecified
    }
    val decorations = buildList {
        if (underline) add(TextDecoration.Underline)
        if (strikethrough) add(TextDecoration.LineThrough)
    }
    val displayedForeground = if (dim) {
        val dimmable = if (foregroundColor == Color.Unspecified) {
            defaultForeground
        } else {
            foregroundColor
        }
        dimmable.copy(alpha = DIMMED_ALPHA)
    } else {
        foregroundColor
    }
    return SpanStyle(
        color = displayedForeground,
        background = backgroundColor,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = decorations.takeIf { it.isNotEmpty() }
            ?.let(TextDecoration::combine),
    )
}

private fun AnsiColor.toColor(): Color = when (this) {
    is AnsiColor.Rgb -> Color(red, green, blue)
    is AnsiColor.Indexed -> indexedAnsiColor(index)
}

private fun indexedAnsiColor(index: Int): Color = when (index) {
    0 -> Color(0xFF000000)
    1 -> Color(0xFFCD3131)
    2 -> Color(0xFF0DBC79)
    3 -> Color(0xFFE5E510)
    4 -> Color(0xFF2472C8)
    5 -> Color(0xFFBC3FBC)
    6 -> Color(0xFF11A8CD)
    7 -> Color(0xFFE5E5E5)
    8 -> Color(0xFF666666)
    9 -> Color(0xFFF14C4C)
    10 -> Color(0xFF23D18B)
    11 -> Color(0xFFF5F543)
    12 -> Color(0xFF3B8EEA)
    13 -> Color(0xFFD670D6)
    14 -> Color(0xFF29B8DB)
    15 -> Color(0xFFFFFFFF)
    in 16..231 -> {
        val value = index - 16
        val red = value / 36
        val green = (value % 36) / 6
        val blue = value % 6
        Color(
            red = ansiCubeComponent(red),
            green = ansiCubeComponent(green),
            blue = ansiCubeComponent(blue),
        )
    }

    else -> {
        val gray = 8 + (index - 232) * 10
        Color(gray, gray, gray)
    }
}

private fun ansiCubeComponent(value: Int): Int =
    if (value == 0) 0 else 55 + value * 40

private fun Context.copyText(
    label: String,
    text: String,
) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private const val COLLAPSED_OUTPUT_CHARACTERS = 8_000
private const val DIMMED_ALPHA = 0.6f
private const val ACTIVE_DURATION_UPDATE_MILLIS = 1_000L
private const val STOP_DISCONNECT_DELAY_MILLIS = 3_000L
