package com.example.samdapp.presentation.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.samdapp.presentation.acknowledgement.AcknowledgementScreen
import com.example.samdapp.presentation.common.GlobalStatusBar
import com.example.samdapp.presentation.compounder.CompounderScreen
import com.example.samdapp.presentation.connectivity.ConnectivityViewModel
import com.example.samdapp.presentation.consultation.ConsultationScreen
import com.example.samdapp.presentation.doctorlist.DoctorListScreen
import com.example.samdapp.presentation.home.HomeScreen
import com.example.samdapp.presentation.medicalbackground.MedicalBackgroundScreen
import com.example.samdapp.presentation.patientsummary.PatientSummaryScreen
import com.example.samdapp.presentation.register.RegisterScreen
import com.example.samdapp.presentation.sending.SendingScreen
import com.example.samdapp.presentation.transcription.TranscriptionScreen

@Composable
fun AppNavHost() {
    val backStack = remember { mutableStateListOf<Any>(Home) }

    // Obtained here, outside any NavEntry — one shared instance for the whole app lifetime,
    // so online/offline status is consistent and persistent across every screen.
    val connectivityViewModel: ConnectivityViewModel = hiltViewModel()
    val isOnline by connectivityViewModel.effectiveOnline.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        GlobalStatusBar(isOnline = isOnline, onToggleOnline = connectivityViewModel::toggle)

        NavDisplay(
        modifier = Modifier.weight(1f),
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(
                    onRegisterNewPatient = { backStack.add(Register) },
                    onOpenPatient = { patientId -> backStack.add(PatientSummary(patientId)) },
                )
            }
            entry<Register> {
                RegisterScreen(onRegistered = { patientId -> backStack.add(MedicalBackground(patientId)) })
            }
            entry<MedicalBackground> { key ->
                MedicalBackgroundScreen(
                    patientId = key.patientId,
                    onContinue = { patientId -> backStack.add(PatientSummary(patientId)) },
                )
            }
            entry<PatientSummary> { key ->
                PatientSummaryScreen(
                    patientId = key.patientId,
                    onStartConsultation = { patientId -> backStack.add(Compounder(patientId)) },
                )
            }
            entry<Compounder> { key ->
                CompounderScreen(
                    patientId = key.patientId,
                    onContinue = { patientId, encounterId, caseRecordId, chiefComplaint ->
                        backStack.add(ConsultationRoute(patientId, encounterId, caseRecordId, chiefComplaint))
                    },
                )
            }
            entry<ConsultationRoute> { key ->
                ConsultationScreen(
                    patientId = key.patientId,
                    encounterId = key.encounterId,
                    caseRecordId = key.caseRecordId,
                    initialChiefComplaint = key.chiefComplaint,
                    onSent = { _, _, caseRecordId, consultationId, audioUri ->
                        backStack.add(SendingRoute(caseRecordId, consultationId, audioUri))
                    },
                )
            }
            entry<SendingRoute> { key ->
                SendingScreen(
                    caseRecordId = key.caseRecordId,
                    consultationId = key.consultationId,
                    audioUri = key.audioUri,
                    onDone = { caseRecordId, consultationId, audioUri ->
                        if (audioUri != null) {
                            backStack.add(TranscriptionRoute(consultationId, audioUri, caseRecordId))
                        } else {
                            backStack.add(AcknowledgementRoute(caseRecordId))
                        }
                    },
                )
            }
            entry<TranscriptionRoute> { key ->
                TranscriptionScreen(
                    consultationId = key.consultationId,
                    audioUri = key.audioUri,
                    onContinue = { backStack.add(AcknowledgementRoute(key.caseRecordId)) },
                )
            }
            entry<AcknowledgementRoute> { key ->
                AcknowledgementScreen(
                    caseRecordId = key.caseRecordId,
                    onContinue = { caseRecordId -> backStack.add(DoctorListRoute(caseRecordId)) },
                )
            }
            entry<DoctorListRoute> { key ->
                DoctorListScreen(
                    caseRecordId = key.caseRecordId,
                    onDone = { backStack.clear(); backStack.add(Home) },
                )
            }
        },
        )
    }
}
