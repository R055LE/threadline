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
            while (session.sent.size < expected.length) delay(10)
        }
        assertEquals(expected, session.sent.joinToString("") { it.decodeToString() })

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
