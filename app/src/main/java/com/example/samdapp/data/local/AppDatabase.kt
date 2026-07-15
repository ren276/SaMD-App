package com.example.samdapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.samdapp.data.local.dao.AllergyDao
import com.example.samdapp.data.local.dao.AttachmentDao
import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.dao.CaseRecordDao
import com.example.samdapp.data.local.dao.ConsultationDao
import com.example.samdapp.data.local.dao.EncounterDao
import com.example.samdapp.data.local.dao.FamilyHistoryEntryDao
import com.example.samdapp.data.local.dao.MedicalHistoryItemDao
import com.example.samdapp.data.local.dao.MedicationEntryDao
import com.example.samdapp.data.local.dao.ObservationDao
import com.example.samdapp.data.local.dao.PatientDao
import com.example.samdapp.data.local.dao.SocialHistoryDao
import com.example.samdapp.data.local.dao.SymptomDao
import com.example.samdapp.data.local.entity.AllergyEntity
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.FamilyHistoryEntryEntity
import com.example.samdapp.data.local.entity.MedicalHistoryItemEntity
import com.example.samdapp.data.local.entity.MedicationEntryEntity
import com.example.samdapp.data.local.entity.ObservationEntity
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.data.local.entity.SocialHistoryEntity
import com.example.samdapp.data.local.entity.SymptomEntity

@Database(
    entities = [
        PatientEntity::class,
        EncounterEntity::class,
        ObservationEntity::class,
        ConsultationEntity::class,
        SymptomEntity::class,
        AttachmentEntity::class,
        MedicalHistoryItemEntity::class,
        MedicationEntryEntity::class,
        AllergyEntity::class,
        FamilyHistoryEntryEntity::class,
        SocialHistoryEntity::class,
        CaseRecordEntity::class,
        AuditLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun encounterDao(): EncounterDao
    abstract fun observationDao(): ObservationDao
    abstract fun consultationDao(): ConsultationDao
    abstract fun symptomDao(): SymptomDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun medicalHistoryItemDao(): MedicalHistoryItemDao
    abstract fun medicationEntryDao(): MedicationEntryDao
    abstract fun allergyDao(): AllergyDao
    abstract fun familyHistoryEntryDao(): FamilyHistoryEntryDao
    abstract fun socialHistoryDao(): SocialHistoryDao
    abstract fun caseRecordDao(): CaseRecordDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        const val DATABASE_NAME = "samd_app.db"
    }
}
