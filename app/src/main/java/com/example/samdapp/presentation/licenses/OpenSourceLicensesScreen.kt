@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.licenses

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private data class LicensedComponent(
    val name: String,
    val version: String,
    val licence: String,
    val role: String,
    val link: String? = null,
)

/**
 * Attribution list, not an about screen: discharges the CC BY 4.0 obligation on the vendored
 * Parakeet weights (ships in the APK regardless of [com.example.samdapp.config.FeatureFlags
 * .VOICE_FIELD_IMPACT_ENABLED], so the obligation attaches at APK distribution, not at the
 * flag). The link-out below is the only outbound action on this screen — user-initiated, and
 * unrelated to the on-device ASR path egress proofs.
 */
private val LICENSED_COMPONENTS = listOf(
    LicensedComponent(
        name = "NVIDIA Parakeet TDT 0.6B v2 (int8)",
        version = "TDT 0.6B v2 (int8)",
        licence = "CC BY 4.0",
        role = "On-device speech recognition model.",
        link = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2",
    ),
    LicensedComponent(
        name = "sherpa-onnx",
        version = "1.13.7",
        licence = "Apache-2.0",
        role = "On-device speech recognition runtime.",
    ),
    LicensedComponent(
        name = "ONNX Runtime",
        version = "1.27.1",
        licence = "MIT",
        role = "Inference engine, bundled inside sherpa-onnx.",
    ),
    LicensedComponent(
        name = "Jetpack Compose",
        version = "2026.06.01",
        licence = "Apache-2.0",
        role = "UI toolkit.",
    ),
    LicensedComponent(
        name = "Room",
        version = "2.8.4",
        licence = "Apache-2.0",
        role = "Local database persistence.",
    ),
    LicensedComponent(
        name = "Hilt",
        version = "2.60.1",
        licence = "Apache-2.0",
        role = "Dependency injection.",
    ),
    LicensedComponent(
        name = "SQLCipher for Android",
        version = "4.17.0",
        licence = "BSD-style (SQLCipher community licence)",
        role = "Local database encryption.",
        link = "https://www.zetetic.net/sqlcipher/license/",
    ),
    LicensedComponent(
        name = "Retrofit",
        version = "2.11.0",
        licence = "Apache-2.0",
        role = "Kernel REST API client.",
    ),
    LicensedComponent(
        name = "OkHttp",
        version = "4.12.0",
        licence = "Apache-2.0",
        role = "HTTP client, underlies Retrofit.",
    ),
    LicensedComponent(
        name = "Kotlin Coroutines",
        version = "1.11.0",
        licence = "Apache-2.0",
        role = "Asynchronous/concurrent programming.",
    ),
    LicensedComponent(
        name = "WorkManager",
        version = "2.10.1",
        licence = "Apache-2.0",
        role = "Background sync scheduling.",
    ),
)

@Composable
fun OpenSourceLicensesScreen() {
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Open-source licences") }) },
    ) { padding: PaddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(LICENSED_COMPONENTS) { component ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = component.name, style = MaterialTheme.typography.titleMedium)
                        Text(text = component.version, style = MaterialTheme.typography.bodySmall)
                        Text(text = component.licence, style = MaterialTheme.typography.bodyMedium)
                        Text(text = component.role, style = MaterialTheme.typography.bodySmall)
                        if (component.link != null) {
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(component.link)))
                                }
                            }) {
                                Text(component.link)
                            }
                        }
                    }
                }
            }
        }
    }
}
