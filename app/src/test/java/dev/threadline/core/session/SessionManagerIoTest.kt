package dev.threadline.core.session

import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.core.model.TerminalSize
import dev.threadline.core.security.HostKeyFingerprint
import dev.threadline.core.security.KnownHostKey
import dev.threadline.core.security.KnownHostRecord
import dev.threadline.core.security.KnownHostStore
import dev.threadline.core.shell.CommandExecutionMode
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
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.TranscriptArchiveSink
import dev.threadline.core.transcript.TranscriptSessionArchive
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
import java.util.concurrent.atomic.AtomicInteger

class SessionManagerIoTest {
    @Test
    fun `unknown host saves and reconnects after deferred decision`() = runBlocking {
        val key = byteArrayOf(1, 2, 3, 4)
        val session = RecordingSession()
        val adapter = HostKeyCheckingAdapter(listOf(key), session)
        val store = MutableKnownHostStore()
        val request = fixtureRequest()
        val manager = SessionManager(adapter, store, FakeTerminal)

        manager.prepareConnection(request)
        manager.connectPrepared()
        val awaiting = withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.AwaitingHostKey>().first()
        }

        assertEquals("fixture.test", awaiting.prompt.endpoint.hostname)
        assertEquals(2222, awaiting.prompt.endpoint.port)
        assertEquals("ssh-ed25519", awaiting.prompt.algorithm)
        assertEquals(HostKeyFingerprint.sha256(key), awaiting.prompt.fingerprint)
        delay(100)
        assertTrue(manager.state.value is SessionState.AwaitingHostKey)
        assertEquals(1, adapter.attempts)

