package dev.threadline.core.session

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.threadline.SessionRuntime
import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.SessionState
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.transcript.CommandStatus
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTranscriptCheckpointProcessDeathTest {
    // The fixture runner kills the preparing process, then verifies in a new one.
    @Test
    fun completedHistorySurvivesProcessDeathWithoutResumingConnection() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("threadlineCheckpointPhase")
        assumeTrue("Run through test-android-transcript-process-death.sh", phase != null)
        val ephemeral = arguments.getString("threadlineEphemeral") == "true"
        val manager = SessionRuntime.manager
        val history = SessionRuntime.transcriptHistory

        assertEquals(SessionState.Disconnected, manager.state.value)
        assertEquals(StructuredShellState.Inactive, manager.structuredState.value)
        assertTrue(manager.transcriptState.value.turns.isEmpty())

        if (phase == "verify") {
            val sessions = history.sessions.first()
            if (ephemeral) {
                assertTrue(sessions.isEmpty())
            } else {
                val saved = history.load(sessions.single().id)
                val turn = saved.turns.single().turn
                assertEquals("printf 'checkpoint-completed\\n'", turn.command)
                assertEquals("checkpoint-completed\n", turn.output.plainText)
                assertEquals(CommandStatus.SUCCEEDED, turn.status)
                assertTrue(turn.completedAtMillis != null)
            }
            history.clearAll()
            return@runBlocking
        }

        assertEquals("prepare", phase)
        history.clearAll()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyFile = File(context.filesDir, "threadline-fixture-private-key")
        val keyBytes = keyFile.readBytes()
        keyFile.delete()
        val credential = try {
            SessionCredential.PrivateKey.from(keyBytes, passphrase = null)
        } finally {
            keyBytes.fill(0)
        }
        try {
            assertTrue(
                manager.prepareConnection(
                    ConnectionRequest(
                        profile = HostProfile(
                            displayName = "Checkpoint fixture",
                            endpoint = HostEndpoint("10.0.2.2", 2222),
                            username = "threadline",
                        ),
                        credential = credential,
                        ephemeral = ephemeral,
                    ),
                ),
            )
            assertTrue(manager.connectPrepared())
            val connection = withTimeout(20_000) {
                manager.state.first {
                    it is SessionState.AwaitingHostKey || it is SessionState.Connected ||
                        it is SessionState.Failed
                }
            }
            if (connection is SessionState.AwaitingHostKey) {
                assertEquals(
                    arguments.getString("threadlineFixtureHostKeyFingerprint"),
                    connection.prompt.fingerprint,
                )
                assertTrue(manager.resolveHostKey(HostKeyDecision.ACCEPT_AND_SAVE))
            }
            withTimeout(20_000) {
                manager.state.filterIsInstance<SessionState.Connected>().first()
                manager.structuredState.filterIsInstance<StructuredShellState.Ready>().first()
            }
        } finally {
            credential.clear()
        }

        val completed = manager.submitCommand("printf 'checkpoint-completed\\n'")
            as CommandSubmissionResult.Accepted
        withTimeout(20_000) {
            manager.structuredState.filterIsInstance<StructuredShellState.Ready>()
                .first { it.lastCommand?.id == completed.commandId }
            if (!ephemeral) history.sessions.first { it.singleOrNull()?.turnCount == 1 }
        }
        val unfinished = manager.submitCommand("printf 'checkpoint-unfinished\\n'; sleep 120")
            as CommandSubmissionResult.Accepted
        withTimeout(20_000) {
            manager.transcriptState.first { transcript ->
                transcript.turns.firstOrNull { it.id == unfinished.commandId }
                    ?.output?.plainText == "checkpoint-unfinished\n"
            }
        }
        assertTrue(manager.state.value is SessionState.Connected)
        assertTrue(!manager.transcriptSaveFailed.value)
        val saved = history.sessions.first()
        assertEquals(if (ephemeral) 0 else 1, saved.size)
        if (!ephemeral) assertEquals(1, history.load(saved.single().id).turns.size)

        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("stream", "THREADLINE_CHECKPOINT_READY pid=${Process.myPid()}\n")
            },
        )
        // No disconnect or database close: the host must kill this live process.
        withTimeout(60_000) { awaitCancellation() }
    }
}
