package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.Doctor

interface DoctorRepository {
    suspend fun getDoctors(): Result<List<Doctor>>
}
