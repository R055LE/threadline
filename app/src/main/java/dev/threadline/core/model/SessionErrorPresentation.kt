package dev.threadline.core.model

enum class SessionErrorAction {
    REVIEW_SERVER,
    REVIEW_CREDENTIALS,
    REVIEW_PRIVATE_KEY,
    OPEN_NOTIFICATION_SETTINGS,
}

data class SessionErrorPresentation(
    val title: String,
    val message: String,
    val recovery: String,
    val action: SessionErrorAction? = null,
)

fun SessionError.presentation(): SessionErrorPresentation = when (this) {
    is SessionError.HostKeyRejected -> SessionErrorPresentation(
        title = "Server identity not accepted",
        message = userMessage,
        recovery = "Reconnect when you are ready to verify the server fingerprint through " +
            "a trusted channel.",
    )

    is SessionError.HostKeyChanged -> SessionErrorPresentation(
        title = "Server identity changed",
        message = userMessage,
        recovery = "Do not reconnect until you have verified why the server key changed.",
    )

    SessionError.KnownHostStorageFailed -> SessionErrorPresentation(
        title = "Server trust unavailable",
        message = userMessage,
        recovery = "Do not bypass identity verification. Try again, then open Diagnostics if " +
            "the secure trust store remains unavailable.",
    )

    SessionError.AuthenticationRejected -> SessionErrorPresentation(
        title = "Authentication failed",
        message = userMessage,
        recovery = "Re-enter the username and session credential, or select the intended key. " +
            "Passwords and passphrases are cleared after every attempt.",
        action = SessionErrorAction.REVIEW_CREDENTIALS,
    )

    SessionError.UnsupportedPrivateKey -> SessionErrorPresentation(
        title = "Private key could not be used",
        message = userMessage,
        recovery = "Select the intended OpenSSH key and re-enter its passphrase. If it still " +
            "fails, verify the key format outside Threadline.",
        action = SessionErrorAction.REVIEW_PRIVATE_KEY,
    )

    SessionError.PtyRejected -> SessionErrorPresentation(
        title = "Remote PTY unavailable",
        message = userMessage,
        recovery = "Check whether the SSH account and server allow PTY allocation.",
    )

    SessionError.ShellRejected -> SessionErrorPresentation(
        title = "Remote shell unavailable",
        message = userMessage,
        recovery = "Check whether the SSH account has an allowed login shell.",
    )

    SessionError.PtyResizeRejected -> SessionErrorPresentation(
        title = "Terminal resize failed",
        message = userMessage,
        recovery = "Reconnect the session. If this repeats, include sanitized Diagnostics " +
            "when reporting the server behavior.",
    )

    SessionError.DnsResolutionFailed -> SessionErrorPresentation(
        title = "Server name not found",
        message = userMessage,
        recovery = "Check the hostname, network connection, DNS, and any required VPN.",
        action = SessionErrorAction.REVIEW_SERVER,
    )

    SessionError.ConnectionTimedOut -> SessionErrorPresentation(
        title = "Connection timed out",
        message = userMessage,
        recovery = "Check the hostname, port, network or VPN, and whether a firewall allows SSH.",
        action = SessionErrorAction.REVIEW_SERVER,
    )

    SessionError.ConnectionRefused -> SessionErrorPresentation(
        title = "Connection refused",
        message = userMessage,
        recovery = "Check the SSH port and confirm the SSH service is running on the server.",
        action = SessionErrorAction.REVIEW_SERVER,
    )

    SessionError.NetworkUnreachable -> SessionErrorPresentation(
        title = "Server network unreachable",
        message = userMessage,
        recovery = "Check the device network, VPN, and route to the server.",
        action = SessionErrorAction.REVIEW_SERVER,
    )

    SessionError.ConnectionFailed -> SessionErrorPresentation(
        title = "Connection failed",
        message = userMessage,
        recovery = "Check the hostname, port, network or VPN, then try the connection again.",
        action = SessionErrorAction.REVIEW_SERVER,
    )

    SessionError.ProtocolMismatch -> SessionErrorPresentation(
        title = "SSH compatibility problem",
        message = userMessage,
        recovery = "Confirm the server offers modern SSH host-key, key-exchange, cipher, and " +
            "message-authentication algorithms.",
    )

    SessionError.ConnectionLost -> SessionErrorPresentation(
        title = "Connection lost",
        message = userMessage,
        recovery = "Check the device network, VPN, and server status before reconnecting.",
        action = SessionErrorAction.REVIEW_SERVER,
    )

    SessionError.InputBackpressure -> SessionErrorPresentation(
        title = "Session input stopped",
        message = userMessage,
        recovery = "Reconnect before sending more input. If this followed a large paste, retry " +
            "with smaller chunks.",
    )

    SessionError.TerminalRendererFailed -> SessionErrorPresentation(
        title = "Terminal rendering failed",
        message = userMessage,
        recovery = "Reconnect the session. If this repeats, share sanitized Diagnostics with " +
            "the failing steps.",
    )

    SessionError.NotificationPermissionRequired -> SessionErrorPresentation(
        title = "Notification access required",
        message = userMessage,
        recovery = "Allow Threadline notifications in Android settings, then connect again.",
        action = SessionErrorAction.OPEN_NOTIFICATION_SETTINGS,
    )

    SessionError.ServiceStartFailed -> SessionErrorPresentation(
        title = "Background session could not start",
        message = userMessage,
        recovery = "Check Threadline notification access, then close and reopen the app before " +
            "trying again.",
        action = SessionErrorAction.OPEN_NOTIFICATION_SETTINGS,
    )
}
