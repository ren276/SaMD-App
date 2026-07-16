package com.example.samdapp

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.example.samdapp.presentation.navigation.AppNavHost
import com.example.samdapp.ui.theme.SaMDAppTheme
import dagger.hilt.android.AndroidEntryPoint

/** [FragmentActivity], not [androidx.activity.ComponentActivity] (its superclass) — required by
 *  [androidx.biometric.BiometricPrompt] for the worker sign-in gate (REQ-SEC-03). */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SaMDAppTheme {
                AppNavHost()
            }
        }
    }
}