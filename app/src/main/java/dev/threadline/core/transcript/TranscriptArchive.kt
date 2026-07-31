package dev.threadline.core.transcript

import dev.threadline.core.model.HostProfile

data class TranscriptSessionArchive(
    val id: String,
    val profile: HostProfile,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val transcript: CommandTranscriptState,
)

fun interface TranscriptArchiveSink {
    suspend fun save(archive: TranscriptSessionArchive)
}

internal object NoOpTranscriptArchiveSink : TranscriptArchiveSink {
    override suspend fun save(archive: TranscriptSessionArchive) = Unit
}
