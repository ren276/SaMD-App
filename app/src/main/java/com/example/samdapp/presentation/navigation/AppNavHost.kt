package com.example.samdapp.presentation.navigation

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.samdapp.config.FeatureFlags
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.presentation.abha.AbhaAadhaarEntryScreen
import com.example.samdapp.presentation.abha.AbhaCreateOtpScreen
import com.example.samdapp.presentation.abha.AbhaEntryScreen
import com.example.samdapp.presentation.abha.AbhaLoginScreen
import com.example.samdapp.presentation.abha.AbhaOtpScreen
import com.example.samdapp.presentation.abha.AbhaProfileScreen
import com.example.samdapp.presentation.licenses.OpenSourceLicensesScreen
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
import com.example.samdapp.presentation.documents.DocumentViewerScreen
import com.example.samdapp.presentation.emergency.EmergencyOverrideScreen
import com.example.samdapp.presentation.kernelassessment.KernelAssessmentScreen
import com.example.samdapp.presentation.home.HomeScreen
import com.example.samdapp.presentation.login.LoginScreen
import com.example.samdapp.presentation.login.PinChangeScreen
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
        is AuthUiState.MustChangePin -> PinChangeScreen()
        is AuthUiState.SignedIn -> MainNavHost(session = state.session, onSignOut = authViewModel::signOut)
    }
}

