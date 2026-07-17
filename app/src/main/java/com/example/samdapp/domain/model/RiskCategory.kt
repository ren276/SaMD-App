package com.example.samdapp.domain.model

/** The kernel's coarse risk banding for a case — distinct from [UrgencyLevel] (which already
 *  exists for referrals): risk categorizes how serious the predicted condition could be,
 *  urgency categorizes how soon it needs attention. A LOW-risk finding can still be URGENT
 *  (e.g. a common but time-sensitive infection), so the two are kept as separate fields. */
enum class RiskCategory { LOW, MODERATE, HIGH, CRITICAL }
