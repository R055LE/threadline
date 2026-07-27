package dev.threadline.core.ssh

import org.conscrypt.OpenSSLProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Installs the same bundled Conscrypt provider used by ConnectBot's OSS app.
 *
 * Android's platform provider can advertise Ed25519 while rejecting the X.509
 * public-key specification used to verify an SSH host signature. A real
 * sign/decode/verify probe distinguishes that partial implementation from a
 * usable one.
 */
internal object AndroidSshCryptoProvider {
    @Volatile
    private var installed: Boolean? = null

    @Synchronized
    fun install(): Boolean {
        installed?.let { return it }

        val available = runCatching {
            Security.insertProviderAt(OpenSSLProvider(), 1)
            currentProviderSupportsEd25519()
        }.getOrDefault(false)
        installed = available
        return available
    }

    internal fun currentProviderSupportsEd25519(): Boolean = runCatching {
        val keyPair = KeyPairGenerator.getInstance(ED25519).generateKeyPair()
        val decodedPublicKey = KeyFactory.getInstance(ED25519).generatePublic(
            X509EncodedKeySpec(keyPair.public.encoded),
        )
        val signer = Signature.getInstance(ED25519)
        signer.initSign(keyPair.private)
        signer.update(PROBE_MESSAGE)

        val verifier = Signature.getInstance(ED25519)
        verifier.initVerify(decodedPublicKey)
        verifier.update(PROBE_MESSAGE)
        verifier.verify(signer.sign())
    }.getOrDefault(false)

    private const val ED25519 = "Ed25519"
    private val PROBE_MESSAGE = "threadline-ed25519-provider-probe".toByteArray()
}

internal object HostKeyAlgorithmPolicy {
    private val fallbackAlgorithms = listOf(
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
        "rsa-sha2-512",
        "rsa-sha2-256",
    ).joinToString(",")

    fun overrideWhenEd25519Unavailable(ed25519Available: Boolean): String? =
        if (ed25519Available) null else fallbackAlgorithms
}
