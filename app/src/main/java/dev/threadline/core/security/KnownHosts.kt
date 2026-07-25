package dev.threadline.core.security

import android.annotation.SuppressLint
import android.content.SharedPreferences
import dev.threadline.core.model.HostEndpoint
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
)

interface KnownHostStore {
    fun find(endpoint: HostEndpoint): KnownHostRecord?

    fun save(record: KnownHostRecord)
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

@OptIn(ExperimentalEncodingApi::class)
class SharedPreferencesKnownHostStore(
    private val preferences: SharedPreferences,
) : KnownHostStore {
    override fun find(endpoint: HostEndpoint): KnownHostRecord? {
        val serialized = preferences.getString(endpoint.storageKey, null) ?: return null
        val separator = serialized.indexOf('\n')
        if (separator <= 0 || separator == serialized.lastIndex) return null

        return runCatching {
            KnownHostRecord(
                endpoint = endpoint,
                key = KnownHostKey(
                    algorithm = serialized.substring(0, separator),
                    encoded = Base64.Default.decode(serialized.substring(separator + 1)),
                ),
            )
        }.getOrNull()
    }

    @SuppressLint("UseKtx")
    override fun save(record: KnownHostRecord) {
        val serialized =
            "${record.key.algorithm}\n${Base64.Default.encode(record.key.encoded)}"
        // The KTX edit helper discards commit's Boolean result. A failed
        // host-key write must fail the handshake rather than imply persistence.
        check(preferences.edit().putString(record.endpoint.storageKey, serialized).commit()) {
            "Could not persist the accepted host key"
        }
    }
}
