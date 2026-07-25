package dev.threadline.core.session

import dev.threadline.core.model.ConnectionStage
import dev.threadline.core.model.HostKeyPrompt
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.core.model.TerminalSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionEvent {
    data class ConnectRequested(val displayName: String) : SessionEvent
    data class StageChanged(val stage: ConnectionStage) : SessionEvent
    data class HostKeyRequired(val prompt: HostKeyPrompt) : SessionEvent
    data object HostKeyAccepted : SessionEvent
    data class ShellReady(val terminalSize: TerminalSize) : SessionEvent
    data object DisconnectRequested : SessionEvent
    data object Disconnected : SessionEvent
    data class Failed(val error: SessionError) : SessionEvent
}

object SessionReducer {
    fun reduce(
        state: SessionState,
        event: SessionEvent,
    ): SessionState = when (event) {
        is SessionEvent.ConnectRequested ->
            if (state is SessionState.Disconnected || state is SessionState.Failed) {
                SessionState.Connecting(event.displayName, ConnectionStage.CONNECTING)
            } else {
                state
            }

        is SessionEvent.StageChanged ->
            when (state) {
                is SessionState.Connecting -> state.copy(stage = event.stage)
                is SessionState.AwaitingHostKey ->
                    SessionState.Connecting(state.displayName, event.stage)
                else -> state
            }

        is SessionEvent.HostKeyRequired ->
            if (state is SessionState.Connecting) {
                SessionState.AwaitingHostKey(state.displayName, event.prompt)
            } else {
                state
            }

        SessionEvent.HostKeyAccepted ->
            if (state is SessionState.AwaitingHostKey) {
                SessionState.Connecting(state.displayName, ConnectionStage.CONNECTING)
            } else {
                state
            }

        is SessionEvent.ShellReady ->
            if (state is SessionState.Connecting) {
                SessionState.Connected(state.displayName, event.terminalSize)
            } else {
                state
            }

        SessionEvent.DisconnectRequested ->
            when (state) {
                SessionState.Disconnected -> state
                is SessionState.Disconnecting -> state
                is SessionState.Connecting -> SessionState.Disconnecting(state.displayName)
                is SessionState.AwaitingHostKey -> SessionState.Disconnecting(state.displayName)
                is SessionState.Connected -> SessionState.Disconnecting(state.displayName)
                is SessionState.Failed -> SessionState.Disconnecting(null)
            }

        SessionEvent.Disconnected -> SessionState.Disconnected
        is SessionEvent.Failed -> SessionState.Failed(event.error)
    }
}

class SessionStateMachine(
    initialState: SessionState = SessionState.Disconnected,
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    @Synchronized
    fun apply(event: SessionEvent): SessionState {
        val next = SessionReducer.reduce(mutableState.value, event)
        mutableState.value = next
        return next
    }
}
