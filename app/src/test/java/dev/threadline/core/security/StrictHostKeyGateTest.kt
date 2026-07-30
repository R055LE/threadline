package dev.threadline.core.security

import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.SessionError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictHostKeyGateTest {
    private val endpoint = HostEndpoint("fixture.test", 2222)

    @Test
    fun `accepted unknown key is persisted`() = runTest {
        val store = FakeKnownHostStore()
        val gate = StrictHostKeyGate(
            endpoint = endpoint,
            store = store,
            requestDecision = {
                assertEquals("ssh-ed25519", it.algorithm)
                assertTrue(it.fingerprint.startsWith("SHA256:"))
                HostKeyDecision.ACCEPT_AND_SAVE
            },
            currentTimeMillis = { 42 },
        )

        assertTrue(gate.verify("ssh-ed25519", byteArrayOf(1, 2, 3)))
        val record = store.find(endpoint)
        assertNotNull(record)
        assertEquals(42L, record?.firstSeenAtMillis)
        assertEquals(42L, record?.lastSeenAtMillis)
    }

    @Test
    fun `rejected unknown key is not persisted`() = runTest {
        val store = FakeKnownHostStore()
        val gate = StrictHostKeyGate(
            endpoint = endpoint,
            store = store,
            requestDecision = { HostKeyDecision.REJECT },
        )

        assertFalse(gate.verify("ssh-ed25519", byteArrayOf(1, 2, 3)))
        assertEquals(null, store.find(endpoint))
        assertTrue(gate.rejection is SessionError.HostKeyRejected)
    }

    @Test
    fun `changed key is blocked without asking for acceptance`() = runTest {
        val store = FakeKnownHostStore().apply {
            save(
                KnownHostRecord(
                    endpoint = endpoint,
                    key = KnownHostKey("ssh-ed25519", byteArrayOf(1, 2, 3)),
                    firstSeenAtMillis = 1,
                    lastSeenAtMillis = 1,
                ),
            )
        }
        var prompted = false
        val gate = StrictHostKeyGate(
            endpoint = endpoint,
            store = store,
            requestDecision = {
                prompted = true
                HostKeyDecision.ACCEPT_AND_SAVE
            },
        )

        assertFalse(gate.verify("ssh-ed25519", byteArrayOf(9, 9, 9)))
        assertFalse(prompted)
        assertTrue(gate.rejection is SessionError.HostKeyChanged)
    }

    @Test
    fun `trusted key advances last seen without prompting`() = runTest {
        val store = FakeKnownHostStore().apply {
            save(
                KnownHostRecord(
                    endpoint = endpoint,
                    key = KnownHostKey("ssh-ed25519", byteArrayOf(1, 2, 3)),
                    firstSeenAtMillis = 10,
                    lastSeenAtMillis = 10,
                ),
            )
        }
        val gate = StrictHostKeyGate(
            endpoint = endpoint,
            store = store,
            requestDecision = { error("Trusted keys do not prompt") },
            currentTimeMillis = { 20 },
        )

        assertTrue(gate.verify("ssh-ed25519", byteArrayOf(1, 2, 3)))
        assertEquals(20L, store.find(endpoint)?.lastSeenAtMillis)
    }

    @Test
    fun `known host storage failure blocks verification with typed error`() = runTest {
        val gate = StrictHostKeyGate(
            endpoint = endpoint,
            store = FailingKnownHostStore,
            requestDecision = { error("Storage failure must not prompt") },
        )

        assertFalse(gate.verify("ssh-ed25519", byteArrayOf(1, 2, 3)))
        assertEquals(SessionError.KnownHostStorageFailed, gate.rejection)
    }
}

private class FakeKnownHostStore : KnownHostStore {
    private val records = mutableMapOf<String, KnownHostRecord>()

    override suspend fun find(endpoint: HostEndpoint): KnownHostRecord? =
        records[endpoint.storageKey]

    override suspend fun save(record: KnownHostRecord) {
        records[record.endpoint.storageKey] = record
    }

    override suspend fun recordTrustedSeen(
        endpoint: HostEndpoint,
        key: KnownHostKey,
        seenAtMillis: Long,
    ) {
        val record = requireNotNull(records[endpoint.storageKey])
        check(record.key == key)
        records[endpoint.storageKey] = record.copy(lastSeenAtMillis = seenAtMillis)
    }
}

private object FailingKnownHostStore : KnownHostStore {
    override suspend fun find(endpoint: HostEndpoint): KnownHostRecord? {
        throw KnownHostStoreException(IllegalStateException("synthetic storage failure"))
    }

    override suspend fun save(record: KnownHostRecord) = error("Not reached")

    override suspend fun recordTrustedSeen(
        endpoint: HostEndpoint,
        key: KnownHostKey,
        seenAtMillis: Long,
    ) = error("Not reached")
}
