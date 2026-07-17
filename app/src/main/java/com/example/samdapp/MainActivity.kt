package com.example.samdapp

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.example.samdapp.presentation.common.IdleLockViewModel
import com.example.samdapp.presentation.navigation.AppNavHost
import com.example.samdapp.ui.theme.SaMDAppTheme
import dagger.hilt.android.AndroidEntryPoint

/** [FragmentActivity], not [androidx.activity.ComponentActivity] (its superclass) — required by
 *  [androidx.biometric.BiometricPrompt] for the worker sign-in gate (REQ-SEC-03). */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    // Activity-scoped, same instance [AppNavHost] reads via hiltViewModel() (both resolve
    // through this Activity's ViewModelStore) — see IdleLockViewModel's KDoc.
    private val idleLockViewModel: IdleLockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SaMDAppTheme {
                AppNavHost()
            }
        }
    }

    /** Fired by the Android framework on the touch-down/key event that begins any gesture
     *  dispatched to this activity — the standard idiom for idle-timeout detection (item 2,
     *  privacy hardening for a shared/unattended tablet). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        idleLockViewModel.onUserInteraction()
    }
}