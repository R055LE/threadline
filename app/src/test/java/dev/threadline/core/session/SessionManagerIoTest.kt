package dev.threadline.core.session

import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.core.model.TerminalSize
import dev.threadline.core.security.KnownHostRecord
import dev.threadline.core.security.KnownHostStore
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.CommandSubmissionRejection
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.CompletedCommand
import dev.threadline.core.shell.SessionNonce
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.shell.StructuredShellUnavailableReason
import dev.threadline.core.ssh.LiveSshSession
import dev.threadline.core.ssh.ServerHostKeyVerifier
import dev.threadline.core.ssh.SshClientAdapter
import dev.threadline.core.terminal.TerminalSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SessionManagerIoTest {
    @Test
    fun `rapid input bytes reach the SSH session in order`() = runBlocking {
        val session = RecordingSession()
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = FakeTerminal,
        )
        val request = ConnectionRequest(
            profile = HostProfile(
                displayName = "Fixture",
                endpoint = HostEndpoint("fixture.test", 2222),
                username = "threadline",
            ),
            credential = SessionCredential.Password.from("test".toCharArray()),
        )

        manager.prepareConnection(request)
        manager.connectPrepared()
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Connected>().first()
        }

        val expected = "abcdefghijklmnopqrstuvwxyz"
        expected.forEach { manager.send(byteArrayOf(it.code.toByte())) }

        withTimeout(2_000) {
            while (!session.sent.joinToString("") { it.decodeToString() }.endsWith(expected)) {
                delay(10)
            }
        }
        assertTrue(session.sent.joinToString("") { it.decodeToString() }.endsWith(expected))

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }
        Unit
    }

    @Test
    fun `terminal output is delivered in order with backpressure`() = runBlocking {
        val session = RecordingSession()
        val terminal = BlockingTerminal()
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = terminal,
        )

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Connected>().first()
        }

        session.output.send("first".encodeToByteArray())
        withTimeout(2_000) { terminal.firstStarted.await() }
        session.output.send("second".encodeToByteArray())
        delay(50)
        assertEquals(listOf("first"), terminal.received)

        terminal.releaseFirst.complete(Unit)
        withTimeout(2_000) {
            while (terminal.received.size < 2) delay(10)
        }
        assertEquals(listOf("first", "second"), terminal.received)

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }
        Unit
    }

    @Test
    fun `disconnect cancels terminal output delivery`() = runBlocking {
        val session = RecordingSession()
        val terminal = CancellableTerminal()
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = terminal,
        )

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Connected>().first()
        }

        session.output.send("pending".encodeToByteArray())
        withTimeout(2_000) { terminal.started.await() }
        manager.disconnect()

        withTimeout(2_000) {
            terminal.cancelled.await()
            session.disconnected.await()
            manager.state.first { it is SessionState.Disconnected }
        }
        Unit
    }

    @Test
    fun `terminal renderer failure becomes a typed session error`() = runBlocking {
        val session = RecordingSession()
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = FailingTerminal,
        )

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Connected>().first()
        }

        session.output.send("trigger".encodeToByteArray())
        val failed = withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Failed>().first()
        }
        assertEquals(SessionError.TerminalRendererFailed, failed.error)

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }
        assertTrue(session.disconnected.isCompleted)
    }

    @Test
    fun `structured lifecycle preserves exact raw output and enforces one active command`() =
        runBlocking {
            val session = RecordingSession()
            val terminal = RecordingTerminal()
            val nonce = SessionNonce("0123456789abcdef0123456789abcdef")
            val commandIds = ArrayDeque(
                listOf(
                    CommandId("bootstrap-probe"),
                    CommandId("first-command"),
                    CommandId("second-command"),
                ),
            )
            val manager = SessionManager(
                adapter = ImmediateAdapter(session),
                knownHostStore = EmptyKnownHostStore,
                terminal = terminal,
                sessionNonceFactory = { nonce },
                commandIdFactory = { commandIds.removeFirst() },
                bootstrapTimeoutMillis = 2_000,
            )

            manager.prepareConnection(fixtureRequest())
            manager.connectPrepared()
            withTimeout(2_000) {
                manager.state.filterIsInstance<SessionState.Connected>().first()
            }
            withTimeout(2_000) {
                while (session.sent.isEmpty()) delay(10)
            }
            assertTrue(
                session.sent.first().decodeToString().contains(
                    "__threadline_run_${nonce.value}",
                ),
            )

            val bootstrapRaw = lifecycleBytes(
                nonce = nonce,
                commandId = CommandId("bootstrap-probe"),
                exitStatus = 0,
                currentDirectory = "/home/threadline",
            )
            session.output.send(bootstrapRaw.copyOfRange(0, 17))
            session.output.send(bootstrapRaw.copyOfRange(17, bootstrapRaw.size))
            withTimeout(2_000) {
                manager.structuredState.filterIsInstance<StructuredShellState.Ready>().first()
            }

            val accepted = manager.submitCommand("cd /tmp && false")
            assertEquals(
                CommandSubmissionResult.Accepted(CommandId("first-command")),
                accepted,
            )
            assertEquals(
                CommandSubmissionResult.Rejected(
                    CommandSubmissionRejection.COMMAND_ALREADY_RUNNING,
                ),
                manager.submitCommand("printf duplicate"),
            )
            withTimeout(2_000) {
                while (session.sent.size < 2) delay(10)
            }

            val commandRaw = lifecycleBytes(
                nonce = nonce,
                commandId = CommandId("first-command"),
                exitStatus = 1,
                currentDirectory = "/tmp",
                output = "visible output\r\n",
            )
            session.output.send(commandRaw)
            val ready = withTimeout(2_000) {
                manager.structuredState.filterIsInstance<StructuredShellState.Ready>()
                    .first { it.lastCommand != null }
            }

            assertEquals(
                CompletedCommand(
                    id = CommandId("first-command"),
                    command = "cd /tmp && false",
                    directoryAtStart = "/home/threadline",
                    currentDirectory = "/tmp",
                    exitStatus = 1,
                ),
                ready.lastCommand,
            )
            assertArrayEquals(
                bootstrapRaw + commandRaw,
                terminal.received.flattenBytes(),
            )
            assertEquals(
                CommandSubmissionResult.Accepted(CommandId("second-command")),
                manager.submitCommand("printf next"),
            )

            manager.disconnect()
            withTimeout(2_000) {
                manager.state.first { it is SessionState.Disconnected }
            }
            assertEquals(StructuredShellState.Inactive, manager.structuredState.value)
        }

    @Test
    fun `bootstrap timeout downgrades to raw mode without failing connection`() = runBlocking {
        val session = RecordingSession()
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = FakeTerminal,
            sessionNonceFactory = {
                SessionNonce("0123456789abcdef0123456789abcdef")
            },
            commandIdFactory = { CommandId("bootstrap-probe") },
            bootstrapTimeoutMillis = 50,
        )

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        val unavailable = withTimeout(2_000) {
            manager.structuredState.filterIsInstance<StructuredShellState.Unavailable>().first()
        }

        assertEquals(
            StructuredShellUnavailableReason.BOOTSTRAP_TIMED_OUT,
            unavailable.reason,
        )
        assertTrue(manager.state.value is SessionState.Connected)

        manager.send("raw-still-works".encodeToByteArray())
        withTimeout(2_000) {
            while (
                !session.sent.joinToString("") { it.decodeToString() }
                    .endsWith("raw-still-works")
            ) {
                delay(10)
            }
        }

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }
        Unit
    }

    @Test
    fun `structured setup failure leaves the connected raw shell available`() = runBlocking {
        val session = RecordingSession()
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = FakeTerminal,
            sessionNonceFactory = { error("nonce provider unavailable") },
        )

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        val unavailable = withTimeout(2_000) {
            manager.structuredState.filterIsInstance<StructuredShellState.Unavailable>().first()
        }

        assertEquals(
            StructuredShellUnavailableReason.BOOTSTRAP_FAILED,
            unavailable.reason,
        )
        assertTrue(manager.state.value is SessionState.Connected)

        manager.send("raw-after-setup-failure".encodeToByteArray())
        withTimeout(2_000) {
            while (
                !session.sent.joinToString("") { it.decodeToString() }
                    .endsWith("raw-after-setup-failure")
            ) {
                delay(10)
            }
        }

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }
        Unit
    }
}

