package com.example.samdapp.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.PatientRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel(assistedFactory = PatientHeaderViewModel.Factory::class)
class PatientHeaderViewModel @AssistedInject constructor(
    @Assisted private val patientId: String,
    patientRepository: PatientRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(patientId: String): PatientHeaderViewModel
    }

    val patient: StateFlow<Patient?> = patientRepository.observePatient(patientId)
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * Persistent identity banner shown under the global status bar whenever a patient is in
 * context, so the worker always knows exactly who this encounter is for — full name plus the
 * unique patient ID, which disambiguates same-name patients. Keyed by patientId so it
 * re-binds as navigation moves between patients.
 */
@Composable
fun PatientContextBar(patientId: String, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<PatientHeaderViewModel, PatientHeaderViewModel.Factory>(
        key = patientId,
        creationCallback = { factory -> factory.create(patientId) },
    )
    val patient by viewModel.patient.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = patient?.fullName ?: "Loading patient…",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "ID $patientId",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
