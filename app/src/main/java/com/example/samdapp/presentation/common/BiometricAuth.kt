package com.example.samdapp.presentation.common

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** [Success] = the device owner unlocked with their fingerprint/face/PIN. This verifies "whoever
 *  is holding this device right now can unlock it" — it does NOT verify the typed name/role
 *  actually belongs to that person; there is no per-person identity backing it in this mock
 *  (REQ-SEC-03 stays PLANNED for real account-bound identity). [Unavailable] fires when the
 *  device has no biometric enrolled and no screen lock at all — sign-in is refused outright
 *  rather than silently skipping the check, since "verified, not just typed" is the whole point. */
sealed interface BiometricResult {
    data object Success : BiometricResult
    data class Failed(val message: String) : BiometricResult
    data object Unavailable : BiometricResult
}

private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Returns a launcher: call it with a subtitle (e.g. the worker's name) to show the system
 *  biometric/device-credential prompt and get a [BiometricResult] back via [onResult]. */
@Composable
fun rememberBiometricAuthenticator(onResult: (BiometricResult) -> Unit): (subtitle: String) -> Unit {
    val activity = LocalContext.current as FragmentActivity

    return { subtitle ->
        val manager = BiometricManager.from(activity)
        if (manager.canAuthenticate(ALLOWED_AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(BiometricResult.Unavailable)
        } else {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onResult(BiometricResult.Success)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onResult(BiometricResult.Failed(errString.toString()))
                    }

                    override fun onAuthenticationFailed() {
                        onResult(BiometricResult.Failed("Fingerprint/face did not match"))
                    }
                },
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify to sign in")
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                .build()
            prompt.authenticate(promptInfo)
        }
    }
}
