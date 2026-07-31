package dev.threadline.data.transcript

import dev.threadline.core.shell.CommandId
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTurn
import dev.threadline.core.transcript.TranscriptArchiveSink
import dev.threadline.core.transcript.TranscriptSessionArchive
import dev.threadline.data.db.TranscriptArchiveDao
import dev.threadline.data.db.TranscriptOutputChunkEntity
import dev.threadline.data.db.TranscriptSessionEntity
import dev.threadline.data.db.TranscriptSessionRows
import dev.threadline.data.db.TranscriptSessionSummaryRow
import dev.threadline.data.db.TranscriptTurnEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal data class SavedTranscriptSessionSummary(
    val id: String,
    val displayName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val turnsTruncated: Boolean,
    val turnCount: Int,
)

internal data class SavedTranscriptTurn(
    val turn: CommandTurn,
    val commandTruncated: Boolean,
)

internal data class SavedTranscriptSession(
    val summary: SavedTranscriptSessionSummary,
    val turns: List<SavedTranscriptTurn>,
)

internal class RoomTranscriptHistoryStore(
    private val dao: TranscriptArchiveDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TranscriptArchiveSink {
    val sessions: Flow<List<SavedTranscriptSessionSummary>> =
        dao.observeSummaries().map { rows ->
            rows.map(TranscriptSessionSummaryRow::toSummary)
        }

    override suspend fun save(archive: TranscriptSessionArchive) = withContext(ioDispatcher) {
        if (archive.transcript.turns.isEmpty()) return@withContext

        val retainedTurns = archive.transcript.turns.takeLast(MAXIMUM_TURNS_PER_SESSION)
        val session = TranscriptSessionEntity(
            sessionId = archive.id,
            displayName = archive.profile.displayName,
            hostname = archive.profile.endpoint.hostname,
            port = archive.profile.endpoint.port,
            username = archive.profile.username,
            startedAtMillis = archive.startedAtMillis,
            endedAtMillis = archive.endedAtMillis,
            turnsTruncated = retainedTurns.size < archive.transcript.turns.size,
        )
        val turns = retainedTurns.mapIndexed { index, turn ->
            val retainedCommand = turn.command.takeCodePointSafe(MAXIMUM_COMMAND_CHARACTERS)
            val retainedOutput = turn.output.plainText.takeLastCodePointSafe(
                MAXIMUM_OUTPUT_CHARACTERS_PER_TURN,
            )
            TranscriptTurnEntity(
                sessionId = archive.id,
                commandId = turn.id.value,
                turnIndex = index,
                command = retainedCommand,
                commandTruncated = retainedCommand.length < turn.command.length,
                directoryAtStart = turn.directoryAtStart,
                submittedAtMillis = turn.submittedAtMillis,
                startedAtMillis = turn.startedAtMillis,
                completedAtMillis = turn.completedAtMillis,
                status = turn.status.name,
                exitStatus = turn.exitStatus,
                currentDirectory = turn.currentDirectory,
                outputTruncated = turn.output.truncated ||
                    retainedOutput.length < turn.output.plainText.length,
                outputApproximate = turn.output.approximate,
                outputByteCount = turn.output.byteCount,
            )
        }
        val retainedOutputByCommand = retainedTurns.associate { turn ->
            turn.id.value to turn.output.plainText.takeLastCodePointSafe(
                MAXIMUM_OUTPUT_CHARACTERS_PER_TURN,
            )
        }
        val chunks = turns.flatMap { turn ->
            retainedOutputByCommand.getValue(turn.commandId)
                .codePointSafeChunks(OUTPUT_CHUNK_CHARACTERS)
                .mapIndexed { index, text ->
                    TranscriptOutputChunkEntity(
                        sessionId = archive.id,
                        commandId = turn.commandId,
                        chunkIndex = index,
                        text = text,
                    )
                }
        }

        protectTranscriptStorage("The transcript could not be saved.") {
            dao.replaceArchive(
                session = session,
                turns = turns,
                chunks = chunks,
                maximumSessions = MAXIMUM_SESSIONS,
            )
        }
    }

    suspend fun load(id: String): SavedTranscriptSession = withContext(ioDispatcher) {
        val rows = protectTranscriptStorage("The transcript could not be opened.") {
            dao.loadArchive(id)
        } ?: throw TranscriptHistoryUnavailableException()
        rows.toSavedSession()
    }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        val deleted = protectTranscriptStorage("The transcript could not be deleted.") {
            dao.deleteSession(id)
        }
        if (deleted != 1) throw TranscriptHistoryUnavailableException()
    }

    suspend fun clearAll() = withContext(ioDispatcher) {
        protectTranscriptStorage("Transcript history could not be cleared.") {
            dao.clearSessions()
        }
    }

    internal companion object {
        const val MAXIMUM_SESSIONS = 20
        const val MAXIMUM_TURNS_PER_SESSION = 50
        const val MAXIMUM_COMMAND_CHARACTERS = 16 * 1024
        const val MAXIMUM_OUTPUT_CHARACTERS_PER_TURN = 64 * 1024
        const val OUTPUT_CHUNK_CHARACTERS = 16 * 1024
    }
}

