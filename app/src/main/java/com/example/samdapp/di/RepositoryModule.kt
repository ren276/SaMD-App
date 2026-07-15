package com.example.samdapp.di

import com.example.samdapp.data.local.audit.RoomAuditLogger
import com.example.samdapp.data.sync.MockSyncStatus
import com.example.samdapp.data.repository.CaseRecordRepositoryImpl
import com.example.samdapp.data.repository.ConsultationRepositoryImpl
import com.example.samdapp.data.repository.DoctorRepositoryImpl
import com.example.samdapp.data.repository.EncounterRepositoryImpl
import com.example.samdapp.data.repository.MedicalBackgroundRepositoryImpl
import com.example.samdapp.data.repository.PatientRepositoryImpl
import com.example.samdapp.data.repository.VitalsRepositoryImpl
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.sync.SyncStatus
import com.example.samdapp.domain.repository.CaseRecordRepository
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
    abstract fun bindSyncStatus(impl: MockSyncStatus): SyncStatus
}
