package com.example.samdapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.samdapp.data.local.dao.AbhaProfileDao
import com.example.samdapp.data.local.dao.AilmentDao
import com.example.samdapp.data.local.dao.AllergyDao
import com.example.samdapp.data.local.dao.AttachmentDao
import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.dao.CaseRecordDao
import com.example.samdapp.data.local.dao.ConsultationDao
import com.example.samdapp.data.local.dao.DoctorDao
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
import com.example.samdapp.data.local.entity.AbhaProfileEntity
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.data.local.entity.AllergyEntity
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.data.local.entity.DoctorEntity
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.FamilyHistoryEntryEntity
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.data.local.entity.MedicalHistoryItemEntity
import com.example.samdapp.data.local.entity.MedicationEntryEntity
import com.example.samdapp.data.local.entity.MedicationLineEntity
import com.example.samdapp.data.local.entity.ObservationEntity
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.data.local.entity.PrescriptionEntity
import com.example.samdapp.data.local.entity.ReferralEntity
import com.example.samdapp.data.local.entity.SocialHistoryEntity

@Database(
    entities = [
        PatientEntity::class,
        EncounterEntity::class,
        ObservationEntity::class,
        ConsultationEntity::class,
        AttachmentEntity::class,
        MedicalHistoryItemEntity::class,
        MedicationEntryEntity::class,
        AllergyEntity::class,
        FamilyHistoryEntryEntity::class,
        SocialHistoryEntity::class,
        CaseRecordEntity::class,
        AuditLogEntity::class,
        AbhaProfileEntity::class,
        AilmentEntity::class,
        PrescriptionEntity::class,
        MedicationLineEntity::class,
        KernelReportEntity::class,
        ReferralEntity::class,
        DoctorEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun encounterDao(): EncounterDao
    abstract fun observationDao(): ObservationDao
    abstract fun consultationDao(): ConsultationDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun medicalHistoryItemDao(): MedicalHistoryItemDao
    abstract fun medicationEntryDao(): MedicationEntryDao
    abstract fun allergyDao(): AllergyDao
    abstract fun familyHistoryEntryDao(): FamilyHistoryEntryDao
    abstract fun socialHistoryDao(): SocialHistoryDao
    abstract fun caseRecordDao(): CaseRecordDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun abhaProfileDao(): AbhaProfileDao
    abstract fun ailmentDao(): AilmentDao
    abstract fun prescriptionDao(): PrescriptionDao
    abstract fun kernelReportDao(): KernelReportDao
    abstract fun referralDao(): ReferralDao
    abstract fun doctorDao(): DoctorDao

    companion object {
        const val DATABASE_NAME = "samd_app.db"
    }
}
