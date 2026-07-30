package dev.threadline.core.session

import dev.threadline.core.model.SessionState
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.transcript.CommandTranscriptState

data class SessionSnapshot(
    val connection: SessionState,
    val structuredShell: StructuredShellState,
    val transcript: CommandTranscriptState,
)
