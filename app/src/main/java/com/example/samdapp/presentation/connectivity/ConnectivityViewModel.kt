package com.example.samdapp.presentation.connectivity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.connectivity.ConnectivityController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Thin UI wrapper around [ConnectivityController] — the controller is the actual shared state
 * (so non-UI callers like [com.example.samdapp.presentation.doctorassignment.DoctorAssignmentConfirmViewModel]
 * can read it too); this just exposes it as a [StateFlow] for Compose. */
@HiltViewModel
class ConnectivityViewModel @Inject constructor(
    private val connectivityController: ConnectivityController,
) : ViewModel() {

    val effectiveOnline: StateFlow<Boolean> = connectivityController.isOnline
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = true)

    val isManuallyOnline: StateFlow<Boolean> = connectivityController.isManuallyOnline

    fun toggle() {
        connectivityController.toggle()
    }
}
