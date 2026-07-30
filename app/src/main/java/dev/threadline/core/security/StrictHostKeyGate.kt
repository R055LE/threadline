package dev.threadline.core.security

import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.HostKeyPrompt
import dev.threadline.core.model.SessionError
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class StrictHostKeyGate(
    private val endpoint: HostEndpoint,
    private val store: KnownHostStore,
    private val requestDecision: suspend (HostKeyPrompt) -> HostKeyDecision,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    var rejection: SessionError? = null
        private set

    suspend fun verify(
        algorithm: String,
        encoded: ByteArray,
    ): Boolean = try {
        val candidate = KnownHostKey(algorithm, encoded.copyOf())
        when (val match = KnownHostPolicy.evaluate(store.find(endpoint), candidate)) {
            KnownHostMatch.Trusted -> {
                store.recordTrustedSeen(endpoint, candidate, currentTimeMillis())
                true
            }
            KnownHostMatch.Unknown -> verifyUnknown(candidate)
            is KnownHostMatch.Changed -> {
                rejection = SessionError.HostKeyChanged(
                    previousFingerprint = fingerprint(match.previous.encoded),
                    presentedFingerprint = fingerprint(candidate.encoded),
                )
                false
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: KnownHostStoreException) {
        rejection = SessionError.KnownHostStorageFailed
        false
    }

    private suspend fun verifyUnknown(candidate: KnownHostKey): Boolean {
        val candidateFingerprint = fingerprint(candidate.encoded)
        val decision = requestDecision(
            HostKeyPrompt(
                endpoint = endpoint,
                algorithm = candidate.algorithm,
                fingerprint = candidateFingerprint,
            ),
        )
        if (decision != HostKeyDecision.ACCEPT_AND_SAVE) {
            rejection = SessionError.HostKeyRejected(candidateFingerprint)
            return false
        }

        val acceptedAtMillis = currentTimeMillis()
        store.save(
            KnownHostRecord(
                endpoint = endpoint,
                key = candidate,
                firstSeenAtMillis = acceptedAtMillis,
                lastSeenAtMillis = acceptedAtMillis,
            ),
        )
        return true
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun fingerprint(encoded: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return "SHA256:${Base64.Default.encode(digest).trimEnd('=')}"
    }
}
