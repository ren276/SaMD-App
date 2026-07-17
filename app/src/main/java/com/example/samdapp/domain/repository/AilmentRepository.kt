package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.AilmentEntry
import kotlinx.coroutines.flow.Flow

/**
 * [observeForEncounter] returns EVERY ailment for the encounter, public and private alike — this
 * is also the boundary the clinical kernel reads from (Phase 4). Visibility-based hiding for the
 * worker's UI is applied only in presentation-layer mapping to a worker-facing model (see
 * `AilmentListItem` in `CompounderViewModel`), never here (REQ-AIL-04). Do not add a
 * visibility-filtered query to this interface — that would make it easy to accidentally starve
 * the kernel path of private entries it is required to receive.
 */
interface AilmentRepository {
    suspend fun addAilment(ailment: AilmentEntry): Result<Unit>
    fun observeForEncounter(encounterId: String): Flow<List<AilmentEntry>>
    suspend fun markDeleted(id: String): Result<Unit>
}
