package dev.threadline.core.security

import dev.threadline.core.model.HostEndpoint
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class KnownHostKey(
    val algorithm: String,
    val encoded: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KnownHostKey &&
            algorithm == other.algorithm &&
            encoded.contentEquals(other.encoded)

    override fun hashCode(): Int = 31 * algorithm.hashCode() + encoded.contentHashCode()
}

data class KnownHostRecord(
    val endpoint: HostEndpoint,
    val key: KnownHostKey,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
) {
    init {
        require(firstSeenAtMillis >= 0)
        require(lastSeenAtMillis >= firstSeenAtMillis)
    }
}

interface KnownHostStore {
    suspend fun find(endpoint: HostEndpoint): KnownHostRecord?

    suspend fun save(record: KnownHostRecord)

    suspend fun recordTrustedSeen(
        endpoint: HostEndpoint,
        key: KnownHostKey,
        seenAtMillis: Long,
    )
}

class KnownHostStoreException(
    cause: Throwable,
) : Exception("Known-host storage failed", cause)

@OptIn(ExperimentalEncodingApi::class)
internal object HostKeyFingerprint {
    fun sha256(encoded: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return "SHA256:${Base64.Default.encode(digest).trimEnd('=')}"
    }
}

sealed interface KnownHostMatch {
    data object Unknown : KnownHostMatch
    data object Trusted : KnownHostMatch
    data class Changed(val previous: KnownHostKey) : KnownHostMatch
}

object KnownHostPolicy {
    fun evaluate(
        stored: KnownHostRecord?,
        presented: KnownHostKey,
    ): KnownHostMatch = when {
        stored == null -> KnownHostMatch.Unknown
        stored.key == presented -> KnownHostMatch.Trusted
        else -> KnownHostMatch.Changed(stored.key)
    }
}
