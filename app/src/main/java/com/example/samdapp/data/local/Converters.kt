package com.example.samdapp.data.local

import androidx.room.TypeConverter
import com.example.samdapp.domain.model.AllergyCategory
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.MedicalHistoryCategory
import com.example.samdapp.domain.model.MedicationKind
import com.example.samdapp.domain.model.ObservationSource
import com.example.samdapp.domain.model.ObservationType
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.domain.model.VitalsCaptureMethod
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

class Converters {
    @TypeConverter fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun localDateToIso(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun isoToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter fun observationTypeToString(value: ObservationType): String = value.name
    @TypeConverter fun stringToObservationType(value: String): ObservationType = ObservationType.valueOf(value)

    @TypeConverter fun observationSourceToString(value: ObservationSource): String = value.name
    @TypeConverter fun stringToObservationSource(value: String): ObservationSource = ObservationSource.valueOf(value)

    @TypeConverter fun attachmentTypeToString(value: AttachmentType): String = value.name
    @TypeConverter fun stringToAttachmentType(value: String): AttachmentType = AttachmentType.valueOf(value)

    @TypeConverter fun medicalHistoryCategoryToString(value: MedicalHistoryCategory): String = value.name
    @TypeConverter fun stringToMedicalHistoryCategory(value: String): MedicalHistoryCategory =
        MedicalHistoryCategory.valueOf(value)

    @TypeConverter fun medicationKindToString(value: MedicationKind): String = value.name
    @TypeConverter fun stringToMedicationKind(value: String): MedicationKind = MedicationKind.valueOf(value)

    @TypeConverter fun allergyCategoryToString(value: AllergyCategory): String = value.name
    @TypeConverter fun stringToAllergyCategory(value: String): AllergyCategory = AllergyCategory.valueOf(value)

    @TypeConverter fun caseStatusToString(value: CaseStatus): String = value.name
    @TypeConverter fun stringToCaseStatus(value: String): CaseStatus = CaseStatus.valueOf(value)

    @TypeConverter fun measurementTypeToString(value: MeasurementType): String = value.name
    @TypeConverter fun stringToMeasurementType(value: String): MeasurementType = MeasurementType.valueOf(value)

    @TypeConverter fun visibilityToString(value: Visibility): String = value.name
    @TypeConverter fun stringToVisibility(value: String): Visibility = Visibility.valueOf(value)

    @TypeConverter fun urgencyLevelToString(value: UrgencyLevel): String = value.name
    @TypeConverter fun stringToUrgencyLevel(value: String): UrgencyLevel = UrgencyLevel.valueOf(value)

    @TypeConverter fun referralStatusToString(value: ReferralStatus): String = value.name
    @TypeConverter fun stringToReferralStatus(value: String): ReferralStatus = ReferralStatus.valueOf(value)

    @TypeConverter fun vitalsCaptureMethodToString(value: VitalsCaptureMethod?): String? = value?.name
    @TypeConverter fun stringToVitalsCaptureMethod(value: String?): VitalsCaptureMethod? =
        value?.let { runCatching { VitalsCaptureMethod.valueOf(it) }.getOrNull() }

    @TypeConverter fun kernelDecisionToString(value: KernelDecision?): String? = value?.name
    @TypeConverter fun stringToKernelDecision(value: String?): KernelDecision? =
        value?.let { runCatching { KernelDecision.valueOf(it) }.getOrNull() }

    @TypeConverter fun stringListToJson(value: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), value)
    @TypeConverter fun jsonToStringList(value: String): List<String> =
        Json.decodeFromString(ListSerializer(String.serializer()), value)
}
