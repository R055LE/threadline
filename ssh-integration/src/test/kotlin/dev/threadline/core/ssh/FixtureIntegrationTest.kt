package dev.threadline.core.ssh

import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.TerminalSize
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FixtureIntegrationTest {
    @Test
    fun `password auth opens shell and proves PTY resize`() = runBlocking {
        val fixture = fixtureConfiguration()
        val request = ConnectionRequest(
            profile = fixture.profile,
            credential = SessionCredential.Password.from(
                requiredEnvironment("THREADLINE_FIXTURE_PASSWORD").toCharArray(),
            ),
        )

        withSession(fixture, request) { session ->
            assertTrue(session.resize(TerminalSize(rows = 41, columns = 101)))
            session.send("stty size; printf 'threadline-%s-ok\\n' password\n".toByteArray())
            val output = readUntil(session, "threadline-password-ok")
            assertTrue(output.contains("41 101"))
        }
    }

    @Test
    fun `Ed25519 key auth opens shell and exchanges bytes`() = runBlocking {
        val fixture = fixtureConfiguration()
        val keyBytes = Files.readAllBytes(
            Path.of(requiredEnvironment("THREADLINE_FIXTURE_PRIVATE_KEY")),
        )
        val request = try {
            ConnectionRequest(
                profile = fixture.profile,
                credential = SessionCredential.PrivateKey.from(keyBytes, passphrase = null),
            )
        } finally {
            keyBytes.fill(0)
        }

        withSession(fixture, request) { session ->
            session.send("printf 'threadline-%s-ok\\n' key\n".toByteArray())
            assertTrue(readUntil(session, "threadline-key-ok").contains("threadline-key-ok"))
        }
    }

    private suspend fun withSession(
        fixture: FixtureConfiguration,
        request: ConnectionRequest,
        block: suspend (LiveSshSession) -> Unit,
    ) {
        val session = ConnectBotSshClientAdapter().connect(
            request = request,
            verifier = ServerHostKeyVerifier { _, encoded ->
                fingerprint(encoded) == fixture.fingerprint
            },
            initialSize = TerminalSize(rows = 24, columns = 80),
            onStage = {},
        )
        try {
            block(session)
        } finally {
            session.disconnect()
        }
    }

    private suspend fun readUntil(
        session: LiveSshSession,
        token: String,
    ): String = withTimeout(10_000) {
        val output = ByteArrayOutputStream()
        while (true) {
            output.write(session.output.receive())
            val text = output.toString(Charsets.UTF_8.name())
            if (token in text) return@withTimeout text
        }
        error("unreachable")
    }

    private fun fixtureConfiguration(): FixtureConfiguration {
        val host = requiredEnvironment("THREADLINE_FIXTURE_HOST")
        val port = requiredEnvironment("THREADLINE_FIXTURE_PORT").toInt()
        val username = requiredEnvironment("THREADLINE_FIXTURE_USER")
        return FixtureConfiguration(
            profile = HostProfile(
                displayName = "Integration fixture",
                endpoint = HostEndpoint(host, port),
                username = username,
            ),
            fingerprint = requiredEnvironment("THREADLINE_FIXTURE_FINGERPRINT"),
        )
    }

    private fun requiredEnvironment(name: String): String {
        val value = System.getenv(name)
        assumeTrue("$name is not configured; skipping Docker integration test", !value.isNullOrBlank())
        return requireNotNull(value)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun fingerprint(encoded: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return "SHA256:${Base64.Default.encode(digest).trimEnd('=')}"
    }
}

private data class FixtureConfiguration(
    val profile: HostProfile,
    val fingerprint: String,
)
