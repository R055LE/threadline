package dev.threadline.core.session

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.SessionState
import dev.threadline.core.model.TerminalSize
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.CommandSubmissionRejection
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.CompletedCommand
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.ssh.AndroidSshCryptoProvider
import dev.threadline.core.ssh.ConnectBotSshClientAdapter
import dev.threadline.core.ssh.HostKeyAlgorithmPolicy
import dev.threadline.core.terminal.TerminalSink
import dev.threadline.core.transcript.AnsiColor
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.StyledRun
import dev.threadline.core.transcript.TranscriptStyle
import dev.threadline.data.db.ThreadlineDatabase
import dev.threadline.data.host.RoomKnownHostStore
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidStructuredShellIntegrationTest {
    @Test
    fun productionSessionRetainsStateAndReportsCommandLifecycle() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString(PASSWORD_ARGUMENT)
        assumeTrue(
            "No runtime fixture password supplied; skipping Android SSH integration test",
            !password.isNullOrEmpty(),
        )
        val fixturePassword = requireNotNull(password)
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyPreferences = targetContext.getSharedPreferences(
            PRODUCTION_LEGACY_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        legacyPreferences.edit().clear().commit()
        val database = Room.inMemoryDatabaseBuilder(
            targetContext,
            ThreadlineDatabase::class.java,
        ).build()
        val knownHostStore = RoomKnownHostStore(
            dao = database.knownHosts(),
            legacyPreferences = legacyPreferences,
        )

        val hostKeyAlgorithms = HostKeyAlgorithmPolicy.overrideWhenEd25519Unavailable(
            AndroidSshCryptoProvider.install(),
        )
        val manager = SessionManager(
            adapter = ConnectBotSshClientAdapter(hostKeyAlgorithms),
            knownHostStore = knownHostStore,
            terminal = NoOpTerminal,
        )
        val profile = HostProfile(
            displayName = "Android integration fixture",
            endpoint = HostEndpoint(
                hostname = arguments.getString(HOST_ARGUMENT) ?: DEFAULT_HOST,
                port = arguments.getString(PORT_ARGUMENT)?.toIntOrNull() ?: DEFAULT_PORT,
            ),
            username = arguments.getString(USER_ARGUMENT) ?: DEFAULT_USER,
        )
        fun request() = ConnectionRequest(
            profile = profile,
            credential = SessionCredential.Password.from(fixturePassword.toCharArray()),
        )

        try {
            assertTrue(manager.prepareConnection(request()))
            assertTrue(manager.connectPrepared())
            val prompt = withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                manager.state.filterIsInstance<SessionState.AwaitingHostKey>().first()
            }
            assertEquals("ssh-ed25519", prompt.prompt.algorithm)
            assertTrue(manager.resolveHostKey(HostKeyDecision.ACCEPT_AND_SAVE))
            withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                manager.state.filterIsInstance<SessionState.Connected>().first()
            }
            val initialReady = withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                manager.structuredState.filterIsInstance<StructuredShellState.Ready>().first()
            }
            assertTrue(initialReady.currentDirectory.isNotEmpty())

            val cdSubmission = accepted(manager.submitCommand("cd /tmp"))
            assertEquals(
                CommandSubmissionResult.Rejected(
                    CommandSubmissionRejection.COMMAND_ALREADY_RUNNING,
                ),
                manager.submitCommand("printf duplicate"),
            )
            val changedDirectory = awaitCompletion(manager, cdSubmission.commandId)
            assertEquals(0, changedDirectory.exitStatus)
            assertEquals("/tmp", changedDirectory.currentDirectory)

            assertSuccessful(
                manager,
                "export THREADLINE_ANDROID_PHASE_ONE=retained",
            )
            assertSuccessful(
                manager,
                "test \"\$THREADLINE_ANDROID_PHASE_ONE\" = retained && " +
                    "test \"\$PWD\" = /tmp",
            )

            val failed = execute(manager, "false")
            assertEquals(1, failed.exitStatus)
            assertEquals("/tmp", failed.currentDirectory)

            assertSuccessful(
                manager,
                "value='two lines'\ntest \"\$value\" = 'two lines'",
            )

            val renderedSubmission = accepted(
                manager.submitCommand(
                    "printf '\\033[31mred\\033[0m\\n'; " +
                        "printf '\\rstep 1'; printf '\\rstep 2'; printf '\\n'; " +
                        "printf 'unicode: π 日本語 🚀\\n'",
                ),
            )
            awaitCompletion(manager, renderedSubmission.commandId)
            val renderedTurn = requireNotNull(
                manager.transcriptState.value.turns
                    .firstOrNull { it.id == renderedSubmission.commandId },
            )
            assertEquals(CommandStatus.SUCCEEDED, renderedTurn.status)
            assertEquals(
                "red\nstep 2\nunicode: π 日本語 🚀\n",
                renderedTurn.output.plainText,
            )
            assertEquals(
                listOf(
                    StyledRun(
                        start = 0,
                        endExclusive = 3,
                        style = TranscriptStyle(
                            foreground = AnsiColor.Indexed(1),
                        ),
                    ),
                ),
                renderedTurn.output.styledRuns,
            )
            assertTrue(!renderedTurn.output.approximate)
            assertTrue(!renderedTurn.output.truncated)

            executeInteractive(
                manager = manager,
                command = "less /etc/services",
                exitInput = "q".encodeToByteArray(),
                description = "less",
            )
            executeInteractive(
                manager = manager,
                command = "top",
                exitInput = "q".encodeToByteArray(),
                description = "top",
            )
            executeInteractive(
                manager = manager,
                command = "vim -Nu NONE -n -i NONE /tmp/threadline-vim-proof",
                exitInput = "\u001b:q!\r".encodeToByteArray(),
                description = "vim",
            )

            val volumeSubmission = accepted(
                manager.submitCommand("yes 0123456789 | head -n 20000"),
            )
            awaitCompletion(
                manager,
                volumeSubmission.commandId,
                "large-output command",
            )
            val volumeTurn = requireNotNull(
                manager.transcriptState.value.turns
                    .firstOrNull { it.id == volumeSubmission.commandId },
            )
            assertEquals(CommandStatus.SUCCEEDED, volumeTurn.status)
            assertTrue(volumeTurn.output.truncated)
            assertEquals(
                MAXIMUM_RENDERED_CHARACTERS,
                volumeTurn.output.plainText.length,
            )
            assertTrue(volumeTurn.output.byteCount > volumeTurn.output.plainText.length)
            assertTrue(volumeTurn.output.plainText.endsWith("0123456789\n"))

            val cancellationSubmission = accepted(
                manager.submitCommand("sleep 30"),
            )
            withTimeout(COMMAND_TIMEOUT_MILLIS) {
                manager.structuredState
                    .filterIsInstance<StructuredShellState.Running>()
                    .first { it.activeCommand.id == cancellationSubmission.commandId }
            }
            manager.sendControlC()
            val interrupted = awaitCompletion(
                manager,
                cancellationSubmission.commandId,
                "interrupted command",
            )
            assertEquals(130, interrupted.exitStatus)
            val interruptedTurn = requireNotNull(
                manager.transcriptState.value.turns
                    .firstOrNull { it.id == cancellationSubmission.commandId },
            )
            assertEquals(CommandStatus.INTERRUPTED, interruptedTurn.status)
            assertTrue(interruptedTurn.stopRequestedAtMillis != null)

            manager.disconnect()
            withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                manager.state.first { it is SessionState.Disconnected }
            }
            val persisted = requireNotNull(knownHostStore.find(profile.endpoint))
            assertEquals("ssh-ed25519", persisted.key.algorithm)
            assertTrue(persisted.lastSeenAtMillis >= persisted.firstSeenAtMillis)

            val reconnectManager = SessionManager(
                adapter = ConnectBotSshClientAdapter(hostKeyAlgorithms),
                knownHostStore = knownHostStore,
                terminal = NoOpTerminal,
            )
            try {
                assertTrue(reconnectManager.prepareConnection(request()))
                assertTrue(reconnectManager.connectPrepared())
                // No host-key decision is supplied. Reaching Connected proves
                // the real server key was read back from Room and trusted.
                withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                    reconnectManager.state.filterIsInstance<SessionState.Connected>().first()
                }
            } finally {
                reconnectManager.disconnect()
                withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                    reconnectManager.state.first { it is SessionState.Disconnected }
                }
            }
            Unit
        } finally {
            manager.disconnect()
            withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                manager.state.first { it is SessionState.Disconnected }
            }
            database.close()
            legacyPreferences.edit().clear().commit()
        }
    }

    private suspend fun executeInteractive(
        manager: SessionManager,
        command: String,
        exitInput: ByteArray,
        description: String,
    ) {
        val submission = accepted(manager.submitCommand(command))
        val suggestedTurn = withTimeout(COMMAND_TIMEOUT_MILLIS) {
            manager.transcriptState.first { transcript ->
                transcript.turns
                    .firstOrNull { it.id == submission.commandId }
                    ?.output
                    ?.interactiveHint != null
            }.turns.first { it.id == submission.commandId }
        }
        assertEquals(CommandStatus.RUNNING, suggestedTurn.status)
        assertTrue(suggestedTurn.output.interactiveHint != null)

        manager.send(exitInput)
        val completed = awaitCompletion(
            manager,
            submission.commandId,
            "interactive $description command",
        )
        assertEquals(0, completed.exitStatus)
    }

    private suspend fun assertSuccessful(
        manager: SessionManager,
        command: String,
    ) {
        val completed = execute(manager, command)
        assertEquals(0, completed.exitStatus)
        assertEquals("/tmp", completed.currentDirectory)
    }

    private suspend fun execute(
        manager: SessionManager,
        command: String,
    ): CompletedCommand {
        val submission = accepted(manager.submitCommand(command))
        return awaitCompletion(manager, submission.commandId)
    }

    private suspend fun awaitCompletion(
        manager: SessionManager,
        commandId: CommandId,
        description: String = commandId.value,
    ): CompletedCommand =
        withTimeoutOrNull(COMMAND_TIMEOUT_MILLIS) {
            manager.structuredState
                .filterIsInstance<StructuredShellState.Ready>()
                .first { it.lastCommand?.id == commandId }
                .lastCommand
        } ?: throw AssertionError("Timed out waiting for $description")

    private fun accepted(result: CommandSubmissionResult): CommandSubmissionResult.Accepted {
        assertTrue("Expected accepted command, got $result", result is CommandSubmissionResult.Accepted)
        return result as CommandSubmissionResult.Accepted
    }

    private companion object {
        const val PASSWORD_ARGUMENT = "threadlineFixturePassword"
        const val HOST_ARGUMENT = "threadlineFixtureHost"
        const val PORT_ARGUMENT = "threadlineFixturePort"
        const val USER_ARGUMENT = "threadlineFixtureUser"
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = 2_222
        const val DEFAULT_USER = "threadline"
        const val CONNECTION_TIMEOUT_MILLIS = 20_000L
        const val COMMAND_TIMEOUT_MILLIS = 20_000L
        const val MAXIMUM_RENDERED_CHARACTERS = 128 * 1024
        const val PRODUCTION_LEGACY_PREFERENCES = "android_structured_known_hosts"
    }
}

private object NoOpTerminal : TerminalSink {
    override val size = TerminalSize(rows = 24, columns = 80)

    override fun clear() = Unit

    override suspend fun receive(bytes: ByteArray) = Unit
}
