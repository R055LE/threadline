package dev.threadline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.data.transcript.SavedTranscriptSession
import dev.threadline.data.transcript.SavedTranscriptSessionSummary
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal object TranscriptHistoryTags {
    const val OPEN = "transcript-history-open"
    const val CLEAR_ALL = "transcript-history-clear-all"
    const val CONFIRM_CLEAR_ALL = "transcript-history-confirm-clear-all"
    const val CONFIRM_DELETE = "transcript-history-confirm-delete"
    const val SESSION_PREFIX = "transcript-history-session-"
    const val DELETE_PREFIX = "transcript-history-delete-"
    const val OUTPUT_PREFIX = "transcript-history-output-"
}

@Composable
internal fun TranscriptHistorySection(
    sessions: List<SavedTranscriptSessionSummary>,
    saveFailed: Boolean,
    onLoad: suspend (String) -> SavedTranscriptSession,
    onDelete: suspend (String) -> Unit,
    onClearAll: suspend () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var historyOpen by remember { mutableStateOf(false) }
    var selectedSession by remember { mutableStateOf<SavedTranscriptSession?>(null) }
    var loadingSessionId by remember { mutableStateOf<String?>(null) }
    var pendingDeletion by remember { mutableStateOf<SavedTranscriptSessionSummary?>(null) }
    var pendingClearAll by remember { mutableStateOf(false) }
    var isMutating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (saveFailed || error != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = error ?: "The last session ended, but its transcript could not be saved.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (sessions.isNotEmpty()) {
        OutlinedButton(
            onClick = {
                error = null
                historyOpen = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TranscriptHistoryTags.OPEN),
        ) {
            Text("Saved transcripts (${sessions.size})")
        }
    }

    if (historyOpen) {
        AlertDialog(
            onDismissRequest = {
                if (loadingSessionId == null) historyOpen = false
            },
            title = { Text("Saved transcripts") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "History is stored only on this device. Commands and output may " +
                            "contain sensitive text.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    sessions.forEach { session ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(12.dp),
                            ) {
                                Text(session.displayName)
                                Text(
                                    "${session.username}@${session.hostname}:${session.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "${formatTranscriptTimestamp(session.endedAtMillis)} · " +
                                        "${session.turnCount} " +
                                        if (session.turnCount == 1) "turn" else "turns",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = {
                                            if (loadingSessionId != null) return@TextButton
                                            loadingSessionId = session.id
                                            error = null
                                            coroutineScope.launch {
                                                try {
                                                    selectedSession = onLoad(session.id)
                                                    historyOpen = false
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (failure: Exception) {
                                                    error = failure.message
                                                        ?: "The transcript could not be opened."
                                                } finally {
                                                    loadingSessionId = null
                                                }
                                            }
                                        },
                                        enabled = loadingSessionId == null,
                                        modifier = Modifier.testTag(
                                            TranscriptHistoryTags.SESSION_PREFIX + session.id,
                                        ),
                                    ) {
                                        Text(
                                            if (loadingSessionId == session.id) {
                                                "Opening…"
                                            } else {
                                                "View"
                                            },
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            pendingDeletion = session
                                            historyOpen = false
                                            error = null
                                        },
                                        enabled = loadingSessionId == null,
                                        modifier = Modifier.testTag(
                                            TranscriptHistoryTags.DELETE_PREFIX + session.id,
                                        ),
                                    ) {
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClearAll = true
                        historyOpen = false
                        error = null
                    },
                    enabled = loadingSessionId == null,
                    modifier = Modifier.testTag(TranscriptHistoryTags.CLEAR_ALL),
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { historyOpen = false },
                    enabled = loadingSessionId == null,
                ) {
                    Text("Close")
                }
            },
        )
    }

    selectedSession?.let { session ->
        AlertDialog(
            onDismissRequest = { selectedSession = null },
            title = { Text(session.summary.displayName) },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = 560.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${session.summary.username}@${session.summary.hostname}:" +
                                    session.summary.port,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Ended " +
                                    formatTranscriptTimestamp(session.summary.endedAtMillis) +
                                    ". Saved output is plain text; terminal styling and " +
                                    "links are inert.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (session.summary.turnsTruncated) item {
                        Text(
                            "Only the newest retained turns are shown.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(
                        items = session.turns,
                        key = { it.turn.id.value },
                    ) { savedTurn ->
                        val turn = savedTurn.turn
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(12.dp),
                            ) {
                                SelectionContainer {
                                    Text(turn.command, fontFamily = FontFamily.Monospace)
                                }
                                if (savedTurn.commandTruncated) {
                                    Text(
                                        "Command truncated in saved history",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    savedTurnStatus(turn.status, turn.exitStatus),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (turn.output.plainText.isNotEmpty()) {
                                    SelectionContainer {
                                        Text(
                                            turn.output.plainText,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.testTag(
                                                TranscriptHistoryTags.OUTPUT_PREFIX + turn.id.value,
                                            ),
                                        )
                                    }
                                }
                                if (turn.output.truncated) {
                                    Text(
                                        "Earlier output omitted from saved history",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (turn.output.approximate) {
                                    Text(
                                        "Transcript rendering was approximate",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedSession = null
                        historyOpen = true
                    },
                ) {
                    Text("Back")
                }
            },
        )
    }

    pendingDeletion?.let { session ->
        AlertDialog(
            onDismissRequest = {
                if (!isMutating) pendingDeletion = null
            },
            title = { Text("Delete saved transcript?") },
            text = {
                Text(
                    "This removes the local history for ${session.displayName} at " +
                        "${session.hostname}:${session.port}. SQLite deletion is logical; " +
                        "Threadline does not claim forensic erasure of storage pages.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isMutating) return@Button
                        isMutating = true
                        coroutineScope.launch {
                            try {
                                onDelete(session.id)
                                pendingDeletion = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message
                                    ?: "The transcript could not be deleted."
                                pendingDeletion = null
                            } finally {
                                isMutating = false
                            }
                        }
                    },
                    enabled = !isMutating,
                    modifier = Modifier.testTag(TranscriptHistoryTags.CONFIRM_DELETE),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeletion = null },
                    enabled = !isMutating,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (pendingClearAll) {
        AlertDialog(
            onDismissRequest = {
                if (!isMutating) pendingClearAll = false
            },
            title = { Text("Clear all transcript history?") },
            text = {
                Text(
                    "This deletes every saved command and output record. Host profiles, " +
                        "trusted servers, and saved private keys are not changed. SQLite " +
                        "deletion is logical, not guaranteed forensic erasure.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isMutating) return@Button
                        isMutating = true
                        coroutineScope.launch {
                            try {
                                onClearAll()
                                pendingClearAll = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message
                                    ?: "Transcript history could not be cleared."
                                pendingClearAll = false
                            } finally {
                                isMutating = false
                            }
                        }
                    },
                    enabled = !isMutating,
                    modifier = Modifier.testTag(TranscriptHistoryTags.CONFIRM_CLEAR_ALL),
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingClearAll = false },
                    enabled = !isMutating,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun savedTurnStatus(status: CommandStatus, exitStatus: Int?): String = buildString {
    append(status.name.lowercase().replaceFirstChar(Char::uppercase))
    exitStatus?.let {
        append(" · exit ")
        append(it)
    }
}

private fun formatTranscriptTimestamp(timestampMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(
        Date(timestampMillis),
    )
