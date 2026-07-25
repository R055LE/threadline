package dev.threadline.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCredentialTest {
    @Test
    fun `password string representation is redacted and storage is wiped`() {
        val secret = "correct horse".toCharArray()
        val credential = SessionCredential.Password.from(secret)

        assertFalse(credential.toString().contains("correct horse"))
        credential.clear()
        assertTrue(credential.characters.all { it == '\u0000' })
        assertTrue(secret.contentEquals("correct horse".toCharArray()))
    }

    @Test
    fun `private key and passphrase storage are wiped`() {
        val credential = SessionCredential.PrivateKey.from(
            keyBytes = byteArrayOf(1, 2, 3),
            passphrase = "secret".toCharArray(),
        )

        assertFalse(credential.toString().contains("secret"))
        credential.clear()
        assertTrue(credential.keyBytes.all { it == 0.toByte() })
        assertTrue(credential.passphrase?.all { it == '\u0000' } == true)
    }
}
