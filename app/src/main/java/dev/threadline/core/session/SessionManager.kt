package dev.threadline.core.session

import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.ConnectionTarget
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.HostKeyPrompt
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.core.model.TerminalSize
import dev.threadline.core.security.KnownHostStore
import dev.threadline.core.security.StrictHostKeyGate
import dev.threadline.core.shell.BashShellIntegration
import dev.threadline.core.shell.CommandExecutionMode
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.CommandSubmissionRejection
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.ProtocolStreamItem
import dev.threadline.core.shell.SessionNonce
import dev.threadline.core.shell.ShellLifecycleEvent
import dev.threadline.core.shell.StructuredShellEvent
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.shell.StructuredShellStateMachine
import dev.threadline.core.shell.StructuredShellUnavailableReason
import dev.threadline.core.shell.ThreadlineOscParser
import dev.threadline.core.ssh.LiveSshSession
import dev.threadline.core.ssh.ServerHostKeyVerifier
import dev.threadline.core.ssh.SshAdapterException
import dev.threadline.core.ssh.SshClientAdapter
import dev.threadline.core.terminal.TerminalSink
import dev.threadline.core.transcript.CommandTranscript
import dev.threadline.core.transcript.CommandTranscriptState
import dev.threadline.core.transcript.NoOpTranscriptArchiveSink
import dev.threadline.core.transcript.TranscriptArchiveSink
import dev.threadline.core.transcript.TranscriptSessionArchive
import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReplySubmissionResult {
    SENT,
    NOT_RUNNING,
    INVALID_TEXT,
    INPUT_BACKPRESSURE,
}

