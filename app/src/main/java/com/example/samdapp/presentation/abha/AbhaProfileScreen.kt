@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.abha

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.model.abhaGenderToBiologicalSex
import com.example.samdapp.domain.model.formatAbhaId
import com.example.samdapp.presentation.common.SamdLoadingIndicator
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Worker-facing, read-only ABHA profile view. Reached from [com.example.samdapp.presentation.patientsummary.PatientSummaryScreen]'s
 * ABHA row (shown only when `patient.abhaNumber != null`). Renders [formatAbhaId], never
 * `maskAbhaId` — that masking exists for the printed clinical report, which leaves the device;
 * this screen is on an authenticated, SQLCipher-encrypted device.
 */
@Composable
fun AbhaProfileScreen(
    abhaId: String,
    viewModel: AbhaProfileViewModel = hiltViewModel<AbhaProfileViewModel, AbhaProfileViewModel.Factory>(
        creationCallback = { factory -> factory.create(abhaId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AbhaProfileContent(uiState = uiState)
}

@Composable
internal fun AbhaProfileContent(uiState: AbhaProfileUiState) {
    Scaffold(topBar = { TopAppBar(title = { Text("ABHA profile") }) }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                uiState.isLoading -> SamdLoadingIndicator(modifier = Modifier.padding(24.dp))
                uiState.profile == null -> AbhaProfileEmptyState()
                else -> AbhaProfileDetails(uiState.profile)
            }
        }
    }
}

/** Honest empty state for the rare case where `patient.abhaNumber != null` but the row never
 *  landed on this device (deleted locally, or never synced from the enrolling device). The
 *  Patient-level linkage still exists — this is not a "no ABHA" state, it is a "not on this
 *  device (yet)" state. */
@Composable
private fun AbhaProfileEmptyState() {
    Text(
        text = "Profile not on device",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 48.dp),
    )
}

@Composable
private fun AbhaProfileDetails(profile: AbhaProfile) {
    InitialsAvatar(name = profile.name)

    Text(
        text = profile.name,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 16.dp),
    )
    Text(
        text = formatAbhaId(profile.abhaId),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 4.dp),
    )
    profile.abhaAddress?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }

    KycBadgeRow(profile)

    Card(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            profile.dateOfBirth?.let { ProfileRow("Date of birth", it.format(DateTimeFormatter.ofPattern("d MMM yyyy"))) }
            abhaGenderToBiologicalSex(profile.gender)?.let { ProfileRow("Biological sex", it) }
            profile.mobileNumber?.let { ProfileRow("Mobile", it) }
            profile.emailAddress?.let { ProfileRow("Email", it) }
            addressBlock(profile)?.let { ProfileRow("Address", it) }
        }
    }
}

@Composable
private fun KycBadgeRow(profile: AbhaProfile) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 12.dp)) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(if (profile.kycVerified) "KYC verified" else "KYC not verified") },
        )
        if (profile.kycVerified) {
            Text(
                text = "Verified on " + DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(profile.createdAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Decision (a): no image-loading dependency exists in this project, and the sandbox `photo_url`
 *  is always null — an initials circle over [MaterialTheme.colorScheme.primaryContainer] instead
 *  of fetching or storing image bytes.
 *  // TODO(BUILD-N): first production ABDM response with a non-null photo_url is the trigger to
 *  //  capture the real value and design storage/render against its actual shape. */
@Composable
private fun InitialsAvatar(name: String) {
    val initials = name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
    Box(
        modifier = Modifier
            .size(72.dp)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
        )
    }
}

private fun addressBlock(profile: AbhaProfile): String? {
    val parts = listOfNotNull(profile.address, profile.district, profile.state, profile.pincode)
    return parts.joinToString(", ").takeIf { it.isNotBlank() }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Text(text = "$label: $value", style = MaterialTheme.typography.bodyLarge)
}
