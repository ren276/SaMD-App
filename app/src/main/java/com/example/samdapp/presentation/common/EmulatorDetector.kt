package com.example.samdapp.presentation.common

import android.os.Build

/** True only on emulators/AVDs — used solely to bypass the biometric prompt in
 *  [rememberBiometricAuthenticator], since emulators without a configured virtual
 *  fingerprint/screen lock can't satisfy [androidx.biometric.BiometricPrompt]. Real
 *  devices always go through the actual prompt. Null-safe: plain JVM unit tests (not
 *  Robolectric) leave `android.os.Build`'s static fields null, which must read as "not an
 *  emulator," not throw. */
fun isEmulator(): Boolean =
    Build.FINGERPRINT?.startsWith("generic") == true ||
        Build.FINGERPRINT?.startsWith("unknown") == true ||
        Build.MODEL?.contains("google_sdk") == true ||
        Build.MODEL?.contains("Emulator") == true ||
        Build.MODEL?.contains("Android SDK built for x86") == true ||
        Build.MANUFACTURER?.contains("Genymotion") == true ||
        Build.PRODUCT?.contains("sdk_gphone") == true ||
        Build.PRODUCT == "google_sdk" ||
        (Build.BRAND?.startsWith("generic") == true && Build.DEVICE?.startsWith("generic") == true) ||
        Build.HARDWARE?.contains("goldfish") == true ||
        Build.HARDWARE?.contains("ranchu") == true