@OptIn(FlowPreview::class)
class SessionManager(
    private val adapter: SshClientAdapter,
    private val knownHostStore: KnownHostStore,
    private val terminal: TerminalSink,
    private val sessionNonceFactory: () -> SessionNonce = { SessionNonce.random() },
    private val commandIdFactory: () -> CommandId = { CommandId.random() },
    private val bootstrapTimeoutMillis: Long = DEFAULT_BOOTSTRAP_TIMEOUT_MILLIS,
    private val transcriptArchiveSink: TranscriptArchiveSink = NoOpTranscriptArchiveSink,
    private val transcriptSessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    // This scope is owned by the application process. A connection exists only
    // while SshSessionService is in the foreground; the service calls disconnect
    // from onDestroy so per-session jobs always have a cancellation path.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMachine = SessionStateMachine()
    private val structuredStateMachine = StructuredShellStateMachine()
    private val commandTranscript = CommandTranscript(clockMillis = clockMillis)
    private val pendingLock = Any()
    private val decisionLock = Any()
    private val structuredLock = Any()
    private val transcriptArchiveLock = Any()
    private val transcriptSaveMutex = Mutex()
    private val resizeRequests = MutableSharedFlow<TerminalSize>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val inputRequests = Channel<SessionInput>(capacity = INPUT_QUEUE_CAPACITY)
    private val transcriptPublishRequests = Channel<Unit>(capacity = Channel.CONFLATED)
    private val internalInputEchoFilter = InternalInputEchoFilter()

    val state: StateFlow<SessionState> = stateMachine.state
    val structuredState: StateFlow<StructuredShellState> = structuredStateMachine.state
    val transcriptState: StateFlow<CommandTranscriptState> = commandTranscript.state
    private val mutableTranscriptSaveFailed = MutableStateFlow(false)
    val transcriptSaveFailed: StateFlow<Boolean> = mutableTranscriptSaveFailed
    private val mutableConnectionTarget = MutableStateFlow<ConnectionTarget?>(null)
    val connectionTarget: StateFlow<ConnectionTarget?> = mutableConnectionTarget
    val snapshot: StateFlow<SessionSnapshot> = combine(
        state,
        structuredState,
        transcriptState,
        ::SessionSnapshot,
    ).stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SessionSnapshot(
            state.value,
            structuredState.value,
            transcriptState.value,
        ),
    )

    private var pendingRequest: ConnectionRequest? = null
    private var hostKeyDecision: CompletableDeferred<HostKeyDecision>? = null
    private var connectJob: Job? = null
    private var outputJob: Job? = null
    private var disconnectMonitorJob: Job? = null
    private var disconnectJob: Job? = null
    private var cleanupJob: Job? = null
    private var bootstrapTimeoutJob: Job? = null
    private var activeTranscriptSession: ActiveTranscriptSession? = null

    @Volatile
    private var liveSession: LiveSshSession? = null

    @Volatile
    private var structuredContext: StructuredShellContext? = null

    init {
        require(bootstrapTimeoutMillis > 0)
    }

    init {
        scope.launch {
            inputRequests.consumeEach { input ->
                if (
                    liveSession !== input.session ||
                    input.commandId != null &&
                        (structuredState.value as? StructuredShellState.Running)
                            ?.activeCommand?.id != input.commandId
                ) {
                    input.bytes.fill(0)
                    return@consumeEach
                }
                try {
                    runCatching {
                        if (input.maximumPtyEchoes > 0) {
                            internalInputEchoFilter.expect(input.bytes, input.maximumPtyEchoes)
                        }
                        input.session.send(input.bytes)
                    }
                        .onFailure {
                            if (liveSession === input.session) {
                                internalInputEchoFilter.cancel()
                                failSession(SessionError.ConnectionLost, input.session)
                            }
                        }
                } finally {
                    if (input.clearAfterSend) input.bytes.fill(0)
                }
            }
        }
        scope.launch {
            resizeRequests
                .debounce(100)
                .collect { size ->
                    val session = liveSession ?: return@collect
                    val accepted = runCatching { session.resize(size) }
                        .getOrDefault(false)
                    if (!accepted) {
                        failSession(SessionError.PtyResizeRejected, session)
                    }
                }
        }
        scope.launch {
            for (ignored in transcriptPublishRequests) {
                delay(TRANSCRIPT_UPDATE_INTERVAL_MILLIS)
                commandTranscript.publishOutput()
            }
        }
    }

    fun prepareConnection(request: ConnectionRequest): Boolean = synchronized(pendingLock) {
        if (
            disconnectJob?.isActive == true ||
            state.value !is SessionState.Disconnected && state.value !is SessionState.Failed
        ) {
            request.credential.clear()
            return@synchronized false
        }

        pendingRequest?.credential?.clear()
        pendingRequest = request
        true
    }

    fun connectPrepared(): Boolean = synchronized(pendingLock) {
        val request = pendingRequest ?: return@synchronized false
        pendingRequest = null
        if (disconnectJob?.isActive == true ||
            state.value !is SessionState.Disconnected && state.value !is SessionState.Failed
        ) {
            request.credential.clear()
            return@synchronized false
        }
        stateMachine.apply(SessionEvent.ConnectRequested(request.profile.displayName))
        val previousCleanup = cleanupJob
        connectJob = scope.launch {
            previousCleanup?.join()
            // Abandoned input must not consume the fresh shell's bootstrap slot.
            while (true) {
                val input = inputRequests.tryReceive().getOrNull() ?: break
                input.bytes.fill(0)
            }
            synchronized(transcriptArchiveLock) {
                activeTranscriptSession = ActiveTranscriptSession(
                    id = transcriptSessionIdFactory(),
                    profile = request.profile,
                    ephemeral = request.ephemeral,
                )
            }
            commandTranscript.reset()
            resetStructuredShell()
            internalInputEchoFilter.reset()
            terminal.clear()
            establish(request)
        }.also { job -> job.invokeOnCompletion { request.credential.clear() } }
        true
    }

    fun cancelPrepared(error: SessionError) {
        synchronized(pendingLock) {
            pendingRequest?.credential?.clear()
            pendingRequest = null
        }
        failSession(error)
    }

    fun resolveHostKey(decision: HostKeyDecision): Boolean {
        val pending = synchronized(decisionLock) { hostKeyDecision }
        return pending?.complete(decision) == true
    }

    fun send(bytes: ByteArray) {
        val session = liveSession ?: return
        if (
            inputRequests.trySend(SessionInput(session, bytes.copyOf())).isFailure &&
            state.value is SessionState.Connected
        ) {
            failSession(SessionError.InputBackpressure, session)
        }
    }

    fun sendControlC() {
        commandTranscript.stopRequested()
        send(byteArrayOf(0x03))
    }

    fun sendReply(commandId: CommandId, reply: CharArray): ReplySubmissionResult =
        synchronized(structuredLock) {
            val running = structuredState.value as? StructuredShellState.Running
            if (
                state.value !is SessionState.Connected ||
                running?.activeCommand?.id != commandId
            ) {
                return@synchronized ReplySubmissionResult.NOT_RUNNING
            }
            if (reply.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                return@synchronized ReplySubmissionResult.INVALID_TEXT
            }
            val session = liveSession ?: return@synchronized ReplySubmissionResult.NOT_RUNNING
            val encoded = try {
                Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(reply))
            } catch (_: CharacterCodingException) {
                return@synchronized ReplySubmissionResult.INVALID_TEXT
            }
            val bytes = try {
                ByteArray(encoded.remaining() + 1).also {
                    encoded.get(it, 0, it.lastIndex)
                    it[it.lastIndex] = '\r'.code.toByte()
                }
            } finally {
                if (encoded.hasArray()) encoded.array().fill(0)
            }
            if (
                inputRequests.trySend(
                    SessionInput(
                        session = session,
                        bytes = bytes,
                        clearAfterSend = true,
                        commandId = commandId,
                    ),
                ).isFailure
            ) {
                bytes.fill(0)
                return@synchronized ReplySubmissionResult.INPUT_BACKPRESSURE
            }
            ReplySubmissionResult.SENT
        }

    fun submitCommand(command: String): CommandSubmissionResult = submitCommand(
        command = command,
        executionMode = CommandExecutionMode.PERSISTENT,
    )

    fun submitIsolatedCommand(command: String): CommandSubmissionResult = submitCommand(
        command = command,
        executionMode = CommandExecutionMode.ISOLATED,
    )

    private fun submitCommand(
        command: String,
        executionMode: CommandExecutionMode,
    ): CommandSubmissionResult = synchronized(structuredLock) {
        val currentState = structuredState.value
        if (currentState is StructuredShellState.Running) {
            return@synchronized CommandSubmissionResult.Rejected(
                CommandSubmissionRejection.COMMAND_ALREADY_RUNNING,
            )
        }
        if (currentState !is StructuredShellState.Ready) {
            return@synchronized CommandSubmissionResult.Rejected(
                CommandSubmissionRejection.NOT_READY,
            )
        }
        if ('\u0000' in command) {
            return@synchronized CommandSubmissionResult.Rejected(
                CommandSubmissionRejection.INVALID_COMMAND,
            )
        }

        val session = liveSession
        val context = structuredContext
        if (session == null || context == null) {
            return@synchronized CommandSubmissionResult.Rejected(
                CommandSubmissionRejection.NOT_READY,
            )
        }

        val commandId = commandIdFactory()
        val invocation = context.integration.invocation(commandId, command, executionMode)
        structuredStateMachine.apply(
            StructuredShellEvent.CommandSubmitted(commandId, command),
        )
        commandTranscript.commandSubmitted(
            id = commandId,
            command = command,
            executionMode = executionMode,
            directoryAtStart = currentState.currentDirectory,
        )
        if (
            inputRequests.trySend(
                SessionInput(session, invocation, maximumPtyEchoes = 1),
            ).isFailure
        ) {
            structuredStateMachine.apply(
                StructuredShellEvent.CommandSendRejected(commandId),
            )
            commandTranscript.commandSendRejected(commandId)
            return@synchronized CommandSubmissionResult.Rejected(
                CommandSubmissionRejection.INPUT_BACKPRESSURE,
            )
        }
        CommandSubmissionResult.Accepted(commandId)
    }

    fun resize(size: TerminalSize) {
        resizeRequests.tryEmit(size)
    }

    fun disconnect() = synchronized(pendingLock) {
        if (disconnectJob?.isActive == true) return@synchronized
        stateMachine.apply(SessionEvent.DisconnectRequested)
        val previousCleanup = cleanupJob
        disconnectJob = scope.launch {
            previousCleanup?.join()
            synchronized(decisionLock) {
                hostKeyDecision?.complete(HostKeyDecision.REJECT)
            }
            connectJob?.cancelAndJoin()
            outputJob?.cancelAndJoin()
            disconnectMonitorJob?.cancelAndJoin()
            bootstrapTimeoutJob?.cancelAndJoin()
            val session = liveSession
            liveSession = null
            commandTranscript.sessionDisconnected()
            val archive = closeTranscriptSession()
            resetStructuredShell()
            runCatching { session?.disconnect() }
            persistTranscriptArchive(archive)
            stateMachine.apply(SessionEvent.Disconnected)
        }
    }

    fun onServiceDestroyed() {
        if (state.value !is SessionState.Failed) disconnect()
    }

    private suspend fun establish(request: ConnectionRequest) {
        try {
            val firstAttempt = connectOnce(request, captureUnknownKey = true)
            val session = when (firstAttempt) {
                is ConnectionAttempt.Connected -> firstAttempt.session
                is ConnectionAttempt.Failed -> {
                    val unknownKey = firstAttempt.unknownKey.takeIf {
                        firstAttempt.failure.error is SessionError.HostKeyRejected &&
                            firstAttempt.gateError is SessionError.HostKeyRejected
                    }
                        ?: throw firstAttempt.asException()
                    try {
                        when (awaitHostKeyDecision(unknownKey.prompt)) {
                            HostKeyDecision.REJECT -> throw firstAttempt.asException()
                            HostKeyDecision.ACCEPT_AND_SAVE -> {
                                persistAcceptedHostKey(request, unknownKey)
                                when (val retry = connectOnce(request, captureUnknownKey = false)) {
                                    is ConnectionAttempt.Connected -> retry.session
                                    is ConnectionAttempt.Failed -> throw retry.asException()
                                }
                            }
                        }
                    } finally {
                        unknownKey.encoded.fill(0)
                    }
                }
            }
            synchronized(pendingLock) {
                liveSession = session
                mutableConnectionTarget.value = ConnectionTarget(
                    profile = request.profile,
                    usesPrivateKey = request.credential is SessionCredential.PrivateKey,
                    ephemeral = request.ephemeral,
                    identityId = request.identityId,
                    importedPrivateKeyId = request.importedPrivateKeyId,
                    privateKeyUri = request.privateKeyUri,
                )
                markTranscriptSessionConnected()
                startStructuredShell(session)
                stateMachine.apply(SessionEvent.ShellReady(terminal.size))
                startSessionJobs(session)
            }
        } catch (failure: SshAdapterException) {
            failSession(failure.error)
        } finally {
            request.credential.clear()
        }
    }

    private suspend fun connectOnce(
        request: ConnectionRequest,
        captureUnknownKey: Boolean,
    ): ConnectionAttempt {
        var presentedAlgorithm: String? = null
        var presentedKey: ByteArray? = null
        var unknownKey: DeferredHostKey? = null
        val gate = StrictHostKeyGate(
            endpoint = request.profile.endpoint,
            store = knownHostStore,
            requestDecision = { prompt ->
                if (captureUnknownKey) {
                    unknownKey = DeferredHostKey(
                        prompt = prompt,
                        algorithm = requireNotNull(presentedAlgorithm),
                        encoded = requireNotNull(presentedKey).copyOf(),
                    )
                }
                HostKeyDecision.REJECT
            },
        )
        val attemptCredential = request.credential.copyForConnectionAttempt()
        val attemptRequest = ConnectionRequest(
            profile = request.profile,
            credential = attemptCredential,
            ephemeral = request.ephemeral,
            identityId = request.identityId,
            importedPrivateKeyId = request.importedPrivateKeyId,
            privateKeyUri = request.privateKeyUri,
        )

        return try {
            val session = adapter.connect(
                request = attemptRequest,
                verifier = ServerHostKeyVerifier { algorithm, encoded ->
                    presentedAlgorithm = algorithm
                    presentedKey = encoded
                    try {
                        gate.verify(algorithm, encoded)
                    } finally {
                        presentedAlgorithm = null
                        presentedKey = null
                    }
                },
                initialSize = terminal.size,
                onStage = { stage -> stateMachine.apply(SessionEvent.StageChanged(stage)) },
            )
            ConnectionAttempt.Connected(session)
        } catch (failure: SshAdapterException) {
            ConnectionAttempt.Failed(
                failure = failure,
                gateError = gate.rejection,
                unknownKey = unknownKey,
            )
        } finally {
            attemptCredential.clear()
        }
    }

    private suspend fun persistAcceptedHostKey(
        request: ConnectionRequest,
        unknownKey: DeferredHostKey,
    ) {
        val gate = StrictHostKeyGate(
            endpoint = request.profile.endpoint,
            store = knownHostStore,
            requestDecision = { prompt ->
                check(prompt == unknownKey.prompt) {
                    "Accepted host key must match the displayed fingerprint"
                }
                HostKeyDecision.ACCEPT_AND_SAVE
            },
        )
        if (!gate.verify(unknownKey.algorithm, unknownKey.encoded)) {
            throw SshAdapterException(
                gate.rejection ?: SessionError.HostKeyRejected(unknownKey.prompt.fingerprint),
            )
        }
    }

    private suspend fun awaitHostKeyDecision(prompt: HostKeyPrompt): HostKeyDecision {
        val pending = CompletableDeferred<HostKeyDecision>()
        synchronized(decisionLock) {
            check(hostKeyDecision == null) { "Only one host-key decision may be pending" }
            hostKeyDecision = pending
        }
        stateMachine.apply(SessionEvent.HostKeyRequired(prompt))

        return try {
            pending.await().also { decision ->
                if (decision == HostKeyDecision.ACCEPT_AND_SAVE) {
                    stateMachine.apply(SessionEvent.HostKeyAccepted)
                }
            }
        } finally {
            synchronized(decisionLock) {
                if (hostKeyDecision === pending) hostKeyDecision = null
            }
        }
    }

    private fun startSessionJobs(session: LiveSshSession) {
        outputJob = scope.launch {
            try {
                for (bytes in session.output) {
                    if (liveSession !== session) break
                    val terminalBytes = internalInputEchoFilter.consume(bytes)
                    if (terminalBytes.isNotEmpty()) terminal.receive(terminalBytes)
                    processStructuredOutput(bytes)
                }
                failSession(session.outputEndError(), session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failSession(SessionError.TerminalRendererFailed, session)
            }
        }
        disconnectMonitorJob = scope.launch {
            if (session.disconnects.firstOrNull() != null) {
                failSession(SessionError.ConnectionLost, session)
            }
        }
    }

    private fun createStructuredShellContext(): StructuredShellContext {
        val nonce = sessionNonceFactory()
        return StructuredShellContext(
            parser = ThreadlineOscParser(nonce),
            integration = BashShellIntegration(nonce),
            probeCommandId = commandIdFactory(),
        )
    }

    private fun startStructuredShell(session: LiveSshSession) {
        val context = try {
            createStructuredShellContext()
        } catch (_: Exception) {
            structuredStateMachine.apply(
                StructuredShellEvent.IntegrationFailed(
                    StructuredShellUnavailableReason.BOOTSTRAP_FAILED,
                ),
            )
            return
        }
        synchronized(structuredLock) {
            structuredContext = context
            structuredStateMachine.apply(
                StructuredShellEvent.BootstrapRequested(context.probeCommandId),
            )
        }
        try {
            startStructuredBootstrap(session, context)
        } catch (_: Exception) {
            downgradeStructuredShell(
                context,
                StructuredShellUnavailableReason.BOOTSTRAP_FAILED,
            )
        }
    }

    private fun startStructuredBootstrap(
        session: LiveSshSession,
        context: StructuredShellContext,
    ) {
        val bootstrap = context.integration.bootstrap(context.probeCommandId)
        bootstrapTimeoutJob?.cancel()
        bootstrapTimeoutJob = scope.launch {
            delay(bootstrapTimeoutMillis)
            val timedOut = synchronized(structuredLock) {
                if (structuredContext !== context) return@synchronized false
                val next = structuredStateMachine.apply(
                    StructuredShellEvent.BootstrapTimedOut(context.probeCommandId),
                )
                if (next is StructuredShellState.Unavailable) {
                    structuredContext = null
                }
                next is StructuredShellState.Unavailable
            }
            if (timedOut) {
                val pendingBytes = internalInputEchoFilter.cancel()
                if (pendingBytes.isNotEmpty()) terminal.receive(pendingBytes)
            }
        }
        if (
            inputRequests.trySend(
                // Startup can echo the bootstrap twice before its lifecycle begins.
                SessionInput(session, bootstrap, maximumPtyEchoes = 2),
            ).isFailure
        ) {
            downgradeStructuredShell(
                context,
                StructuredShellUnavailableReason.BOOTSTRAP_FAILED,
            )
        }
    }

    private suspend fun processStructuredOutput(bytes: ByteArray) {
        val context = structuredContext ?: return
        val scan = try {
            context.parser.consume(bytes)
        } catch (_: Exception) {
            val pendingBytes = internalInputEchoFilter.cancel()
            if (pendingBytes.isNotEmpty()) terminal.receive(pendingBytes)
            downgradeStructuredShell(
                context,
                StructuredShellUnavailableReason.PARSER_FAILED,
            )
            return
        }

        scan.items.forEach { item ->
            when (item) {
                is ProtocolStreamItem.TranscriptBytes -> {
                    if (commandTranscript.consumeOutput(item.bytes)) {
                        transcriptPublishRequests.trySend(Unit)
                    }
                }

                is ProtocolStreamItem.Lifecycle -> {
                    var commandCompleted = false
                    val pendingBytes = synchronized(structuredLock) {
                        val pending = if (item.event is ShellLifecycleEvent.CommandStarted) {
                            internalInputEchoFilter.cancel()
                        } else {
                            byteArrayOf()
                        }
                        if (structuredContext === context) {
                            commandCompleted = item.event is ShellLifecycleEvent.CommandEnded &&
                                commandTranscript.state.value.activeCommandId == item.event.commandId
                            commandTranscript.lifecycle(item.event)
                            val next = structuredStateMachine.apply(
                                StructuredShellEvent.Lifecycle(item.event),
                            )
                            if (next is StructuredShellState.Unavailable) {
                                commandTranscript.structuredShellFailed(item.event.commandId)
                            }
                            if (next !is StructuredShellState.Bootstrapping) {
                                bootstrapTimeoutJob?.cancel()
                                bootstrapTimeoutJob = null
                            }
                            if (next is StructuredShellState.Unavailable) {
                                structuredContext = null
                            }
                        }
                        pending
                    }
                    if (pendingBytes.isNotEmpty()) terminal.receive(pendingBytes)
                    if (commandCompleted) checkpointTranscriptSession(context)
                }
            }
        }
    }

    private fun downgradeStructuredShell(
        context: StructuredShellContext,
        reason: StructuredShellUnavailableReason,
    ) {
        synchronized(structuredLock) {
            if (structuredContext !== context) return
            structuredStateMachine.apply(StructuredShellEvent.IntegrationFailed(reason))
            commandTranscript.structuredShellFailed()
            structuredContext = null
            bootstrapTimeoutJob?.cancel()
            bootstrapTimeoutJob = null
        }
    }

    private fun resetStructuredShell() {
        synchronized(structuredLock) {
            structuredContext = null
            structuredStateMachine.apply(StructuredShellEvent.Reset)
            bootstrapTimeoutJob?.cancel()
            bootstrapTimeoutJob = null
        }
    }

    private fun failSession(error: SessionError, expectedSession: LiveSshSession? = null) =
        synchronized(pendingLock) {
            if (expectedSession != null &&
                (liveSession !== expectedSession || state.value !is SessionState.Connected)
            ) return@synchronized
            val session = liveSession
            liveSession = null
            val connecting = connectJob
            val output = outputJob
            val monitor = disconnectMonitorJob
            val previousCleanup = cleanupJob
            connecting?.cancel()
            output?.cancel()
            monitor?.cancel()
            commandTranscript.sessionDisconnected()
            val archive = closeTranscriptSession()
            resetStructuredShell()
            cleanupJob = scope.launch {
                previousCleanup?.join()
                connecting?.join()
                output?.join()
                monitor?.join()
                runCatching { session?.disconnect() }
                persistTranscriptArchive(archive)
            }
            stateMachine.apply(SessionEvent.Failed(error))
        }

    private fun markTranscriptSessionConnected() = synchronized(transcriptArchiveLock) {
        activeTranscriptSession = activeTranscriptSession?.copy(
            startedAtMillis = activeTranscriptSession?.startedAtMillis ?: clockMillis(),
        )
    }

    private fun closeTranscriptSession(): TranscriptSessionArchive? =
        synchronized(transcriptArchiveLock) {
            val active = activeTranscriptSession ?: return@synchronized null
            activeTranscriptSession = null
            transcriptArchive(active, completedOnly = false)
        }

    private suspend fun checkpointTranscriptSession(context: StructuredShellContext) {
        transcriptSaveMutex.withLock {
            // Capture after acquiring the save lock so a closed or replaced session
            // cannot enqueue an older snapshot after its final archive.
            val archive = synchronized(structuredLock) {
                if (structuredContext !== context) return@synchronized null
                synchronized(transcriptArchiveLock) {
                    activeTranscriptSession?.let { transcriptArchive(it, completedOnly = true) }
                }
            }
            saveTranscriptArchive(archive)
        }
    }

    private fun transcriptArchive(
        active: ActiveTranscriptSession,
        completedOnly: Boolean,
    ): TranscriptSessionArchive? {
        val startedAtMillis = active.startedAtMillis ?: return null
        if (active.ephemeral) return null
        val transcript = commandTranscript.state.value.let { state ->
            if (completedOnly) {
                state.copy(
                    turns = state.turns.filter { it.completedAtMillis != null },
                    activeCommandId = null,
                )
            } else {
                state
            }
        }
        if (transcript.turns.isEmpty()) return null
        return TranscriptSessionArchive(
            id = active.id,
            profile = active.profile,
            startedAtMillis = startedAtMillis,
            savedAtMillis = clockMillis(),
            transcript = transcript,
        )
    }

    private suspend fun persistTranscriptArchive(archive: TranscriptSessionArchive?) =
        transcriptSaveMutex.withLock { saveTranscriptArchive(archive) }

    private suspend fun saveTranscriptArchive(archive: TranscriptSessionArchive?) {
        if (archive == null) return
        try {
            transcriptArchiveSink.save(archive)
            mutableTranscriptSaveFailed.value = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableTranscriptSaveFailed.value = true
        }
    }

    private data class SessionInput(
        val session: LiveSshSession,
        val bytes: ByteArray,
        val maximumPtyEchoes: Int = 0,
        val clearAfterSend: Boolean = false,
        val commandId: CommandId? = null,
    )

    private data class StructuredShellContext(
        val parser: ThreadlineOscParser,
        val integration: BashShellIntegration,
        val probeCommandId: CommandId,
    )

    private data class ActiveTranscriptSession(
        val id: String,
        val profile: HostProfile,
        val ephemeral: Boolean,
        val startedAtMillis: Long? = null,
    )

    private data class DeferredHostKey(
        val prompt: HostKeyPrompt,
        val algorithm: String,
        val encoded: ByteArray,
    )

    private sealed interface ConnectionAttempt {
        data class Connected(val session: LiveSshSession) : ConnectionAttempt

        data class Failed(
            val failure: SshAdapterException,
            val gateError: SessionError?,
            val unknownKey: DeferredHostKey?,
        ) : ConnectionAttempt {
            fun asException(): SshAdapterException = gateError?.let { error ->
                SshAdapterException(error, failure)
            } ?: failure
        }
    }

    private companion object {
        const val INPUT_QUEUE_CAPACITY = 256
        const val DEFAULT_BOOTSTRAP_TIMEOUT_MILLIS = 10_000L
        const val TRANSCRIPT_UPDATE_INTERVAL_MILLIS = 50L
    }
}

