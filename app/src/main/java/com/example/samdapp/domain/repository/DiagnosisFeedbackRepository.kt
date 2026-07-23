package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.DiagnosisFeedback

interface DiagnosisFeedbackRepository {
    suspend fun save(feedback: DiagnosisFeedback): Result<Unit>
    suspend fun getForCase(caseRecordId: String): DiagnosisFeedback?
}
