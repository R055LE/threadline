package dev.threadline.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionErrorPresentationTest {
    @Test
    fun networkAndAuthenticationFailuresExposeSpecificRecoveryActions() {
        assertEquals(
            SessionErrorAction.REVIEW_SERVER,
            SessionError.DnsResolutionFailed.presentation().action,
        )
        assertEquals(
            SessionErrorAction.REVIEW_SERVER,
            SessionError.ConnectionTimedOut.presentation().action,
        )
        assertEquals(
            SessionErrorAction.REVIEW_SERVER,
            SessionError.ConnectionRefused.presentation().action,
        )
        assertEquals(
            SessionErrorAction.REVIEW_SERVER,
            SessionError.NetworkUnreachable.presentation().action,
        )
        assertEquals(
            SessionErrorAction.REVIEW_CREDENTIALS,
            SessionError.AuthenticationRejected.presentation().action,
        )
        assertEquals(
            SessionErrorAction.REVIEW_PRIVATE_KEY,
            SessionError.UnsupportedPrivateKey.presentation().action,
        )
        assertEquals(
            SessionErrorAction.OPEN_NOTIFICATION_SETTINGS,
            SessionError.NotificationPermissionRequired.presentation().action,
        )
    }

    @Test
    fun everyFailureHasDistinctVisibleTitleMessageAndRecoveryGuidance() {
        allErrors().forEach { error ->
            val presentation = error.presentation()
            assertTrue(presentation.title.isNotBlank())
            assertEquals(error.userMessage, presentation.message)
            assertTrue(presentation.recovery.isNotBlank())
            assertFalse(presentation.title == presentation.message)
        }
    }

    @Test
    fun changedHostKeyPresentationDoesNotDuplicateSensitiveFields() {
        val error = SessionError.HostKeyChanged(
            endpoint = HostEndpoint("private.example", 22),
            previousFingerprint = "private-old",
            presentedFingerprint = "private-new",
        )

        val presentation = error.presentation()

        assertNull(presentation.action)
        assertFalse(presentation.title.contains("private"))
        assertFalse(presentation.message.contains("private"))
        assertFalse(presentation.recovery.contains("private"))
    }

    private fun allErrors(): List<SessionError> = listOf(
        SessionError.HostKeyRejected("private-fingerprint"),
        SessionError.HostKeyChanged(
            endpoint = HostEndpoint("private.example", 22),
            previousFingerprint = "private-old",
            presentedFingerprint = "private-new",
        ),
        SessionError.KnownHostStorageFailed,
        SessionError.AuthenticationRejected,
        SessionError.UnsupportedPrivateKey,
        SessionError.PtyRejected,
        SessionError.ShellRejected,
        SessionError.PtyResizeRejected,
        SessionError.ConnectionFailed,
        SessionError.DnsResolutionFailed,
        SessionError.ConnectionTimedOut,
        SessionError.ConnectionRefused,
        SessionError.NetworkUnreachable,
        SessionError.ProtocolMismatch,
        SessionError.ConnectionLost,
        SessionError.InputBackpressure,
        SessionError.TerminalRendererFailed,
        SessionError.NotificationPermissionRequired,
        SessionError.ServiceStartFailed,
    )
}