private fun SessionCredential.copyForConnectionAttempt(): SessionCredential = when (this) {
    is SessionCredential.Password -> SessionCredential.Password.from(characters)
    is SessionCredential.PrivateKey -> SessionCredential.PrivateKey.from(keyBytes, passphrase)
}

internal class InternalInputEchoFilter {
    private var pattern: ByteArray? = null
    private var prefixLengths = IntArray(0)
    private var matchedBytes = 0
    private var echoesRemaining = 0

    @Synchronized
    fun expect(input: ByteArray, maximumEchoes: Int = 1) {
        check(pattern == null) { "Only one internal input echo may be pending" }
        require(maximumEchoes in 1..2)
        val expected = input.withPtyEchoLineEnding()
        pattern = expected
        prefixLengths = expected.prefixLengths()
        matchedBytes = 0
        echoesRemaining = maximumEchoes
    }

    @Synchronized
    fun consume(bytes: ByteArray): ByteArray {
        if (pattern == null) return bytes
        val output = ByteArrayOutputStream(bytes.size)

        bytes.forEach { byte ->
            val expected = pattern
            if (expected == null) {
                output.write(byte.toInt())
                return@forEach
            }

            while (matchedBytes > 0 && byte != expected[matchedBytes]) {
                val fallback = prefixLengths[matchedBytes - 1]
                output.write(expected, 0, matchedBytes - fallback)
                matchedBytes = fallback
            }

            if (byte == expected[matchedBytes]) {
                matchedBytes += 1
                if (matchedBytes == expected.size) {
                    matchedBytes = 0
                    echoesRemaining -= 1
                    if (echoesRemaining == 0) reset()
                }
            } else {
                output.write(byte.toInt())
            }
        }

        return output.toByteArray()
    }

    @Synchronized
    fun cancel(): ByteArray {
        val pending = pattern?.copyOfRange(0, matchedBytes) ?: byteArrayOf()
        reset()
        return pending
    }

    @Synchronized
    fun reset() {
        pattern = null
        prefixLengths = IntArray(0)
        matchedBytes = 0
        echoesRemaining = 0
    }
}

private fun ByteArray.withPtyEchoLineEnding(): ByteArray {
    require(lastOrNull() == '\n'.code.toByte())
    return copyOf(size + 1).also {
        it[size - 1] = '\r'.code.toByte()
        it[size] = '\n'.code.toByte()
    }
}

private fun ByteArray.prefixLengths(): IntArray = IntArray(size).also { prefixes ->
    var matched = 0
    for (index in 1 until size) {
        while (matched > 0 && this[index] != this[matched]) {
            matched = prefixes[matched - 1]
        }
        if (this[index] == this[matched]) matched += 1
        prefixes[index] = matched
    }
}
