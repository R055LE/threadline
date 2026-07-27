package dev.threadline.core.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyAlgorithmPolicyTest {
    @Test
    fun `keeps library defaults when Ed25519 is available`() {
        assertNull(HostKeyAlgorithmPolicy.overrideWhenEd25519Unavailable(true))
    }

    @Test
    fun `fallback keeps modern ECDSA and RSA SHA2 algorithms only`() {
        val algorithms = requireNotNull(
            HostKeyAlgorithmPolicy.overrideWhenEd25519Unavailable(false),
        ).split(",")

        assertTrue("ecdsa-sha2-nistp256" in algorithms)
        assertTrue("rsa-sha2-512" in algorithms)
        assertTrue("rsa-sha2-256" in algorithms)
        assertFalse("ssh-ed25519" in algorithms)
        assertFalse("ssh-rsa" in algorithms)
    }
}
