"""Kernel proxy service: PHI guard, pseudonym substitution, forwarding, logging, persistence.

Routes stay thin (app/api/v1/kernel.py); the sequence below is the whole contract:

1. Resolve and facility-scope the real case_record_id the caller sent as case_token. A kernel
   call against another facility's case is a 404, not a 403 (confirming existence would leak
   across the facility boundary).
2. Guard the parsed body against the PHI denylist.
3. Substitute the HMAC pseudonym for the outbound case_token.
4. Check the circuit breaker. If open, fail fast without a network call.
5. Forward. No retry.
6. Restore the real case_record_id into the response's case_token before returning it.
7. Write exactly one kernel_call_log row and one audit_events row, whatever happened
   (api-contract.md section 5.3: "Audit record written for every call, success or failure, to
   kernel_call_log and to the hash-chained audit log").
8. On success only, write one kernel_assessments row holding the response body verbatim.

WHAT THIS SERVICE DOES NOT WRITE, and the reason is a correctness rule, not a scoping choice.
It does not write kernel_reports. The proxy used to reshape each /v1/assess response into a
kernel_reports row, computing predicted_condition, confidence_score, risk_category and
required_human_verification and storing them beside model_id and model_version. None of those
four is on the wire. That made backend arithmetic look like model output attributed to a named
model version, which breaks IEC 62304 traceability, and it collided with the device, which
upserts one kernel_reports row per case while the proxy inserted one per call. kernel_reports is
now device-owned and written only by sync push (Phase 4). Raw model output goes to
kernel_assessments; anything derived is computed at read time by app/domain/kernel_derivation.py,
which carries its own rule version. D-9 and D-10 in docs/backend/backend-prd.md section 9.

TRANSACTIONS. Every record this service writes about a kernel call goes out of band, in its own
session, committed immediately, on both the success and the failure path. The reason is the same
on both: a kernel call is a network event that really happened, and whether it is recorded must
not depend on what the request transaction does afterwards. app.db.session.session_scope commits
the request's session on success and rolls back the WHOLE transaction on any exception, so a
kernel_call_log or audit_events row for a FAILED call cannot be left in the request session (the
SamdError about to propagate would take it with it), and a row for a SUCCESSFUL call would still
be lost if anything downstream of the forward raised. app.api.v1.auth hit the same problem in
Phase 1 for failed logins; both call sites now share one helper,
app.db.session.write_out_of_band, rather than re-deriving the pattern. The kernel_call_log row,
the audit_events row and the kernel_assessments row all commit in ONE out-of-band transaction, so
a call cannot be logged without its assessment or the reverse.

evaluate_reports is still written on the caller's request session, unchanged from Phase 3. It is
a device-mirrored table and has the same one-row-per-call-versus-upsert-per-case question
kernel_reports had; that is recorded as an open item rather than changed here, since the fix pass
brief scoped this change to kernel_reports.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from datetime import datetime
from typing import Any, NoReturn

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.adapters.kernel.circuit_breaker import KernelCircuitBreakers
from app.adapters.kernel.client import call_kernel
from app.adapters.kernel.phi_guard import assert_no_identity_fields
from app.adapters.kernel.pseudonym import case_token_for
from app.config import Settings
from app.db.base import utcnow
from app.db.session import write_out_of_band
from app.deps import CurrentWorker
from app.errors import ErrorCode, SamdError
from app.models.clinical import CaseRecord
from app.models.enums import (
    AuditAction,
    AuditOrigin,
    KernelCallOutcome,
    KernelEndpoint,
)
from app.models.kernel import EvaluateReport
from app.models.sync import KernelAssessment, KernelCallLog
from app.services import audit as audit_service


def sha256_hex(payload: dict[str, Any]) -> str:
    canonical = json.dumps(payload, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


async def _resolve_case_record(
    session: AsyncSession, worker: CurrentWorker, case_record_id: str
) -> CaseRecord:
    case = (
        await session.execute(select(CaseRecord).where(CaseRecord.id == case_record_id))
    ).scalar_one_or_none()
    if case is None or case.facility_id != worker.facility_id:
        raise SamdError(ErrorCode.ENC_CASE_NOT_FOUND)
    return case


def _duration_ms(started_at: datetime, completed_at: datetime) -> int:
    return int((completed_at - started_at).total_seconds() * 1000)


async def _fail(
    *,
    code: ErrorCode,
    detail: str,
    outcome: KernelCallOutcome,
    request_id: str,
    case_record_id: str,
    case_token: str,
    worker: CurrentWorker,
    endpoint: KernelEndpoint,
    kernel_base_url: str,
    input_hash: str,
    started_at: datetime,
    http_status: int | None = None,
) -> NoReturn:
    """Write the terminal kernel_call_log and audit_events rows for a failed call, via
    app.db.session.write_out_of_band, then raise. Never returns; every failure branch in
    _forward ends here.
    """
    completed_at = utcnow()
    duration_ms = _duration_ms(started_at, completed_at)

    async def _write(session: AsyncSession) -> None:
        session.add(
            KernelCallLog(
                request_id=request_id,
                case_record_id=case_record_id,
                case_token=case_token,
                worker_id=worker.worker_id,
                facility_id=worker.facility_id,
                endpoint=endpoint.value,
                kernel_base_url=kernel_base_url,
                input_sha256=input_hash,
                outcome=outcome.value,
                error_code=code.value,
                http_status=http_status,
                started_at=started_at,
                completed_at=completed_at,
                duration_ms=duration_ms,
            )
        )
        await audit_service.append(
            session,
            action=AuditAction.KERNEL_CALL_FAILED.value,
            facility_id=worker.facility_id,
            actor_id=worker.worker_id,
            actor_role=worker.role,
            device_id=worker.device_id,
            request_id=request_id,
            origin=AuditOrigin.SERVER,
            case_record_id=case_record_id,
            payload=json.dumps(
                {"endpoint": endpoint.value, "outcome": outcome.value, "error_code": code.value},
                separators=(",", ":"),
                sort_keys=True,
            ),
        )

    await write_out_of_band(_write, context=f"kernel.{endpoint.value}.failed")

    raise SamdError(code, detail=detail)


async def _forward(
    *,
    worker: CurrentWorker,
    settings: Settings,
    kernel_client: httpx.AsyncClient,
    breakers: KernelCircuitBreakers,
    request_id: str,
    endpoint: KernelEndpoint,
    case_record_id: str,
    outbound_body: dict[str, Any],
    case_token: str,
) -> tuple[dict[str, Any], datetime, datetime]:
    """PHI guard, circuit check, and forward. Returns (response_body, started_at, completed_at)
    on success. Every failure branch calls _fail, which writes the terminal records and raises;
    this function never returns a partially-logged failure.

    Takes no session: every row it causes to be written goes out of band (see the module
    docstring). The caller's session is used only for the case-record lookup that happens before
    this function is entered.
    """
    started_at = utcnow()
    input_hash = sha256_hex(outbound_body)

    async def fail(
        *, code: ErrorCode, detail: str, outcome: KernelCallOutcome, http_status: int | None = None
    ) -> NoReturn:
        await _fail(
            code=code,
            detail=detail,
            outcome=outcome,
            request_id=request_id,
            case_record_id=case_record_id,
            case_token=case_token,
            worker=worker,
            endpoint=endpoint,
            kernel_base_url=settings.kernel_base_url,
            input_hash=input_hash,
            started_at=started_at,
            http_status=http_status,
        )

    try:
        assert_no_identity_fields(outbound_body)
    except SamdError:
        await fail(
            code=ErrorCode.KERN_IDENTITY_LEAK_BLOCKED,
            detail="Identity field detected on the kernel boundary.",
            outcome=KernelCallOutcome.PHI_REJECTED,
        )

    breaker = breakers.for_endpoint(endpoint.value)
    if not breaker.allow():
        await fail(
            code=ErrorCode.KERN_CIRCUIT_OPEN,
            detail="The clinical kernel has failed repeatedly and is temporarily unavailable.",
            outcome=KernelCallOutcome.CIRCUIT_OPEN,
        )

    try:
        response = await call_kernel(kernel_client, endpoint=endpoint, json_body=outbound_body)
    except (httpx.ConnectTimeout, httpx.ConnectError) as exc:
        breaker.record_failure()
        await fail(
            code=ErrorCode.KERN_UNREACHABLE,
            detail="The clinical kernel could not be reached.",
            outcome=KernelCallOutcome.UNREACHABLE,
        )
        raise AssertionError("unreachable") from exc  # _fail never returns; satisfies mypy
    except httpx.ReadTimeout as exc:
        breaker.record_failure()
        await fail(
            code=ErrorCode.KERN_TIMEOUT,
            detail="The clinical kernel timed out.",
            outcome=KernelCallOutcome.TIMEOUT,
        )
        raise AssertionError("unreachable") from exc
    except httpx.HTTPError as exc:
        breaker.record_failure()
        await fail(
            code=ErrorCode.KERN_UNREACHABLE,
            detail="The clinical kernel could not be reached.",
            outcome=KernelCallOutcome.UNREACHABLE,
        )
        raise AssertionError("unreachable") from exc

    if response.status_code >= 500:
        breaker.record_failure()
        await fail(
            code=ErrorCode.KERN_INTERNAL_ERROR,
            detail="The clinical kernel returned an internal error.",
            outcome=KernelCallOutcome.KERNEL_ERROR,
            http_status=response.status_code,
        )

    if 400 <= response.status_code < 500:
        # A 4xx from the kernel is not a breaker failure: the kernel answered, correctly, that
        # this particular payload is bad. Counting it toward the threshold would open the
        # circuit and punish every subsequent caller for one bad request.
        await fail(
            code=ErrorCode.KERN_PAYLOAD_REJECTED,
            detail=_safe_upstream_detail(response),
            outcome=KernelCallOutcome.PAYLOAD_REJECTED,
            http_status=response.status_code,
        )

    try:
        body = response.json()
        if not isinstance(body, dict):
            raise ValueError("Kernel response body is not a JSON object.")
    except (ValueError, json.JSONDecodeError) as exc:
        breaker.record_failure()
        await fail(
            code=ErrorCode.KERN_MALFORMED_RESPONSE,
            detail="The clinical kernel returned an unparseable response.",
            outcome=KernelCallOutcome.MALFORMED_RESPONSE,
            http_status=response.status_code,
        )
        raise AssertionError("unreachable") from exc

    breaker.record_success()
    completed_at = utcnow()

    await _record_success(
        request_id=request_id,
        case_record_id=case_record_id,
        case_token=case_token,
        worker=worker,
        endpoint=endpoint,
        kernel_base_url=settings.kernel_base_url,
        input_hash=input_hash,
        response_body=body,
        http_status=response.status_code,
        started_at=started_at,
        completed_at=completed_at,
    )

    return body, started_at, completed_at


async def _record_success(
    *,
    request_id: str,
    case_record_id: str,
    case_token: str,
    worker: CurrentWorker,
    endpoint: KernelEndpoint,
    kernel_base_url: str,
    input_hash: str,
    response_body: dict[str, Any],
    http_status: int,
    started_at: datetime,
    completed_at: datetime,
) -> None:
    """Write the kernel_call_log, kernel_assessments and audit_events rows for a successful call,
    via app.db.session.write_out_of_band.

    All three in ONE out-of-band session, committed immediately, the same pattern _fail uses. A
    successful kernel call is a network event that happened; whether it is recorded must not
    depend on the request transaction surviving. Because they share one transaction, a call can
    never be logged without its assessment or the reverse.

    Nothing here is computed from the response. output_sha256 is a hash of the bytes, the four
    lifted columns are copied straight out of the body, and every derived clinical value is
    somebody else's job at read time (app/domain/kernel_derivation.py).
    """
    output_hash = sha256_hex(response_body)
    model_metadata = response_body.get("model_metadata")
    model_metadata = model_metadata if isinstance(model_metadata, dict) else {}
    # /evaluate carries no model_metadata block; reading it there would be reading a field the
    # contract does not define.
    is_assess = endpoint is KernelEndpoint.ASSESS
    model_version = _as_str(model_metadata.get("model_version")) if is_assess else None

    async def _write(session: AsyncSession) -> None:
        session.add(
            KernelCallLog(
                request_id=request_id,
                case_record_id=case_record_id,
                case_token=case_token,
                worker_id=worker.worker_id,
                facility_id=worker.facility_id,
                endpoint=endpoint.value,
                kernel_base_url=kernel_base_url,
                input_sha256=input_hash,
                outcome=KernelCallOutcome.SUCCESS.value,
                http_status=http_status,
                output_sha256=output_hash,
                model_version=model_version,
                started_at=started_at,
                completed_at=completed_at,
                duration_ms=_duration_ms(started_at, completed_at),
            )
        )
        session.add(
            KernelAssessment(
                id=str(uuid.uuid4()),
                request_id=request_id,
                case_record_id=case_record_id,
                facility_id=worker.facility_id,
                worker_id=worker.worker_id,
                endpoint=endpoint.value,
                raw_response=response_body,
                model_version=model_version,
                inference_time_ms=(
                    _as_int(model_metadata.get("inference_time_ms")) if is_assess else None
                ),
                safety_screen_passed=(
                    _as_bool(response_body.get("safety_screen_passed")) if is_assess else None
                ),
                triage_urgency=(
                    _as_str(response_body.get("triage_urgency")) if is_assess else None
                ),
                response_sha256=output_hash,
                created_at=completed_at,
            )
        )
        await audit_service.append(
            session,
            action=AuditAction.KERNEL_CALL_FORWARDED.value,
            facility_id=worker.facility_id,
            actor_id=worker.worker_id,
            actor_role=worker.role,
            device_id=worker.device_id,
            request_id=request_id,
            origin=AuditOrigin.SERVER,
            case_record_id=case_record_id,
            payload=json.dumps(
                {"endpoint": endpoint.value, "outcome": "SUCCESS"},
                separators=(",", ":"),
                sort_keys=True,
            ),
        )

    await write_out_of_band(_write, context=f"kernel.{endpoint.value}.success")


# Coercions, not derivations: each one answers "is this raw field the type the column declares",
# and returns None when it is not. A wrong-typed field from the kernel becomes a NULL an operator
# can find, never a substituted default.


def _as_str(value: Any) -> str | None:
    return value if isinstance(value, str) else None


def _as_int(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _as_bool(value: Any) -> bool | None:
    return value if isinstance(value, bool) else None


def _safe_upstream_detail(response: httpx.Response) -> str:
    """Preserve the upstream failure reason without leaking a raw body to the client.

    api-contract.md section 5.3: "upstream detail preserved in detail". Only a short message
    field is read from the kernel's error shape, never the whole body, and any surprise in that
    body (not JSON, no message key) falls back to a generic sentence instead of propagating.
    """
    try:
        parsed = response.json()
        message = parsed.get("message") if isinstance(parsed, dict) else None
        if isinstance(message, str):
            return message[:500]
    except (ValueError, json.JSONDecodeError):
        pass
    return "The clinical kernel rejected the request payload."


# ---------------------------------------------------------------------------
# /api/v1/assess
# ---------------------------------------------------------------------------


async def assess(
    session: AsyncSession,
    worker: CurrentWorker,
    settings: Settings,
    kernel_client: httpx.AsyncClient,
    breakers: KernelCircuitBreakers,
    request_id: str,
    body: dict[str, Any],
) -> dict[str, Any]:
    case_record_id = str(body["case_token"])
    await _resolve_case_record(session, worker, case_record_id)
    case_token = case_token_for(case_record_id, key=settings.case_token_key)

    outbound = {**body, "case_token": case_token}
    response_body, _started_at, _ended_at = await _forward(
        worker=worker,
        settings=settings,
        kernel_client=kernel_client,
        breakers=breakers,
        request_id=request_id,
        endpoint=KernelEndpoint.ASSESS,
        case_record_id=case_record_id,
        outbound_body=outbound,
        case_token=case_token,
    )

    # No kernel_reports write here, and there must never be one again. _forward has already
    # stored the raw response in kernel_assessments. Derived clinical values are computed at read
    # time by app/domain/kernel_derivation.py. See the module docstring, D-9 and D-10.

    # Restore the real case_record_id before returning to the caller. The kernel only ever saw
    # the pseudonym; the client only ever asked about the real id.
    return {**response_body, "case_token": case_record_id}


# ---------------------------------------------------------------------------
# /api/v1/evaluate
# ---------------------------------------------------------------------------


async def evaluate(
    session: AsyncSession,
    worker: CurrentWorker,
    settings: Settings,
    kernel_client: httpx.AsyncClient,
    breakers: KernelCircuitBreakers,
    request_id: str,
    body: dict[str, Any],
) -> dict[str, Any]:
    case_record_id = str(body["case_token"])
    await _resolve_case_record(session, worker, case_record_id)
    case_token = case_token_for(case_record_id, key=settings.case_token_key)

    outbound = {**body, "case_token": case_token}
    response_body, started_at, ended_at = await _forward(
        worker=worker,
        settings=settings,
        kernel_client=kernel_client,
        breakers=breakers,
        request_id=request_id,
        endpoint=KernelEndpoint.EVALUATE,
        case_record_id=case_record_id,
        outbound_body=outbound,
        case_token=case_token,
    )

    # payload_json stores the kernel's tree exactly as returned, mixed casing included
    # (app/models/kernel.py). No reshaping, matching api-contract.md section 5.4 literally.
    # This row now duplicates kernel_assessments.raw_response for the same call, and
    # evaluate_reports is a device-mirrored table with the same ownership question kernel_reports
    # had. Left in place deliberately: the fix pass scoped the change to kernel_reports, and
    # removing a second table's write on inference is not something to slip in unannounced. See
    # the open item recorded alongside D-9/D-10.
    session.add(
        EvaluateReport(
            id=str(uuid.uuid4()),
            case_record_id=case_record_id,
            facility_id=worker.facility_id,
            payload_json=response_body,
            inference_started_at=started_at,
            inference_ended_at=ended_at,
        )
    )
    await session.flush()

    return {**response_body, "case_token": case_record_id}
