package com.example.samdapp.di

import android.content.Context
import androidx.room.Room
import com.example.samdapp.data.local.AppDatabase
import com.example.samdapp.data.local.MIGRATION_1_2
import com.example.samdapp.data.local.MIGRATION_2_3
import com.example.samdapp.data.local.MIGRATION_3_4
import com.example.samdapp.data.local.MIGRATION_4_5
import com.example.samdapp.data.local.security.DatabasePassphraseProvider
import com.example.samdapp.data.local.dao.AbhaProfileDao
import com.example.samdapp.data.local.dao.AilmentDao
import com.example.samdapp.data.local.dao.AllergyDao
import com.example.samdapp.data.local.dao.AttachmentDao
import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.dao.CaseRecordDao
import com.example.samdapp.data.local.dao.ConsultationDao
import com.example.samdapp.data.local.dao.EncounterDao
import com.example.samdapp.data.local.dao.FamilyHistoryEntryDao
import com.example.samdapp.data.local.dao.KernelReportDao
import com.example.samdapp.data.local.dao.MedicalHistoryItemDao
import com.example.samdapp.data.local.dao.MedicationEntryDao
import com.example.samdapp.data.local.dao.ObservationDao
import com.example.samdapp.data.local.dao.PatientDao
import com.example.samdapp.data.local.dao.PrescriptionDao
import com.example.samdapp.data.local.dao.ReferralDao
import com.example.samdapp.data.local.dao.SocialHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = DatabasePassphraseProvider(context).getOrCreatePassphrase()
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }

    @Provides fun providePatientDao(db: AppDatabase): PatientDao = db.patientDao()
    @Provides fun provideEncounterDao(db: AppDatabase): EncounterDao = db.encounterDao()
    @Provides fun provideObservationDao(db: AppDatabase): ObservationDao = db.observationDao()
    @Provides fun provideConsultationDao(db: AppDatabase): ConsultationDao = db.consultationDao()
    @Provides fun provideAttachmentDao(db: AppDatabase): AttachmentDao = db.attachmentDao()
    @Provides fun provideMedicalHistoryItemDao(db: AppDatabase): MedicalHistoryItemDao = db.medicalHistoryItemDao()
    @Provides fun provideMedicationEntryDao(db: AppDatabase): MedicationEntryDao = db.medicationEntryDao()
    @Provides fun provideAllergyDao(db: AppDatabase): AllergyDao = db.allergyDao()
    @Provides fun provideFamilyHistoryEntryDao(db: AppDatabase): FamilyHistoryEntryDao = db.familyHistoryEntryDao()
    @Provides fun provideSocialHistoryDao(db: AppDatabase): SocialHistoryDao = db.socialHistoryDao()
    @Provides fun provideCaseRecordDao(db: AppDatabase): CaseRecordDao = db.caseRecordDao()
    @Provides fun provideAuditLogDao(db: AppDatabase): AuditLogDao = db.auditLogDao()
    @Provides fun provideAbhaProfileDao(db: AppDatabase): AbhaProfileDao = db.abhaProfileDao()
    @Provides fun provideAilmentDao(db: AppDatabase): AilmentDao = db.ailmentDao()
    @Provides fun providePrescriptionDao(db: AppDatabase): PrescriptionDao = db.prescriptionDao()
    @Provides fun provideKernelReportDao(db: AppDatabase): KernelReportDao = db.kernelReportDao()
    @Provides fun provideReferralDao(db: AppDatabase): ReferralDao = db.referralDao()
}
