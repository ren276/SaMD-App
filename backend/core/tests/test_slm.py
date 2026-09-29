"""SLM readback proxy. docs/backend/api-contract.md section 11, slm-service-contract.md.

The SLM service is mocked with httpx.MockTransport (ScriptedSlm below), never a live process.
There is no SLM service to run yet, and CI must never need one.

Two shapes this suite deliberately avoids, both of which have already produced a green test over
a broken property in this project:

  - asserting against a fake that re-implements the policy. The scripted service here answers
    canned bytes and makes no decisions; every assertion about behaviour reads the real
    slm_call_log row, the real audit_events row, or the real outbound request.
  - a guard whose needle is not unique against the artifact. test_audit_appends_only_out_of_band
    parses app/services/slm.py with ast and checks WHERE the call is, rather than grepping for a
    string that could match a comment.
"""

from __future__ import annotations

import ast
import asyncio
import json
import pathlib
from dataclasses import dataclass, field
from typing import Any

import httpx
import pytest
from httpx import AsyncClient
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.adapters.kernel.circuit_breaker import CircuitBreaker
from app.adapters.slm.client import SERVICE_TOKEN_HEADER, build_slm_client
from app.deps import slm_breaker_dep, slm_client_dep
from app.models.audit import AuditEvent
from app.models.enums import AuditAction, CaseStatus, SlmCallOutcome
from app.models.slm import SlmCallLog
from tests.conftest import TEST_DEVICE_ID, TEST_FACILITY_ID, TEST_WORKER_ID
from tests.test_encounters import CASE_ID, seed_case_record, seed_patient_and_encounter

READBACK_PATH = "/api/v1/slm/readback"
SERVICE_PATH = "/v1/generate"
TEST_SERVICE_TOKEN = "test-only-slm-service-token"

# No identifier, no digit run, and nothing that looks like one. This is the shape the device's
# buildPrompt produces: instructions, the approved record, the question.
PROMPT = (
    "You are restating a record a doctor has already approved. Use plain language.\n"
    "Do not add any medical information that is not written below.\n\n"
    "APPROVED RECORD\n"
    "Doctor's decision on the assessment: AGREE\n"
    "Diagnosis: acute upper respiratory infection\n"
    "Medicines:\n"
    "- Paracetamol 500 mg oral three times a day for 5 days, 15 tablets\n"
    "Referral suggested: no\n\n"
    "QUESTION\n"
    "What should the patient do about the fever?"
)

REQUEST_BODY: dict[str, Any] = {
    "case_token": CASE_ID,
    "prompt": PROMPT,
    "model_id": "google/gemma-4-E2B-it",
    "prompt_template_version": "slm-readback-v1",
    "max_tokens": 512,
    "seed": 20260918,
}

SERVICE_ENVELOPE: dict[str, Any] = {
    "generation_id": "gen-01J8Z2Q7K3",
    "text": "The doctor has approved paracetamol for your fever.",
    "model_id": "google/gemma-4-E2B-it",
    "model_sha256": "cc8d3a0ce36466ccc1278bf987df5f71db1719b9ca6b4118264f45cb627bfe0f",
    "prompt_template_version": "slm-readback-v1",
    "finish_reason": "stop",
    "decode": {
        "temperature": 0,
        "do_sample": False,
        "seed": 20260918,
        "top_p": None,
        "top_k": None,
        "max_new_tokens": 512,
        "stop": [],
    },
    "usage": {"prompt_tokens": 214, "completion_tokens": 96, "total_tokens": 310},
}


def _envelope(**overrides: Any) -> dict[str, Any]:
    body = dict(SERVICE_ENVELOPE)
    body.update(overrides)
    return body


def _problem(code: str) -> dict[str, Any]:
    """An RFC 9457 body of the shape the service contract's section 4.1 requires."""
    return {"type": f"https://samd.example.com/errors/{code}", "title": code, "code": code}


# ---------------------------------------------------------------------------
# Scripted service double. It answers bytes. It decides nothing.
# ---------------------------------------------------------------------------


