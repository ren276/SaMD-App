package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.Consultation
import kotlinx.coroutines.flow.Flow

interface ConsultationRepository {
    suspend fun saveConsultation(consultation: Consultation): Result<Unit>
    suspend fun addAttachment(attachment: Attachment): Result<Unit>
    suspend fun updateTranscription(consultationId: String, transcription: String): Result<Unit>

    fun observeForEncounter(encounterId: String): Flow<Consultation?>

    /** H-18, Build 3a: one-shot lookup by the consultation's own id, used to derive
     *  [Consultation.patientId] for a document upload rather than trusting a caller value. */
    suspend fun getById(consultationId: String): Consultation?
}
