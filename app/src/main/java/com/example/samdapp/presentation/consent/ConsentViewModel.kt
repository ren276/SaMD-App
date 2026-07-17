package com.example.samdapp.presentation.consent

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class ConsentUiState(val agreed: Boolean = false) {
    val canContinue: Boolean get() = agreed
}

sealed interface ConsentEffect {
    data object Continue : ConsentEffect
}

@Stable
interface ConsentActions {
    fun onAgreedChange(agreed: Boolean)
    fun onContinue()
}

/**
 * Digital consent checkpoint (REQ-TRS-01) — shown once, before any ailment capture begins. No case
 * record/encounter exists yet at this point (that's created lazily when the Compounder screen
 * starts), so this logs with [patientId] only; there is no [caseRecordId] to attach it to.
 */
@HiltViewModel(assistedFactory = ConsentViewModel.Factory::class)
class ConsentViewModel @AssistedInject constructor(
    @Assisted private val patientId: String,
    private val auditLogger: AuditLogger,
) : ViewModel(), ConsentActions {

    @AssistedFactory
    interface Factory {
        fun create(patientId: String): ConsentViewModel
    }

    private val _uiState = MutableStateFlow(ConsentUiState())
    val uiState: StateFlow<ConsentUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ConsentEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    override fun onAgreedChange(agreed: Boolean) = _uiState.update { it.copy(agreed = agreed) }

    override fun onContinue() {
        if (!_uiState.value.canContinue) return
        viewModelScope.launch {
            auditLogger.log(
                action = AuditAction.CONSENT_RECORDED,
                patientId = patientId,
                payload = auditPayload("timestamp" to Instant.now().toString()),
            )
            _effects.send(ConsentEffect.Continue)
        }
    }
}
