package com.example.samdapp.domain.kernel

import com.example.samdapp.domain.model.AssessGateSnapshot
import com.example.samdapp.domain.model.SyncFailureReason
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.isServerPresent
import com.example.samdapp.domain.model.syncFailureReasonFor

/** What the assess gate decided for one case. */
sealed interface AssessGate {
    /** The server holds the case record: call `/api/v1/assess`. */
    data object Proceed : AssessGate

    /** Do not call; record an honest UNAVAILABLE with this cause. */
    data class Stop(val failure: KernelFailure) : AssessGate
}

/**
 * The assess gate, on this case's own chain and never on the global sync Result (a 200 batch can
 * still reject the patient individually, which is how a healthy kernel came to be labelled
 * unavailable).
 *
 * `/api/v1/assess` resolves exactly one server row, the case record, and on the server its patient
 * and encounter are foreign keys. So the case record's presence decides; the parents are read only
 * to name the cause when it is absent, parents first:
 *
 * 1. no local case record: [KernelFailure.RECORD_INCOMPLETE];
 * 2. the case record is on the server: [AssessGate.Proceed];
 * 3. the patient was refused as a duplicate: [KernelFailure.PATIENT_DUPLICATE];
 * 4. any row in the chain is FAILED or CONFLICT: [KernelFailure.CASE_SYNC_BLOCKED];
 * 5. otherwise the chain is still PENDING or RETRYABLE: [KernelFailure.CASE_NOT_SENT_YET].
 */
fun assessGateDecision(snapshot: AssessGateSnapshot?): AssessGate {
    if (snapshot == null) return AssessGate.Stop(KernelFailure.RECORD_INCOMPLETE)
    if (snapshot.caseRecord.isServerPresent()) return AssessGate.Proceed
    val patient = snapshot.patient
    if (patient != null && patient.syncState == SyncState.FAILED &&
        syncFailureReasonFor(patient.syncState, patient.syncErrorCode, patient.syncErrorMessage) == SyncFailureReason.DUPLICATE_RECORD
    ) {
        return AssessGate.Stop(KernelFailure.PATIENT_DUPLICATE)
    }
    val blocked = listOfNotNull(snapshot.patient, snapshot.encounter, snapshot.caseRecord)
        .any { it.syncState == SyncState.FAILED || it.syncState == SyncState.CONFLICT }
    return AssessGate.Stop(if (blocked) KernelFailure.CASE_SYNC_BLOCKED else KernelFailure.CASE_NOT_SENT_YET)
}
