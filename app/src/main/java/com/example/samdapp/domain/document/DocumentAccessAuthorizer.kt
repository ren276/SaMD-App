package com.example.samdapp.domain.document

import com.example.samdapp.domain.auth.CadreTier
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.auth.toCadreTier
import com.example.samdapp.domain.model.ConsultationDocument

/**
 * H-18, Build 3c. The single seam for "can this viewer see this document's raw decrypted
 * content" — replaces 3a's interim uploader-or-DOCTOR gate. [com.example.samdapp.presentation.documents.DocumentViewerViewModel]
 * is the only caller, so there is one place to reason about and test the gate rather than a check
 * duplicated across the ViewModel and the viewer composable.
 *
 * Tier-uniform for now: every interpretive [ConsultationDocument] is gated the same way regardless
 * of [com.example.samdapp.domain.model.RecordTypeCode] — per-type gating is a deferred refinement.
 *
 * **H-06 caveat**: [com.example.samdapp.domain.auth.UserRole] is self-asserted at login, so this
 * is an accountability/intent control (a bypass leaves a userId in the audit trail), not access
 * control against a determined actor — same caveat as [com.example.samdapp.domain.usecase.RetractConsultationDocumentUseCase]'s
 * gate and the H-17 `canOpenDoctorReview` gate.
 */
enum class DocumentAccessOutcome(val auditValue: String, val granted: Boolean) {
    /** Uploader exception (operator-signed, named — not a hole): whoever captured/uploaded a
     *  document can always open it, regardless of their tier. Blocking an ASHA from a document she
     *  photographed herself would be hostile and pointless. */
    GRANTED_UPLOADER("granted_uploader", granted = true),

    /** [CadreTier.PHYSICIAN] full access. */
    GRANTED_TIER("granted", granted = true),

    /** [CadreTier.LICENSED_CLINICAL] and [CadreTier.COMMUNITY] both land here for now — both see
     *  metadata only, never raw content, until a future refinement diverges METADATA from
     *  ABSTRACTED for a richer field set. */
    DENIED_TIER("denied_tier", granted = false),
}

object DocumentAccessAuthorizer {
    fun authorize(document: ConsultationDocument, session: UserSession): DocumentAccessOutcome = when {
        session.userId == document.uploaderUserId -> DocumentAccessOutcome.GRANTED_UPLOADER
        session.role.toCadreTier() == CadreTier.PHYSICIAN -> DocumentAccessOutcome.GRANTED_TIER
        else -> DocumentAccessOutcome.DENIED_TIER
    }
}
