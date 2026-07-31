package dev.threadline.data.transcript

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostProfile
import dev.threadline.core.shell.CommandId
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTranscriptState
import dev.threadline.core.transcript.CommandTurn
import dev.threadline.core.transcript.TranscriptSessionArchive
import dev.threadline.data.db.ThreadlineDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTranscriptHistoryStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: ThreadlineDatabase

    @Before
    fun createDatabase() {
        context.deleteDatabase(DATABASE_NAME)
        database = persistentDatabase()
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun archiveSurvivesReopenWithOrderedChunksAndBoundedNewestData() = runBlocking {
        val store = store()
        val retainedOutput =
            "x".repeat(RoomTranscriptHistoryStore.OUTPUT_CHUNK_CHARACTERS - 1) +
                "🙂" +
                "y".repeat(
                    RoomTranscriptHistoryStore.MAXIMUM_OUTPUT_CHARACTERS_PER_TURN -
                        RoomTranscriptHistoryStore.OUTPUT_CHUNK_CHARACTERS - 1,
                )
        val output = "discarded-prefix" + retainedOutput
        val turns = (0..RoomTranscriptHistoryStore.MAXIMUM_TURNS_PER_SESSION).map { index ->
            turn(
                index = index,
                command = if (index == 0) {
                    "discarded command"
                } else if (index == RoomTranscriptHistoryStore.MAXIMUM_TURNS_PER_SESSION) {
                    "c".repeat(RoomTranscriptHistoryStore.MAXIMUM_COMMAND_CHARACTERS - 1) +
                        "🙂tail"
                } else {
                    "printf $index"
                },
                output = if (index == RoomTranscriptHistoryStore.MAXIMUM_TURNS_PER_SESSION) {
                    output
                } else {
                    "output $index"
                },
            )
        }
        store.save(archive("persistent-session", 100, turns))

        database.close()
        database = persistentDatabase()
        val reopenedStore = store()
        val summary = reopenedStore.sessions.first().single()
        assertEquals("persistent-session", summary.id)
        assertEquals(RoomTranscriptHistoryStore.MAXIMUM_TURNS_PER_SESSION, summary.turnCount)
        assertTrue(summary.turnsTruncated)

        val reopened = reopenedStore.load(summary.id)
        assertEquals("command-1", reopened.turns.first().turn.id.value)
        val last = reopened.turns.last()
        assertTrue(last.commandTruncated)
        assertEquals(
            RoomTranscriptHistoryStore.MAXIMUM_COMMAND_CHARACTERS - 1,
            last.turn.command.length,
        )
        assertTrue(!last.turn.command.endsWith("tail"))
        assertTrue(last.turn.output.truncated)
        assertEquals(
            RoomTranscriptHistoryStore.MAXIMUM_OUTPUT_CHARACTERS_PER_TURN,
            last.turn.output.plainText.length,
        )
        assertEquals(retainedOutput, last.turn.output.plainText)
        assertEquals(output.encodeToByteArray().size.toLong(), last.turn.output.byteCount)

        val chunkRows = database.transcriptArchives().findChunks(summary.id)
            .filter { it.commandId == last.turn.id.value }
        assertTrue(chunkRows.size > 1)
        assertEquals(
            last.turn.output.plainText,
            chunkRows.joinToString(separator = "") { it.text },
        )
    }

    @Test
    fun retentionDeleteAndClearAllAreSessionScoped() = runBlocking {
        val store = store()
        repeat(RoomTranscriptHistoryStore.MAXIMUM_SESSIONS + 1) { index ->
            store.save(archive("session-$index", index.toLong(), listOf(turn(index))))
        }

        val retained = store.sessions.first()
        assertEquals(RoomTranscriptHistoryStore.MAXIMUM_SESSIONS, retained.size)
        assertNull(database.transcriptArchives().findSession("session-0"))
        assertEquals("session-20", retained.first().id)

        store.delete("session-10")
        assertNull(database.transcriptArchives().findSession("session-10"))
        assertTrue(database.transcriptArchives().findSession("session-11") != null)

        store.clearAll()
        assertTrue(store.sessions.first().isEmpty())
        assertTrue(database.transcriptArchives().findTurns("session-11").isEmpty())
        assertTrue(database.transcriptArchives().findChunks("session-11").isEmpty())
    }

    @Test
    fun deletingMissingSessionFailsExplicitly() = runBlocking {
        val failure = runCatching { store().delete("missing") }.exceptionOrNull()

        assertTrue(failure is TranscriptHistoryUnavailableException)
    }

    private fun store() = RoomTranscriptHistoryStore(
        dao = database.transcriptArchives(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun persistentDatabase() = Room.databaseBuilder(
        context,
        ThreadlineDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(
        ThreadlineDatabase.MIGRATION_1_2,
        ThreadlineDatabase.MIGRATION_2_3,
        ThreadlineDatabase.MIGRATION_3_4,
    ).build()

    private fun archive(
        id: String,
        endedAtMillis: Long,
        turns: List<CommandTurn>,
    ) = TranscriptSessionArchive(
        id = id,
        profile = HostProfile(
            displayName = "Fixture $id",
            endpoint = HostEndpoint("fixture.test", 2222),
            username = "threadline",
        ),
        startedAtMillis = 1,
        endedAtMillis = endedAtMillis,
        transcript = CommandTranscriptState(turns = turns),
    )

    private fun turn(
        index: Int,
        command: String = "printf $index",
        output: String = "output $index",
    ) = CommandTurn(
        id = CommandId("command-$index"),
        command = command,
        directoryAtStart = "/tmp",
        submittedAtMillis = 10,
        startedAtMillis = 11,
        completedAtMillis = 12,
        status = CommandStatus.SUCCEEDED,
        exitStatus = 0,
        currentDirectory = "/tmp",
        output = CommandOutput(
            plainText = output,
            byteCount = output.encodeToByteArray().size.toLong(),
        ),
    )

    private companion object {
        const val DATABASE_NAME = "threadline-transcript-history-test.db"
    }
}
