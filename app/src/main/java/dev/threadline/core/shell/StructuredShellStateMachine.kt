package dev.threadline.core.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LifecyclePhase {
    AWAITING_START,
    STARTED,
    OUTPUT_STARTED,
}

data class ActiveCommand(
    val id: CommandId,
    val command: String,
    val directoryAtStart: String?,
    val phase: LifecyclePhase,
)

data class CompletedCommand(
    val id: CommandId,
    val command: String,
    val directoryAtStart: String?,
    val currentDirectory: String,
    val exitStatus: Int,
)

enum class StructuredShellUnavailableReason {
    BOOTSTRAP_FAILED,
    BOOTSTRAP_TIMED_OUT,
    PROTOCOL_MISMATCH,
    PARSER_FAILED,
}

sealed interface StructuredShellState {
    data object Inactive : StructuredShellState

    data class Bootstrapping(
        val probeCommandId: CommandId,
        val phase: LifecyclePhase = LifecyclePhase.AWAITING_START,
    ) : StructuredShellState

    data class Ready(
        val currentDirectory: String,
        val lastCommand: CompletedCommand? = null,
    ) : StructuredShellState

    data class Running(
        val activeCommand: ActiveCommand,
        val lastCommand: CompletedCommand?,
    ) : StructuredShellState

    data class Unavailable(
        val reason: StructuredShellUnavailableReason,
    ) : StructuredShellState
}

enum class CommandSubmissionRejection {
    NOT_READY,
    COMMAND_ALREADY_RUNNING,
    INVALID_COMMAND,
    INPUT_BACKPRESSURE,
}

sealed interface CommandSubmissionResult {
    data class Accepted(
        val commandId: CommandId,
    ) : CommandSubmissionResult

    data class Rejected(
        val reason: CommandSubmissionRejection,
    ) : CommandSubmissionResult
}

internal sealed interface StructuredShellEvent {
    data class BootstrapRequested(
        val probeCommandId: CommandId,
    ) : StructuredShellEvent

    data class BootstrapTimedOut(
        val probeCommandId: CommandId,
    ) : StructuredShellEvent

    data class CommandSubmitted(
        val commandId: CommandId,
        val command: String,
    ) : StructuredShellEvent

    data class CommandSendRejected(
        val commandId: CommandId,
    ) : StructuredShellEvent

    data class Lifecycle(
        val event: ShellLifecycleEvent,
    ) : StructuredShellEvent

    data class IntegrationFailed(
        val reason: StructuredShellUnavailableReason,
    ) : StructuredShellEvent

    data object Reset : StructuredShellEvent
}

internal object StructuredShellReducer {
    fun reduce(
        state: StructuredShellState,
        event: StructuredShellEvent,
    ): StructuredShellState = when (event) {
        is StructuredShellEvent.BootstrapRequested ->
            when (state) {
                StructuredShellState.Inactive,
                is StructuredShellState.Unavailable,
                -> StructuredShellState.Bootstrapping(event.probeCommandId)

                else -> state
            }

        is StructuredShellEvent.BootstrapTimedOut ->
            if (
                state is StructuredShellState.Bootstrapping &&
                state.probeCommandId == event.probeCommandId
            ) {
                StructuredShellState.Unavailable(
                    StructuredShellUnavailableReason.BOOTSTRAP_TIMED_OUT,
                )
            } else {
                state
            }

        is StructuredShellEvent.CommandSubmitted ->
            if (state is StructuredShellState.Ready) {
                StructuredShellState.Running(
                    activeCommand = ActiveCommand(
                        id = event.commandId,
                        command = event.command,
                        directoryAtStart = state.currentDirectory,
                        phase = LifecyclePhase.AWAITING_START,
                    ),
                    lastCommand = state.lastCommand,
                )
            } else {
                state
            }

        is StructuredShellEvent.CommandSendRejected ->
            if (
                state is StructuredShellState.Running &&
                state.activeCommand.id == event.commandId &&
                state.activeCommand.phase == LifecyclePhase.AWAITING_START
            ) {
                StructuredShellState.Ready(
                    currentDirectory = requireNotNull(state.activeCommand.directoryAtStart),
                    lastCommand = state.lastCommand,
                )
            } else {
                state
            }

        is StructuredShellEvent.Lifecycle -> reduceLifecycle(state, event.event)

        is StructuredShellEvent.IntegrationFailed ->
            StructuredShellState.Unavailable(event.reason)

        StructuredShellEvent.Reset -> StructuredShellState.Inactive
    }

