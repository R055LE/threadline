package dev.threadline.core.transcript

import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.ShellLifecycleEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class CommandTranscript(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val maximumTurns: Int = DEFAULT_MAXIMUM_TURNS,
    private val collectorFactory: () -> TranscriptCollector = { TranscriptCollector() },
) {
    private val mutableState = MutableStateFlow(CommandTranscriptState())
    val state: StateFlow<CommandTranscriptState> = mutableState.asStateFlow()
    private val turns = mutableListOf<MutableTurn>()
    private var active: MutableTurn? = null

    init {
        require(maximumTurns > 0)
    }

    @Synchronized
    fun commandSubmitted(
        id: CommandId,
        command: String,
        directoryAtStart: String?,
    ) {
        check(active == null) { "Only one transcript command may be active" }
        while (turns.size >= maximumTurns) {
            turns.removeAt(0)
        }
        MutableTurn(
            id = id,
            command = command,
            directoryAtStart = directoryAtStart,
            submittedAtMillis = clockMillis(),
            collector = collectorFactory(),
        ).also {
            turns += it
            active = it
        }
        publishLocked()
    }

    @Synchronized
    fun commandSendRejected(id: CommandId) {
        val turn = active?.takeIf { it.id == id } ?: return
        turns.remove(turn)
        active = null
        publishLocked()
    }

    @Synchronized
    fun lifecycle(event: ShellLifecycleEvent) {
        val turn = active?.takeIf { it.id == event.commandId } ?: return
        when (event) {
            is ShellLifecycleEvent.CommandStarted -> {
                if (turn.startedAtMillis == null) {
                    turn.startedAtMillis = clockMillis()
                    turn.status = CommandStatus.RUNNING
                }
            }

            is ShellLifecycleEvent.CommandOutputStarted -> {
                turn.collectingOutput = true
                if (turn.startedAtMillis == null) {
                    turn.startedAtMillis = clockMillis()
                }
                turn.status = CommandStatus.RUNNING
            }

            is ShellLifecycleEvent.CommandEnded -> {
                turn.collector.finish()
                turn.publishedOutput = turn.collector.snapshot()
                turn.collectingOutput = false
                turn.completedAtMillis = clockMillis()
                turn.exitStatus = event.exitStatus
                turn.currentDirectory = event.currentDirectory
                turn.status = when {
                    turn.stopRequested -> CommandStatus.INTERRUPTED
                    event.exitStatus == 0 -> CommandStatus.SUCCEEDED
                    else -> CommandStatus.FAILED
                }
                active = null
            }
        }
        publishLocked()
    }

    @Synchronized
    fun consumeOutput(bytes: ByteArray): Boolean {
        val turn = active?.takeIf { it.collectingOutput } ?: return false
        turn.collector.consume(bytes)
        return true
    }

    @Synchronized
    fun publishOutput() {
        active?.let { it.publishedOutput = it.collector.snapshot() }
        publishLocked()
    }

    @Synchronized
    fun stopRequested(): Boolean {
        val turn = active ?: return false
        if (turn.stopRequested) return false
        turn.stopRequested = true
        turn.stopRequestedAtMillis = clockMillis()
        turn.status = CommandStatus.STOPPING
        publishLocked()
        return true
    }

    @Synchronized
    fun structuredShellFailed(commandId: CommandId? = active?.id) {
        val turn = active ?: turns.lastOrNull { it.id == commandId } ?: return
        turn.collector.finish()
        turn.publishedOutput = turn.collector.snapshot()
        turn.collectingOutput = false
        turn.completedAtMillis = clockMillis()
        turn.status = CommandStatus.UNKNOWN
        if (active === turn) active = null
        publishLocked()
    }

    @Synchronized
    fun sessionDisconnected() {
        val turn = active ?: return
        turn.collector.finish()
        turn.publishedOutput = turn.collector.snapshot()
        turn.collectingOutput = false
        turn.completedAtMillis = clockMillis()
        turn.status = CommandStatus.DISCONNECTED
        active = null
        publishLocked()
    }

    @Synchronized
    fun reset() {
        turns.clear()
        active = null
        publishLocked()
    }

    private fun publishLocked() {
        mutableState.value = CommandTranscriptState(
            turns = turns.map(MutableTurn::snapshot),
            activeCommandId = active?.id,
        )
    }

    private class MutableTurn(
        val id: CommandId,
        val command: String,
        val directoryAtStart: String?,
        val submittedAtMillis: Long,
        val collector: TranscriptCollector,
        var startedAtMillis: Long? = null,
        var completedAtMillis: Long? = null,
        var status: CommandStatus = CommandStatus.SUBMITTED,
        var exitStatus: Int? = null,
        var currentDirectory: String? = null,
        var collectingOutput: Boolean = false,
        var stopRequested: Boolean = false,
        var stopRequestedAtMillis: Long? = null,
        var publishedOutput: CommandOutput = CommandOutput(),
    ) {
        fun snapshot() = CommandTurn(
            id = id,
            command = command,
            directoryAtStart = directoryAtStart,
            submittedAtMillis = submittedAtMillis,
            startedAtMillis = startedAtMillis,
            completedAtMillis = completedAtMillis,
            status = status,
            exitStatus = exitStatus,
            currentDirectory = currentDirectory,
            output = publishedOutput,
            stopRequestedAtMillis = stopRequestedAtMillis,
        )
    }

    companion object {
        const val DEFAULT_MAXIMUM_TURNS = 100
    }
}
