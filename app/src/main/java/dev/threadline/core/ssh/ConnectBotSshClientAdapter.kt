package dev.threadline.core.ssh

import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.ConnectionStage
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.TerminalSize
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.SshSession

class ConnectBotSshClientAdapter(
    private val hostKeyAlgorithmsOverride: String? = null,
) : SshClientAdapter {
    override suspend fun connect(
        request: ConnectionRequest,
        verifier: ServerHostKeyVerifier,
        initialSize: TerminalSize,
        onStage: (ConnectionStage) -> Unit,
    ): LiveSshSession {
        val config = SshClientConfig {
            host = request.profile.endpoint.hostname
            port = request.profile.endpoint.port
            preferPasswordAuth = true
            hostKeyAlgorithmsOverride?.let { hostKeyAlgorithms = it }
            hostKeyVerifier = object : HostKeyVerifier {
                override suspend fun verify(key: PublicKey): Boolean =
                    verifier.verify(key.type, key.encoded)
            }
        }
        val client = SshClient(config)
        var session: SshSession? = null

        try {
            when (val result = client.connect()) {
                ConnectResult.Success -> Unit
                is ConnectResult.HostKeyRejected ->
                    throw SshAdapterException(SessionError.HostKeyRejected(null))
                is ConnectResult.AlgorithmMismatch ->
                    throw SshAdapterException(SessionError.ProtocolMismatch)
                is ConnectResult.ProtocolError ->
                    throw SshAdapterException(SessionError.ConnectionFailed, result.cause)
                is ConnectResult.TransportError ->
                    throw SshAdapterException(
                        transportSessionError(result.cause),
                        result.cause,
                    )
            }

            onStage(ConnectionStage.AUTHENTICATING)
            val authResult = authenticate(client, request)
            when (authResult) {
                AuthResult.Success -> Unit
                is AuthResult.Failure ->
                    throw SshAdapterException(SessionError.AuthenticationRejected)
                is AuthResult.Error -> {
                    val error = when (request.credential) {
                        is SessionCredential.PrivateKey -> SessionError.UnsupportedPrivateKey
                        is SessionCredential.Password -> transportSessionError(authResult.cause)
                    }
                    throw SshAdapterException(error, authResult.cause)
                }
            }

            onStage(ConnectionStage.STARTING_SHELL)
            session = client.openSession()
                ?: throw SshAdapterException(SessionError.ConnectionFailed)
            if (
                !session.requestPty(
                    terminalType = "xterm-256color",
                    widthChars = initialSize.columns,
                    heightRows = initialSize.rows,
                )
            ) {
                throw SshAdapterException(SessionError.PtyRejected)
            }
            if (!session.requestShell()) {
                throw SshAdapterException(SessionError.ShellRejected)
            }

            return ConnectBotLiveSession(client, session)
        } catch (cancelled: CancellationException) {
            cleanUp(client, session)
            throw cancelled
        } catch (expected: SshAdapterException) {
            cleanUp(client, session)
            throw expected
        } catch (unexpected: Exception) {
            cleanUp(client, session)
            throw SshAdapterException(transportSessionError(unexpected), unexpected)
        } finally {
            request.credential.clear()
        }
    }

    private suspend fun authenticate(
        client: SshClient,
        request: ConnectionRequest,
    ): AuthResult = when (val credential = request.credential) {
        is SessionCredential.Password ->
            client.authenticatePassword(
                request.profile.username,
                String(credential.characters),
            )

        is SessionCredential.PrivateKey ->
            client.authenticatePublicKey(
                username = request.profile.username,
                privateKeyData = credential.keyBytes,
                passphrase = credential.passphrase?.let(::String),
            )
    }

    private suspend fun cleanUp(
        client: SshClient,
        session: SshSession?,
    ) {
        runCatching { session?.close() }
        runCatching { client.disconnect() }
    }
}

internal fun transportSessionError(failure: Throwable?): SessionError {
    var current = failure
    repeat(MAX_CAUSE_DEPTH) {
        when (current) {
            is UnknownHostException,
            is UnresolvedAddressException,
            -> return SessionError.DnsResolutionFailed

            is SocketTimeoutException -> return SessionError.ConnectionTimedOut
            is ConnectException -> return SessionError.ConnectionRefused
            is NoRouteToHostException -> return SessionError.NetworkUnreachable
        }
        val next = current?.cause
        if (next === current) return SessionError.ConnectionFailed
        current = next
    }
    return SessionError.ConnectionFailed
}

private const val MAX_CAUSE_DEPTH = 8

private class ConnectBotLiveSession(
    private val client: SshClient,
    private val session: SshSession,
) : LiveSshSession {
    override val output: ReceiveChannel<ByteArray> = session.stdout
    override val disconnects: Flow<Unit> = client.disconnectedFlow.map { }

    override suspend fun send(bytes: ByteArray) = session.write(bytes)

    override suspend fun resize(size: TerminalSize): Boolean =
        session.resizeTerminal(
            widthChars = size.columns,
            heightRows = size.rows,
            widthPixels = 0,
            heightPixels = 0,
        )

    override suspend fun disconnect() {
        runCatching { session.close() }
        client.disconnect()
    }
}
