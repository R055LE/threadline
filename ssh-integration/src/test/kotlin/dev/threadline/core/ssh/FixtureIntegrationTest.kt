package dev.threadline.core.ssh

import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.TerminalSize
import dev.threadline.core.shell.BashShellIntegration
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.ProtocolStreamItem
import dev.threadline.core.shell.SessionNonce
import dev.threadline.core.shell.ShellLifecycleEvent
import dev.threadline.core.shell.ThreadlineOscParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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

    @Test
    fun `structured commands retain shell state and report lifecycle`() = runBlocking {
        val fixture = fixtureConfiguration()
        val request = ConnectionRequest(
            profile = fixture.profile,
            credential = SessionCredential.Password.from(
                requiredEnvironment("THREADLINE_FIXTURE_PASSWORD").toCharArray(),
            ),
        )
        val nonce = SessionNonce("0123456789abcdef0123456789abcdef")
        val integration = BashShellIntegration(nonce)
        val parser = ThreadlineOscParser(nonce)

        withSession(fixture, request) { session ->
            val bootstrapId = CommandId("bootstrap-probe")
            session.send(integration.bootstrap(bootstrapId))
            assertCommandCompleted(session, parser, bootstrapId, expectedExit = 0)

            val cdId = CommandId("change-directory")
            session.send(integration.invocation(cdId, "cd /tmp"))
            val changedDirectory = assertCommandCompleted(
                session,
                parser,
                cdId,
                expectedExit = 0,
            )
            assertEquals("/tmp", changedDirectory.currentDirectory)

            val exportId = CommandId("export-variable")
            session.send(integration.invocation(exportId, "export THREADLINE_PHASE_ONE=retained"))
            assertCommandCompleted(session, parser, exportId, expectedExit = 0)

            val stateId = CommandId("read-persistent-state")
            session.send(
                integration.invocation(
                    stateId,
                    "printf 'state=%s pwd=%s\\n' \"\$THREADLINE_PHASE_ONE\" \"\$PWD\"",
                ),
            )
            val state = assertCommandCompleted(session, parser, stateId, expectedExit = 0)
            assertTrue(state.output.contains("state=retained pwd=/tmp"))

            val failureId = CommandId("failure-status")
            session.send(integration.invocation(failureId, "false"))
            assertCommandCompleted(session, parser, failureId, expectedExit = 1)

            val multilineId = CommandId("multiline-command")
            session.send(
                integration.invocation(
                    multilineId,
                    "value='two lines'\nprintf 'multiline=%s\\n' \"\$value\"",
                ),
            )
            val multiline = assertCommandCompleted(
                session,
                parser,
                multilineId,
                expectedExit = 0,
            )
            assertTrue(multiline.output.contains("multiline=two lines"))
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

    private suspend fun assertCommandCompleted(
        session: LiveSshSession,
        parser: ThreadlineOscParser,
        commandId: CommandId,
        expectedExit: Int,
    ): StructuredCommandResult = withTimeout(10_000) {
        val output = ByteArrayOutputStream()
        var collectingOutput = false
        var sawStart = false
        var sawOutputStart = false

        while (true) {
            val scan = parser.consume(session.output.receive())
            scan.items.forEach { item ->
                when (item) {
                    is ProtocolStreamItem.TranscriptBytes -> {
                        if (collectingOutput) output.write(item.bytes)
                    }

                    is ProtocolStreamItem.Lifecycle -> when (val event = item.event) {
                        is ShellLifecycleEvent.CommandStarted -> {
                            if (event.commandId == commandId) sawStart = true
                        }

                        is ShellLifecycleEvent.CommandOutputStarted -> {
                            if (event.commandId == commandId) {
                                assertTrue(sawStart)
                                sawOutputStart = true
                                collectingOutput = true
                            }
                        }

                        is ShellLifecycleEvent.CommandEnded -> {
                            if (event.commandId == commandId) {
                                assertTrue(sawStart)
                                assertTrue(sawOutputStart)
                                assertEquals(expectedExit, event.exitStatus)
                                return@withTimeout StructuredCommandResult(
                                    output = output.toString(Charsets.UTF_8.name()),
                                    currentDirectory = event.currentDirectory,
                                )
                            }
                        }
                    }
                }
            }
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

private data class StructuredCommandResult(
    val output: String,
    val currentDirectory: String,
)
