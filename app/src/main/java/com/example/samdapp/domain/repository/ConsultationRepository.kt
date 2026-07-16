package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.Consultation
import kotlinx.coroutines.flow.Flow

interface ConsultationRepository {
    suspend fun saveConsultation(consultation: Consultation): Result<Unit>
    suspend fun addAttachment(attachment: Attachment): Result<Unit>
    suspend fun updateTranscription(consultationId: String, transcription: String): Result<Unit>

    fun observeForEncounter(encounterId: String): Flow<Consultation?>
}