@Composable
private fun MainNavHost(session: UserSession, onSignOut: () -> Unit) {
    // Saveable, so the worker comes back to the screen they were on after process death instead
    // of losing the whole stack (the old `remember { mutableStateListOf<Any>(Home) }`).
    //
    // NOT `rememberNavBackStack`: verified against navigation3-runtime 1.1.4's sources, that
    // helper takes no `inputs` and has no failure path, and this call site needs both. The
    // `session.userId` key is what preserves the guarantee this composable's KDoc above relies on
    // (every new sign-in starts at Home), which a saveable stack would otherwise break, and the
    // saver's own null-on-failure contract is what stops a route class that can no longer be
    // deserialized from crashing the app on every launch. See NavBackStackSaver.kt.
    val backStack = rememberSaveable(
        session.userId,
        saver = rememberNavBackStackSaver(session.userId),
    ) { NavBackStack<NavKey>(Home) }

    // A discarded stack must leave a trace: a stack that vanishes silently looks exactly like a
    // worker who walked back to Home themselves. Drained once per composition; the reporter
    // clears itself, so one discard writes one row.
    val navRestoreViewModel: NavRestoreViewModel = hiltViewModel()
    LaunchedEffect(Unit) { navRestoreViewModel.logPendingDiscard() }

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
        transitionSpec = {
            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
        },
        popTransitionSpec = {
            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
        },
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
                    onOpenLicences = { backStack.add(OpenSourceLicensesRoute) },
                    onSignOut = onSignOut,
                    bottomBar = bottomNavBar(BottomNavTab.PROFILE),
                )
            }
            entry<OpenSourceLicensesRoute> {
                OpenSourceLicensesScreen()
            }
            entry<AbhaEntry> {
                AbhaEntryScreen(
                    // "Create ABHA ID" now opens the real ABDM-backed Aadhaar flow. [AbhaSignUp]
                    // and its mock use case are left in place, reachable from AbhaOtpScreen's
                    // "Create ABHA ID instead", because the login side is still P1 mock.
                    onCreateAbha = { backStack.add(AbhaAadhaarEntry) },
                    onLoginWithAbha = { backStack.add(AbhaLogin) },
                    onSkip = { backStack.add(Register()) },
                )
            }
            entry<AbhaAadhaarEntry> {
                AbhaAadhaarEntryScreen(
                    onOtpRequested = { sessionId, maskedMobile ->
                        backStack.add(AbhaCreateOtpRoute(sessionId, maskedMobile))
                    },
                )
            }
            entry<AbhaCreateOtpRoute> { key ->
                AbhaCreateOtpScreen(
                    sessionId = key.sessionId,
                    maskedMobile = key.maskedMobile,
                    onEnrolled = { abhaId -> backStack.add(Register(abhaId)) },
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
                    onContinueToDoctorAssignment = { caseRecordId ->
                        backStack.add(DoctorAssignmentConfirmRoute(caseRecordId))
                    },
                    onOpenChain = { patientId, rootEncounterId ->
                        backStack.add(ConsultationChainRoute(patientId, rootEncounterId))
                    },
                    onOpenAuditTrail = { patientId -> backStack.add(PatientAuditRoute(patientId)) },
                    onOpenAbhaProfile = { abhaId -> backStack.add(AbhaProfileRoute(abhaId)) },
                    onOpenDocumentViewer = { documentId -> backStack.add(DocumentViewerRoute(documentId)) },
                    bottomBar = bottomNavBar(current = null),
                )
            }
            entry<PatientAuditRoute> { key ->
                PatientAuditScreen(patientId = key.patientId)
            }
            entry<DocumentViewerRoute> { key ->
                DocumentViewerScreen(documentId = key.documentId)
            }
            entry<AbhaProfileRoute> { key ->
                AbhaProfileScreen(abhaId = key.abhaId)
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
                    onEmergencyOverride = { encounterId ->
                        backStack.add(EmergencyOverrideRoute(key.patientId, encounterId))
                    },
                )
            }
            entry<EmergencyOverrideRoute> { key ->
                EmergencyOverrideScreen(
                    encounterId = key.encounterId,
                    onAcknowledged = { backStack.clear(); backStack.add(Home) },
                )
            }
            entry<ConsultationRoute> { key ->
                ConsultationScreen(
                    patientId = key.patientId,
                    encounterId = key.encounterId,
                    caseRecordId = key.caseRecordId,
                    initialChiefComplaint = key.chiefComplaint,
                    onSent = { _, encounterId, caseRecordId, consultationId, _ ->
                        backStack.add(SendingRoute(caseRecordId, consultationId, encounterId))
                    },
                )
            }
            entry<SendingRoute> { key ->
                SendingScreen(
                    caseRecordId = key.caseRecordId,
                    consultationId = key.consultationId,
                    encounterId = key.encounterId,
                    onDone = { caseRecordId, consultationId ->
                        backStack.add(KernelAssessmentRoute(caseRecordId, consultationId))
                    },
                )
            }
            entry<KernelAssessmentRoute> { key ->
                KernelAssessmentScreen(
                    caseRecordId = key.caseRecordId,
                    consultationId = key.consultationId,
                    // audioUri now arrives from the ViewModel, which read the consultation's own
                    // AUDIO attachment row, rather than from the route. The branch below is
                    // otherwise unchanged.
                    onContinue = { audioUri ->
                        // The transcription screen auto-transcribes and PERSISTS the result with no
                        // confirmation gate and no provenance (H-15.C2), so it is gated by the same
                        // flag as the attachment that produces audioUri. Belt and braces: with the
                        // flag off nothing can create an AUDIO attachment in the first place, and
                        // TranscribeAudioUseCase refuses independently of this branch.
                        if (audioUri != null && FeatureFlags.VOICE_AUDIO_ATTACHMENT_ENABLED) {
                            backStack.add(TranscriptionRoute(key.consultationId, key.caseRecordId))
                        } else {
                            backStack.add(AcknowledgementRoute(key.caseRecordId))
                        }
                    },
                )
            }
            entry<TranscriptionRoute> { key ->
                TranscriptionScreen(
                    consultationId = key.consultationId,
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

    if (FeatureFlags.IDLE_LOCK_ENABLED && isLocked) {
        IdleLockScreen(
            workerName = session.name,
            onUnlocked = idleLockViewModel::onUnlocked,
            modifier = Modifier.fillMaxSize(),
        )
    }
    }
}
