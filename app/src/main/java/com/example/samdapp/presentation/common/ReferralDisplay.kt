package com.example.samdapp.presentation.common

import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.UrgencyLevel

fun UrgencyLevel.displayLabel(): String = when (this) {
    UrgencyLevel.ROUTINE -> "Routine"
    UrgencyLevel.URGENT -> "Urgent"
    UrgencyLevel.EMERGENCY -> "Emergency"
}

fun ReferralStatus.displayLabel(): String = when (this) {
    ReferralStatus.QUEUED -> "Queued"
    ReferralStatus.SENT -> "Sent"
    ReferralStatus.ACKNOWLEDGED -> "Acknowledged"
    ReferralStatus.CANCELLED -> "Cancelled"
}
