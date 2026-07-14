package com.example.samdapp.domain.vitalssource

import com.example.samdapp.domain.model.VitalsReading

/** Seam a real BLE/device integration plugs into later. [readVitals] returns whichever
 * device-pollable vitals are available — never [com.example.samdapp.domain.model.VitalsSnapshot]'s
 * manual-only fields (pain score, urinalysis), and the caller may still edit every value before save. */
interface VitalsSource {
    suspend fun readVitals(): VitalsReading
}
