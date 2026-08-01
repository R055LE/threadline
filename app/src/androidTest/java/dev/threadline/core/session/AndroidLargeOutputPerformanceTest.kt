package dev.threadline.core.session

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
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
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.CompletedCommand
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.ssh.AndroidSshCryptoProvider
import dev.threadline.core.ssh.ConnectBotSshClientAdapter
import dev.threadline.core.ssh.HostKeyAlgorithmPolicy
import dev.threadline.core.terminal.TerminalBridge
import dev.threadline.core.terminal.TerminalSink
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.data.db.ThreadlineDatabase
import dev.threadline.data.host.RoomKnownHostStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLargeOutputPerformanceTest {
    @Test
    fun largeOutputRemainsBoundedResponsiveAndReleasable() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString(PASSWORD_ARGUMENT)
        assumeTrue(
            "No runtime fixture password supplied; skipping Android performance test",
            !password.isNullOrEmpty(),
        )
        val fixturePassword = requireNotNull(password)
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyPreferences = targetContext.getSharedPreferences(
            LEGACY_PREFERENCES,
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
        val terminal = MeasuringTerminal(TerminalBridge())
        val manager = SessionManager(
            adapter = ConnectBotSshClientAdapter(
                HostKeyAlgorithmPolicy.overrideWhenEd25519Unavailable(
                    AndroidSshCryptoProvider.install(),
                ),
            ),
            knownHostStore = knownHostStore,
            terminal = terminal,
        )
        val profile = HostProfile(
            displayName = "Android performance fixture",
            endpoint = HostEndpoint(
                hostname = arguments.getString(HOST_ARGUMENT) ?: DEFAULT_HOST,
                port = arguments.getString(PORT_ARGUMENT)?.toIntOrNull() ?: DEFAULT_PORT,
            ),
            username = arguments.getString(USER_ARGUMENT) ?: DEFAULT_USER,
        )
        fun request() = ConnectionRequest(
            profile = profile,
            credential = SessionCredential.Password.from(fixturePassword.toCharArray()),
            ephemeral = true,
        )

        try {
            connect(manager, request(), expectHostKeyPrompt = true)
            val baselineHeapBytes = usedHeapBytesAfterGc()
            val baselinePssKilobytes = Debug.getPss()

            val mixed = assertMixedOutputWorkload(manager, terminal).also(::reportProgress)
            val longLine = assertLongLineWorkload(manager, terminal).also(::reportProgress)
            val progress = assertProgressWorkload(manager, terminal).also(::reportProgress)
            val interrupt = assertStreamingInterrupt(manager, terminal).also(::reportProgress)
            val followUp = assertResponsiveFollowUp(manager, terminal).also(::reportProgress)

            val retainedHeapBytes = usedHeapBytesAfterGc()
            val retainedHeapDelta = retainedHeapBytes - baselineHeapBytes
            val retainedPssKilobytes = Debug.getPss()
            assertTrue(
                "Retained large-output heap grew by ${retainedHeapDelta.toMebibytes()} MiB",
                retainedHeapDelta <= MAX_RETAINED_HEAP_DELTA_BYTES,
            )
            assertTrue(
                "A terminal receive call stalled for ${terminal.maximumReceiveMillis()} ms",
                terminal.maximumReceiveMillis() <= MAX_TERMINAL_RECEIVE_MILLIS,
            )

            manager.disconnect()
            awaitDisconnected(manager)
            connect(manager, request(), expectHostKeyPrompt = false)
            assertTrue(manager.transcriptState.value.turns.isEmpty())
            val resetHeapBytes = usedHeapBytesAfterGc()
            val resetHeapDelta = resetHeapBytes - baselineHeapBytes
            assertTrue(
                "A new session retained ${resetHeapDelta.toMebibytes()} MiB above baseline",
                resetHeapDelta <= MAX_RESET_HEAP_DELTA_BYTES,
            )

            Log.i(
                PERFORMANCE_LOG_TAG,
                buildString {
                    append("THREADLINE_PERF ")
                    append(listOf(mixed, longLine, progress, interrupt, followUp).joinToString(" "))
                    append(" baseline_heap_mib=${baselineHeapBytes.toMebibytes()}")
                    append(" retained_heap_mib=${retainedHeapBytes.toMebibytes()}")
                    append(" reset_heap_mib=${resetHeapBytes.toMebibytes()}")
                    append(" baseline_pss_mib=${baselinePssKilobytes.kilobytesToMebibytes()}")
                    append(" retained_pss_mib=${retainedPssKilobytes.kilobytesToMebibytes()}")
                    append(" max_terminal_receive_ms=${terminal.maximumReceiveMillis()}")
                },
            )
            Unit
        } finally {
            if (manager.state.value !is SessionState.Disconnected) {
                manager.disconnect()
                awaitDisconnected(manager)
            }
            database.close()
            legacyPreferences.edit().clear().commit()
        }
    }

    private suspend fun connect(
        manager: SessionManager,
        request: ConnectionRequest,
        expectHostKeyPrompt: Boolean,
    ) {
        assertTrue(manager.prepareConnection(request))
        assertTrue(manager.connectPrepared())
        if (expectHostKeyPrompt) {
            withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                manager.state.filterIsInstance<SessionState.AwaitingHostKey>().first()
            }
            assertTrue(manager.resolveHostKey(HostKeyDecision.ACCEPT_AND_SAVE))
        }
        withTimeout(CONNECTION_TIMEOUT_MILLIS) {
            manager.state.filterIsInstance<SessionState.Connected>().first()
        }
        withTimeout(CONNECTION_TIMEOUT_MILLIS) {
            manager.structuredState.filterIsInstance<StructuredShellState.Ready>().first()
        }
    }

    private suspend fun assertMixedOutputWorkload(
        manager: SessionManager,
        terminal: MeasuringTerminal,
    ): WorkloadMetric = coroutineScope {
        val terminalBytesBefore = terminal.totalBytes.get()
        val terminalChunksBefore = terminal.totalChunks.get()
        val submission = accepted(manager.submitCommand(MIXED_OUTPUT_COMMAND))
        val publications = AtomicInteger()
        val publicationJob = launch {
            manager.transcriptState.collect { transcript ->
                if (transcript.turns.any { it.id == submission.commandId }) {
                    publications.incrementAndGet()
                }
            }
        }
        val started = SystemClock.elapsedRealtime()
        val completed = awaitCompletion(manager, submission.commandId, "mixed 100,000-line output")
        val elapsed = SystemClock.elapsedRealtime() - started
        publicationJob.cancelAndJoin()
        val turn = requireNotNull(
            manager.transcriptState.value.turns.firstOrNull { it.id == submission.commandId },
        )
        assertEquals(CommandStatus.SUCCEEDED, turn.status)
        assertEquals(0, completed.exitStatus)
        assertTrue(turn.output.truncated)
        assertFalse(turn.output.approximate)
        assertEquals(MAXIMUM_RENDERED_CHARACTERS, turn.output.plainText.length)
        assertTrue(turn.output.plainText.endsWith("099999 lambda π 日本語\n"))
        assertTrue(turn.output.styledRuns.isNotEmpty())
        assertTrue(turn.output.byteCount >= MINIMUM_MIXED_OUTPUT_BYTES)
        assertWithin("mixed 100,000-line output", elapsed, MAX_MIXED_OUTPUT_MILLIS)
        val maximumPublications =
            (elapsed / TRANSCRIPT_PUBLICATION_INTERVAL_MILLIS).toInt() + PUBLICATION_SLACK
        assertTrue(
            "${publications.get()} transcript publications exceeded cadence bound $maximumPublications",
            publications.get() <= maximumPublications,
        )
        terminal.metricSince(
            "mixed",
            elapsed,
            terminalBytesBefore,
            terminalChunksBefore,
            publications.get(),
        )
    }

    private suspend fun assertLongLineWorkload(
        manager: SessionManager,
        terminal: MeasuringTerminal,
    ): WorkloadMetric = terminal.measure("long_line") {
        val (elapsed, completed, commandId) = execute(manager, LONG_LINE_COMMAND, "one-megabyte line")
        val turn = requireNotNull(manager.transcriptState.value.turns.firstOrNull { it.id == commandId })
        assertEquals(0, completed.exitStatus)
        assertTrue(turn.output.truncated)
        assertEquals(MAXIMUM_RENDERED_CHARACTERS, turn.output.plainText.length)
        assertTrue(turn.output.plainText.endsWith("\n"))
        assertTrue(turn.output.plainText.dropLast(1).all { it == 'x' })
        assertTrue(turn.output.byteCount >= ONE_MEBIBYTE)
        assertWithin("one-megabyte line", elapsed, MAX_LONG_LINE_MILLIS)
        elapsed
    }

    private suspend fun assertProgressWorkload(
        manager: SessionManager,
        terminal: MeasuringTerminal,
    ): WorkloadMetric = terminal.measure("progress") {
        val (elapsed, completed, commandId) = execute(
            manager,
            PROGRESS_OUTPUT_COMMAND,
            "50,000 progress rewrites",
        )
        val turn = requireNotNull(manager.transcriptState.value.turns.firstOrNull { it.id == commandId })
        assertEquals(0, completed.exitStatus)
        assertFalse(turn.output.truncated)
        assertFalse(turn.output.approximate)
        assertEquals("progress 49999\n", turn.output.plainText)
        assertTrue(turn.output.byteCount >= MINIMUM_PROGRESS_OUTPUT_BYTES)
        assertWithin("50,000 progress rewrites", elapsed, MAX_PROGRESS_OUTPUT_MILLIS)
        elapsed
    }

    private suspend fun assertStreamingInterrupt(
        manager: SessionManager,
        terminal: MeasuringTerminal,
    ): WorkloadMetric {
        val terminalBytesBefore = terminal.totalBytes.get()
        val terminalChunksBefore = terminal.totalChunks.get()
        val submission = accepted(manager.submitCommand(CONTINUOUS_OUTPUT_COMMAND))
        withTimeout(STREAM_START_TIMEOUT_MILLIS) {
            while (terminal.totalBytes.get() - terminalBytesBefore < STREAM_BEFORE_INTERRUPT_BYTES) {
                delay(10)
            }
        }
        val started = SystemClock.elapsedRealtime()
        manager.sendControlC()
        val completed = awaitCompletion(manager, submission.commandId, "continuous-output interrupt")
        val elapsed = SystemClock.elapsedRealtime() - started
        val turn = requireNotNull(
            manager.transcriptState.value.turns.firstOrNull { it.id == submission.commandId },
        )
        assertEquals(130, completed.exitStatus)
        assertEquals(CommandStatus.INTERRUPTED, turn.status)
        assertTrue(turn.output.truncated)
        assertEquals(MAXIMUM_RENDERED_CHARACTERS, turn.output.plainText.length)
        assertWithin("continuous-output interrupt", elapsed, MAX_INTERRUPT_MILLIS)
        return terminal.metricSince(
            "interrupt",
            elapsed,
            terminalBytesBefore,
            terminalChunksBefore,
        )
    }

    private suspend fun assertResponsiveFollowUp(
        manager: SessionManager,
        terminal: MeasuringTerminal,
    ): WorkloadMetric = terminal.measure("follow_up") {
        val (elapsed, completed, commandId) = execute(
            manager,
            "printf 'responsive-after-load\\n'",
            "post-load follow-up",
        )
        val turn = requireNotNull(manager.transcriptState.value.turns.firstOrNull { it.id == commandId })
        assertEquals(0, completed.exitStatus)
        assertEquals("responsive-after-load\n", turn.output.plainText)
        assertWithin("post-load follow-up", elapsed, MAX_FOLLOW_UP_MILLIS)
        elapsed
    }

    private suspend fun execute(
        manager: SessionManager,
        command: String,
        description: String,
    ): TimedCommand {
        val started = SystemClock.elapsedRealtime()
        val submission = accepted(manager.submitCommand(command))
        val completed = awaitCompletion(manager, submission.commandId, description)
        return TimedCommand(
            elapsedMillis = SystemClock.elapsedRealtime() - started,
            completed = completed,
            commandId = submission.commandId,
        )
    }

    private suspend fun awaitCompletion(
        manager: SessionManager,
        commandId: CommandId,
        description: String,
    ): CompletedCommand = withTimeoutOrNull(COMMAND_TIMEOUT_MILLIS) {
        manager.structuredState
            .filterIsInstance<StructuredShellState.Ready>()
            .first { it.lastCommand?.id == commandId }
            .lastCommand
    } ?: throw AssertionError("Timed out waiting for $description")

    private suspend fun awaitDisconnected(manager: SessionManager) {
        withTimeout(CONNECTION_TIMEOUT_MILLIS) {
            manager.state.first { it is SessionState.Disconnected }
        }
    }

    private fun accepted(result: CommandSubmissionResult): CommandSubmissionResult.Accepted {
        assertTrue("Expected accepted command, got $result", result is CommandSubmissionResult.Accepted)
        return result as CommandSubmissionResult.Accepted
    }

    private suspend fun usedHeapBytesAfterGc(): Long {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            delay(100)
        }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    private fun assertWithin(description: String, elapsedMillis: Long, maximumMillis: Long) {
        assertTrue(
            "$description took $elapsedMillis ms; limit is $maximumMillis ms",
            elapsedMillis <= maximumMillis,
        )
    }

    private fun reportProgress(metric: WorkloadMetric) {
        Log.i(PERFORMANCE_LOG_TAG, "THREADLINE_PERF_PROGRESS $metric")
    }

    private data class TimedCommand(
        val elapsedMillis: Long,
        val completed: CompletedCommand,
        val commandId: CommandId,
    )

    private data class WorkloadMetric(
        val name: String,
        val elapsedMillis: Long,
        val terminalBytes: Long,
        val terminalChunks: Long,
        val transcriptPublications: Int? = null,
    ) {
        override fun toString(): String = buildString {
            append("${name}_ms=$elapsedMillis")
            append(" ${name}_terminal_bytes=$terminalBytes")
            append(" ${name}_terminal_chunks=$terminalChunks")
            transcriptPublications?.let { append(" ${name}_publications=$it") }
        }
    }

    private class MeasuringTerminal(
        private val delegate: TerminalSink,
    ) : TerminalSink {
        val totalBytes = AtomicLong()
        val totalChunks = AtomicLong()
        private val maximumReceiveNanos = AtomicLong()

        override val size: TerminalSize
            get() = delegate.size

        override fun clear() = delegate.clear()

        override suspend fun receive(bytes: ByteArray) {
            val started = SystemClock.elapsedRealtimeNanos()
            delegate.receive(bytes)
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            totalBytes.addAndGet(bytes.size.toLong())
            totalChunks.incrementAndGet()
            maximumReceiveNanos.updateAndGet { current -> maxOf(current, elapsed) }
        }

        fun maximumReceiveMillis(): Long = maximumReceiveNanos.get() / NANOS_PER_MILLISECOND

        fun metricSince(
            name: String,
            elapsedMillis: Long,
            bytesBefore: Long,
            chunksBefore: Long,
            publications: Int? = null,
        ) = WorkloadMetric(
            name = name,
            elapsedMillis = elapsedMillis,
            terminalBytes = totalBytes.get() - bytesBefore,
            terminalChunks = totalChunks.get() - chunksBefore,
            transcriptPublications = publications,
        )

        suspend fun measure(name: String, block: suspend () -> Long): WorkloadMetric {
            val bytesBefore = totalBytes.get()
            val chunksBefore = totalChunks.get()
            val elapsed = block()
            return metricSince(name, elapsed, bytesBefore, chunksBefore)
        }
    }

    private companion object {
        const val PASSWORD_ARGUMENT = "threadlineFixturePassword"
        const val HOST_ARGUMENT = "threadlineFixtureHost"
        const val PORT_ARGUMENT = "threadlineFixturePort"
        const val USER_ARGUMENT = "threadlineFixtureUser"
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = 2_222
        const val DEFAULT_USER = "threadline"
        const val LEGACY_PREFERENCES = "android_large_output_known_hosts"
        const val PERFORMANCE_LOG_TAG = "ThreadlinePerf"
        const val CONNECTION_TIMEOUT_MILLIS = 20_000L
        const val COMMAND_TIMEOUT_MILLIS = 45_000L
        const val STREAM_START_TIMEOUT_MILLIS = 20_000L
        const val MAX_MIXED_OUTPUT_MILLIS = 30_000L
        const val MAX_LONG_LINE_MILLIS = 15_000L
        const val MAX_PROGRESS_OUTPUT_MILLIS = 15_000L
        const val MAX_INTERRUPT_MILLIS = 5_000L
        const val MAX_FOLLOW_UP_MILLIS = 5_000L
        const val MAX_TERMINAL_RECEIVE_MILLIS = 2_000L
        const val TRANSCRIPT_PUBLICATION_INTERVAL_MILLIS = 50L
        const val PUBLICATION_SLACK = 12
        const val MAXIMUM_RENDERED_CHARACTERS = 128 * 1024
        const val ONE_MEBIBYTE = 1024L * 1024L
        const val MINIMUM_MIXED_OUTPUT_BYTES = 2_000_000L
        const val MINIMUM_PROGRESS_OUTPUT_BYTES = 700_000L
        const val STREAM_BEFORE_INTERRUPT_BYTES = 512L * 1024L
        const val MAX_RETAINED_HEAP_DELTA_BYTES = 96L * 1024L * 1024L
        const val MAX_RESET_HEAP_DELTA_BYTES = 48L * 1024L * 1024L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MIXED_OUTPUT_COMMAND =
            "awk 'BEGIN { for (i = 0; i < 100000; i++) printf " +
                "\"\\033[3%dm%06d lambda π 日本語\\033[0m\\n\", i % 8, i }'"
        const val LONG_LINE_COMMAND =
            "head -c 1048576 /dev/zero | tr '\\000' x; printf '\\n'"
        const val PROGRESS_OUTPUT_COMMAND =
            "awk 'BEGIN { for (i = 0; i < 50000; i++) printf " +
                "\"\\rprogress %d\", i; printf \"\\n\" }'"
        const val CONTINUOUS_OUTPUT_COMMAND =
            "while :; do printf '0123456789abcdef0123456789abcdef\\n'; done"
    }
}

private fun Long.toMebibytes(): Long = this / (1024L * 1024L)

private fun Long.kilobytesToMebibytes(): Long = this / 1024L
