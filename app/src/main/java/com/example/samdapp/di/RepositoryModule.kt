package com.example.samdapp.di

import com.example.samdapp.data.assessment.AssessmentQueueScheduler
import com.example.samdapp.data.assessment.WorkManagerAssessmentScheduler
import com.example.samdapp.data.local.audit.RoomAuditLogger
import com.example.samdapp.data.local.auth.AuthTokenStore
import com.example.samdapp.data.local.auth.BackendAuthSession
import com.example.samdapp.data.local.auth.DataStoreAuthTokenStore
import com.example.samdapp.data.sync.DataStoreInFlightBatchStore
import com.example.samdapp.data.sync.InFlightBatchStore
import com.example.samdapp.data.sync.RoomSyncOutboxRepository
import com.example.samdapp.data.sync.SyncOutboxRepository
import com.example.samdapp.data.sync.SyncOutboxScheduler
import com.example.samdapp.data.sync.SyncStatusImpl
import com.example.samdapp.data.sync.WorkManagerSyncOutboxScheduler
import com.example.samdapp.data.repository.AbhaProfileRepositoryImpl
import com.example.samdapp.data.repository.AilmentRepositoryImpl
import com.example.samdapp.data.repository.AuditLogRepositoryImpl
import com.example.samdapp.data.repository.CaseRecordRepositoryImpl
import com.example.samdapp.data.local.document.AndroidDocumentCaptureStore
import com.example.samdapp.data.repository.ConsultationDocumentRepositoryImpl
import com.example.samdapp.data.repository.DiagnosisFeedbackRepositoryImpl
import com.example.samdapp.data.repository.EvaluateReportRepositoryImpl
import com.example.samdapp.data.repository.KernelReportRepositoryImpl
import com.example.samdapp.data.repository.PrescriptionRepositoryImpl
import com.example.samdapp.data.repository.ReferralRepositoryImpl
import com.example.samdapp.data.repository.ConsultationRepositoryImpl
import com.example.samdapp.data.repository.DoctorRepositoryImpl
import com.example.samdapp.data.repository.EncounterRepositoryImpl
import com.example.samdapp.data.repository.MedicalBackgroundRepositoryImpl
import com.example.samdapp.data.repository.PatientRepositoryImpl
import com.example.samdapp.data.repository.VitalsRepositoryImpl
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.repository.AuditLogRepository
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.sync.SyncStatus
import com.example.samdapp.domain.repository.AbhaProfileRepository
import com.example.samdapp.domain.repository.AilmentRepository
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.document.DocumentCaptureStore
import com.example.samdapp.domain.repository.ConsultationDocumentRepository
import com.example.samdapp.domain.repository.DiagnosisFeedbackRepository
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import com.example.samdapp.domain.repository.PrescriptionRepository
import com.example.samdapp.domain.repository.ReferralRepository
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.repository.DoctorRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.MedicalBackgroundRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.repository.VitalsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindPatientRepository(impl: PatientRepositoryImpl): PatientRepository

    @Binds @Singleton
    abstract fun bindMedicalBackgroundRepository(impl: MedicalBackgroundRepositoryImpl): MedicalBackgroundRepository

    @Binds @Singleton
    abstract fun bindEncounterRepository(impl: EncounterRepositoryImpl): EncounterRepository

    @Binds @Singleton
    abstract fun bindVitalsRepository(impl: VitalsRepositoryImpl): VitalsRepository

    @Binds @Singleton
    abstract fun bindConsultationRepository(impl: ConsultationRepositoryImpl): ConsultationRepository

    @Binds @Singleton
    abstract fun bindCaseRecordRepository(impl: CaseRecordRepositoryImpl): CaseRecordRepository

    @Binds @Singleton
    abstract fun bindDoctorRepository(impl: DoctorRepositoryImpl): DoctorRepository

    @Binds @Singleton
    abstract fun bindAuditLogger(impl: RoomAuditLogger): AuditLogger

    @Binds @Singleton
    abstract fun bindAuditLogRepository(impl: AuditLogRepositoryImpl): AuditLogRepository

    @Binds @Singleton
    abstract fun bindSyncStatus(impl: SyncStatusImpl): SyncStatus

    @Binds @Singleton
    abstract fun bindSyncOutboxScheduler(impl: WorkManagerSyncOutboxScheduler): SyncOutboxScheduler

    @Binds @Singleton
    abstract fun bindAssessmentQueueScheduler(impl: WorkManagerAssessmentScheduler): AssessmentQueueScheduler

    @Binds @Singleton
    abstract fun bindSyncOutboxRepository(impl: RoomSyncOutboxRepository): SyncOutboxRepository

    @Binds @Singleton
    abstract fun bindInFlightBatchStore(impl: DataStoreInFlightBatchStore): InFlightBatchStore

    @Binds @Singleton
    abstract fun bindAuthSession(impl: BackendAuthSession): AuthSession

    @Binds @Singleton
    abstract fun bindAuthTokenStore(impl: DataStoreAuthTokenStore): AuthTokenStore

    @Binds @Singleton
    abstract fun bindAbhaProfileRepository(impl: AbhaProfileRepositoryImpl): AbhaProfileRepository

    @Binds @Singleton
    abstract fun bindAilmentRepository(impl: AilmentRepositoryImpl): AilmentRepository

    @Binds @Singleton
    abstract fun bindPrescriptionRepository(impl: PrescriptionRepositoryImpl): PrescriptionRepository

    @Binds @Singleton
    abstract fun bindKernelReportRepository(impl: KernelReportRepositoryImpl): KernelReportRepository

    @Binds @Singleton
    abstract fun bindReferralRepository(impl: ReferralRepositoryImpl): ReferralRepository

    @Binds @Singleton
    abstract fun bindEvaluateReportRepository(impl: EvaluateReportRepositoryImpl): EvaluateReportRepository

    @Binds @Singleton
    abstract fun bindDiagnosisFeedbackRepository(impl: DiagnosisFeedbackRepositoryImpl): DiagnosisFeedbackRepository

    @Binds @Singleton
    abstract fun bindConsultationDocumentRepository(impl: ConsultationDocumentRepositoryImpl): ConsultationDocumentRepository

    @Binds @Singleton
    abstract fun bindDocumentCaptureStore(impl: AndroidDocumentCaptureStore): DocumentCaptureStore
}
