package dev.threadline.core.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSshCryptoProviderTest {
    @Test
    fun installedProviderDecodesAndVerifiesEd25519() {
        assertTrue(AndroidSshCryptoProvider.install())
        assertTrue(AndroidSshCryptoProvider.currentProviderSupportsEd25519())
    }
}