private fun fixtureRequest() = ConnectionRequest(
    profile = HostProfile(
        displayName = "Fixture",
        endpoint = HostEndpoint("fixture.test", 2222),
        username = "threadline",
    ),
    credential = SessionCredential.Password.from("test".toCharArray()),
)

private class ImmediateAdapter(
    private val session: LiveSshSession,
) : SshClientAdapter {
    override suspend fun connect(
        request: ConnectionRequest,
        verifier: ServerHostKeyVerifier,
        initialSize: TerminalSize,
        onStage: (dev.threadline.core.model.ConnectionStage) -> Unit,
    ): LiveSshSession = session
}

private class RecordingSession : LiveSshSession {
    override val output = Channel<ByteArray>(Channel.BUFFERED)
    override val disconnects: Flow<Unit> = MutableSharedFlow()
    val sent = CopyOnWriteArrayList<ByteArray>()
    val disconnected = CompletableDeferred<Unit>()

    override suspend fun send(bytes: ByteArray) {
        delay(1)
        sent += bytes
    }

    override suspend fun resize(size: TerminalSize): Boolean = true

    override suspend fun disconnect() {
        disconnected.complete(Unit)
    }
}

private object EmptyKnownHostStore : KnownHostStore {
    override fun find(endpoint: HostEndpoint): KnownHostRecord? = null

