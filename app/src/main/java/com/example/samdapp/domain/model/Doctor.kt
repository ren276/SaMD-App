package com.example.samdapp.domain.model

data class Doctor(
    val id: String,
    val name: String,
    val specialty: String,
    val available: Boolean,
    val facilityName: String?,
    /** NMC/State-Medical-Council registration number — printed on the final report's physician
     *  signature block (REQ-RPT-02 legal footer). Mock reference data; null-safe for older assets. */
    val registrationNumber: String?,
)
