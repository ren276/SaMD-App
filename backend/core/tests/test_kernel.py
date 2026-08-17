"""Kernel proxy endpoints. api-contract.md section 5.

The kernel is mocked with httpx.MockTransport (ScriptedKernel below), never a live process. CI
must not depend on the real XGBoost server being reachable.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

import httpx
import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.adapters.kernel.circuit_breaker import KernelCircuitBreakers
from app.config import get_settings
from app.deps import CurrentWorker, kernel_breakers_dep, kernel_client_dep
from app.errors import ErrorCode, SamdError
from app.models.audit import AuditEvent
from app.models.enums import AuditAction, CaseStatus, KernelCallOutcome
from app.models.kernel import EvaluateReport, KernelReport
from app.models.sync import KernelAssessment, KernelCallLog
from app.services import kernel as kernel_service
from tests.conftest import TEST_FACILITY_ID, TEST_WORKER_ID
from tests.test_encounters import CASE_ID, seed_case_record, seed_patient_and_encounter

# ---------------------------------------------------------------------------
# Scripted kernel double
# ---------------------------------------------------------------------------


@dataclass
class ScriptedKernel:
    """A queue of canned responses or exceptions, played back in order, one per outbound call.

    Exhausting the queue is a test bug (an unexpected extra call, most often a retry that should
    not exist), so it fails loudly rather than returning something plausible.
    """

    calls: list[httpx.Request] = field(default_factory=list)
    _queue: list[tuple[str, Any]] = field(default_factory=list)

    def push_response(self, status_code: int, json_body: dict[str, Any]) -> None:
        self._queue.append(("response", (status_code, json_body)))

    def push_malformed(self, status_code: int, text: str) -> None:
        self._queue.append(("malformed", (status_code, text)))

    def push_exception(self, exc: Exception) -> None:
        self._queue.append(("exception", exc))

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        if not self._queue:
            raise AssertionError(
                "ScriptedKernel queue exhausted: an outbound call happened that no test step "
                "scripted. If this is expected, push another response; if not, something is "
                "retrying when it must not (see app/adapters/kernel/client.py: no retries)."
            )
        kind, payload = self._queue.pop(0)
        if kind == "exception":
            raise payload
        if kind == "malformed":
            status_code, text = payload
            return httpx.Response(status_code, text=text, request=request)
        status_code, json_body = payload
        return httpx.Response(status_code, json=json_body, request=request)


def _build_client(handler: Any) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://kernel.test")


@pytest.fixture
def scripted_kernel() -> ScriptedKernel:
    return ScriptedKernel()


@pytest.fixture
def breakers() -> KernelCircuitBreakers:
    # Short threshold and cooldown: the circuit-breaker tests need to trip and recover quickly.
    return KernelCircuitBreakers(threshold=2, cooldown_seconds=0.05)


@pytest.fixture
def kernel_overrides(
    app: Any, scripted_kernel: ScriptedKernel, breakers: KernelCircuitBreakers
) -> None:
    """Point the app's kernel dependencies at the scripted double for the duration of a test.

    ASGITransport never runs the FastAPI lifespan (see tests/conftest.py's app fixture docstring),
    so app.state.kernel_client is never populated the way production does it. Overriding the
    dependency is the correct substitute, not a workaround.
    """
    app.dependency_overrides[kernel_client_dep] = lambda: _build_client(scripted_kernel.handler)
    app.dependency_overrides[kernel_breakers_dep] = lambda: breakers


ASSESS_BODY: dict[str, Any] = {
    "case_token": CASE_ID,
    "age": 35,
    "sex": "F",
    "systolic_bp": 128.0,
    "diastolic_bp": 84.0,
    "bmi": 24.6,
    "heart_rate": 88.0,
    "random_glucose": 104.0,
    "spo2": 97.0,
}

ASSESS_KERNEL_RESPONSE: dict[str, Any] = {
    "case_token": "will-be-overwritten-by-the-pseudonym",
    "safety_screen_passed": True,
    "triage_urgency": "ROUTINE",
    "differential_diagnosis": [
        {
            "condition_tier": "Viral upper respiratory infection",
            "probability": 0.81,
            "evidence_for": ["fever 3 days", "spo2 97"],
            "evidence_against": ["no chest findings"],
        }
    ],
    "recommended_investigations": ["CBC"],
    "model_metadata": {"model_version": "xgb-2026-06-11", "inference_time_ms": 143},
}

EVALUATE_BODY: dict[str, Any] = {
    "case_token": CASE_ID,
    "symptom_string": "fever, body ache, dry cough",
    "age": 35,
    "sex": "F",
    "systolic_bp": 128.0,
    "diastolic_bp": 84.0,
    "bmi": 24.6,
    "heart_rate": 88.0,
    "spo2": 97.0,
    "respiratory_rate": 18.0,
    "temperature": 38.4,
}

# Exact tree from api-contract.md section 5.4, mixed casing included. The proxy must not touch
# a single key here.
EVALUATE_KERNEL_RESPONSE: dict[str, Any] = {
    "case_token": "will-be-overwritten-by-the-pseudonym",
    "diagnostic_summary": {
        "primary_icd_candidate": "J06",
        "primary_ailment_name": "Acute upper respiratory infection",
        "differential": [
            {
                "icd_candidate": "J06",
                "adjusted_confidence": 0.78,
                "original_symptom_confidence": 0.74,
                "vitals_tier_alignment": 0.9,
                "why": "fever with dry cough and normal spo2",
            }
        ],
    },
    "nlem_treatment": {
        "recommendedDrug": "Paracetamol",
        "levelOfHealthcare": ["PHC"],
        "availableAtPHC": True,
        "dosageForms": ["Tablet 500 mg"],
        "pediatricDose": None,
        "citation": {
            "source": "NLEM 2022",
            "page": 41,
            "section": "2.1",
            "subsection": None,
            "item_num": "2.1.1",
        },
        "confidence": "HIGH",
        "referralReason": None,
        "matchedDisease": {
            "icd_candidate": "J06",
            "disease_name": "Acute upper respiratory infection",
        },
    },
    "brand_mapping": {
        "generic_name": "Paracetamol",
        "jan_aushadhi_brand": "Paracetamol IP 500mg",
        "commercial_brands": ["Crocin", "Dolo 650"],
        "brand_mapping_available": True,
    },
    "safety_and_triage": {
        "vitals_triage": {
            "bp_grade": "NORMAL",
            "pulse": "NORMAL",
            "respiratory_rate": "NORMAL",
            "spo2": "NORMAL",
            "temperature": "FEVER",
            "bmi": "NORMAL",
            "glucose": "NORMAL",
            "overall_urgency": "ROUTINE",
        },
        "requiresHumanReview": False,
        "pediatric_referral_flag": False,
        "failure_reason": None,
    },
}


async def _seed(client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession) -> None:
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.SAVED_LOCALLY)


async def _kernel_call_log_rows(session: AsyncSession) -> list[KernelCallLog]:
    return list((await session.execute(select(KernelCallLog))).scalars())


async def _assessment_rows(session: AsyncSession) -> list[KernelAssessment]:
    return list((await session.execute(select(KernelAssessment))).scalars())


# ---------------------------------------------------------------------------
# Success
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("kernel_overrides")
async def test_assess_forwards_and_returns_the_envelope(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_kernel.push_response(200, ASSESS_KERNEL_RESPONSE)

    response = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert response.status_code == 200

    body = response.json()
    assert body["success"] is True
    data = body["data"]
    # The real case_record_id is restored on the way out. The kernel never saw it.
    assert data["case_token"] == CASE_ID
    assert data["triage_urgency"] == "ROUTINE"
    top_tier = data["differential_diagnosis"][0]["condition_tier"]
    assert top_tier == "Viral upper respiratory infection"
    assert body["meta"]["request_id"]

    assert len(scripted_kernel.calls) == 1
    sent = scripted_kernel.calls[0]
    # The kernel-facing request never carries the real id.
    assert CASE_ID.encode() not in sent.content

    rows = await _kernel_call_log_rows(session)
    assert len(rows) == 1
    assert rows[0].outcome == KernelCallOutcome.SUCCESS.value
    assert rows[0].case_record_id == CASE_ID
    assert rows[0].case_token != CASE_ID
    assert rows[0].input_sha256
    assert rows[0].output_sha256
    assert rows[0].model_version == "xgb-2026-06-11"
    assert rows[0].completed_at is not None

    audit_rows = list(
        (
            await session.execute(
                select(AuditEvent).where(
                    AuditEvent.action == AuditAction.KERNEL_CALL_FORWARDED.value
                )
            )
        ).scalars()
    )
    assert len(audit_rows) == 1
    assert audit_rows[0].case_record_id == CASE_ID


# ---------------------------------------------------------------------------
# kernel_assessments: raw model output only, and kernel_reports is not the proxy's to write
# (D-9, D-10)
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("kernel_overrides")
async def test_assess_stores_the_response_verbatim_in_kernel_assessments(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_kernel.push_response(200, ASSESS_KERNEL_RESPONSE)

    response = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert response.status_code == 200

    rows = await _assessment_rows(session)
    assert len(rows) == 1
    row = rows[0]

    # Verbatim. case_token still holds the pseudonym: the swap back to the real id happens in the
    # HTTP response only, never in what is stored.
    assert row.raw_response == ASSESS_KERNEL_RESPONSE
    assert row.raw_response["case_token"] != CASE_ID

    # Provenance the server owns, stamped from the token and never from the body.
    assert row.case_record_id == CASE_ID
    assert row.facility_id == TEST_FACILITY_ID
    assert row.worker_id == TEST_WORKER_ID
    assert row.endpoint == "ASSESS"

    # Copied out of the response, not computed from it.
    assert row.model_version == "xgb-2026-06-11"
    assert row.inference_time_ms == 143
    assert row.safety_screen_passed is True
    assert row.triage_urgency == "ROUTINE"

    # The row joins to its call log by request_id, and both agree on the response bytes.
    log_rows = await _kernel_call_log_rows(session)
    assert len(log_rows) == 1
    assert row.request_id == log_rows[0].request_id
    assert row.response_sha256 == log_rows[0].output_sha256


@pytest.mark.usefixtures("kernel_overrides")
async def test_the_proxy_never_writes_kernel_reports(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    """D-9, D-10. kernel_reports is device-owned and written only by sync push (Phase 4).

    The proxy used to write one row per call, filling predicted_condition, confidence_score,
    risk_category and required_human_verification with backend-computed values stored beside
    model_version. That attributed backend arithmetic to a named model version and collided with
    the device's upsert-per-case posture. This test is the regression guard, and it is the reason
    a future "just persist the assessment while we are here" cannot come back unnoticed.
    """
    await _seed(client, auth_headers, session)
    scripted_kernel.push_response(200, ASSESS_KERNEL_RESPONSE)

    assert (
        await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    ).status_code == 200

    assert list((await session.execute(select(KernelReport))).scalars()) == []


@pytest.mark.usefixtures("kernel_overrides")
async def test_kernel_assessments_stores_no_derived_column() -> None:
    """The hard rule for this table, asserted against the mapped columns rather than trusted to
    review. A derived value can only be stored here if someone adds a column for it."""
    forbidden = {
        "predicted_condition",
        "confidence_score",
        "risk_category",
        "required_human_verification",
        "requires_human_verification",
        "reasoning_summary",
        "urgency_level",
        "inference_source",
    }
    columns = {column.name for column in KernelAssessment.__table__.columns}
    assert columns & forbidden == set()


@pytest.mark.usefixtures("kernel_overrides")
async def test_evaluate_stores_an_assessment_row_with_the_assess_only_columns_null(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    """/evaluate carries no safety_screen_passed, no triage_urgency and no model_metadata. Those
    columns are NULL, not defaulted: there is nothing to record, and inventing a value is the
    mistake this table was created to remove."""
    await _seed(client, auth_headers, session)
    scripted_kernel.push_response(200, EVALUATE_KERNEL_RESPONSE)

    response = await client.post("/api/v1/evaluate", json=EVALUATE_BODY, headers=auth_headers)
    assert response.status_code == 200

    rows = await _assessment_rows(session)
    assert len(rows) == 1
    row = rows[0]
    assert row.endpoint == "EVALUATE"
    assert row.raw_response == EVALUATE_KERNEL_RESPONSE
    assert row.safety_screen_passed is None
    assert row.triage_urgency is None
    assert row.model_version is None
    assert row.inference_time_ms is None
    assert row.response_sha256


@pytest.mark.usefixtures("kernel_overrides")
async def test_a_failed_call_writes_a_log_row_and_no_assessment_row(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    """Only a successful call that returned parseable JSON produces an assessment row. A failure
    has no model output to store, and kernel_call_log plus audit_events already cover it."""
    await _seed(client, auth_headers, session)
    scripted_kernel.push_exception(httpx.ConnectError("down"))

    assert (
        await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    ).status_code == 502

    assert len(await _kernel_call_log_rows(session)) == 1
    assert await _assessment_rows(session) == []


@pytest.mark.usefixtures("kernel_overrides")
async def test_a_malformed_response_writes_a_log_row_and_no_assessment_row(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_kernel.push_malformed(200, "not json at all {{{")

    assert (
        await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    ).status_code == 502

    rows = await _kernel_call_log_rows(session)
    assert len(rows) == 1
    assert rows[0].outcome == KernelCallOutcome.MALFORMED_RESPONSE.value
    assert await _assessment_rows(session) == []


@pytest.mark.usefixtures("kernel_overrides")
async def test_a_kernel_response_with_a_wrong_typed_field_stores_null_not_a_default(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    """A contract drift at the kernel must surface as a NULL an operator can query for, never as
    a substituted value that reads like something the model said."""
    await _seed(client, auth_headers, session)
    scripted_kernel.push_response(
        200,
        {
            **ASSESS_KERNEL_RESPONSE,
            "safety_screen_passed": "yes",
            "model_metadata": {"model_version": 7, "inference_time_ms": "fast"},
        },
    )

    assert (
        await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    ).status_code == 200

    rows = await _assessment_rows(session)
    assert len(rows) == 1
    assert rows[0].safety_screen_passed is None
    assert rows[0].model_version is None
    assert rows[0].inference_time_ms is None
    # The unparseable values are still there in the raw body, exactly as sent.
    assert rows[0].raw_response["safety_screen_passed"] == "yes"


@pytest.mark.usefixtures("kernel_overrides")
async def test_evaluate_preserves_mixed_casing_byte_for_byte(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    """api-contract.md section 5.4: 'the backend does not reshape, rename, or re-key any part
    of it.' Asserted by comparing the whole tree, not spot fields."""
    await _seed(client, auth_headers, session)
    scripted_kernel.push_response(200, EVALUATE_KERNEL_RESPONSE)

    response = await client.post("/api/v1/evaluate", json=EVALUATE_BODY, headers=auth_headers)
    assert response.status_code == 200

    data = response.json()["data"]
    expected = {**EVALUATE_KERNEL_RESPONSE, "case_token": CASE_ID}
    assert data == expected

    # payload_json in evaluate_reports holds exactly what the kernel returned, case_token
    # included: the pseudonym-to-real-id swap happens only in the HTTP response the caller
    # receives, not in what gets persisted for internal use.
    stored = list((await session.execute(select(EvaluateReport))).scalars())
    assert len(stored) == 1
    assert stored[0].payload_json == EVALUATE_KERNEL_RESPONSE


# ---------------------------------------------------------------------------
# PHI guard, exercised directly against the service (see the module docstring in
# test_kernel_phi_guard.py for why this cannot be reached through the HTTP schema)
# ---------------------------------------------------------------------------


async def test_phi_rejection_writes_a_kernel_call_log_row_and_makes_no_network_call(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
    breakers: KernelCircuitBreakers,
) -> None:
    await _seed(client, auth_headers, session)
    kernel_client = _build_client(scripted_kernel.handler)
    worker = CurrentWorker(
        worker_id=TEST_WORKER_ID,
        role="ASHA_WORKER",
        facility_id=TEST_FACILITY_ID,
        device_id="device-abc12345",
        must_change_pin=False,
    )
    dirty_body = {**ASSESS_BODY, "full_name": "Sunita Devi"}

    with pytest.raises(SamdError) as caught:
        await kernel_service.assess(
            session, worker, get_settings(), kernel_client, breakers, "req-phi-1", dirty_body
        )
    assert caught.value.code is ErrorCode.KERN_IDENTITY_LEAK_BLOCKED

    assert scripted_kernel.calls == []

    rows = await _kernel_call_log_rows(session)
    assert len(rows) == 1
    assert rows[0].outcome == KernelCallOutcome.PHI_REJECTED.value
    assert rows[0].error_code == ErrorCode.KERN_IDENTITY_LEAK_BLOCKED.value
    assert rows[0].http_status is None

    failed_audit = list(
        (
            await session.execute(
                select(AuditEvent).where(AuditEvent.action == AuditAction.KERNEL_CALL_FAILED.value)
            )
        ).scalars()
    )
    assert len(failed_audit) == 1


# ---------------------------------------------------------------------------
# Error mapping
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("kernel_overrides")
async def test_read_timeout_maps_to_kern_timeout(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_kernel.push_exception(httpx.ReadTimeout("simulated slow kernel"))

    response = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert response.status_code == 504
    assert response.json()["code"] == ErrorCode.KERN_TIMEOUT.value

    rows = await _kernel_call_log_rows(session)
    assert rows[0].outcome == KernelCallOutcome.TIMEOUT.value


@pytest.mark.usefixtures("kernel_overrides")
async def test_connect_error_maps_to_kern_unreachable(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_kernel.push_exception(httpx.ConnectError("simulated LAN outage"))

    response = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert response.status_code == 502
    assert response.json()["code"] == ErrorCode.KERN_UNREACHABLE.value

    rows = await _kernel_call_log_rows(session)
    assert rows[0].outcome == KernelCallOutcome.UNREACHABLE.value


@pytest.mark.usefixtures("kernel_overrides")
async def test_kernel_500_maps_to_kern_internal_error(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_kernel.push_response(500, {"error": "boom"})

    response = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert response.status_code == 502
    assert response.json()["code"] == ErrorCode.KERN_INTERNAL_ERROR.value

    rows = await _kernel_call_log_rows(session)
    assert rows[0].outcome == KernelCallOutcome.KERNEL_ERROR.value
    assert rows[0].http_status == 500


@pytest.mark.usefixtures("kernel_overrides")
async def test_kernel_4xx_preserves_upstream_detail_and_is_payload_rejected(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_kernel.push_response(
        422,
        {
            "error": "bad_vitals",
            "message": "systolic_bp out of physiological range",
            "case_token": "x",
        },
    )

    response = await client.post("/api/v1/evaluate", json=EVALUATE_BODY, headers=auth_headers)
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == ErrorCode.KERN_PAYLOAD_REJECTED.value
    assert "systolic_bp out of physiological range" in body["detail"]

    rows = await _kernel_call_log_rows(session)
    assert rows[0].outcome == KernelCallOutcome.PAYLOAD_REJECTED.value


@pytest.mark.usefixtures("kernel_overrides")
async def test_malformed_kernel_response_maps_to_kern_malformed_response(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_kernel.push_malformed(200, "not json at all {{{")

    response = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert response.status_code == 502
    assert response.json()["code"] == ErrorCode.KERN_MALFORMED_RESPONSE.value

    rows = await _kernel_call_log_rows(session)
    assert rows[0].outcome == KernelCallOutcome.MALFORMED_RESPONSE.value


# ---------------------------------------------------------------------------
# No retry
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("kernel_overrides")
async def test_a_failing_call_produces_exactly_one_log_row_and_one_outbound_request(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_kernel.push_exception(httpx.ConnectError("down"))

    response = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert response.status_code == 502

    assert len(scripted_kernel.calls) == 1
    rows = await _kernel_call_log_rows(session)
    assert len(rows) == 1


# ---------------------------------------------------------------------------
# Circuit breaker
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("kernel_overrides")
async def test_circuit_opens_after_threshold_and_fails_fast(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
    breakers: KernelCircuitBreakers,
) -> None:
    await _seed(client, auth_headers, session)
    for _ in range(breakers.assess.threshold):
        scripted_kernel.push_exception(httpx.ConnectError("down"))

    for _ in range(breakers.assess.threshold):
        r = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
        assert r.status_code == 502

    # The next call must not touch the network: nothing left in the queue, and if the breaker
    # let it through, ScriptedKernel.handler would raise on the empty queue instead of a clean
    # KERN_CIRCUIT_OPEN response.
    tripped = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert tripped.status_code == 503
    assert tripped.json()["code"] == ErrorCode.KERN_CIRCUIT_OPEN.value
    assert len(scripted_kernel.calls) == breakers.assess.threshold

    rows = await _kernel_call_log_rows(session)
    assert rows[-1].outcome == KernelCallOutcome.CIRCUIT_OPEN.value


@pytest.mark.usefixtures("kernel_overrides")
async def test_circuit_half_opens_after_cooldown_and_a_success_closes_it(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
    breakers: KernelCircuitBreakers,
) -> None:
    await _seed(client, auth_headers, session)
    for _ in range(breakers.assess.threshold):
        scripted_kernel.push_exception(httpx.ConnectError("down"))
        r = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
        assert r.status_code == 502

    blocked = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert blocked.status_code == 503

    time.sleep(breakers.assess.cooldown_seconds * 1.5)

    scripted_kernel.push_response(200, ASSESS_KERNEL_RESPONSE)
    recovered = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert recovered.status_code == 200

    # The breaker is closed again: a further call goes to the network rather than failing fast.
    scripted_kernel.push_response(200, ASSESS_KERNEL_RESPONSE)
    again = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert again.status_code == 200


@pytest.mark.usefixtures("kernel_overrides")
async def test_kernel_4xx_does_not_count_toward_the_circuit_breaker(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_kernel: ScriptedKernel,
    breakers: KernelCircuitBreakers,
) -> None:
    """A 4xx means the kernel answered correctly that this payload is bad. Counting it toward
    the threshold would open the circuit and punish every subsequent caller for one request."""
    await _seed(client, auth_headers, session)
    for _ in range(breakers.assess.threshold + 2):
        scripted_kernel.push_response(422, {"error": "bad", "message": "bad payload"})
        r = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
        assert r.status_code == 422

    scripted_kernel.push_response(200, ASSESS_KERNEL_RESPONSE)
    still_closed = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=auth_headers)
    assert still_closed.status_code == 200


# ---------------------------------------------------------------------------
# Facility isolation
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("kernel_overrides")
async def test_case_record_from_another_facility_is_404_not_403(
    client: AsyncClient,
    auth_headers: dict[str, str],
    other_facility_headers: dict[str, str],
    session: AsyncSession,
) -> None:
    """Confirming the case exists at all, even to answer with 403, would leak across the
    facility boundary."""
    await _seed(client, auth_headers, session)

    response = await client.post("/api/v1/assess", json=ASSESS_BODY, headers=other_facility_headers)
    assert response.status_code == 404
    assert response.json()["code"] == ErrorCode.ENC_CASE_NOT_FOUND.value


@pytest.mark.usefixtures("kernel_overrides")
async def test_unknown_case_record_id_is_404(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await client.post(
        "/api/v1/assess",
        json={**ASSESS_BODY, "case_token": "does-not-exist-12345"},
        headers=auth_headers,
    )
    assert response.status_code == 404
    assert response.json()["code"] == ErrorCode.ENC_CASE_NOT_FOUND.value


# ---------------------------------------------------------------------------
# Boundary: extra="forbid" catches a denylisted field before the guard ever runs
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("kernel_overrides")
async def test_a_denylisted_field_sent_over_http_is_rejected_by_the_schema_not_the_guard(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """See tests/test_kernel_phi_guard.py's module docstring. None of the denylisted names are
    declared fields, so extra="forbid" rejects the request before app.services.kernel runs at
    all; the response is PAT_VALIDATION_FAILED, not KERN_IDENTITY_LEAK_BLOCKED. That is correct
    and expected, not a gap: the guard's job is the case this test cannot construct, where a
    field has been added to the schema without checking the denylist first.
    """
    response = await client.post(
        "/api/v1/assess",
        json={**ASSESS_BODY, "full_name": "Sunita Devi"},
        headers=auth_headers,
    )
    assert response.status_code == 422
    assert response.json()["code"] == ErrorCode.PAT_VALIDATION_FAILED.value


@pytest.mark.usefixtures("kernel_overrides")
async def test_kernel_endpoints_require_authentication(client: AsyncClient) -> None:
    assert (await client.post("/api/v1/assess", json=ASSESS_BODY)).status_code == 401
    assert (await client.post("/api/v1/evaluate", json=EVALUATE_BODY)).status_code == 401