        assertTrue(manager.resolveHostKey(HostKeyDecision.ACCEPT_AND_SAVE))
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Connected>().first()
        }
        withTimeout(2_000) {
            while (!request.credential.isCleared()) delay(10)
        }

        assertEquals(2, adapter.attempts)
        assertTrue(adapter.credentialWasUsable.all { it })
        val trusted = requireNotNull(store.record)
        assertEquals("ssh-ed25519", trusted.key.algorithm)
        assertArrayEquals(key, trusted.key.encoded)

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }
        Unit
    }

    @Test
    fun `rejecting unknown host never stores trust or reconnects`() = runBlocking {
        val session = RecordingSession()
        val adapter = HostKeyCheckingAdapter(listOf(byteArrayOf(1, 2, 3)), session)
        val store = MutableKnownHostStore()
        val manager = SessionManager(adapter, store, FakeTerminal)

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.AwaitingHostKey>().first()
        }
        assertTrue(manager.resolveHostKey(HostKeyDecision.REJECT))
        val failed = withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Failed>().first()
        }

        assertTrue(failed.error is SessionError.HostKeyRejected)
        assertEquals(1, adapter.attempts)
        assertEquals(null, store.record)

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }
        Unit
    }

    @Test
    fun `host key changed between prompt and reconnect remains blocked`() = runBlocking {
        val displayedKey = byteArrayOf(1, 2, 3)
        val replacementKey = byteArrayOf(9, 9, 9)
        val session = RecordingSession()
        val adapter = HostKeyCheckingAdapter(listOf(displayedKey, replacementKey), session)
        val store = MutableKnownHostStore()
        val manager = SessionManager(adapter, store, FakeTerminal)

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.AwaitingHostKey>().first()
        }
        assertTrue(manager.resolveHostKey(HostKeyDecision.ACCEPT_AND_SAVE))
        val failed = withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Failed>().first()
        }

        assertTrue(failed.error is SessionError.HostKeyChanged)
        assertEquals(2, adapter.attempts)
        assertArrayEquals(displayedKey, requireNotNull(store.record).key.encoded)

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }
        Unit
    }

    @Test
    fun `internal input echo mismatch fails open byte for byte`() {
        val filter = InternalInputEchoFilter()
        val input = "abcab\n".encodeToByteArray()
        val observed = "server output\r\nabcax\r\n".encodeToByteArray()

        filter.expect(input)

        assertArrayEquals(observed, filter.consume(observed))
    }

    @Test
    fun `cancelling input echo expectation releases a partial match`() {
        val filter = InternalInputEchoFilter()
        filter.expect("bootstrap\n".encodeToByteArray())

        assertArrayEquals(
            "remote ".encodeToByteArray(),
            filter.consume("remote b".encodeToByteArray()),
        )
        assertArrayEquals("b".encodeToByteArray(), filter.cancel())
    }

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
                session.sent.first().decodeToString().startsWith("builtin eval -- $'"),
            )

            val bootstrapEcho = ptyEcho(session.sent.first())
            val bootstrapRaw = lifecycleBytes(
                nonce = nonce,
                commandId = CommandId("bootstrap-probe"),
                exitStatus = 0,
                currentDirectory = "/home/threadline",
            )
            val bootstrapStream = bootstrapEcho + bootstrapRaw
            val bootstrapEchoSplit = bootstrapEcho.size / 2
            session.output.send(bootstrapStream.copyOfRange(0, bootstrapEchoSplit))
            session.output.send(
                bootstrapStream.copyOfRange(bootstrapEchoSplit, bootstrapEcho.size + 17),
            )
            session.output.send(
                bootstrapStream.copyOfRange(bootstrapEcho.size + 17, bootstrapStream.size),
            )
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
            val backgroundOutput = "background output\r\n".encodeToByteArray()
            val commandEcho = ptyEcho(session.sent[1])
            val commandStream = backgroundOutput + commandEcho + commandRaw
            session.output.send(commandStream.copyOfRange(0, backgroundOutput.size + 7))
            session.output.send(commandStream.copyOfRange(backgroundOutput.size + 7, commandStream.size))
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
            val turn = manager.transcriptState.value.turns.single()
            assertEquals(CommandId("first-command"), turn.id)
            assertEquals("cd /tmp && false", turn.command)
            assertEquals("/home/threadline", turn.directoryAtStart)
            assertEquals("/tmp", turn.currentDirectory)
            assertEquals(1, turn.exitStatus)
            assertEquals(CommandStatus.FAILED, turn.status)
            assertEquals(CommandExecutionMode.PERSISTENT, turn.executionMode)
            assertEquals(
                CommandOutput(
                    plainText = "visible output\n",
                    byteCount = "visible output\r\n".encodeToByteArray().size.toLong(),
                ),
                turn.output,
            )
            assertArrayEquals(
                bootstrapRaw + backgroundOutput + commandRaw,
                terminal.received.flattenBytes(),
            )
            assertEquals(
                CommandSubmissionResult.Accepted(CommandId("second-command")),
                manager.submitIsolatedCommand("printf next"),
            )
            withTimeout(2_000) {
                while (session.sent.size < 3) delay(10)
            }
            assertTrue(session.sent.last().decodeToString().endsWith("'isolated'\n"))
            assertEquals(
                CommandExecutionMode.ISOLATED,
                manager.transcriptState.value.turns.last().executionMode,
            )

            manager.disconnect()
            withTimeout(2_000) {
                manager.state.first { it is SessionState.Disconnected }
            }
            assertEquals(StructuredShellState.Inactive, manager.structuredState.value)
        }

    @Test
    fun `durable session archives one completed snapshot on disconnect`() = runBlocking {
        val session = RecordingSession()
        val archiveSink = RecordingTranscriptArchiveSink()
        val nonce = SessionNonce("0123456789abcdef0123456789abcdef")
        val commandIds = ArrayDeque(
            listOf(CommandId("bootstrap-probe"), CommandId("saved-command")),
        )
        var now = 100L
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = FakeTerminal,
            sessionNonceFactory = { nonce },
            commandIdFactory = { commandIds.removeFirst() },
            transcriptArchiveSink = archiveSink,
            transcriptSessionIdFactory = { "saved-session" },
            clockMillis = { now },
        )

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Connected>().first()
        }
        session.output.send(
            lifecycleBytes(nonce, CommandId("bootstrap-probe"), 0, "/home/threadline"),
        )
        withTimeout(2_000) {
            manager.structuredState.filterIsInstance<StructuredShellState.Ready>().first()
        }
        now = 125L
        assertEquals(
            CommandSubmissionResult.Accepted(CommandId("saved-command")),
            manager.submitCommand("printf saved"),
        )
        session.output.send(
            lifecycleBytes(
                nonce = nonce,
                commandId = CommandId("saved-command"),
                exitStatus = 0,
                currentDirectory = "/home/threadline",
                output = "saved output\r\n",
            ),
        )
        withTimeout(2_000) {
            manager.transcriptState.first {
                it.turns.singleOrNull()?.status == CommandStatus.SUCCEEDED
            }
        }

        now = 200L
        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }

        val archived = archiveSink.archives.single()
        assertEquals("saved-session", archived.id)
        assertEquals(100L, archived.startedAtMillis)
        assertEquals(200L, archived.endedAtMillis)
        assertEquals("Fixture", archived.profile.displayName)
        assertEquals("printf saved", archived.transcript.turns.single().command)
        assertEquals("saved output\n", archived.transcript.turns.single().output.plainText)
        assertTrue(!manager.transcriptSaveFailed.value)
    }

    @Test
    fun `ephemeral session never reaches transcript archive sink`() = runBlocking {
        val session = RecordingSession()
        val archiveSink = RecordingTranscriptArchiveSink()
        val nonce = SessionNonce("0123456789abcdef0123456789abcdef")
        val commandIds = ArrayDeque(
            listOf(CommandId("bootstrap-probe"), CommandId("ephemeral-command")),
        )
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = FakeTerminal,
            sessionNonceFactory = { nonce },
            commandIdFactory = { commandIds.removeFirst() },
            transcriptArchiveSink = archiveSink,
        )

        manager.prepareConnection(fixtureRequest(ephemeral = true))
        manager.connectPrepared()
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Connected>().first()
        }
        session.output.send(
            lifecycleBytes(nonce, CommandId("bootstrap-probe"), 0, "/tmp"),
        )
        withTimeout(2_000) {
            manager.structuredState.filterIsInstance<StructuredShellState.Ready>().first()
        }
        manager.submitCommand("printf private")
        session.output.send(
            lifecycleBytes(
                nonce,
                CommandId("ephemeral-command"),
                0,
                "/tmp",
                "private output",
            ),
        )
        withTimeout(2_000) {
            manager.transcriptState.first {
                it.turns.singleOrNull()?.status == CommandStatus.SUCCEEDED
            }
        }

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }

        assertTrue(archiveSink.archives.isEmpty())
    }

    @Test
    fun `archive failure is surfaced without preventing disconnect`() = runBlocking {
        val session = RecordingSession()
        val nonce = SessionNonce("0123456789abcdef0123456789abcdef")
        val commandIds = ArrayDeque(
            listOf(CommandId("bootstrap-probe"), CommandId("saved-command")),
        )
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = FakeTerminal,
            sessionNonceFactory = { nonce },
            commandIdFactory = { commandIds.removeFirst() },
            transcriptArchiveSink = TranscriptArchiveSink {
                error("database-path-with-private-output")
            },
        )

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        withTimeout(2_000) {
            manager.state.filterIsInstance<SessionState.Connected>().first()
        }
        session.output.send(lifecycleBytes(nonce, CommandId("bootstrap-probe"), 0, "/tmp"))
        withTimeout(2_000) {
            manager.structuredState.filterIsInstance<StructuredShellState.Ready>().first()
        }
        manager.submitCommand("printf private")
        session.output.send(
            lifecycleBytes(nonce, CommandId("saved-command"), 0, "/tmp", "private output"),
        )
        withTimeout(2_000) {
            manager.transcriptState.first {
                it.turns.singleOrNull()?.status == CommandStatus.SUCCEEDED
            }
        }

        manager.disconnect()
        withTimeout(2_000) {
            manager.state.first { it is SessionState.Disconnected }
        }

        assertTrue(manager.transcriptSaveFailed.value)
    }

    @Test
    fun `bootstrap timeout downgrades to raw mode without failing connection`() = runBlocking {
        val session = RecordingSession()
        val terminal = RecordingTerminal()
        val manager = SessionManager(
            adapter = ImmediateAdapter(session),
            knownHostStore = EmptyKnownHostStore,
            terminal = terminal,
            sessionNonceFactory = {
                SessionNonce("0123456789abcdef0123456789abcdef")
            },
            commandIdFactory = { CommandId("bootstrap-probe") },
            bootstrapTimeoutMillis = 250,
        )

        manager.prepareConnection(fixtureRequest())
        manager.connectPrepared()
        withTimeout(2_000) {
            while (session.sent.isEmpty()) delay(10)
        }
        val partialEcho = ptyEcho(session.sent.first()).copyOfRange(0, 12)
        session.output.send(partialEcho)
        val unavailable = withTimeout(2_000) {
            manager.structuredState.filterIsInstance<StructuredShellState.Unavailable>().first()
        }

        assertEquals(
            StructuredShellUnavailableReason.BOOTSTRAP_TIMED_OUT,
            unavailable.reason,
        )
        assertTrue(manager.state.value is SessionState.Connected)
        withTimeout(2_000) {
            while (terminal.received.flattenBytes().size < partialEcho.size) delay(10)
        }
        assertArrayEquals(partialEcho, terminal.received.flattenBytes())

        manager.send("raw-still-works".encodeToByteArray())
        withTimeout(2_000) {
            while (
                !session.sent.joinToString("") { it.decodeToString() }
                    .endsWith("raw-still-works")
            ) {
                delay(10)
            }
        }
        val rawOutput = "raw output\r\n".encodeToByteArray()
        session.output.send(rawOutput)
        withTimeout(2_000) {
            while (terminal.received.flattenBytes().size < partialEcho.size + rawOutput.size) {
                delay(10)
            }
        }
        assertArrayEquals(partialEcho + rawOutput, terminal.received.flattenBytes())

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

private fun fixtureRequest(ephemeral: Boolean = false) = ConnectionRequest(
    profile = HostProfile(
        displayName = "Fixture",
        endpoint = HostEndpoint("fixture.test", 2222),
        username = "threadline",
    ),
    credential = SessionCredential.Password.from("test".toCharArray()),
    ephemeral = ephemeral,
)

private class RecordingTranscriptArchiveSink : TranscriptArchiveSink {
    val archives = CopyOnWriteArrayList<TranscriptSessionArchive>()

    override suspend fun save(archive: TranscriptSessionArchive) {
        archives += archive
    }
}

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

private class HostKeyCheckingAdapter(
    private val presentedKeys: List<ByteArray>,
    private val session: LiveSshSession,
) : SshClientAdapter {
    private val attemptCount = AtomicInteger()
    val attempts: Int
        get() = attemptCount.get()
    val credentialWasUsable = CopyOnWriteArrayList<Boolean>()

    override suspend fun connect(
        request: ConnectionRequest,
        verifier: ServerHostKeyVerifier,
        initialSize: TerminalSize,
        onStage: (dev.threadline.core.model.ConnectionStage) -> Unit,
    ): LiveSshSession {
        val attempt = attemptCount.getAndIncrement()
        val key = presentedKeys.getOrElse(attempt) { presentedKeys.last() }
        credentialWasUsable += !request.credential.isCleared()
        if (!verifier.verify("ssh-ed25519", key)) {
            throw dev.threadline.core.ssh.SshAdapterException(
                SessionError.HostKeyRejected(null),
            )
        }
        return session
    }
}

private class MutableKnownHostStore : KnownHostStore {
    @Volatile
    var record: KnownHostRecord? = null
        private set

    override suspend fun find(endpoint: HostEndpoint): KnownHostRecord? = record

    override suspend fun save(record: KnownHostRecord) {
        this.record = record.copy(
            key = KnownHostKey(record.key.algorithm, record.key.encoded.copyOf()),
        )
    }

    override suspend fun recordTrustedSeen(
        endpoint: HostEndpoint,
        key: KnownHostKey,
        seenAtMillis: Long,
    ) {
        record = requireNotNull(record).copy(lastSeenAtMillis = seenAtMillis)
    }
}

private fun SessionCredential.isCleared(): Boolean = when (this) {
    is SessionCredential.Password -> characters.all { it == '\u0000' }
    is SessionCredential.PrivateKey ->
        keyBytes.all { it == 0.toByte() } && passphrase?.all { it == '\u0000' } != false
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
    override suspend fun find(endpoint: HostEndpoint): KnownHostRecord? = null

    override suspend fun save(record: KnownHostRecord) = Unit

    override suspend fun recordTrustedSeen(
        endpoint: HostEndpoint,
        key: dev.threadline.core.security.KnownHostKey,
        seenAtMillis: Long,
    ) = Unit
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

private fun ptyEcho(input: ByteArray): ByteArray = input.copyOf(input.size + 1).also {
    it[input.lastIndex] = '\r'.code.toByte()
    it[input.size] = '\n'.code.toByte()
}

private fun Iterable<ByteArray>.flattenBytes(): ByteArray =
    fold(ByteArray(0)) { accumulated, bytes -> accumulated + bytes }
