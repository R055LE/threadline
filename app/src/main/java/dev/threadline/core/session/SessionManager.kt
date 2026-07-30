package dev.threadline.core.session

import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.HostKeyPrompt
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.core.model.TerminalSize
import dev.threadline.core.security.KnownHostStore
import dev.threadline.core.security.StrictHostKeyGate
import dev.threadline.core.shell.BashShellIntegration
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.CommandSubmissionRejection
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.ProtocolStreamItem
import dev.threadline.core.shell.SessionNonce
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SessionManager(
    private val adapter: SshClientAdapter,
    private val knownHostStore: KnownHostStore,
    private val terminal: TerminalSink,
    private val sessionNonceFactory: () -> SessionNonce = { SessionNonce.random() },
    private val commandIdFactory: () -> CommandId = { CommandId.random() },
    private val bootstrapTimeoutMillis: Long = DEFAULT_BOOTSTRAP_TIMEOUT_MILLIS,
) {
    // This scope is owned by the application process. A connection exists only
    // while SshSessionService is in the foreground; the service calls disconnect
    // from onDestroy so per-session jobs always have a cancellation path.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMachine = SessionStateMachine()
    private val structuredStateMachine = StructuredShellStateMachine()
    private val pendingLock = Any()
    private val decisionLock = Any()
    private val structuredLock = Any()
    private val resizeRequests = MutableSharedFlow<TerminalSize>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val inputRequests = Channel<SessionInput>(capacity = INPUT_QUEUE_CAPACITY)

    val state: StateFlow<SessionState> = stateMachine.state
    val structuredState: StateFlow<StructuredShellState> = structuredStateMachine.state
    val snapshot: StateFlow<SessionSnapshot> = combine(
        state,
        structuredState,
        ::SessionSnapshot,
    ).stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SessionSnapshot(state.value, structuredState.value),
    )

    private var pendingRequest: ConnectionRequest? = null
    private var hostKeyDecision: CompletableDeferred<HostKeyDecision>? = null
    private var connectJob: Job? = null
    private var outputJob: Job? = null
    private var disconnectMonitorJob: Job? = null
    private var disconnectJob: Job? = null
    private var bootstrapTimeoutJob: Job? = null

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
                runCatching { input.session.send(input.bytes) }
                    .onFailure {
                        if (
                            liveSession === input.session &&
                            state.value is SessionState.Connected
                        ) {
                            failSession(SessionError.ConnectionLost)
                        }
                    }
            }
        }
        scope.launch {
            resizeRequests
                .debounce(100)
                .collect { size ->
                    val accepted = runCatching { liveSession?.resize(size) ?: true }
                        .getOrDefault(false)
                    if (!accepted && state.value is SessionState.Connected) {
                        failSession(SessionError.PtyResizeRejected)
                    }
                }
        }
    }

    fun prepareConnection(request: ConnectionRequest): Boolean = synchronized(pendingLock) {
        if (
            state.value !is SessionState.Disconnected &&
            state.value !is SessionState.Failed
        ) {
            request.credential.clear()
            return@synchronized false
        }

        pendingRequest?.credential?.clear()
        pendingRequest = request
        true
    }

    fun connectPrepared(): Boolean {
        val request = synchronized(pendingLock) {
            pendingRequest.also { pendingRequest = null }
        } ?: return false

        stateMachine.apply(SessionEvent.ConnectRequested(request.profile.displayName))
        resetStructuredShell()
        terminal.clear()
        connectJob = scope.launch { establish(request) }
        return true
    }

    fun cancelPrepared(error: SessionError) {
        synchronized(pendingLock) {
            pendingRequest?.credential?.clear()
            pendingRequest = null
        }
        stateMachine.apply(SessionEvent.Failed(error))
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
            failSession(SessionError.InputBackpressure)
        }
    }

    fun sendControlC() = send(byteArrayOf(0x03))

    fun submitCommand(command: String): CommandSubmissionResult = synchronized(structuredLock) {
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
        val invocation = context.integration.invocation(commandId, command)
        structuredStateMachine.apply(
            StructuredShellEvent.CommandSubmitted(commandId, command),
        )
        if (inputRequests.trySend(SessionInput(session, invocation)).isFailure) {
            structuredStateMachine.apply(
                StructuredShellEvent.CommandSendRejected(commandId),
            )
            return@synchronized CommandSubmissionResult.Rejected(
                CommandSubmissionRejection.INPUT_BACKPRESSURE,
            )
        }
        CommandSubmissionResult.Accepted(commandId)
    }

    fun resize(size: TerminalSize) {
        resizeRequests.tryEmit(size)
    }

    fun disconnect() {
        if (disconnectJob?.isActive == true) return
        disconnectJob = scope.launch {
            stateMachine.apply(SessionEvent.DisconnectRequested)
            synchronized(decisionLock) {
                hostKeyDecision?.complete(HostKeyDecision.REJECT)
            }
            connectJob?.cancelAndJoin()
            outputJob?.cancelAndJoin()
            disconnectMonitorJob?.cancelAndJoin()
            bootstrapTimeoutJob?.cancelAndJoin()
            val session = liveSession
            liveSession = null
            resetStructuredShell()
            runCatching { session?.disconnect() }
            stateMachine.apply(SessionEvent.Disconnected)
        }
    }

    fun onServiceDestroyed() {
        scope.launch {
            synchronized(decisionLock) {
                hostKeyDecision?.complete(HostKeyDecision.REJECT)
            }
            connectJob?.cancelAndJoin()
            outputJob?.cancelAndJoin()
            disconnectMonitorJob?.cancelAndJoin()
            bootstrapTimeoutJob?.cancelAndJoin()
            val session = liveSession
            liveSession = null
            resetStructuredShell()
            runCatching { session?.disconnect() }
            if (state.value !is SessionState.Failed) {
                stateMachine.apply(SessionEvent.Disconnected)
            }
        }
    }

    private suspend fun establish(request: ConnectionRequest) {
        val gate = StrictHostKeyGate(
            endpoint = request.profile.endpoint,
            store = knownHostStore,
            requestDecision = ::awaitHostKeyDecision,
        )

        try {
            val session = adapter.connect(
                request = request,
                verifier = ServerHostKeyVerifier(gate::verify),
                initialSize = terminal.size,
                onStage = { stage -> stateMachine.apply(SessionEvent.StageChanged(stage)) },
            )
            liveSession = session
            stateMachine.apply(SessionEvent.ShellReady(terminal.size))
            startSessionJobs(session)
            startStructuredShell(session)
        } catch (failure: SshAdapterException) {
            val mapped = if (failure.error is SessionError.HostKeyRejected) {
                gate.rejection ?: failure.error
            } else {
                failure.error
            }
            failSession(mapped)
        } finally {
            request.credential.clear()
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
                    terminal.receive(bytes)
                    processStructuredOutput(bytes)
                }
                failIfUnexpectedDisconnect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (state.value is SessionState.Connected) {
                    failSession(SessionError.TerminalRendererFailed)
                }
            }
        }
        disconnectMonitorJob = scope.launch {
            session.disconnects.first()
            failIfUnexpectedDisconnect()
        }
    }

    private fun failIfUnexpectedDisconnect() {
        if (state.value is SessionState.Connected) {
            failSession(SessionError.ConnectionLost)
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
            synchronized(structuredLock) {
                if (structuredContext !== context) return@synchronized
                val next = structuredStateMachine.apply(
                    StructuredShellEvent.BootstrapTimedOut(context.probeCommandId),
                )
                if (next is StructuredShellState.Unavailable) {
                    structuredContext = null
                }
            }
        }
        if (inputRequests.trySend(SessionInput(session, bootstrap)).isFailure) {
            downgradeStructuredShell(
                context,
                StructuredShellUnavailableReason.BOOTSTRAP_FAILED,
            )
        }
    }

    private fun processStructuredOutput(bytes: ByteArray) {
        val context = structuredContext ?: return
        val scan = try {
            context.parser.consume(bytes)
        } catch (_: Exception) {
            downgradeStructuredShell(
                context,
                StructuredShellUnavailableReason.PARSER_FAILED,
            )
            return
        }

        scan.items.forEach { item ->
            if (item !is ProtocolStreamItem.Lifecycle) return@forEach
            synchronized(structuredLock) {
                if (structuredContext !== context) return@synchronized
                val next = structuredStateMachine.apply(
                    StructuredShellEvent.Lifecycle(item.event),
                )
                if (next !is StructuredShellState.Bootstrapping) {
                    bootstrapTimeoutJob?.cancel()
                    bootstrapTimeoutJob = null
                }
                if (next is StructuredShellState.Unavailable) {
                    structuredContext = null
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

    private fun failSession(error: SessionError) {
        resetStructuredShell()
        stateMachine.apply(SessionEvent.Failed(error))
    }

    private data class SessionInput(
        val session: LiveSshSession,
        val bytes: ByteArray,
    )

    private data class StructuredShellContext(
        val parser: ThreadlineOscParser,
        val integration: BashShellIntegration,
        val probeCommandId: CommandId,
    )

    private companion object {
        const val INPUT_QUEUE_CAPACITY = 256
        const val DEFAULT_BOOTSTRAP_TIMEOUT_MILLIS = 10_000L
    }
}
