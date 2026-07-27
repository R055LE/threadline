package dev.threadline.core.session

import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.HostKeyPrompt
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.core.model.TerminalSize
import dev.threadline.core.security.KnownHostStore
import dev.threadline.core.security.StrictHostKeyGate
import dev.threadline.core.ssh.LiveSshSession
import dev.threadline.core.ssh.ServerHostKeyVerifier
import dev.threadline.core.ssh.SshAdapterException
import dev.threadline.core.ssh.SshClientAdapter
import dev.threadline.core.terminal.TerminalSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SessionManager(
    private val adapter: SshClientAdapter,
    private val knownHostStore: KnownHostStore,
    private val terminal: TerminalSink,
) {
    // This scope is owned by the application process. A connection exists only
    // while SshSessionService is in the foreground; the service calls disconnect
    // from onDestroy so per-session jobs always have a cancellation path.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMachine = SessionStateMachine()
    private val pendingLock = Any()
    private val decisionLock = Any()
    private val resizeRequests = MutableSharedFlow<TerminalSize>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val inputRequests = Channel<SessionInput>(capacity = INPUT_QUEUE_CAPACITY)

    val state: StateFlow<SessionState> = stateMachine.state

    private var pendingRequest: ConnectionRequest? = null
    private var hostKeyDecision: CompletableDeferred<HostKeyDecision>? = null
    private var connectJob: Job? = null
    private var outputJob: Job? = null
    private var disconnectMonitorJob: Job? = null
    private var disconnectJob: Job? = null

    @Volatile
    private var liveSession: LiveSshSession? = null

    init {
        scope.launch {
            inputRequests.consumeEach { input ->
                runCatching { input.session.send(input.bytes) }
                    .onFailure {
                        if (
                            liveSession === input.session &&
                            state.value is SessionState.Connected
                        ) {
                            stateMachine.apply(SessionEvent.Failed(SessionError.ConnectionLost))
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
                        stateMachine.apply(SessionEvent.Failed(SessionError.PtyResizeRejected))
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
            stateMachine.apply(SessionEvent.Failed(SessionError.InputBackpressure))
        }
    }

    fun sendControlC() = send(byteArrayOf(0x03))

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
            val session = liveSession
            liveSession = null
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
            val session = liveSession
            liveSession = null
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
        } catch (failure: SshAdapterException) {
            val mapped = if (failure.error is SessionError.HostKeyRejected) {
                gate.rejection ?: failure.error
            } else {
                failure.error
            }
            stateMachine.apply(SessionEvent.Failed(mapped))
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
            session.output.consumeEach(terminal::receive)
            failIfUnexpectedDisconnect()
        }
        disconnectMonitorJob = scope.launch {
            session.disconnects.first()
            failIfUnexpectedDisconnect()
        }
    }

    private fun failIfUnexpectedDisconnect() {
        if (state.value is SessionState.Connected) {
            stateMachine.apply(SessionEvent.Failed(SessionError.ConnectionLost))
        }
    }

    private data class SessionInput(
        val session: LiveSshSession,
        val bytes: ByteArray,
    )

    private companion object {
        const val INPUT_QUEUE_CAPACITY = 256
    }
}
