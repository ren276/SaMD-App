package com.example.samdapp.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.samdapp.data.local.AppDatabase
import com.example.samdapp.data.local.MIGRATION_1_2
import com.example.samdapp.data.local.MIGRATION_2_3
import com.example.samdapp.data.local.MIGRATION_3_4
import com.example.samdapp.data.local.MIGRATION_4_5
import com.example.samdapp.data.local.MIGRATION_5_6
import com.example.samdapp.data.local.MIGRATION_6_7
import com.example.samdapp.data.local.MIGRATION_7_8
import com.example.samdapp.data.local.MIGRATION_8_9
import com.example.samdapp.data.local.MIGRATION_9_10
import com.example.samdapp.data.local.MIGRATION_10_11
import com.example.samdapp.data.local.MIGRATION_11_12
import com.example.samdapp.data.local.security.DatabasePassphraseProvider
import com.example.samdapp.data.local.dao.AbhaProfileDao
import com.example.samdapp.data.local.dao.AilmentDao
import com.example.samdapp.data.local.dao.AllergyDao
import com.example.samdapp.data.local.dao.AttachmentDao
import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.dao.CaseRecordDao
import com.example.samdapp.data.local.dao.ConsultationDao
import com.example.samdapp.data.local.dao.DiagnosisFeedbackDao
import com.example.samdapp.data.local.dao.DoctorDao
import com.example.samdapp.data.local.dao.EncounterDao
import com.example.samdapp.data.local.dao.EvaluateReportDao
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

    /**
     * `MIGRATION_6_7`'s doctor-seed INSERTs only run when upgrading an EXISTING database — a
     * fresh install creates every table straight from the entity schema at the current version
     * and never touches migration bodies, so `doctors` would otherwise be empty and every
     * assignment attempt would fail with "no active doctors" (silently, until this was reported).
     * `onCreate` covers the fresh-install path with the same 9 mock doctors.
     */
    private val seedDoctorsOnCreate = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            val insertPrefix = "INSERT INTO `doctors` (`id`, `name`, `specialty`, `available`, `facilityName`, `registrationNumber`) VALUES "
            db.execSQL(insertPrefix + "('doc-gen-001', 'Dr. Anjali Sharma', 'General Physician', 1, 'Community Health Centre, Bhainsa', 'NMC/TS/2011/45231')")
            db.execSQL(insertPrefix + "('doc-gen-002', 'Dr. Rakesh Verma', 'General Physician', 0, 'District Hospital, Sitapur', 'NMC/UP/2008/33127')")
            db.execSQL(insertPrefix + "('doc-gyn-001', 'Dr. Priya Nair', 'Gynecology', 1, 'District Hospital, Sitapur', 'NMC/KL/2013/58902')")
            db.execSQL(insertPrefix + "('doc-ped-001', 'Dr. Suresh Iyer', 'Pediatrics', 1, 'Community Health Centre, Bhainsa', 'NMC/TN/2010/41765')")
            db.execSQL(insertPrefix + "('doc-ortho-001', 'Dr. Manoj Kumar', 'Orthopedics', 0, 'PHC Rampur', 'NMC/UP/2015/61140')")
            db.execSQL(insertPrefix + "('doc-derm-001', 'Dr. Kavita Desai', 'Dermatology', 1, 'District Hospital, Sitapur', 'NMC/MH/2012/50318')")
            db.execSQL(insertPrefix + "('doc-psych-001', 'Dr. Arjun Reddy', 'Psychiatry', 1, 'Community Health Centre, Bhainsa', 'NMC/TS/2014/59477')")
            db.execSQL(insertPrefix + "('doc-ent-001', 'Dr. Neha Joshi', 'ENT', 0, 'PHC Rampur', 'NMC/MH/2009/37652')")
            db.execSQL(insertPrefix + "('doc-oph-001', 'Dr. Vikram Singh', 'Ophthalmology', 1, 'District Hospital, Sitapur', 'NMC/UP/2011/46009')")
            db.execSQL(insertPrefix + "('doc-cardio-001', 'Dr. Ramesh Gupta', 'Cardiology', 1, 'District Hospital, Sitapur', 'NMC/UP/2005/11234')")
            db.execSQL(insertPrefix + "('doc-endo-001', 'Dr. Sunita Patel', 'Endocrinology', 1, 'City General Hospital, Ahmedabad', 'NMC/GJ/2010/45678')")
            db.execSQL(insertPrefix + "('doc-neuro-001', 'Dr. Amit Trivedi', 'Neurology', 1, 'District Hospital, Sitapur', 'NMC/UP/2012/89012')")
            db.execSQL(insertPrefix + "('doc-pulmo-001', 'Dr. Rajesh Khanna', 'Pulmonology', 1, 'Community Health Centre, Bhainsa', 'NMC/TS/2008/34567')")
            db.execSQL(insertPrefix + "('doc-id-001', 'Dr. Meena Kumar', 'Infectious Disease', 1, 'District Hospital, Sitapur', 'NMC/UP/2015/67890')")
            db.execSQL(insertPrefix + "('doc-uro-001', 'Dr. Prakash Rao', 'Urology', 1, 'PHC Rampur', 'NMC/MH/2011/23456')")
            db.execSQL(insertPrefix + "('doc-gastro-001', 'Dr. Anita Desai', 'Gastroenterology', 1, 'District Hospital, Sitapur', 'NMC/UP/2014/90123')")
            db.execSQL(insertPrefix + "('doc-crit-001', 'Dr. Sanjay Singh', 'Critical Care', 1, 'District Hospital, Sitapur', 'NMC/UP/2009/56789')")
            db.execSQL(insertPrefix + "('doc-int-001', 'Dr. Vivek Sharma', 'Internal Medicine', 1, 'Community Health Centre, Bhainsa', 'NMC/TS/2016/12345')")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = DatabasePassphraseProvider(context).getOrCreatePassphrase()
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .addCallback(seedDoctorsOnCreate)
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
    @Provides fun provideDoctorDao(db: AppDatabase): DoctorDao = db.doctorDao()
    @Provides fun provideEvaluateReportDao(db: AppDatabase): EvaluateReportDao = db.evaluateReportDao()
    @Provides fun provideDiagnosisFeedbackDao(db: AppDatabase): DiagnosisFeedbackDao = db.diagnosisFeedbackDao()
}
