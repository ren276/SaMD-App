package com.example.samdapp.presentation.navigation

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.presentation.abha.AbhaEntryScreen
import com.example.samdapp.presentation.abha.AbhaLoginScreen
import com.example.samdapp.presentation.abha.AbhaOtpScreen
import com.example.samdapp.presentation.abha.AbhaSignUpScreen
import com.example.samdapp.presentation.acknowledgement.AcknowledgementScreen
import com.example.samdapp.presentation.auth.AuthUiState
import com.example.samdapp.presentation.auth.AuthViewModel
import com.example.samdapp.presentation.common.GlobalStatusBar
import com.example.samdapp.presentation.common.IdleLockScreen
import com.example.samdapp.presentation.common.IdleLockViewModel
import com.example.samdapp.presentation.common.PatientContextBar
import com.example.samdapp.presentation.compounder.CompounderScreen
import com.example.samdapp.presentation.connectivity.ConnectivityViewModel
import com.example.samdapp.presentation.consent.ConsentScreen
import com.example.samdapp.presentation.consultation.ConsultationScreen
import com.example.samdapp.presentation.consultationchain.ConsultationChainScreen
import com.example.samdapp.presentation.doctorassignment.DoctorAssignmentConfirmScreen
import com.example.samdapp.presentation.doctorlist.DoctorListScreen
import com.example.samdapp.presentation.emergency.EmergencyOverrideScreen
import com.example.samdapp.presentation.kernelassessment.KernelAssessmentScreen
import com.example.samdapp.presentation.home.HomeScreen
import com.example.samdapp.presentation.login.LoginScreen
import com.example.samdapp.presentation.medicalbackground.MedicalBackgroundScreen
import com.example.samdapp.presentation.patients.PatientsScreen
import com.example.samdapp.presentation.patientsummary.PatientAuditScreen
import com.example.samdapp.presentation.patientsummary.PatientSummaryScreen
import com.example.samdapp.presentation.profile.ProfileScreen
import com.example.samdapp.presentation.referrals.ReferralsScreen
import com.example.samdapp.presentation.register.RegisterScreen
import com.example.samdapp.presentation.report.ReportScreen
import com.example.samdapp.presentation.sending.SendingScreen
import com.example.samdapp.presentation.transcription.TranscriptionScreen

/**
 * Sign-in gate: Login is shown first on cold start whenever no session exists, and skipped on
 * every subsequent launch until sign-out — [AuthViewModel.state] is the single source of truth
 * for that decision. [MainNavHost] only mounts once signed in, and mounting it fresh (via a
 * `remember` inside that composable) each time we transition SignedOut->SignedIn gives every
 * new sign-in a clean back stack starting at Home, rather than carrying over a stale one.
 */
@Composable
fun AppNavHost() {
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.state.collectAsStateWithLifecycle()

    when (val state = authState) {
        AuthUiState.Loading -> Unit
        AuthUiState.SignedOut -> LoginScreen()
        is AuthUiState.SignedIn -> MainNavHost(session = state.session, onSignOut = authViewModel::signOut)
    }
}