@dataclass
class ScriptedSlm:
    calls: list[httpx.Request] = field(default_factory=list)
    _queue: list[tuple[str, Any]] = field(default_factory=list)

    def push_response(self, status_code: int, json_body: dict[str, Any]) -> None:
        self._queue.append(("response", (status_code, json_body)))

    def push_text(self, status_code: int, body: str) -> None:
        self._queue.append(("text", (status_code, body)))

    def push_exception(self, exc: Exception) -> None:
        self._queue.append(("exception", exc))

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        if not self._queue:
            raise AssertionError(
                "ScriptedSlm queue exhausted: an outbound call happened that no test step "
                "scripted. If that is a retry, it must not exist (app/adapters/slm/client.py)."
            )
        kind, payload = self._queue.pop(0)
        if kind == "exception":
            raise payload
        status_code, body = payload
        if kind == "text":
            return httpx.Response(status_code, text=body, request=request)
        return httpx.Response(status_code, json=body, request=request)


@pytest.fixture
def scripted_slm() -> ScriptedSlm:
    return ScriptedSlm()


@pytest.fixture
def slm_breaker() -> CircuitBreaker:
    # Short threshold and cooldown so the breaker tests trip and recover quickly.
    return CircuitBreaker(threshold=2, cooldown_seconds=0.5)


@pytest.fixture
def slm_overrides(app: Any, scripted_slm: ScriptedSlm, slm_breaker: CircuitBreaker) -> None:
    """Point the app's SLM dependencies at the scripted double.

    The client is built through the real build_slm_client, so the auth header this suite asserts
    on is the one production sets, not one the test invented.
    """
    client = build_slm_client(
        base_url="http://slm.test",
        service_token=TEST_SERVICE_TOKEN,
        connect_timeout_seconds=5.0,
        read_timeout_seconds=50.0,
        transport=httpx.MockTransport(scripted_slm.handler),
    )
    app.dependency_overrides[slm_client_dep] = lambda: client
    app.dependency_overrides[slm_breaker_dep] = lambda: slm_breaker


@pytest.fixture
def unconfigured_slm(app: Any, slm_breaker: CircuitBreaker) -> None:
    """The deployment that has no SLM_SERVICE_TOKEN. The hop must not happen at all."""
    app.dependency_overrides[slm_client_dep] = lambda: None
    app.dependency_overrides[slm_breaker_dep] = lambda: slm_breaker


async def _seed(client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession) -> None:
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.SAVED_LOCALLY)


async def _log_rows(session: AsyncSession) -> list[SlmCallLog]:
    return list((await session.execute(select(SlmCallLog))).scalars())


async def _slm_audit_rows(session: AsyncSession) -> list[AuditEvent]:
    rows = (await session.execute(select(AuditEvent).order_by(AuditEvent.occurred_at))).scalars()
    return [row for row in rows if row.action.startswith("slm_")]


async def _post(client: AsyncClient, headers: dict[str, str], **overrides: Any) -> httpx.Response:
    body = dict(REQUEST_BODY)
    body.update(overrides)
    # wait_for, not a bare await: the deadlock this proxy must not reproduce is a HANG, and a
    # hung test that never fails is worse than a failing one.
    return await asyncio.wait_for(
        client.post(READBACK_PATH, json=body, headers=headers), timeout=30
    )


