package dev.threadline

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.threadline.core.shell.CommandSubmissionRejection
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.transcript.AnsiColor
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTranscriptState
import dev.threadline.core.transcript.CommandTurn
import dev.threadline.core.transcript.TranscriptStyle
import org.connectbot.terminal.Terminal

internal object TranscriptTags {
    const val TRANSCRIPT = "session-transcript"
    const val COMPOSER = "command-composer"
    const val SEND = "command-send"
    const val MODE_SWITCH = "session-mode-switch"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectedSessionScreen(
    displayName: String,
    structuredShell: StructuredShellState,
    transcript: CommandTranscriptState,
    onSubmit: (String) -> CommandSubmissionResult,
    onControlC: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var rawModeRequested by rememberSaveable { mutableStateOf(false) }
    val rawModeRequired = structuredShell is StructuredShellState.Unavailable
    val showingRawTerminal = rawModeRequested

    LaunchedEffect(rawModeRequired) {
        if (rawModeRequired) rawModeRequested = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayName)
                        Text(
                            structuredShell.statusLabel(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
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
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                },
            )
        },
    ) { contentPadding ->
        if (showingRawTerminal) {
            RawTerminal(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize(),
            )
        } else {
            TranscriptSurface(
                structuredShell = structuredShell,
                transcript = transcript,
                onSubmit = onSubmit,
                onStop = onControlC,
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize(),
            )
        }
    }
}

@Composable
private fun RawTerminal(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .imePadding()
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

@Composable
internal fun TranscriptSurface(
    structuredShell: StructuredShellState,
    transcript: CommandTranscriptState,
    onSubmit: (String) -> CommandSubmissionResult,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var composer by rememberSaveable { mutableStateOf("") }
    var submissionError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var followOutput by remember { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount == 0 ||
                layout.visibleItemsInfo.lastOrNull()?.index == layout.totalItemsCount - 1
        }
    }
    val lastTurn = transcript.turns.lastOrNull()
    val outputRevision = lastTurn?.let {
        "${it.id.value}:${it.status}:${it.output.plainText.length}"
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
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
                if (clearComposer) composer = ""
                followOutput = true
            }

            is CommandSubmissionResult.Rejected -> {
                submissionError = result.reason.userMessage()
            }
        }
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
                    onEdit = {
                        composer = turn.command
                        submissionError = null
                    },
                    onRerun = { submit(turn.command, clearComposer = false) },
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
                        submissionError = null
                    },
                    label = { Text("Command") },
                    minLines = 1,
                    maxLines = 5,
                    enabled = structuredShell is StructuredShellState.Ready,
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
        }
    }
}

@Composable
private fun CommandCard(
    turn: CommandTurn,
    canSubmit: Boolean,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onRerun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(turn.id.value) { mutableStateOf(false) }
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
                )
            }
            Text(
                text = turn.metadataLabel(),
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
                        text = turn.output.toAnnotatedString(outputStart),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (turn.output.plainText.length > COLLAPSED_OUTPUT_CHARACTERS) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Collapse" else "Show all")
                    }
                }
                if (turn.status.isActive()) {
                    TextButton(onClick = onStop) { Text("Stop") }
                } else {
                    TextButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = onRerun, enabled = canSubmit) { Text("Rerun") }
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

private fun CommandTurn.metadataLabel(): String = buildList {
    add(status.label)
    directoryAtStart?.let(::add)
    durationMillis()?.let { add(formatDuration(it)) }
    exitStatus?.let { add("exit $it") }
}.joinToString(" · ")

private fun CommandTurn.durationMillis(): Long? {
    val start = startedAtMillis ?: submittedAtMillis
    val end = completedAtMillis ?: return null
    return (end - start).coerceAtLeast(0)
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
private fun CommandOutput.toAnnotatedString(start: Int): AnnotatedString {
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
