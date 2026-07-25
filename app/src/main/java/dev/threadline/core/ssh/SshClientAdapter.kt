package dev.threadline.core.ssh

import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.ConnectionStage
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.TerminalSize
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow

fun interface ServerHostKeyVerifier {
    suspend fun verify(
        algorithm: String,
        encoded: ByteArray,
    ): Boolean
}

interface LiveSshSession {
    val output: ReceiveChannel<ByteArray>
    val disconnects: Flow<Unit>

    suspend fun send(bytes: ByteArray)

    suspend fun resize(size: TerminalSize): Boolean

    suspend fun disconnect()
}

interface SshClientAdapter {
    suspend fun connect(
        request: ConnectionRequest,
        verifier: ServerHostKeyVerifier,
        initialSize: TerminalSize,
        onStage: (ConnectionStage) -> Unit,
    ): LiveSshSession
}

class SshAdapterException(
    val error: SessionError,
    cause: Throwable? = null,
) : Exception(error.userMessage, cause)
