package com.example.samdapp.presentation.common

import com.example.samdapp.domain.auth.UserRole

/** Shared between LoginScreen (role picker) and Home (signed-in-as display). */
fun UserRole.displayLabel(): String = when (this) {
    UserRole.ASHA_WORKER -> "ASHA worker"
    UserRole.NURSE -> "Nurse"
    UserRole.COMPOUNDER -> "Compounder"
    UserRole.DOCTOR -> "Doctor"
}
