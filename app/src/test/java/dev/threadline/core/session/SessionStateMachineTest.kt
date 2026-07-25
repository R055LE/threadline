package dev.threadline.core.session

import dev.threadline.core.model.ConnectionStage
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostKeyPrompt
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.core.model.TerminalSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    private val prompt = HostKeyPrompt(
        endpoint = HostEndpoint("fixture.test", 2222),
        algorithm = "ssh-ed25519",
        fingerprint = "SHA256:test",
    )

    @Test
    fun `happy path moves through host verification to connected`() {
        val machine = SessionStateMachine()

        machine.apply(SessionEvent.ConnectRequested("Fixture"))
        assertEquals(
            SessionState.Connecting("Fixture", ConnectionStage.CONNECTING),
            machine.state.value,
        )

        machine.apply(SessionEvent.HostKeyRequired(prompt))
        assertEquals(SessionState.AwaitingHostKey("Fixture", prompt), machine.state.value)

        machine.apply(SessionEvent.HostKeyAccepted)
        machine.apply(SessionEvent.StageChanged(ConnectionStage.AUTHENTICATING))
        assertEquals(
            SessionState.Connecting("Fixture", ConnectionStage.AUTHENTICATING),
            machine.state.value,
        )

        machine.apply(SessionEvent.StageChanged(ConnectionStage.STARTING_SHELL))
        machine.apply(SessionEvent.ShellReady(TerminalSize(24, 80)))
        assertEquals(
            SessionState.Connected("Fixture", TerminalSize(24, 80)),
            machine.state.value,
        )
    }

    @Test
    fun `disconnect is explicit and terminal`() {
        val machine = SessionStateMachine(
            SessionState.Connected("Fixture", TerminalSize(24, 80)),
        )

        machine.apply(SessionEvent.DisconnectRequested)
        assertEquals(SessionState.Disconnecting("Fixture"), machine.state.value)

        machine.apply(SessionEvent.Disconnected)
        assertEquals(SessionState.Disconnected, machine.state.value)
    }

    @Test
    fun `invalid shell ready event cannot skip connection`() {
        val machine = SessionStateMachine()

        machine.apply(SessionEvent.ShellReady(TerminalSize(24, 80)))

        assertEquals(SessionState.Disconnected, machine.state.value)
    }

    @Test
    fun `failure carries a typed domain error`() {
        val machine = SessionStateMachine()

        machine.apply(SessionEvent.Failed(SessionError.AuthenticationRejected))

        assertTrue(machine.state.value is SessionState.Failed)
        assertEquals(
            SessionError.AuthenticationRejected,
            (machine.state.value as SessionState.Failed).error,
        )
    }
}
