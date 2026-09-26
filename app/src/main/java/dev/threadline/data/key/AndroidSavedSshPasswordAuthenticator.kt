package dev.threadline.data.key

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun supportsSavedSshPasswords(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    return keyguard.isDeviceSecure
}

internal suspend fun authenticateSavedSshPasswordUse(
    activity: Activity,
    cipher: Cipher,
    purpose: SavedSshPasswordPromptPurpose,
): Cipher {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        throw SavedSshPasswordAuthenticationUnavailableException()
    }
    if (!supportsSavedSshPasswords(activity)) {
        throw SavedSshPasswordAuthenticationUnavailableException()
    }
    return authenticateOnAndroid11(activity, cipher, purpose)
}

internal enum class SavedSshPasswordPromptPurpose {
    SAVE,
    CONNECT,
}

@RequiresApi(Build.VERSION_CODES.R)
private suspend fun authenticateOnAndroid11(
    activity: Activity,
    cipher: Cipher,
    purpose: SavedSshPasswordPromptPurpose,
): Cipher = suspendCancellableCoroutine { continuation ->
    val cancellationSignal = CancellationSignal()
    val prompt = BiometricPrompt.Builder(activity)
        .setTitle(
            when (purpose) {
                SavedSshPasswordPromptPurpose.SAVE -> "Save SSH password"
                SavedSshPasswordPromptPurpose.CONNECT -> "Unlock SSH password"
            },
        )
        .setSubtitle("Approve this operation with device credentials or a strong biometric")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        .build()

    continuation.invokeOnCancellation { cancellationSignal.cancel() }
    prompt.authenticate(
        BiometricPrompt.CryptoObject(cipher),
        cancellationSignal,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (!continuation.isActive) return
                val authorizedCipher = result.cryptoObject?.cipher
                if (authorizedCipher == null) {
                    continuation.resumeWithException(
                        SavedSshPasswordAuthenticationUnavailableException(),
                    )
                } else if (continuation.isActive) {
                    continuation.resume(authorizedCipher)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!continuation.isActive) return
                continuation.resumeWithException(
                    if (
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    ) {
                        SavedSshPasswordAuthenticationCancelledException()
                    } else {
                        SavedSshPasswordAuthenticationUnavailableException()
                    },
                )
            }
        },
    )
}

internal class SavedSshPasswordAuthenticationCancelledException : SavedSshPasswordException(
    "Device approval was canceled. Enter the password for this connection or try again.",
)

internal class SavedSshPasswordAuthenticationUnavailableException : SavedSshPasswordException(
    "Device approval is unavailable. Enter the password for this connection instead.",
)