    private fun reduceLifecycle(
        state: StructuredShellState,
        event: ShellLifecycleEvent,
    ): StructuredShellState = when (state) {
        is StructuredShellState.Bootstrapping ->
            reduceBootstrapLifecycle(state, event)

        is StructuredShellState.Running ->
            reduceCommandLifecycle(state, event)

        else -> state
    }

    private fun reduceBootstrapLifecycle(
        state: StructuredShellState.Bootstrapping,
        event: ShellLifecycleEvent,
    ): StructuredShellState {
        if (event.commandId != state.probeCommandId) return state
        return when (event) {
            is ShellLifecycleEvent.CommandStarted ->
                state.advanceFrom(
                    expected = LifecyclePhase.AWAITING_START,
                    next = LifecyclePhase.STARTED,
                )

            is ShellLifecycleEvent.CommandOutputStarted ->
                state.advanceFrom(
                    expected = LifecyclePhase.STARTED,
                    next = LifecyclePhase.OUTPUT_STARTED,
                )

            is ShellLifecycleEvent.CommandEnded ->
                if (state.phase != LifecyclePhase.OUTPUT_STARTED) {
                    protocolMismatch()
                } else if (event.exitStatus == 0) {
                    StructuredShellState.Ready(event.currentDirectory)
                } else {
                    StructuredShellState.Unavailable(
                        StructuredShellUnavailableReason.BOOTSTRAP_FAILED,
                    )
                }
        }
    }

    private fun reduceCommandLifecycle(
        state: StructuredShellState.Running,
        event: ShellLifecycleEvent,
    ): StructuredShellState {
        if (event.commandId != state.activeCommand.id) return state
        return when (event) {
            is ShellLifecycleEvent.CommandStarted ->
                state.advanceFrom(
                    expected = LifecyclePhase.AWAITING_START,
                    next = LifecyclePhase.STARTED,
                )

            is ShellLifecycleEvent.CommandOutputStarted ->
                state.advanceFrom(
                    expected = LifecyclePhase.STARTED,
                    next = LifecyclePhase.OUTPUT_STARTED,
                )

            is ShellLifecycleEvent.CommandEnded -> {
                if (state.activeCommand.phase != LifecyclePhase.OUTPUT_STARTED) {
                    protocolMismatch()
                } else {
                    val completed = CompletedCommand(
                        id = state.activeCommand.id,
                        command = state.activeCommand.command,
                        directoryAtStart = state.activeCommand.directoryAtStart,
                        currentDirectory = event.currentDirectory,
                        exitStatus = event.exitStatus,
                    )
                    StructuredShellState.Ready(
                        currentDirectory = event.currentDirectory,
                        lastCommand = completed,
                    )
                }
            }
        }
    }

    private fun StructuredShellState.Bootstrapping.advanceFrom(
        expected: LifecyclePhase,
        next: LifecyclePhase,
    ): StructuredShellState =
        if (phase == expected) copy(phase = next) else protocolMismatch()

    private fun StructuredShellState.Running.advanceFrom(
        expected: LifecyclePhase,
        next: LifecyclePhase,
    ): StructuredShellState =
        if (activeCommand.phase == expected) {
            copy(activeCommand = activeCommand.copy(phase = next))
        } else {
            protocolMismatch()
        }

    private fun protocolMismatch(): StructuredShellState =
        StructuredShellState.Unavailable(
            StructuredShellUnavailableReason.PROTOCOL_MISMATCH,
        )
}

internal class StructuredShellStateMachine(
    initialState: StructuredShellState = StructuredShellState.Inactive,
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<StructuredShellState> = mutableState.asStateFlow()

    @Synchronized
    fun apply(event: StructuredShellEvent): StructuredShellState {
        val next = StructuredShellReducer.reduce(mutableState.value, event)
        mutableState.value = next
        return next
    }
}
