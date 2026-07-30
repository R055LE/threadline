package dev.threadline.core.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredShellStateMachineTest {
    private val probeId = CommandId("bootstrap-probe")
    private val commandId = CommandId("command-42")

    @Test
    fun `successful bootstrap reaches ready with current directory`() {
        val machine = StructuredShellStateMachine()

        machine.apply(StructuredShellEvent.BootstrapRequested(probeId))
        machine.apply(
            StructuredShellEvent.Lifecycle(
                ShellLifecycleEvent.CommandStarted(probeId),
            ),
        )
        machine.apply(
            StructuredShellEvent.Lifecycle(
                ShellLifecycleEvent.CommandOutputStarted(probeId),
            ),
        )
        machine.apply(
            StructuredShellEvent.Lifecycle(
                ShellLifecycleEvent.CommandEnded(probeId, 0, "/home/threadline"),
            ),
        )

        assertEquals(
            StructuredShellState.Ready("/home/threadline"),
            machine.state.value,
        )
    }

    @Test
    fun `out-of-order bootstrap marker downgrades structured mode`() {
        val machine = StructuredShellStateMachine()

        machine.apply(StructuredShellEvent.BootstrapRequested(probeId))
        machine.apply(
            StructuredShellEvent.Lifecycle(
                ShellLifecycleEvent.CommandOutputStarted(probeId),
            ),
        )

        assertEquals(
            StructuredShellState.Unavailable(
                StructuredShellUnavailableReason.PROTOCOL_MISMATCH,
            ),
            machine.state.value,
        )
    }

    @Test
    fun `command completion retains text directories and failure status`() {
        val machine = readyMachine()

        machine.apply(
            StructuredShellEvent.CommandSubmitted(
                commandId = commandId,
                command = "cd /tmp && false",
            ),
        )
        machine.apply(
            StructuredShellEvent.Lifecycle(
                ShellLifecycleEvent.CommandStarted(commandId),
            ),
        )
        machine.apply(
            StructuredShellEvent.Lifecycle(
                ShellLifecycleEvent.CommandOutputStarted(commandId),
            ),
        )
        machine.apply(
            StructuredShellEvent.Lifecycle(
                ShellLifecycleEvent.CommandEnded(commandId, 1, "/tmp"),
            ),
        )

        assertEquals(
            StructuredShellState.Ready(
                currentDirectory = "/tmp",
                lastCommand = CompletedCommand(
                    id = commandId,
                    command = "cd /tmp && false",
                    directoryAtStart = "/home/threadline",
                    currentDirectory = "/tmp",
                    exitStatus = 1,
                ),
            ),
            machine.state.value,
        )
    }

    @Test
    fun `unrelated command marker cannot mutate active command`() {
        val machine = readyMachine()
        machine.apply(StructuredShellEvent.CommandSubmitted(commandId, "printf expected"))
        val running = machine.state.value

        machine.apply(
            StructuredShellEvent.Lifecycle(
                ShellLifecycleEvent.CommandStarted(CommandId("unrelated-command")),
            ),
        )

        assertEquals(running, machine.state.value)
    }

    @Test
    fun `rejected send returns awaiting command to ready`() {
        val machine = readyMachine()
        machine.apply(StructuredShellEvent.CommandSubmitted(commandId, "printf retry"))

        machine.apply(StructuredShellEvent.CommandSendRejected(commandId))

        assertEquals(
            StructuredShellState.Ready("/home/threadline"),
            machine.state.value,
        )
    }

    private fun readyMachine(): StructuredShellStateMachine =
        StructuredShellStateMachine(
            StructuredShellState.Ready("/home/threadline"),
        )
}
