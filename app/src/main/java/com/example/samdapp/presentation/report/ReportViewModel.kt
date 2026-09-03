package com.example.samdapp.presentation.report

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.report.ClinicalReport
import com.example.samdapp.domain.report.ReportAudience
import com.example.samdapp.domain.usecase.AssembleReportUseCase
import com.example.samdapp.domain.usecase.CreateReferralUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReportUiState(
    val isLoading: Boolean = true,
    val report: ClinicalReport? = null,
    val errorMessage: String? = null,
    val isExporting: Boolean = false,
    val showReferralSheet: Boolean = false,
    val referralUrgency: UrgencyLevel = UrgencyLevel.ROUTINE,
    val referralReason: String = "",
    val isSubmittingReferral: Boolean = false,
    val referralErrorMessage: String? = null,
    val referralConfirmationMessage: String? = null,
) {
    val canSubmitReferral: Boolean get() = referralReason.isNotBlank() && !isSubmittingReferral
}

sealed interface ReportEffect {
    data class SharePdf(val uri: Uri) : ReportEffect
    data class ExportFailed(val message: String) : ReportEffect
}

@Stable
interface ReportReferralActions {
    fun onOpenReferralSheet()
    fun onDismissReferralSheet()
    fun onReferralUrgencyChange(urgency: UrgencyLevel)
    fun onReferralReasonChange(reason: String)
    fun onSubmitReferral()
    fun onDismissReferralConfirmation()
}

/**
 * Loads the assembled [ClinicalReport] for one case and drives the preview + PDF export + referral
 * (REQ-REF-01). The preliminary report is rendered for [ReportAudience.WORKER] (private ailments
 * redacted); the final report (Phase 5) is the same use case once a prescription has arrived.
 */
@HiltViewModel(assistedFactory = ReportViewModel.Factory::class)
class ReportViewModel @AssistedInject constructor(
    @Assisted private val caseRecordId: String,
    private val assembleReportUseCase: AssembleReportUseCase,
    private val pdfExporter: ReportPdfExporter,
    private val createReferralUseCase: CreateReferralUseCase,
    private val auditLogger: AuditLogger,
    private val authSession: AuthSession,
) : ViewModel(), ReportReferralActions {

    @AssistedFactory
    interface Factory {
        fun create(caseRecordId: String): ReportViewModel
    }

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ReportEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            assembleReportUseCase(caseRecordId, ReportAudience.WORKER).fold(
                onSuccess = { report ->
                    _uiState.update { it.copy(isLoading = false, report = report, referralReason = report.referralReasonSuggestion) }
                    // H-16 (Build 1): only once the gate has actually resolved to "show" —
                    // a committed physician decision — not on a preliminary, pre-decision load.
                    // Never the drug name.
                    if (report.kernelDecision != null) {
                        val viewerRole = authSession.currentUser().first()?.role
                        auditLogger.log(
                            action = AuditAction.PRESCRIPTION_SURFACED_TO_WORKER,
                            caseRecordId = caseRecordId,
                            payload = auditPayload(
                                "kernelDecision" to report.kernelDecision.name,
                                "viewerRole" to viewerRole?.name,
                                "caseRecordId" to caseRecordId,
                            ),
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Could not build report") } },
            )
        }
    }

    fun onExportPdf() {
        val report = _uiState.value.report ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            pdfExporter.export(report).fold(
                onSuccess = { uri ->
                    _uiState.update { it.copy(isExporting = false) }
                    auditLogger.log(
                        action = AuditAction.REPORT_EXPORTED,
                        caseRecordId = caseRecordId,
                        payload = auditPayload("isFinal" to report.isFinal.toString()),
                    )
                    _effects.send(ReportEffect.SharePdf(uri))
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isExporting = false) }
                    _effects.send(ReportEffect.ExportFailed(e.message ?: "PDF export failed"))
                },
            )
        }
    }

    override fun onOpenReferralSheet() = _uiState.update { it.copy(showReferralSheet = true) }
    override fun onDismissReferralSheet() = _uiState.update { it.copy(showReferralSheet = false) }
    override fun onReferralUrgencyChange(urgency: UrgencyLevel) = _uiState.update { it.copy(referralUrgency = urgency) }
    override fun onReferralReasonChange(reason: String) = _uiState.update { it.copy(referralReason = reason) }
    override fun onDismissReferralConfirmation() = _uiState.update { it.copy(referralConfirmationMessage = null) }

    override fun onSubmitReferral() {
        val current = _uiState.value
        val report = current.report ?: return
        if (!current.canSubmitReferral) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingReferral = true, referralErrorMessage = null) }
            createReferralUseCase(
                patientUid = report.header.patientUid,
                caseRecordId = caseRecordId,
                urgencyLevel = current.referralUrgency,
                reason = current.referralReason,
                sendingPhcId = report.header.phcName,
            ).fold(
                onSuccess = { referral ->
                    auditLogger.log(
                        action = AuditAction.REFERRAL_CREATED,
                        caseRecordId = caseRecordId,
                        payload = auditPayload(
                            "urgencyLevel" to referral.urgencyLevel.name,
                            "patientUid" to referral.patientUid,
                        ),
                    )
                    _uiState.update {
                        it.copy(
                            isSubmittingReferral = false,
                            showReferralSheet = false,
                            referralConfirmationMessage =
                                "Referral sent — Patient UID ${referral.patientUid} queued for CHC/District Hospital appointment.",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isSubmittingReferral = false, referralErrorMessage = e.message ?: "Could not create referral") }
                },
            )
        }
    }
}
