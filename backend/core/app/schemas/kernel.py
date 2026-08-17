"""Kernel proxy request models. See api-contract.md section 5.

Field names match KernelAssessmentRequestDto and EvaluateRequestDto exactly, byte for byte, so
the Android app's existing request bodies work unchanged once RetrofitKernelSource and
RetrofitEvaluateSource are rebased from KERNEL_BASE_URL to BACKEND_BASE_URL in Phase 6.

extra="forbid" is the first of the two PHI boundary mechanisms (app/adapters/kernel/phi_guard.py
documents both). Only the pseudonymized clinical fields are declared; nothing else can arrive.

case_token here carries the caller's real case_record_id, exactly as it does on the wire today
(api-contract.md section 5.2: "case_token remains the value the client sends, which is
CaseRecord.id today"). The service layer is what substitutes the HMAC pseudonym before the value
ever reaches the kernel; the schema does not know about the substitution.
"""

from __future__ import annotations

from app.schemas.common import StrictModel


class KernelAssessRequest(StrictModel):
    """POST /api/v1/assess. Mirrors KernelAssessmentRequestDto."""

    case_token: str
    age: int
    sex: str
    systolic_bp: float
    diastolic_bp: float
    bmi: float
    heart_rate: float
    random_glucose: float
    spo2: float


class KernelEvaluateRequest(StrictModel):
    """POST /api/v1/evaluate. Mirrors EvaluateRequestDto.

    random_glucose, respiratory_rate, and temperature are optional on the wire, matching the
    Kotlin DTO's nullable fields with Gson's null-omission semantics.
    """

    case_token: str
    symptom_string: str
    age: int
    sex: str
    systolic_bp: float
    diastolic_bp: float
    bmi: float
    heart_rate: float
    random_glucose: float | None = None
    spo2: float
    respiratory_rate: float | None = None
    temperature: float | None = None
