package com.example.samdapp.domain.model

import java.time.Instant

/** Measurable (vitals-adjacent, e.g. "fever measured at 101°F") vs non-measurable (subjective,
 *  e.g. "stomach ache, sharp, 3 days"). Persisted as [name]; adding a type later is additive. */
enum class MeasurementType { MEASURABLE, NON_MEASURABLE }

/**
 * Per-entry disclosure scope. Defaults to [PUBLIC] at the creation site.
 *
 * [PRIVATE] hides the entry's text/audio from the PHC worker's UI only — never render private
 * text into worker-facing UI state (not merely visually hidden). It does NOT hide the entry from
 * the pseudonymized clinical-kernel payload: the kernel receives ALL ailments regardless of
 * visibility. Getting this backwards is the easy mistake — see [AilmentEntry] KDoc and the
 * KernelPayload assembly.
 */
enum class Visibility { PUBLIC, PRIVATE }

/**
 * A single ailment captured during an encounter — the Phase 2 replacement for the old free-text
 * "complaint"/Symptom. Table introduced additively in Phase 0; `MIGRATION_3_4` backfilled every
 * `symptoms` row into this table and dropped `symptoms` when Phase 2 landed.
 *
 * Visibility is per-entry (not per-consultation): [visibility] PRIVATE hides this entry from the
 * worker UI but the kernel still receives it. [audioLocalUri] (private-entry voice note) stays
 * filesystem-local — never uploaded, never given a play button in the worker role, delete-only.
 *
 * Dual timestamps prove the offline-first architecture accounts for rural network latency:
 * [capturedAtOffline] is set when the worker records the entry on-device; [syncedToCloudAt] is
 * null until a (future, real) sync writes it upstream — the two must be visibly different in the
 * audit trail, never defaulted to the same value.
 */
data class AilmentEntry(
    val id: String,
    val patientId: String,
    val encounterId: String,
    val description: String,
    val measurementType: MeasurementType,
    val visibility: Visibility,
    val measuredValue: Double?,
    val measuredUnit: String?,
    val severity: Int?,
    val onset: String?,
    val duration: String?,
    val qualifiers: String?,
    val audioLocalUri: String?,
    val capturedAtOffline: Instant,
    val syncedToCloudAt: Instant?,
    val deletedAt: Instant?,
    val createdAt: Instant,
)
