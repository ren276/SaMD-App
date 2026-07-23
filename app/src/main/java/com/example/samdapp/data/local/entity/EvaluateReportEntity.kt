package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/** [payloadJson] is the whole diagnosticSummary/nlemTreatment/brandMapping/safetyAndTriage tree
 *  Gson-serialized into one column — the nesting is too deep for a per-field TypeConverter scheme
 *  to pay for itself (see [com.example.samdapp.data.repository.EvaluateReportRepositoryImpl]). */
@Entity(tableName = "evaluate_reports", indices = [Index("caseRecordId")])
data class EvaluateReportEntity(
    @PrimaryKey val id: String,
    val caseRecordId: String,
    val payloadJson: String,
    val inferenceStartedAt: Instant,
    val inferenceEndedAt: Instant,
)
