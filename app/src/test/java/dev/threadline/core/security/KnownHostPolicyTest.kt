package dev.threadline.core.security

import dev.threadline.core.model.HostEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownHostPolicyTest {
    private val endpoint = HostEndpoint("example.test", 2222)
    private val key = KnownHostKey("ssh-ed25519", byteArrayOf(1, 2, 3))

    @Test
    fun `missing record is unknown`() {
        assertEquals(KnownHostMatch.Unknown, KnownHostPolicy.evaluate(null, key))
    }

    @Test
    fun `same algorithm and bytes are trusted`() {
        val record = record()

        assertEquals(
            KnownHostMatch.Trusted,
            KnownHostPolicy.evaluate(
                record,
                KnownHostKey("ssh-ed25519", byteArrayOf(1, 2, 3)),
            ),
        )
    }

    @Test
    fun `different key is changed`() {
        val result = KnownHostPolicy.evaluate(
            record(),
            KnownHostKey("ssh-ed25519", byteArrayOf(9, 9, 9)),
        )

        assertTrue(result is KnownHostMatch.Changed)
        assertEquals(key, (result as KnownHostMatch.Changed).previous)
    }

    @Test
    fun `different algorithm is changed even when bytes match`() {
        val result = KnownHostPolicy.evaluate(
            record(),
            KnownHostKey("ssh-rsa", byteArrayOf(1, 2, 3)),
        )

        assertTrue(result is KnownHostMatch.Changed)
    }

    private fun record() = KnownHostRecord(
        endpoint = endpoint,
        key = key,
        firstSeenAtMillis = 1,
        lastSeenAtMillis = 1,
    )
}
