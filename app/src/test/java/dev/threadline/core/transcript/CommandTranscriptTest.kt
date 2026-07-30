package dev.threadline.core.transcript

import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.ShellLifecycleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTranscriptTest {
    private val commandId = CommandId("command-42")

    @Test
    fun `ordered lifecycle attributes only command output to one completed turn`() {
        var time = 100L
        val transcript = CommandTranscript(clockMillis = { time })

        transcript.commandSubmitted(commandId, "printf hello", "/srv/app")
        assertFalse(transcript.consumeOutput("prompt before".encodeToByteArray()))
        time = 110L
        transcript.lifecycle(ShellLifecycleEvent.CommandStarted(commandId))
        assertFalse(transcript.consumeOutput("wrapper echo".encodeToByteArray()))
        transcript.lifecycle(ShellLifecycleEvent.CommandOutputStarted(commandId))
        assertTrue(transcript.consumeOutput("hel".encodeToByteArray()))
        assertTrue(transcript.consumeOutput("lo".encodeToByteArray()))
        transcript.publishOutput()

        val running = transcript.state.value.turns.single()
        assertEquals(CommandStatus.RUNNING, running.status)
        assertEquals("hello", running.output.plainText)
        assertEquals(commandId, transcript.state.value.activeCommandId)

        time = 145L
        transcript.lifecycle(
            ShellLifecycleEvent.CommandEnded(commandId, 0, "/srv/app"),
        )
        assertFalse(transcript.consumeOutput("prompt after".encodeToByteArray()))

        assertEquals(
            CommandTurn(
                id = commandId,
                command = "printf hello",
                directoryAtStart = "/srv/app",
                submittedAtMillis = 100L,
                startedAtMillis = 110L,
                completedAtMillis = 145L,
                status = CommandStatus.SUCCEEDED,
                exitStatus = 0,
                currentDirectory = "/srv/app",
                output = CommandOutput(
                    plainText = "hello",
                    byteCount = 5,
                ),
            ),
            transcript.state.value.turns.single(),
        )
        assertNull(transcript.state.value.activeCommandId)
    }

    @Test
    fun `stop request distinguishes interrupted completion`() {
        val transcript = CommandTranscript(clockMillis = { 10L })
        transcript.commandSubmitted(commandId, "sleep 10", "/tmp")
        transcript.lifecycle(ShellLifecycleEvent.CommandStarted(commandId))
        transcript.lifecycle(ShellLifecycleEvent.CommandOutputStarted(commandId))

        assertTrue(transcript.stopRequested())
        assertEquals(CommandStatus.STOPPING, transcript.state.value.turns.single().status)

        transcript.lifecycle(ShellLifecycleEvent.CommandEnded(commandId, 130, "/tmp"))

        assertEquals(CommandStatus.INTERRUPTED, transcript.state.value.turns.single().status)
        assertEquals(130, transcript.state.value.turns.single().exitStatus)
    }

    @Test
    fun `history retains only the configured number of completed turns`() {
        val transcript = CommandTranscript(
            maximumTurns = 2,
            clockMillis = { 10L },
        )

        repeat(3) { index ->
            val id = CommandId("command-$index")
            transcript.commandSubmitted(id, "printf $index", "/tmp")
            transcript.lifecycle(ShellLifecycleEvent.CommandStarted(id))
            transcript.lifecycle(ShellLifecycleEvent.CommandOutputStarted(id))
            transcript.lifecycle(ShellLifecycleEvent.CommandEnded(id, 0, "/tmp"))
        }

        assertEquals(
            listOf(CommandId("command-1"), CommandId("command-2")),
            transcript.state.value.turns.map { it.id },
        )
    }

    @Test
    fun `send rejection removes the optimistic turn`() {
        val transcript = CommandTranscript(clockMillis = { 10L })
        transcript.commandSubmitted(commandId, "printf retry", "/tmp")

        transcript.commandSendRejected(commandId)

        assertEquals(CommandTranscriptState(), transcript.state.value)
    }

    @Test
    fun `protocol failure overrides a prematurely completed turn with unknown status`() {
        val transcript = CommandTranscript(clockMillis = { 10L })
        transcript.commandSubmitted(commandId, "printf broken", "/tmp")

        transcript.lifecycle(ShellLifecycleEvent.CommandEnded(commandId, 0, "/tmp"))
        transcript.structuredShellFailed(commandId)

        assertEquals(CommandStatus.UNKNOWN, transcript.state.value.turns.single().status)
        assertNull(transcript.state.value.activeCommandId)
    }
}
