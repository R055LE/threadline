package dev.threadline.core.session

import dev.threadline.core.model.SessionState
import dev.threadline.core.shell.StructuredShellState

data class SessionSnapshot(
    val connection: SessionState,
    val structuredShell: StructuredShellState,
)
