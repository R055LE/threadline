package dev.threadline.data.host

import android.annotation.SuppressLint
import android.content.SharedPreferences
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.security.KnownHostKey
import dev.threadline.core.security.KnownHostRecord
import dev.threadline.core.security.KnownHostStore
import dev.threadline.core.security.KnownHostStoreException
import dev.threadline.data.db.KnownHostDao
import dev.threadline.data.db.KnownHostEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class RoomKnownHostStore(
    private val dao: KnownHostDao,
    legacyPreferences: SharedPreferences,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : KnownHostStore {
    private val legacy = LegacyKnownHostRecords(legacyPreferences)
    private val migrationMutex = Mutex()

    @Volatile
    private var legacyMigrationAttempted = false

    override suspend fun find(endpoint: HostEndpoint): KnownHostRecord? = storageOperation {
        ensureLegacyMigrated()
        dao.find(endpoint.storageKey)?.toRecord()
    }

    override suspend fun save(record: KnownHostRecord) = storageOperation {
        ensureLegacyMigrated()
        dao.insert(record.toEntity())
    }

    override suspend fun recordTrustedSeen(
        endpoint: HostEndpoint,
        key: KnownHostKey,
        seenAtMillis: Long,
    ) = storageOperation {
        ensureLegacyMigrated()
        check(
            dao.recordTrustedSeen(
                endpointKey = endpoint.storageKey,
                algorithm = key.algorithm,
                encodedKey = key.encoded,
                seenAtMillis = seenAtMillis,
            ) == 1,
        ) {
            "Trusted host record changed before its timestamp could be updated"
        }
    }

    private suspend fun ensureLegacyMigrated() {
        if (legacyMigrationAttempted) return
        migrationMutex.withLock {
            if (legacyMigrationAttempted) return

            val importedAtMillis = currentTimeMillis()
            val records = legacy.readAll(importedAtMillis)
            if (records.isNotEmpty()) {
                // IGNORE is security-sensitive: a stale legacy record must
                // never replace a newer Room trust decision.
                dao.insertLegacy(records.map(KnownHostRecord::toEntity))
            }
            legacy.clear()
            legacyMigrationAttempted = true
        }
    }

    private suspend fun <T> storageOperation(block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: KnownHostStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw KnownHostStoreException(failure)
        }
}

@OptIn(ExperimentalEncodingApi::class)
private class LegacyKnownHostRecords(
    private val preferences: SharedPreferences,
) {
    fun readAll(importedAtMillis: Long): List<KnownHostRecord> =
        preferences.all.mapNotNull { (storageKey, value) ->
            decode(storageKey, value as? String, importedAtMillis)
        }

    @SuppressLint("UseKtx")
    fun clear() {
        // A failed clear is safe: Room is already authoritative and a later
        // process will retry an idempotent INSERT OR IGNORE import.
        preferences.edit().clear().commit()
    }

    private fun decode(
        storageKey: String,
        serialized: String?,
        importedAtMillis: Long,
    ): KnownHostRecord? {
        if (serialized == null) return null
        val endpointSeparator = storageKey.lastIndexOf(':')
        val recordSeparator = serialized.indexOf('\n')
        if (
            endpointSeparator <= 0 ||
            endpointSeparator == storageKey.lastIndex ||
            recordSeparator <= 0 ||
            recordSeparator == serialized.lastIndex
        ) {
            return null
        }

        return runCatching {
            KnownHostRecord(
                endpoint = HostEndpoint(
                    hostname = storageKey.substring(0, endpointSeparator),
                    port = storageKey.substring(endpointSeparator + 1).toInt(),
                ),
                key = KnownHostKey(
                    algorithm = serialized.substring(0, recordSeparator),
                    encoded = Base64.Default.decode(serialized.substring(recordSeparator + 1)),
                ),
                firstSeenAtMillis = importedAtMillis,
                lastSeenAtMillis = importedAtMillis,
            )
        }.getOrNull()
    }
}

private fun KnownHostEntity.toRecord(): KnownHostRecord = KnownHostRecord(
    endpoint = HostEndpoint(hostname, port),
    key = KnownHostKey(algorithm, encodedKey.copyOf()),
    firstSeenAtMillis = firstSeenAtMillis,
    lastSeenAtMillis = lastSeenAtMillis,
)

private fun KnownHostRecord.toEntity(): KnownHostEntity = KnownHostEntity(
    endpointKey = endpoint.storageKey,
    hostname = endpoint.hostname.trim().lowercase(),
    port = endpoint.port,
    algorithm = key.algorithm,
    encodedKey = key.encoded.copyOf(),
    firstSeenAtMillis = firstSeenAtMillis,
    lastSeenAtMillis = lastSeenAtMillis,
)
