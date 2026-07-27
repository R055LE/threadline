package dev.threadline.core.model

data class HostEndpoint(
    val hostname: String,
    val port: Int,
) {
    init {
        require(hostname.isNotBlank())
        require(port in 1..65535)
    }

    val storageKey: String = "${hostname.trim().lowercase()}:$port"
}

data class HostProfile(
    val displayName: String,
    val endpoint: HostEndpoint,
    val username: String,
) {
    init {
        require(displayName.isNotBlank())
        require(username.isNotBlank())
    }
}

sealed class SessionCredential {
    internal abstract fun clear()

    class Password private constructor(
        internal val characters: CharArray,
    ) : SessionCredential() {
        override fun clear() = characters.fill('\u0000')

        override fun toString(): String = "Password(**redacted**)"

        companion object {
            fun from(characters: CharArray): Password = Password(characters.copyOf())
        }
    }

    class PrivateKey private constructor(
        internal val keyBytes: ByteArray,
        internal val passphrase: CharArray?,
    ) : SessionCredential() {
        override fun clear() {
            keyBytes.fill(0)
            passphrase?.fill('\u0000')
        }

        override fun toString(): String = "PrivateKey(**redacted**)"

        companion object {
            fun from(
                keyBytes: ByteArray,
                passphrase: CharArray?,
            ): PrivateKey = PrivateKey(
                keyBytes = keyBytes.copyOf(),
                passphrase = passphrase?.copyOf(),
            )
        }
    }
}

class ConnectionRequest(
    val profile: HostProfile,
    val credential: SessionCredential,
) {
    override fun toString(): String = "ConnectionRequest(profile=$profile, credential=$credential)"
}

data class TerminalSize(
    val rows: Int,
    val columns: Int,
) {
    init {
        require(rows > 0)
        require(columns > 0)
    }
}

data class HostKeyPrompt(
    val endpoint: HostEndpoint,
    val algorithm: String,
    val fingerprint: String,
)

enum class HostKeyDecision {
    ACCEPT_AND_SAVE,
    REJECT,
}

enum class ConnectionStage {
    CONNECTING,
    AUTHENTICATING,
    STARTING_SHELL,
}

sealed interface SessionError {
    val userMessage: String

    data class HostKeyRejected(
        val fingerprint: String?,
    ) : SessionError {
        override val userMessage: String = "The server identity was not accepted."
    }

    data class HostKeyChanged(
        val previousFingerprint: String,
        val presentedFingerprint: String,
    ) : SessionError {
        override val userMessage: String =
            "The server host key changed. The connection was blocked."
    }

    data object AuthenticationRejected : SessionError {
        override val userMessage: String = "The server rejected these credentials."
    }

    data object UnsupportedPrivateKey : SessionError {
        override val userMessage: String =
            "The private key could not be read. Check its format or passphrase."
    }

    data object PtyRejected : SessionError {
        override val userMessage: String = "The server refused to create a PTY."
    }

    data object ShellRejected : SessionError {
        override val userMessage: String = "The server refused to start a shell."
    }

    data object PtyResizeRejected : SessionError {
        override val userMessage: String = "The server refused the terminal resize."
    }

    data object ConnectionFailed : SessionError {
        override val userMessage: String = "Could not establish the SSH connection."
    }

    data object ProtocolMismatch : SessionError {
        override val userMessage: String =
            "The server and client could not agree on secure SSH algorithms."
    }

    data object ConnectionLost : SessionError {
        override val userMessage: String = "The SSH connection was lost."
    }

    data object InputBackpressure : SessionError {
        override val userMessage: String =
            "Local input was stopped because the SSH send queue filled."
    }

    data object NotificationPermissionRequired : SessionError {
        override val userMessage: String =
            "Notification permission is required while an SSH session is active."
    }

    data object ServiceStartFailed : SessionError {
        override val userMessage: String =
            "Android would not start the background SSH session."
    }
}

sealed interface SessionState {
    data object Disconnected : SessionState

    data class Connecting(
        val displayName: String,
        val stage: ConnectionStage,
    ) : SessionState

    data class AwaitingHostKey(
        val displayName: String,
        val prompt: HostKeyPrompt,
    ) : SessionState

    data class Connected(
        val displayName: String,
        val terminalSize: TerminalSize,
    ) : SessionState

    data class Disconnecting(
        val displayName: String?,
    ) : SessionState

    data class Failed(
        val error: SessionError,
    ) : SessionState
}
