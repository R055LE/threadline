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
        val gate = StrictHostKeyGate(endpoint, store) {
            assertEquals("ssh-ed25519", it.algorithm)
            assertTrue(it.fingerprint.startsWith("SHA256:"))
            HostKeyDecision.ACCEPT_AND_SAVE
        }

        assertTrue(gate.verify("ssh-ed25519", byteArrayOf(1, 2, 3)))
        assertNotNull(store.find(endpoint))
    }

    @Test
    fun `rejected unknown key is not persisted`() = runTest {
        val store = FakeKnownHostStore()
        val gate = StrictHostKeyGate(endpoint, store) { HostKeyDecision.REJECT }

        assertFalse(gate.verify("ssh-ed25519", byteArrayOf(1, 2, 3)))
        assertEquals(null, store.find(endpoint))
        assertTrue(gate.rejection is SessionError.HostKeyRejected)
    }

    @Test
    fun `changed key is blocked without asking for acceptance`() = runTest {
        val store = FakeKnownHostStore().apply {
            save(
                KnownHostRecord(
                    endpoint,
                    KnownHostKey("ssh-ed25519", byteArrayOf(1, 2, 3)),
                ),
            )
        }
        var prompted = false
        val gate = StrictHostKeyGate(endpoint, store) {
            prompted = true
            HostKeyDecision.ACCEPT_AND_SAVE
        }

        assertFalse(gate.verify("ssh-ed25519", byteArrayOf(9, 9, 9)))
        assertFalse(prompted)
        assertTrue(gate.rejection is SessionError.HostKeyChanged)
    }
}

private class FakeKnownHostStore : KnownHostStore {
    private val records = mutableMapOf<String, KnownHostRecord>()

    override fun find(endpoint: HostEndpoint): KnownHostRecord? = records[endpoint.storageKey]

    override fun save(record: KnownHostRecord) {
        records[record.endpoint.storageKey] = record
    }
}