internal class TranscriptHistoryUnavailableException : Exception(
    "The saved transcript is no longer available.",
)

internal class TranscriptHistoryStorageException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

private fun TranscriptSessionRows.toSavedSession(): SavedTranscriptSession {
    val outputByCommand = chunks
        .groupBy(TranscriptOutputChunkEntity::commandId)
        .mapValues { (_, commandChunks) ->
            commandChunks.sortedBy(TranscriptOutputChunkEntity::chunkIndex)
                .joinToString(separator = "", transform = TranscriptOutputChunkEntity::text)
        }
    val summary = TranscriptSessionSummaryRow(
        sessionId = session.sessionId,
        displayName = session.displayName,
        hostname = session.hostname,
        port = session.port,
        username = session.username,
        startedAtMillis = session.startedAtMillis,
        endedAtMillis = session.endedAtMillis,
        turnsTruncated = session.turnsTruncated,
        turnCount = turns.size,
    ).toSummary()
    return SavedTranscriptSession(
        summary = summary,
        turns = turns.sortedBy(TranscriptTurnEntity::turnIndex).map { entity ->
            SavedTranscriptTurn(
                turn = CommandTurn(
                    id = CommandId(entity.commandId),
                    command = entity.command,
                    directoryAtStart = entity.directoryAtStart,
                    submittedAtMillis = entity.submittedAtMillis,
                    startedAtMillis = entity.startedAtMillis,
                    completedAtMillis = entity.completedAtMillis,
                    status = CommandStatus.valueOf(entity.status),
                    exitStatus = entity.exitStatus,
                    currentDirectory = entity.currentDirectory,
                    output = CommandOutput(
                        plainText = outputByCommand[entity.commandId].orEmpty(),
                        truncated = entity.outputTruncated,
                        approximate = entity.outputApproximate,
                        byteCount = entity.outputByteCount,
                    ),
                ),
                commandTruncated = entity.commandTruncated,
            )
        },
    )
}

private fun TranscriptSessionSummaryRow.toSummary() = SavedTranscriptSessionSummary(
    id = sessionId,
    displayName = displayName,
    hostname = hostname,
    port = port,
    username = username,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    turnsTruncated = turnsTruncated,
    turnCount = turnCount,
)

private fun String.takeCodePointSafe(maximumCharacters: Int): String {
    if (length <= maximumCharacters) return this
    var end = maximumCharacters
    if (end > 0 && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
        end -= 1
    }
    return substring(0, end)
}

private fun String.takeLastCodePointSafe(maximumCharacters: Int): String {
    if (length <= maximumCharacters) return this
    var start = length - maximumCharacters
    if (start > 0 && this[start].isLowSurrogate() && this[start - 1].isHighSurrogate()) {
        start += 1
    }
    return substring(start)
}

private fun String.codePointSafeChunks(maximumCharacters: Int): List<String> {
    if (isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < length) {
        var end = (start + maximumCharacters).coerceAtMost(length)
        if (end < length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
            end -= 1
        }
        chunks += substring(start, end)
        start = end
    }
    return chunks
}

private suspend inline fun <T> protectTranscriptStorage(
    message: String,
    crossinline operation: suspend () -> T,
): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw TranscriptHistoryStorageException(message, failure)
}