# ---------------------------------------------------------------------------
# Success
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("slm_overrides")
async def test_success_returns_the_envelope_and_writes_one_row_pair(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_slm.push_response(200, SERVICE_ENVELOPE)

    response = await _post(client, auth_headers)
    assert response.status_code == 200

    data = response.json()["data"]
    assert data["generation_id"] == "gen-01J8Z2Q7K3"
    assert data["finish_reason"] == "stop"
    assert data["model_sha256"] == SERVICE_ENVELOPE["model_sha256"]

    rows = await _log_rows(session)
    assert len(rows) == 1
    row = rows[0]
    assert row.outcome == SlmCallOutcome.SUCCESS.value
    assert row.error_code is None
    assert row.http_status == 200
    assert row.case_record_id == CASE_ID
    assert row.case_token and row.case_token != CASE_ID
    assert row.model_id_requested == "google/gemma-4-E2B-it"
    assert row.model_id_served == "google/gemma-4-E2B-it"
    assert row.finish_reason == "stop"
    assert row.prompt_chars == len(PROMPT)
    assert row.prompt_tokens == 214
    assert row.completion_tokens == 96
    assert row.total_tokens == 310
    assert row.input_sha256 and row.output_sha256
    assert row.duration_ms is not None

    audit = await _slm_audit_rows(session)
    assert len(audit) == 1
    assert audit[0].action == AuditAction.SLM_CALL_FORWARDED.value


@pytest.mark.usefixtures("slm_overrides")
async def test_no_row_and_no_audit_payload_carries_the_text(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    """Nothing about this hop stores the prompt, the question or the generated text.

    Checked against every text-typed column of the row and against the audit payload, rather than
    against the columns this test's author happened to think of.
    """
    await _seed(client, auth_headers, session)
    scripted_slm.push_response(200, SERVICE_ENVELOPE)
    await _post(client, auth_headers)

    secrets = ("acute upper respiratory", "Paracetamol", "What should the patient do", "approved")
    row = (await _log_rows(session))[0]
    stored = " ".join(str(value) for value in row.__dict__.values() if isinstance(value, str | int))
    for needle in secrets:
        assert needle not in stored

    payload = (await _slm_audit_rows(session))[0].payload
    for needle in secrets:
        assert needle not in payload


@pytest.mark.usefixtures("slm_overrides")
async def test_outbound_request_is_authenticated_and_carries_the_fixed_decode_parameters(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_slm.push_response(200, SERVICE_ENVELOPE)
    await _post(client, auth_headers)

    assert len(scripted_slm.calls) == 1
    sent = scripted_slm.calls[0]
    assert sent.url.path == SERVICE_PATH
    assert sent.headers[SERVICE_TOKEN_HEADER] == TEST_SERVICE_TOKEN

    body = json.loads(sent.content)
    assert body["temperature"] == 0
    assert body["do_sample"] is False
    assert body["stop"] == []
    assert body["seed"] == 20260918
    assert body["max_tokens"] == 512
    assert body["prompt"] == PROMPT
    # No identifier crosses this hop. There is no field to put one on, and the case id is not in
    # the prompt either.
    assert "case_token" not in body
    assert CASE_ID.encode() not in sent.content


@pytest.mark.usefixtures("slm_overrides")
async def test_no_retry_on_failure(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    """One clinical event is one call. A second scripted answer is never consumed."""
    await _seed(client, auth_headers, session)
    scripted_slm.push_response(500, _problem("SAMD-SLM-8006"))
    scripted_slm.push_response(200, SERVICE_ENVELOPE)

    response = await _post(client, auth_headers)
    assert response.status_code == 502
    assert len(scripted_slm.calls) == 1
    assert len(await _log_rows(session)) == 1


# ---------------------------------------------------------------------------
# Truncation: a 200 that must not be treated as a success
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("finish_reason", ["length", "error"])
@pytest.mark.usefixtures("slm_overrides")
async def test_truncated_is_relayed_to_the_device_and_logged_as_truncated(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
    finish_reason: str,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_slm.push_response(200, _envelope(finish_reason=finish_reason))

    response = await _post(client, auth_headers)
    # Relayed, not converted: the device's seam is what refuses a cut-off readback, and it reads
    # exactly this field.
    assert response.status_code == 200
    assert response.json()["data"]["finish_reason"] == finish_reason

    row = (await _log_rows(session))[0]
    assert row.outcome == SlmCallOutcome.TRUNCATED.value
    assert row.finish_reason == finish_reason
    assert (await _slm_audit_rows(session))[0].action == AuditAction.SLM_CALL_FORWARDED.value


@pytest.mark.usefixtures("slm_overrides")
async def test_absent_finish_reason_is_truncated_not_success(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    """Contract section 4.2: absent means TRUNCATED. Section 3.1 would make it malformed.

    The tension is resolved toward 4.2's explicit row. Either way the text is refused downstream;
    what must never happen is SUCCESS.
    """
    await _seed(client, auth_headers, session)
    envelope = _envelope()
    del envelope["finish_reason"]
    scripted_slm.push_response(200, envelope)

    response = await _post(client, auth_headers)
    assert response.status_code == 200
    row = (await _log_rows(session))[0]
    assert row.outcome == SlmCallOutcome.TRUNCATED.value
    assert row.finish_reason is None


# ---------------------------------------------------------------------------
# The PHI boundary
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("slm_overrides")
async def test_denied_identity_key_is_rejected_before_any_network_call(
    auth_headers: dict[str, str],
    client: AsyncClient,
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
    slm_breaker: CircuitBreaker,
    test_settings: Any,
) -> None:
    """The denylist fires on a body the request model could not produce today.

    Called at the service layer on purpose. extra="forbid" means full_name cannot arrive through
    the route, so routing this through HTTP would test Pydantic, not the guard. The guard exists
    for the edit that DECLARES a denied name on the model, and this is what that edit would look
    like from the service's side.
    """
    from app.deps import CurrentWorker
    from app.errors import ErrorCode, SamdError
    from app.services import slm as slm_service

    await _seed(client, auth_headers, session)
    worker = CurrentWorker(
        worker_id=TEST_WORKER_ID,
        role="ASHA_WORKER",
        facility_id=TEST_FACILITY_ID,
        device_id=TEST_DEVICE_ID,
        must_change_pin=False,
    )
    slm_client = build_slm_client(
        base_url="http://slm.test",
        service_token=TEST_SERVICE_TOKEN,
        connect_timeout_seconds=5.0,
        read_timeout_seconds=50.0,
        transport=httpx.MockTransport(scripted_slm.handler),
    )

    body = dict(REQUEST_BODY)
    body["full_name"] = "Sunita Devi"

    with pytest.raises(SamdError) as caught:
        await slm_service.readback(
            session, worker, test_settings, slm_client, slm_breaker, "req-phi-1", body
        )

    assert caught.value.code is ErrorCode.SLM_IDENTITY_LEAK_BLOCKED
    assert scripted_slm.calls == []
    row = (await _log_rows(session))[0]
    assert row.outcome == SlmCallOutcome.PHI_REJECTED.value
    assert len(await _slm_audit_rows(session)) == 1


@pytest.mark.usefixtures("slm_overrides")
async def test_case_identifier_in_the_prompt_is_rejected_before_any_network_call(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    await _seed(client, auth_headers, session)

    response = await _post(client, auth_headers, prompt=f"{PROMPT}\nRecord id {CASE_ID}")
    assert response.status_code == 422
    assert response.json()["code"] == "SAMD-SLM-8014"
    assert scripted_slm.calls == []

    row = (await _log_rows(session))[0]
    assert row.outcome == SlmCallOutcome.PHI_REJECTED.value
    assert row.error_code == "SAMD-SLM-8014"
    assert len(await _slm_audit_rows(session)) == 1


@pytest.mark.parametrize(
    "injected",
    [
        "Aadhaar 1234 5678 9012",  # grouped, the way it is written on the card
        "aadhaar 123456789012",  # contiguous
        "mobile 9876543210",  # a ten-digit Indian mobile
        "abha 12345678901234",  # a fourteen-digit ABHA number
    ],
)
@pytest.mark.usefixtures("slm_overrides")
async def test_identifier_shaped_digit_runs_are_rejected_before_any_network_call(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
    injected: str,
) -> None:
    await _seed(client, auth_headers, session)

    response = await _post(client, auth_headers, prompt=f"{PROMPT}\n{injected}")
    assert response.status_code == 422
    assert response.json()["code"] == "SAMD-SLM-8014"
    assert scripted_slm.calls == []
    assert (await _log_rows(session))[0].outcome == SlmCallOutcome.PHI_REJECTED.value


@pytest.mark.parametrize(
    "clinical",
    [
        "Fever since 2026-09-12 38.5 C",
        "Paracetamol 500 mg three times a day for 5 days, 15 tablets",
        "BP 128/84, pulse 88, SpO2 97",
        "Amoxicillin 625 mg twice a day for 7 days, quantity 14",
    ],
)
@pytest.mark.usefixtures("slm_overrides")
async def test_ordinary_clinical_numbers_are_not_refused(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
    clinical: str,
) -> None:
    """A guard a worker learns to route around is worse than no guard.

    Dates, doses, durations, quantities and vitals must pass, including a date sitting next to a
    temperature, which is the false positive a naive digit-count check produces.
    """
    await _seed(client, auth_headers, session)
    scripted_slm.push_response(200, SERVICE_ENVELOPE)

    response = await _post(client, auth_headers, prompt=f"{PROMPT}\n{clinical}")
    assert response.status_code == 200
    assert len(scripted_slm.calls) == 1


# ---------------------------------------------------------------------------
# The failure table: every outcome reachable, one row pair on every path
# ---------------------------------------------------------------------------

# (label, how to script it, expected outcome, expected device-facing status and code)
FAILURE_TABLE: list[tuple[str, Any, SlmCallOutcome, int, str]] = [
    (
        "connect_error",
        lambda s: s.push_exception(httpx.ConnectError("no route")),
        SlmCallOutcome.UNREACHABLE,
        502,
        "SAMD-SLM-8010",
    ),
    (
        "connect_timeout",
        lambda s: s.push_exception(httpx.ConnectTimeout("connect budget")),
        SlmCallOutcome.UNREACHABLE,
        502,
        "SAMD-SLM-8010",
    ),
    (
        "read_timeout",
        lambda s: s.push_exception(httpx.ReadTimeout("read budget")),
        SlmCallOutcome.TIMEOUT,
        504,
        "SAMD-SLM-8011",
    ),
    (
        "other_http_error",
        lambda s: s.push_exception(httpx.TooManyRedirects("loop")),
        SlmCallOutcome.UNREACHABLE,
        502,
        "SAMD-SLM-8010",
    ),
    (
        "not_loaded",
        lambda s: s.push_response(503, _problem("SAMD-SLM-8004")),
        SlmCallOutcome.NOT_LOADED,
        503,
        "SAMD-SLM-8004",
    ),
    (
        "queue_full",
        lambda s: s.push_response(503, _problem("SAMD-SLM-8005")),
        SlmCallOutcome.QUEUE_FULL,
        503,
        "SAMD-SLM-8005",
    ),
    (
        "unrecognised_503",
        lambda s: s.push_response(503, {"detail": "no code here"}),
        SlmCallOutcome.ENGINE_ERROR,
        502,
        "SAMD-SLM-8006",
    ),
    (
        "generation_failed",
        lambda s: s.push_response(500, _problem("SAMD-SLM-8006")),
        SlmCallOutcome.ENGINE_ERROR,
        502,
        "SAMD-SLM-8006",
    ),
    (
        "service_wall_clock",
        lambda s: s.push_response(504, _problem("SAMD-SLM-8007")),
        SlmCallOutcome.ENGINE_ERROR,
        502,
        "SAMD-SLM-8006",
    ),
    (
        "validation_rejected",
        lambda s: s.push_response(422, _problem("SAMD-SLM-8001")),
        SlmCallOutcome.PAYLOAD_REJECTED,
        422,
        "SAMD-SLM-8012",
    ),
    (
        "model_mismatch",
        lambda s: s.push_response(409, _problem("SAMD-SLM-8002")),
        SlmCallOutcome.PAYLOAD_REJECTED,
        422,
        "SAMD-SLM-8012",
    ),
    (
        "prompt_too_long",
        lambda s: s.push_response(413, _problem("SAMD-SLM-8003")),
        SlmCallOutcome.PAYLOAD_REJECTED,
        422,
        "SAMD-SLM-8012",
    ),
    (
        # PR-8. The service's injection guard fired. PAYLOAD_REJECTED like its siblings, because
        # the operational fact is the same, under a device-facing code of its own, because what a
        # worker should be told is not: the TEXT of an approved record or of a question carries
        # something the model would read as an instruction.
        "control_tokens_in_prompt",
        lambda s: s.push_response(422, _problem("SAMD-SLM-8009")),
        SlmCallOutcome.PAYLOAD_REJECTED,
        422,
        "SAMD-SLM-8016",
    ),
    (
        "unparseable_body",
        lambda s: s.push_text(200, "<html>not json</html>"),
        SlmCallOutcome.MALFORMED_RESPONSE,
        502,
        "SAMD-SLM-8013",
    ),
    (
        "blank_model_sha256",
        lambda s: s.push_response(200, _envelope(model_sha256="   ")),
        SlmCallOutcome.MALFORMED_RESPONSE,
        502,
        "SAMD-SLM-8013",
    ),
]


@pytest.mark.parametrize(
    ("label", "script", "outcome", "status", "code"),
    FAILURE_TABLE,
    ids=[row[0] for row in FAILURE_TABLE],
)
@pytest.mark.usefixtures("slm_overrides")
async def test_failure_paths_map_and_write_exactly_one_row_pair(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
    label: str,
    script: Any,
    outcome: SlmCallOutcome,
    status: int,
    code: str,
) -> None:
    await _seed(client, auth_headers, session)
    script(scripted_slm)

    response = await _post(client, auth_headers)
    assert response.status_code == status
    assert response.json()["code"] == code

    rows = await _log_rows(session)
    assert len(rows) == 1
    assert rows[0].outcome == outcome.value
    assert rows[0].error_code == code
    assert rows[0].case_record_id == CASE_ID

    audit = await _slm_audit_rows(session)
    assert len(audit) == 1
    assert audit[0].action == AuditAction.SLM_CALL_FAILED.value


@pytest.mark.usefixtures("slm_overrides")
async def test_the_injection_refusal_does_not_relay_the_services_detail(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    """The one 4xx whose upstream detail must not be passed through, and here is why.

    Every other 4xx relays _safe_upstream_detail, which is a bounded read of a message-shaped
    field. That is tolerable when the field describes a malformed request. It is not tolerable
    here: the service refuses an injected prompt rather than sanitising it, and a verbose service
    quoting the offending text back would be quoting a physician's free-text working diagnosis
    into this backend's own error body, which the device then shows and the audit chain sees.
    This branch therefore writes its own detail and reads nothing from the upstream body.
    """
    await _seed(client, auth_headers, session)
    scripted_slm.push_response(
        422,
        {
            **_problem("SAMD-SLM-8009"),
            "detail": "rejected, not sanitised: <|turn> in 'Diagnosis: acute upper respiratory'",
        },
    )

    response = await _post(client, auth_headers)

    assert response.status_code == 422
    assert response.json()["code"] == "SAMD-SLM-8016"
    detail = response.json()["detail"]
    assert "acute upper respiratory" not in detail
    assert "<|turn>" not in detail

    rows = await _log_rows(session)
    assert len(rows) == 1
    assert rows[0].outcome == SlmCallOutcome.PAYLOAD_REJECTED.value
    assert rows[0].error_code == "SAMD-SLM-8016"


def test_every_outcome_in_the_vocabulary_is_exercised() -> None:
    """Totality, against the enum rather than against a list this file keeps in its head.

    Adding a value to SlmCallOutcome without a path that produces it fails here, which is the
    point: contract section 4.2 says there is no 'other' row and there must never be one.
    """
    from_table = {outcome for _, _, outcome, _, _ in FAILURE_TABLE}
    covered = from_table | {
        SlmCallOutcome.SUCCESS,  # test_success_returns_the_envelope_and_writes_one_row_pair
        SlmCallOutcome.TRUNCATED,  # test_truncated_is_relayed_to_the_device_and_logged
        SlmCallOutcome.PHI_REJECTED,  # test_case_identifier_in_the_prompt_is_rejected
        SlmCallOutcome.CIRCUIT_OPEN,  # test_breaker_opens_on_engine_failures
    }
    assert covered == set(SlmCallOutcome)


# ---------------------------------------------------------------------------
# Circuit breaker
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("slm_overrides")
async def test_breaker_opens_on_engine_failures_and_then_refuses_without_calling(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    await _seed(client, auth_headers, session)
    scripted_slm.push_response(500, _problem("SAMD-SLM-8006"))
    scripted_slm.push_response(500, _problem("SAMD-SLM-8006"))

    for _ in range(2):
        assert (await _post(client, auth_headers)).status_code == 502

    response = await _post(client, auth_headers)
    assert response.status_code == 503
    assert response.json()["code"] == "SAMD-SLM-8015"
    # Nothing was sent: the third request never reached the (now empty) queue.
    assert len(scripted_slm.calls) == 2

    rows = await _log_rows(session)
    assert len(rows) == 3
    assert rows[2].outcome == SlmCallOutcome.CIRCUIT_OPEN.value
    assert rows[2].http_status is None
    assert len(await _slm_audit_rows(session)) == 3


@pytest.mark.parametrize(
    ("label", "script"),
    [
        ("payload_rejected", lambda s: s.push_response(422, _problem("SAMD-SLM-8001"))),
        # PR-8. Same rule, and worth its own row: 8009 takes a branch of its own inside the 4xx
        # block, so "every 4xx is breaker-neutral" has to be re-proved for the branch that was
        # added rather than assumed from the one that was already there.
        ("control_tokens", lambda s: s.push_response(422, _problem("SAMD-SLM-8009"))),
        ("queue_full", lambda s: s.push_response(503, _problem("SAMD-SLM-8005"))),
    ],
)
@pytest.mark.usefixtures("slm_overrides")
async def test_breaker_does_not_open_on_a_rejected_payload_or_on_backpressure(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
    slm_breaker: CircuitBreaker,
    label: str,
    script: Any,
) -> None:
    """Two of these in a row would open a threshold-2 breaker if they counted. They must not.

    A 4xx is the service answering correctly that one request is bad; a queue-full 503 is a
    healthy service answering immediately that it is busy. Opening the circuit for either
    punishes every later caller for something that is not an outage.
    """
    await _seed(client, auth_headers, session)
    script(scripted_slm)
    script(scripted_slm)
    scripted_slm.push_response(200, SERVICE_ENVELOPE)

    await _post(client, auth_headers)
    await _post(client, auth_headers)
    assert slm_breaker.allow() is True

    response = await _post(client, auth_headers)
    assert response.status_code == 200
    assert len(scripted_slm.calls) == 3


# ---------------------------------------------------------------------------
# Rejections that happen before there is a call
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("unconfigured_slm")
async def test_an_unconfigured_hop_fails_closed(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """No shared secret, no call. Not a degraded mode."""
    await _seed(client, auth_headers, session)

    response = await _post(client, auth_headers)
    assert response.status_code == 503
    assert response.json()["code"] == "SAMD-SLM-8020"

    rows = await _log_rows(session)
    assert len(rows) == 1
    assert rows[0].outcome is None
    assert rows[0].error_code == "SAMD-SLM-8020"
    assert rows[0].case_record_id is None
    assert len(await _slm_audit_rows(session)) == 1


def test_build_slm_client_refuses_an_empty_token() -> None:
    with pytest.raises(ValueError, match="SLM_SERVICE_TOKEN"):
        build_slm_client(
            base_url="http://slm.test",
            service_token="",
            connect_timeout_seconds=5.0,
            read_timeout_seconds=50.0,
        )


@pytest.mark.usefixtures("slm_overrides")
async def test_unknown_case_is_a_404_that_still_leaves_an_audit_row(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    """The kernel proxy's one un-audited failure, not reproduced here.

    kernel_service._resolve_case_record raises before _forward is entered, so a kernel call for a
    case the caller's facility does not have leaves no KERNEL_CALL_FAILED row at all. That gap is
    standalone and is not fixed by this change; this asserts the SLM proxy does not have it.
    """
    await _seed(client, auth_headers, session)

    response = await _post(client, auth_headers, case_token="cr-not-here")
    assert response.status_code == 404
    assert response.json()["code"] == "SAMD-ENC-4002"
    assert scripted_slm.calls == []

    rows = await _log_rows(session)
    assert len(rows) == 1
    assert rows[0].outcome is None
    assert rows[0].error_code == "SAMD-ENC-4002"
    assert rows[0].case_record_id is None

    audit = await _slm_audit_rows(session)
    assert len(audit) == 1
    assert audit[0].action == AuditAction.SLM_CALL_FAILED.value
    assert audit[0].case_record_id is None


@pytest.mark.usefixtures("slm_overrides")
async def test_a_case_in_another_facility_is_a_404_not_a_403(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    """Confirming existence across a facility boundary would leak, so the answer is the same 404."""
    await _seed(client, auth_headers, session)
    await session.execute(
        text(
            "INSERT INTO facilities (id, name, district, state) "
            "VALUES ('PHC-OTHER', 'Other PHC', 'D', 'S')"
        )
    )
    await session.execute(
        text("UPDATE case_records SET facility_id = 'PHC-OTHER' WHERE id = :id"), {"id": CASE_ID}
    )
    await session.commit()

    response = await _post(client, auth_headers)
    assert response.status_code == 404
    assert response.json()["code"] == "SAMD-ENC-4002"
    assert scripted_slm.calls == []
    assert len(await _slm_audit_rows(session)) == 1


# ---------------------------------------------------------------------------
# The deadlock, proved rather than read
# ---------------------------------------------------------------------------


@pytest.mark.usefixtures("slm_overrides")
async def test_failure_path_records_survive_the_request_rollback_without_hanging(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    scripted_slm: ScriptedSlm,
) -> None:
    """The S-5 hang, end to end, on the path that would produce it.

    Two properties in one test, and both are needed. The request COMPLETES (the _post helper
    wraps every call in asyncio.wait_for, so the advisory-lock deadlock would fail this test
    instead of hanging the suite forever), and the rows are readable on a FRESH session after the
    request's own transaction has rolled back, which is only true if they were written out of
    band. A row written on the request session would be gone here, and an in-request audit append
    before that write would never have got here at all.
    """
    await _seed(client, auth_headers, session)
    scripted_slm.push_response(500, _problem("SAMD-SLM-8006"))

    response = await _post(client, auth_headers)
    assert response.status_code == 502

    from app.db import session as session_module

    factory = session_module.get_sessionmaker()
    async with factory() as fresh:
        rows = list((await fresh.execute(select(SlmCallLog))).scalars())
        assert len(rows) == 1
        assert rows[0].outcome == SlmCallOutcome.ENGINE_ERROR.value
        audit = [
            row
            for row in (await fresh.execute(select(AuditEvent))).scalars()
            if row.action.startswith("slm_")
        ]
        assert len(audit) == 1


def test_audit_appends_only_out_of_band() -> None:
    """Structural, against the parsed artifact rather than a grep.

    Every audit_service.append call in app/services/slm.py must be lexically inside the _write
    closure that write_out_of_band runs. One appended on the request session would deadlock the
    next out-of-band write in the same request, which is the whole S-5 finding; this fails the
    moment such a call is added, without needing a test that hangs to discover it.
    """
    source = pathlib.Path(__file__).resolve().parents[1] / "app" / "services" / "slm.py"
    tree = ast.parse(source.read_text())

    # ast.walk is breadth first, so a nested function is visited after the one that contains it
    # and its plain assignment wins. setdefault here would record the OUTER function and make
    # this guard assert the wrong thing while still passing.
    enclosing: dict[int, str] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
            for child in ast.walk(node):
                enclosing[id(child)] = node.name

    appends = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "append"
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "audit_service"
    ]
    assert appends, "no audit append found at all: this guard would pass vacuously"
    assert {enclosing[id(node)] for node in appends} == {"_write"}


@pytest.mark.usefixtures("slm_overrides")
async def test_an_unexpected_exception_still_leaves_both_rows(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The bug path, which the audit middleware does not cover.

    MEASURED, and this test is what established it: an exception that is not a SamdError
    propagates through AuditMiddleware's `await self.app(...)`, because Starlette's
    ServerErrorMiddleware sits outside it, so the middleware's post-response fallback row is
    never written. Reasoning about where the route sets request.state.audit_action was wrong
    about this; the service therefore records the bug path itself. outcome is NULL because no
    call outcome is known, and the code is the internal one.
    """
    from app.services import slm as slm_service

    await _seed(client, auth_headers, session)

    async def _boom(*args: Any, **kwargs: Any) -> dict[str, Any]:
        raise RuntimeError("a bug, not a SamdError")

    monkeypatch.setattr(slm_service, "_readback", _boom)

    with pytest.raises(RuntimeError, match="a bug"):
        await _post(client, auth_headers)

    rows = await _log_rows(session)
    assert len(rows) == 1
    assert rows[0].outcome is None
    assert rows[0].error_code == "SAMD-SYS-9005"
    assert rows[0].case_record_id is None

    audit = await _slm_audit_rows(session)
    assert len(audit) == 1
    assert audit[0].action == AuditAction.SLM_CALL_FAILED.value


def test_timeout_budget_matches_the_contract(test_settings: Any) -> None:
    """slm-service-contract.md section 4.3, the middle row of the table.

    Pinned against the settings defaults, not against numbers this test passes in, because the
    property is that the DEPLOYED budget nests: device 10/55/60 outside, backend 5/50/50 here,
    service wall clock 40 inside. A timeout has to be classified at the innermost layer that can
    still see the cause, and widening any of these silently breaks that.
    """
    assert test_settings.slm_connect_timeout_seconds == 5.0
    assert test_settings.slm_read_timeout_seconds == 50.0

    client = build_slm_client(
        base_url="http://slm.test",
        service_token=TEST_SERVICE_TOKEN,
        connect_timeout_seconds=test_settings.slm_connect_timeout_seconds,
        read_timeout_seconds=test_settings.slm_read_timeout_seconds,
    )
    assert client.timeout.connect == 5.0
    assert client.timeout.read == 50.0
    assert client.timeout.write == 50.0
    assert client.timeout.pool == 5.0