@Composable
private fun MainNavHost(session: UserSession, onSignOut: () -> Unit) {
    val backStack = remember { mutableStateListOf<Any>(Home) }

    // Obtained here, outside any NavEntry — one shared instance for the whole app lifetime,
    // so online/offline status is consistent and persistent across every screen.
    val connectivityViewModel: ConnectivityViewModel = hiltViewModel()
    val isOnline by connectivityViewModel.effectiveOnline.collectAsStateWithLifecycle()

    // Idle auto-lock (item 2, privacy hardening): same Activity-scoped instance MainActivity
    // feeds from onUserInteraction(). Reset once per fresh sign-in (this composable's own
    // mount, matching the KDoc above) so idling on Login before signing in never carries an
    // already-tripped lock into the new session.
    val idleLockViewModel: IdleLockViewModel = hiltViewModel()
    val isLocked by idleLockViewModel.isLocked.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { idleLockViewModel.reset() }

    // Tab switches always reset to a single-tab-root back stack (same clear+add idiom already
    // used to return Home from EmergencyOverride/DoctorList) — no cross-tab history to get lost in.
    fun switchTab(tab: BottomNavTab) {
        backStack.clear()
        backStack.add(
            when (tab) {
                BottomNavTab.HOME -> Home
                BottomNavTab.PATIENTS -> Patients
                BottomNavTab.REFERRALS -> Referrals
                BottomNavTab.PROFILE -> Profile
            },
        )
    }
    fun bottomNavBar(current: BottomNavTab?): @Composable () -> Unit =
        { BottomNavBar(current = current, onSelect = ::switchTab) }

    // Single-Activity app: FLAG_SECURE is a window-wide flag, so it's toggled reactively as the
    // back stack's top route changes rather than being set once per-screen (REQ: privacy hardening
    // for shared/stolen-tablet scenario — blocks screenshots/screen-recording and blanks the
    // recent-apps thumbnail on any screen carrying patient data).
    val view = LocalView.current
    val currentRoute = backStack.lastOrNull()
    LaunchedEffect(currentRoute) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        if (requiresScreenSecurity(currentRoute)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        GlobalStatusBar(isOnline = isOnline, onToggleOnline = connectivityViewModel::toggle)

        currentPatientId(backStack)?.let { patientId ->
            PatientContextBar(patientId = patientId)
        }

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
                    onRegisterNewPatient = { backStack.add(AbhaEntry) },
                    onOpenPatient = { patientId -> backStack.add(PatientSummary(patientId)) },
                    onOpenDoctorList = { backStack.add(DoctorListRoute) },
                    onResumeEncounter = { patientId, encounterId, caseRecordId ->
                        backStack.add(Compounder(patientId, resumeEncounterId = encounterId, resumeCaseRecordId = caseRecordId))
                    },
                    isOnline = isOnline,
                    session = session,
                    onSignOut = onSignOut,
                    bottomBar = bottomNavBar(BottomNavTab.HOME),
                )
            }
            entry<Patients> {
                PatientsScreen(
                    onOpenPatient = { patientId -> backStack.add(PatientSummary(patientId)) },
                    bottomBar = bottomNavBar(BottomNavTab.PATIENTS),
                )
            }
            entry<Referrals> {
                ReferralsScreen(bottomBar = bottomNavBar(BottomNavTab.REFERRALS))
            }
            entry<Profile> {
                ProfileScreen(
                    session = session,
                    isOnline = isOnline,
                    onToggleOnline = connectivityViewModel::toggle,
                    onSignOut = onSignOut,
                    bottomBar = bottomNavBar(BottomNavTab.PROFILE),
                )
            }
            entry<AbhaEntry> {
                AbhaEntryScreen(
                    onCreateAbha = { backStack.add(AbhaSignUp) },
                    onLoginWithAbha = { backStack.add(AbhaLogin) },
                    onSkip = { backStack.add(Register()) },
                )
            }
            entry<AbhaSignUp> {
                AbhaSignUpScreen(onCreated = { abhaId -> backStack.add(Register(abhaId)) })
            }
            entry<AbhaLogin> {
                AbhaLoginScreen(onContinue = { abhaId -> backStack.add(AbhaOtpRoute(abhaId)) })
            }
            entry<AbhaOtpRoute> { key ->
                AbhaOtpScreen(
                    abhaId = key.abhaId,
                    onVerified = { abhaId -> backStack.add(Register(abhaId)) },
                    onCreateInstead = { backStack.add(AbhaSignUp) },
                )
            }
            entry<Register> { key ->
                RegisterScreen(
                    abhaId = key.abhaId,
                    onRegistered = { patientId -> backStack.add(MedicalBackground(patientId)) },
                )
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
                    onStartConsultation = { patientId, followUpOfEncounterId ->
                        backStack.add(ConsentRoute(patientId, followUpOfEncounterId))
                    },
                    onViewReport = { caseRecordId -> backStack.add(ReportRoute(caseRecordId)) },
                    onOpenChain = { patientId, rootEncounterId ->
                        backStack.add(ConsultationChainRoute(patientId, rootEncounterId))
                    },
                    onOpenAuditTrail = { patientId -> backStack.add(PatientAuditRoute(patientId)) },
                    bottomBar = bottomNavBar(current = null),
                )
            }
            entry<PatientAuditRoute> { key ->
                PatientAuditScreen(patientId = key.patientId)
            }
            entry<ConsentRoute> { key ->
                ConsentScreen(
                    patientId = key.patientId,
                    onContinue = { backStack.add(Compounder(key.patientId, key.followUpOfEncounterId)) },
                )
            }
            entry<Compounder> { key ->
                CompounderScreen(
                    patientId = key.patientId,
                    followUpOfEncounterId = key.followUpOfEncounterId,
                    resumeEncounterId = key.resumeEncounterId,
                    resumeCaseRecordId = key.resumeCaseRecordId,
                    onContinue = { patientId, encounterId, caseRecordId, chiefComplaint ->
                        backStack.add(ConsultationRoute(patientId, encounterId, caseRecordId, chiefComplaint))
                    },
                    onEmergencyOverride = { reasons -> backStack.add(EmergencyOverrideRoute(reasons)) },
                )
            }
            entry<EmergencyOverrideRoute> { key ->
                EmergencyOverrideScreen(
                    reasons = key.reasons,
                    onAcknowledged = { backStack.clear(); backStack.add(Home) },
                )
            }
            entry<ConsultationRoute> { key ->
                ConsultationScreen(
                    patientId = key.patientId,
                    encounterId = key.encounterId,
                    caseRecordId = key.caseRecordId,
                    initialChiefComplaint = key.chiefComplaint,
                    onSent = { _, encounterId, caseRecordId, consultationId, audioUri ->
                        backStack.add(SendingRoute(caseRecordId, consultationId, audioUri, encounterId))
                    },
                )
            }
            entry<SendingRoute> { key ->
                SendingScreen(
                    caseRecordId = key.caseRecordId,
                    consultationId = key.consultationId,
                    audioUri = key.audioUri,
                    encounterId = key.encounterId,
                    onDone = { caseRecordId, consultationId, audioUri ->
                        backStack.add(KernelAssessmentRoute(caseRecordId, consultationId, audioUri))
                    },
                )
            }
            entry<KernelAssessmentRoute> { key ->
                KernelAssessmentScreen(
                    caseRecordId = key.caseRecordId,
                    onContinue = {
                        if (key.audioUri != null) {
                            backStack.add(TranscriptionRoute(key.consultationId, key.audioUri, key.caseRecordId))
                        } else {
                            backStack.add(AcknowledgementRoute(key.caseRecordId))
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
                    onSendToDoctor = { caseRecordId -> backStack.add(DoctorAssignmentConfirmRoute(caseRecordId)) },
                    onViewReport = { caseRecordId -> backStack.add(ReportRoute(caseRecordId)) },
                )
            }
            entry<DoctorAssignmentConfirmRoute> { key ->
                DoctorAssignmentConfirmScreen(
                    caseRecordId = key.caseRecordId,
                    onDone = { backStack.clear(); backStack.add(Home) },
                )
            }
            entry<ConsultationChainRoute> { key ->
                ConsultationChainScreen(
                    patientId = key.patientId,
                    rootEncounterId = key.rootEncounterId,
                    onOpenReport = { caseRecordId -> backStack.add(ReportRoute(caseRecordId)) },
                )
            }
            entry<ReportRoute> { key ->
                ReportScreen(caseRecordId = key.caseRecordId)
            }
            entry<DoctorListRoute> {
                DoctorListScreen(onOpenReport = { caseRecordId -> backStack.add(ReportRoute(caseRecordId)) })
            }
        },
        )
    }

    if (isLocked) {
        IdleLockScreen(
            workerName = session.name,
            onUnlocked = idleLockViewModel::onUnlocked,
            modifier = Modifier.fillMaxSize(),
        )
    }
    }
}
