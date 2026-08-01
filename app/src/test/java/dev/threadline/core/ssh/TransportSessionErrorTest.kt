package dev.threadline.core.ssh

import dev.threadline.core.model.SessionError
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportSessionErrorTest {
    @Test
    fun mapsKnownNetworkFailuresWithoutInspectingMessages() {
        assertEquals(
            SessionError.DnsResolutionFailed,
            transportSessionError(UnknownHostException("private.example")),
        )
        assertEquals(
            SessionError.DnsResolutionFailed,
            transportSessionError(UnresolvedAddressException()),
        )
        assertEquals(
            SessionError.ConnectionTimedOut,
            transportSessionError(SocketTimeoutException("private timeout detail")),
        )
        assertEquals(
            SessionError.ConnectionRefused,
            transportSessionError(ConnectException("private refusal detail")),
        )
        assertEquals(
            SessionError.NetworkUnreachable,
            transportSessionError(NoRouteToHostException("private route detail")),
        )
    }

    @Test
    fun mapsWrappedKnownFailureAndBoundsUnknownCauseTraversal() {
        assertEquals(
            SessionError.ConnectionTimedOut,
            transportSessionError(IllegalStateException(SocketTimeoutException())),
        )
        assertEquals(
            SessionError.ConnectionFailed,
            transportSessionError(deepFailure(10)),
        )
        assertEquals(SessionError.ConnectionFailed, transportSessionError(null))
    }

    private fun deepFailure(depth: Int): Throwable {
        var failure: Throwable = UnknownHostException("too deep")
        repeat(depth) { failure = IllegalStateException(failure) }
        return failure
    }
}