    override fun save(record: KnownHostRecord) = Unit
}

private object FakeTerminal : TerminalSink {
    override val size = TerminalSize(rows = 24, columns = 80)

    override fun clear() = Unit

    override suspend fun receive(bytes: ByteArray) = Unit
}

private class BlockingTerminal : TerminalSink {
    override val size = TerminalSize(rows = 24, columns = 80)
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    val received = CopyOnWriteArrayList<String>()

    override fun clear() = Unit

    override suspend fun receive(bytes: ByteArray) {
        received += bytes.decodeToString()
        if (received.size == 1) {
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
    }
}

private class CancellableTerminal : TerminalSink {
    override val size = TerminalSize(rows = 24, columns = 80)
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    private val never = CompletableDeferred<Unit>()

    override fun clear() = Unit

    override suspend fun receive(bytes: ByteArray) {
        started.complete(Unit)
        try {
            never.await()
        } finally {
            cancelled.complete(Unit)
        }
    }
}

private object FailingTerminal : TerminalSink {
    override val size = TerminalSize(rows = 24, columns = 80)

    override fun clear() = Unit

    override suspend fun receive(bytes: ByteArray) {
        error("renderer failed")
    }
}

private class RecordingTerminal : TerminalSink {
    override val size = TerminalSize(rows = 24, columns = 80)
    val received = CopyOnWriteArrayList<ByteArray>()

    override fun clear() {
        received.clear()
    }

    override suspend fun receive(bytes: ByteArray) {
        received += bytes.copyOf()
    }
}

private fun lifecycleBytes(
    nonce: SessionNonce,
    commandId: CommandId,
    exitStatus: Int,
    currentDirectory: String,
    output: String = "",
): ByteArray {
    fun marker(event: String, vararg fields: String): ByteArray =
        (
            "\u001b]777;threadline;${nonce.value};$event;${commandId.value}" +
                fields.joinToString(separator = "", prefix = "") { ";$it" } +
                "\u0007"
        ).encodeToByteArray()

    return marker("start") +
        marker("output") +
        output.encodeToByteArray() +
        marker("end", exitStatus.toString(), currentDirectory)
}

private fun Iterable<ByteArray>.flattenBytes(): ByteArray =
    fold(ByteArray(0)) { accumulated, bytes -> accumulated + bytes }
