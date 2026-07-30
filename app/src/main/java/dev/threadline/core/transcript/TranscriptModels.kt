package dev.threadline.core.transcript

import dev.threadline.core.shell.CommandId

enum class CommandStatus {
    SUBMITTED,
    RUNNING,
    STOPPING,
    SUCCEEDED,
    FAILED,
    INTERRUPTED,
    DISCONNECTED,
    UNKNOWN,
}

sealed interface AnsiColor {
    data class Indexed(val index: Int) : AnsiColor {
        init {
            require(index in 0..255)
        }
    }

    data class Rgb(
        val red: Int,
        val green: Int,
        val blue: Int,
    ) : AnsiColor {
        init {
            require(red in 0..255)
            require(green in 0..255)
            require(blue in 0..255)
        }
    }
}

data class TranscriptStyle(
    val foreground: AnsiColor? = null,
    val background: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val strikethrough: Boolean = false,
)

data class StyledRun(
    val start: Int,
    val endExclusive: Int,
    val style: TranscriptStyle,
) {
    init {
        require(start >= 0)
        require(endExclusive > start)
    }
}

data class CommandOutput(
    val plainText: String = "",
    val styledRuns: List<StyledRun> = emptyList(),
    val truncated: Boolean = false,
    val approximate: Boolean = false,
    val byteCount: Long = 0,
)

data class CommandTurn(
    val id: CommandId,
    val command: String,
    val directoryAtStart: String?,
    val submittedAtMillis: Long,
    val startedAtMillis: Long?,
    val completedAtMillis: Long?,
    val status: CommandStatus,
    val exitStatus: Int?,
    val currentDirectory: String?,
    val output: CommandOutput,
    val stopRequestedAtMillis: Long? = null,
)

data class CommandTranscriptState(
    val turns: List<CommandTurn> = emptyList(),
    val activeCommandId: CommandId? = null,
)
