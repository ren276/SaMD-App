"""SLM readback proxy: resolve, guard, forward, classify, record. Topology (A), permanently.

The device never reaches the generation service. It reaches this backend, which reaches the
service, and that is the shipping architecture rather than a development arrangement: it gives
clinical narrative exactly one egress point, the one that already carries /api/v1/assess, with
one credential on the device, one reachability model, one audit chain and one circuit breaker.

The sequence, deliberately the same shape as app/services/kernel.py, with its two gaps closed
and two steps that do not survive contact with this contract:

1. Resolve and facility-scope the case the caller named. A readback against another facility's
   case is a 404, not a 403, because confirming existence would leak across the boundary.
   UNLIKE THE KERNEL PROXY, this path writes its records. The kernel's _resolve_case_record
   raises before _forward is entered, so a cross-facility kernel call is the one kernel failure
   with no audit row at all; that gap is real, is recorded standalone, and is not reproduced
   here.
2. PHI guard, applied to this payload shape rather than inherited. See app/adapters/slm/
   phi_guard.py, which is explicit about the fact that a key-name denylist catches nothing on a
   hop whose payload is one free-text string.
3. NO PSEUDONYM SUBSTITUTION, and this is the contract's doing, not an omission. The service's
   request schema (slm-service-contract.md section 2.1) has no identifier field and rejects
   unknown fields, so there is nothing to substitute a pseudonym INTO. The case id is absent from
   the wire entirely, which is a stronger property than replacing it, and step 2 enforces that
   absence instead of trusting it. The HMAC token is still computed, for the log row, so an
   operator can line an SLM row up against a kernel row for the same case.
4. Circuit breaker. Open means no network call.
5. Forward. No retry. A retried generation is a second generation, and on a single-worker service
   it also queues behind the first one.
6. NOTHING TO RESTORE on the way back. The response envelope carries no identifier either.
7. Exactly one slm_call_log row and one audit_events row, whatever happened, in ONE out-of-band
   transaction.
8. No separate response row. There is no second table because there is no body to keep: the
   generated text is clinical prose and no row about this hop holds it (see app/models/slm.py).
   The success metadata lands on the same row as the call.

TRANSACTIONS, AND THE DEADLOCK THAT WOULD OTHERWISE BE INHERITED. Every row this service writes
goes through app.db.session.write_out_of_band, in its own session, committed immediately, on
every path. Two reasons, and the second one is the trap:

  - session_scope rolls the whole request transaction back on any exception, so a record of a
    call that really happened cannot be left in the request session while a SamdError propagates.
  - write_out_of_band's rule 2: audit.append takes pg_advisory_xact_lock(facility), transaction
    scoped. If this request's own session had already appended an audit row, this helper's
    session would block on the same key forever while the request session waits for the helper to
    return. A permanent hang, measured in S-5, not an error. THIS SERVICE THEREFORE NEVER APPENDS
    AN AUDIT ROW ON THE CALLER'S SESSION. The only thing it does with that session is the case
    lookup in step 1, which is a SELECT and takes no advisory lock, and the route suppresses the
    audit middleware's fallback row, which in any case runs after the handler has returned. Every
    reachable path in this module writes its audit row out of band and only out of band.

WHAT A ROW SAYS WHEN outcome IS NULL. Three rejections happen before there is a call to describe:
the hop is not configured, the case does not resolve, or the PHI guard trips. The first two never
reach the vocabulary at all and carry outcome NULL with the error code saying why; the PHI guard
has PHI_REJECTED, which the contract's section 4.2 lists as a call-site outcome, so it carries
that. Every one of the three still writes both rows.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from typing import Any, NoReturn

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.adapters.kernel.circuit_breaker import CircuitBreaker
from app.adapters.kernel.phi_guard import assert_no_identity_fields
from app.adapters.kernel.pseudonym import case_token_for
from app.adapters.slm.client import call_slm
from app.adapters.slm.phi_guard import assert_prompt_is_identifier_free
from app.config import Settings
from app.db.base import utcnow
from app.db.session import write_out_of_band
from app.deps import CurrentWorker
from app.errors import ErrorCode, SamdError
from app.models.clinical import CaseRecord
from app.models.enums import AuditAction, AuditOrigin, SlmCallOutcome
from app.models.slm import SlmCallLog
from app.services import audit as audit_service

# slm-service-contract.md section 3.1: every one of these is required on a 200. A missing or
# blank one is a MALFORMED_RESPONSE here rather than a problem the device has to discover, with
# one exception argued at _envelope_is_complete: finish_reason.
REQUIRED_ENVELOPE_FIELDS = (
    "generation_id",
    "text",
    "model_id",
    "model_sha256",
    "prompt_template_version",
    "finish_reason",
)

# section 3.4. Anything else, including absent, means the text may be cut off.
COMPLETE_FINISH_REASONS = frozenset({"stop", "stop_sequence"})

# section 4.1. The two 503s the service is allowed to return, which mean different things to an
# operator and are therefore not folded together here.
_UPSTREAM_NOT_LOADED = "SAMD-SLM-8004"
_UPSTREAM_QUEUE_FULL = "SAMD-SLM-8005"

# section 2.8 and section 4.1. The service's injection guard: the prompt carried one of the loaded
# artifact's special or added tokens as text and was refused, never stripped. Named here rather
# than matched inline for _UPSTREAM_NOT_LOADED's reason, and mirrored on the device by
# SlmControlTokenMirrorTest, which reads this file as text.
_UPSTREAM_CONTROL_TOKENS = "SAMD-SLM-8009"


def sha256_hex(payload: dict[str, Any]) -> str:
    canonical = json.dumps(payload, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _as_int(value: Any) -> int | None:
    """A coercion, not a derivation: the declared type or NULL, never a substituted default."""
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _as_text(value: Any) -> str | None:
    return value if isinstance(value, str) else None


@dataclass
class _CallContext:
    """Everything a row needs, filled in as the call progresses.

    Mutable on purpose: case_record_id and case_token are unknown until step 1 succeeds, and a
    rejection before that must still be able to write its rows.
    """

    request_id: str
    worker: CurrentWorker
    slm_base_url: str
    prompt_template_version: str
    model_id_requested: str
    prompt_chars: int
    input_hash: str
    started_at: datetime
    case_record_id: str | None = None
    case_token: str | None = None


def _duration_ms(started_at: datetime, completed_at: datetime) -> int:
    return int((completed_at - started_at).total_seconds() * 1000)


async def _record(
    ctx: _CallContext,
    *,
    outcome: SlmCallOutcome | None,
    error_code: ErrorCode | None,
    action: AuditAction,
    http_status: int | None = None,
    envelope: dict[str, Any] | None = None,
) -> None:
    """Write the slm_call_log row and the audit row for one request, out of band, together.

    One transaction for both, the kernel's pattern: a call cannot be logged without its audit row
    or the reverse. The audit payload carries measured metadata only. It never carries the
    prompt, the question, the diagnosis, the generated text or the service token, and the one
    string it does take from the response is the SERVED model identity, which is a claim about
    the service rather than about a patient.
    """
    completed_at = utcnow()
    usage = envelope.get("usage") if isinstance(envelope, dict) else None
    usage = usage if isinstance(usage, dict) else {}

    payload: dict[str, Any] = {
        "outcome": outcome.value if outcome else None,
        "error_code": error_code.value if error_code else None,
    }
    if envelope is not None:
        payload["finish_reason"] = _as_text(envelope.get("finish_reason"))
        payload["model_id_served"] = _as_text(envelope.get("model_id"))
        payload["completion_tokens"] = _as_int(usage.get("completion_tokens"))

    async def _write(session: AsyncSession) -> None:
        session.add(
            SlmCallLog(
                request_id=ctx.request_id,
                case_record_id=ctx.case_record_id,
                case_token=ctx.case_token,
                worker_id=ctx.worker.worker_id,
                facility_id=ctx.worker.facility_id,
                slm_base_url=ctx.slm_base_url,
                prompt_template_version=ctx.prompt_template_version,
                model_id_requested=ctx.model_id_requested,
                prompt_chars=ctx.prompt_chars,
                input_sha256=ctx.input_hash,
                outcome=outcome.value if outcome else None,
                error_code=error_code.value if error_code else None,
                http_status=http_status,
                generation_id=_as_text((envelope or {}).get("generation_id")),
                model_id_served=_as_text((envelope or {}).get("model_id")),
                model_sha256=_as_text((envelope or {}).get("model_sha256")),
                finish_reason=_as_text((envelope or {}).get("finish_reason")),
                prompt_tokens=_as_int(usage.get("prompt_tokens")),
                completion_tokens=_as_int(usage.get("completion_tokens")),
                total_tokens=_as_int(usage.get("total_tokens")),
                output_sha256=sha256_hex(envelope) if envelope is not None else None,
                started_at=ctx.started_at,
                completed_at=completed_at,
                duration_ms=_duration_ms(ctx.started_at, completed_at),
            )
        )
        await audit_service.append(
            session,
            action=action.value,
            facility_id=ctx.worker.facility_id,
            actor_id=ctx.worker.worker_id,
            actor_role=ctx.worker.role,
            device_id=ctx.worker.device_id,
            request_id=ctx.request_id,
            origin=AuditOrigin.SERVER,
            case_record_id=ctx.case_record_id,
            payload=json.dumps(payload, separators=(",", ":"), sort_keys=True),
        )

    await write_out_of_band(_write, context=f"slm.readback.{action.value}")


async def _fail(
    ctx: _CallContext,
    *,
    code: ErrorCode,
    detail: str,
    outcome: SlmCallOutcome | None,
    http_status: int | None = None,
) -> NoReturn:
    """Record the failure, then raise. Never returns; every failure path here ends in this."""
    await _record(
        ctx,
        outcome=outcome,
        error_code=code,
        action=AuditAction.SLM_CALL_FAILED,
        http_status=http_status,
    )
    raise SamdError(code, detail=detail)


def _upstream_code(response: httpx.Response) -> str | None:
    """The SAMD-SLM-8xxx code out of an RFC 9457 body, or None if the body is not one."""
    try:
        parsed = response.json()
    except (ValueError, json.JSONDecodeError):
        return None
    if not isinstance(parsed, dict):
        return None
    code = parsed.get("code")
    return code if isinstance(code, str) else None


def _safe_upstream_detail(response: httpx.Response) -> str:
    """Preserve the upstream reason without leaking a body.

    Only a short message-shaped field is read, never the whole body, and never the prompt this
    backend just sent, which a verbose service could well have echoed into its own error.
    """
    try:
        parsed = response.json()
    except (ValueError, json.JSONDecodeError):
        return "The SLM service rejected the request."
    if isinstance(parsed, dict):
        for key in ("detail", "message", "title"):
            value = parsed.get(key)
            if isinstance(value, str):
                return value[:500]
    return "The SLM service rejected the request."


def _envelope_is_complete(envelope: dict[str, Any]) -> bool:
    """Section 3.1's required fields, present and non-blank.

    finish_reason is checked here for PRESENCE only; whether its value means a complete
    generation is _classify_envelope's question. An absent one is not treated as malformed,
    because section 4.2 gives it a row of its own: absent means TRUNCATED. The two sections are
    in tension (3.1 makes every field required on a 200), and this resolves it the way that keeps
    a cut-off readback out of a worker's hands either way.
    """
    if not isinstance(envelope.get("usage"), dict) or not isinstance(envelope.get("decode"), dict):
        return False
    for field in REQUIRED_ENVELOPE_FIELDS:
        value = envelope.get(field)
        if field == "finish_reason":
            continue
        if not isinstance(value, str):
            return False
        # text may legitimately be an empty string; identity and correlation fields may not.
        if field != "text" and not value.strip():
            return False
    return True


async def readback(
    session: AsyncSession,
    worker: CurrentWorker,
    settings: Settings,
    slm_client: httpx.AsyncClient | None,
    breaker: CircuitBreaker,
    request_id: str,
    body: dict[str, Any],
) -> dict[str, Any]:
    """Proxy one approved-record readback. Returns the service's response envelope verbatim.

    TRUNCATED IS RETURNED, NOT RAISED. A 200 whose finish_reason says the text was cut off is
    relayed to the device with its envelope intact, and logged as TRUNCATED. The device's seam is
    what refuses it (SlmRefusal.OUTPUT_TRUNCATED reads exactly this field), and turning it into a
    5xx here would take the finish_reason away from the one layer built to act on it.

    THE BUG PATH IS A PATH. An exception that is not a SamdError is a defect in this module or
    below it, and it is recorded here rather than left to the audit middleware, because MEASURED:
    the middleware's fallback row does not exist on that path. Starlette's ServerErrorMiddleware
    sits outside AuditMiddleware, so an unhandled exception propagates THROUGH the audit
    middleware's `await self.app(...)` and its post-response write never runs. A test found that;
    reasoning about the route's ordering did not. outcome stays NULL, because no call outcome is
    known, and the error code is the internal one.
    """
    try:
        return await _readback(session, worker, settings, slm_client, breaker, request_id, body)
    except SamdError:
        raise  # _fail already wrote both rows for this one.
    except Exception:
        await _record(
            _bug_context(request_id, worker, settings, body),
            outcome=None,
            error_code=ErrorCode.SYS_INTERNAL,
            action=AuditAction.SLM_CALL_FAILED,
        )
        raise


def _bug_context(
    request_id: str, worker: CurrentWorker, settings: Settings, body: dict[str, Any]
) -> _CallContext:
    """A context for the bug path, rebuilt from the request alone.

    Deliberately not the live context: an exception can arrive with that one half filled in, and
    a row asserting a case id the request never got past is worse than a row that says only what
    the caller asked for.
    """
    prompt = str(body.get("prompt", ""))
    return _CallContext(
        request_id=request_id,
        worker=worker,
        slm_base_url=settings.slm_base_url,
        prompt_template_version=str(body.get("prompt_template_version", "")),
        model_id_requested=str(body.get("model_id", "")),
        prompt_chars=len(prompt),
        input_hash="",
        started_at=utcnow(),
    )


async def _readback(
    session: AsyncSession,
    worker: CurrentWorker,
    settings: Settings,
    slm_client: httpx.AsyncClient | None,
    breaker: CircuitBreaker,
    request_id: str,
    body: dict[str, Any],
) -> dict[str, Any]:
    prompt = str(body["prompt"])
    case_record_id = str(body["case_token"])

    # Every invariant the contract fixes is supplied here, not accepted from the caller. See
    # app/schemas/slm.py for why temperature, do_sample and stop are not on the request model.
    outbound: dict[str, Any] = {
        "prompt": prompt,
        "model_id": str(body["model_id"]),
        "prompt_template_version": str(body["prompt_template_version"]),
        "max_tokens": int(body["max_tokens"]),
        "temperature": 0,
        "do_sample": False,
        "seed": int(body["seed"]),
        "stop": [],
    }

    ctx = _CallContext(
        request_id=request_id,
        worker=worker,
        slm_base_url=settings.slm_base_url,
        prompt_template_version=str(body["prompt_template_version"]),
        model_id_requested=str(body["model_id"]),
        prompt_chars=len(prompt),
        input_hash=sha256_hex(outbound),
        started_at=utcnow(),
    )

    # Step 0. Fail closed on an unauthenticated hop. build_slm_client refuses to construct a
    # client without the shared secret, so a None client here means SLM_SERVICE_TOKEN is unset.
    # No call is attempted and none may be: this is the R-2 gap the kernel hop still carries.
    if slm_client is None:
        await _fail(
            ctx,
            code=ErrorCode.SLM_NOT_CONFIGURED,
            detail="Readback is not configured for this deployment.",
            outcome=None,
        )

    # Step 1. Resolve and facility-scope. A 404 either way: confirming that another facility's
    # case exists would leak across the boundary. Unlike the kernel proxy, this writes its rows.
    case = (
        await session.execute(select(CaseRecord).where(CaseRecord.id == case_record_id))
    ).scalar_one_or_none()
    if case is None or case.facility_id != worker.facility_id:
        await _fail(
            ctx,
            code=ErrorCode.ENC_CASE_NOT_FOUND,
            detail="Case record not found.",
            outcome=None,
        )

    ctx.case_record_id = case_record_id
    ctx.case_token = case_token_for(case_record_id, key=settings.case_token_key)

    # Step 2. The PHI boundary. The denylist is called on the INBOUND body for the kernel's own
    # reason (it guards the next edit, not this payload); the prompt checks are the ones that can
    # fire on this shape. Neither can see a name inside free text; see the guard's docstring.
    try:
        assert_no_identity_fields(body)
        assert_prompt_is_identifier_free(prompt, case_record_id=case_record_id)
    except SamdError as exc:
        await _fail(
            ctx,
            code=ErrorCode.SLM_IDENTITY_LEAK_BLOCKED,
            detail=exc.detail or "Identity content detected on the SLM boundary.",
            outcome=SlmCallOutcome.PHI_REJECTED,
        )

    # Step 4. (Step 3 does not exist on this hop; see the module docstring.)
    if not breaker.allow():
        await _fail(
            ctx,
            code=ErrorCode.SLM_CIRCUIT_OPEN,
            detail="The readback service has failed repeatedly and is temporarily unavailable.",
            outcome=SlmCallOutcome.CIRCUIT_OPEN,
        )

    # Step 5. One call. No retry, ever.
    try:
        response = await call_slm(slm_client, json_body=outbound)
    except (httpx.ConnectTimeout, httpx.ConnectError) as exc:
        breaker.record_failure()
        await _fail(
            ctx,
            code=ErrorCode.SLM_UNREACHABLE,
            detail="The readback service could not be reached.",
            outcome=SlmCallOutcome.UNREACHABLE,
        )
        raise AssertionError("unreachable") from exc  # _fail never returns; satisfies mypy
    except httpx.ReadTimeout as exc:
        breaker.record_failure()
        await _fail(
            ctx,
            code=ErrorCode.SLM_TIMEOUT,
            detail="The readback service timed out.",
            outcome=SlmCallOutcome.TIMEOUT,
        )
        raise AssertionError("unreachable") from exc
    except httpx.HTTPError as exc:
        breaker.record_failure()
        await _fail(
            ctx,
            code=ErrorCode.SLM_UNREACHABLE,
            detail="The readback service could not be reached.",
            outcome=SlmCallOutcome.UNREACHABLE,
        )
        raise AssertionError("unreachable") from exc

    status = response.status_code

    if status == 503:
        upstream = _upstream_code(response)
        if upstream == _UPSTREAM_QUEUE_FULL:
            # NOT a breaker failure, and this is the one place this proxy diverges from "every
            # 5xx counts". A full queue is a loaded, healthy service answering immediately and
            # correctly that it is busy, with a Retry-After that already says what to do.
            # Counting normal backpressure toward the threshold would convert a one-second retry
            # into a thirty-second outage for every other worker in the PHC, which is the same
            # mistake the kernel proxy avoids for a 4xx.
            await _fail(
                ctx,
                code=ErrorCode.SLM_QUEUE_FULL,
                detail="The readback service is busy. Try again shortly.",
                outcome=SlmCallOutcome.QUEUE_FULL,
                http_status=status,
            )
        breaker.record_failure()
        if upstream == _UPSTREAM_NOT_LOADED:
            await _fail(
                ctx,
                code=ErrorCode.SLM_NOT_LOADED,
                detail="The readback service has no model loaded.",
                outcome=SlmCallOutcome.NOT_LOADED,
                http_status=status,
            )
        # A 503 carrying neither code is a service this contract does not recognise. It is not
        # claimed to be NOT_LOADED, because that would make the log assert something unmeasured.
        await _fail(
            ctx,
            code=ErrorCode.SLM_INTERNAL_ERROR,
            detail="The readback service returned an unrecognised unavailable response.",
            outcome=SlmCallOutcome.ENGINE_ERROR,
            http_status=status,
        )

    if status >= 500:
        # 500 (generation failed, including OOM) and 504 (the service's own 40 s wall clock
        # expired and it abandoned the generation). Both are the service failing inside itself,
        # which is section 4.2's ENGINE_ERROR. TIMEOUT stays reserved for this proxy's own budget
        # expiring, so the two are distinguishable in the log.
        breaker.record_failure()
        await _fail(
            ctx,
            code=ErrorCode.SLM_INTERNAL_ERROR,
            detail="The readback service failed during generation.",
            outcome=SlmCallOutcome.ENGINE_ERROR,
            http_status=status,
        )

    if 400 <= status < 500:
        # 422, 409, 413 and 8009's 422, all from section 4.1. NOT a breaker failure, any of them:
        # the service answered correctly that this one request is bad, and punishing every later
        # caller for it is the defect the kernel proxy already names at its own 4xx branch. The
        # split below is a second DEVICE-FACING code for one outcome, not a second outcome.
        if _upstream_code(response) == _UPSTREAM_CONTROL_TOKENS:
            # section 4.2.3. The injection guard fired one hop in. Outcome PAYLOAD_REJECTED,
            # because the operational fact is the same as the other three and a new outcome value
            # would be an Alembic migration over slm_call_log.outcome's CHECK constraint to record
            # what error_code already carries. The code differs because the worker-facing answer
            # does: 8012 means the request was shaped wrong, this means the TEXT of an approved
            # record or of a worker's question contains something the model reads as an
            # instruction. The service REFUSES it rather than sanitising it, so this backend has
            # nothing to clean up and must not invent a retry.
            await _fail(
                ctx,
                code=ErrorCode.SLM_PROMPT_CONTROL_TOKENS,
                detail="The readback text contains control tokens and was refused, not edited.",
                outcome=SlmCallOutcome.PAYLOAD_REJECTED,
                http_status=status,
            )
        await _fail(
            ctx,
            code=ErrorCode.SLM_PAYLOAD_REJECTED,
            detail=_safe_upstream_detail(response),
            outcome=SlmCallOutcome.PAYLOAD_REJECTED,
            http_status=status,
        )

    try:
        envelope = response.json()
        if not isinstance(envelope, dict):
            raise ValueError("SLM response body is not a JSON object.")
    except (ValueError, json.JSONDecodeError) as exc:
        breaker.record_failure()
        await _fail(
            ctx,
            code=ErrorCode.SLM_MALFORMED_RESPONSE,
            detail="The readback service returned an unparseable response.",
            outcome=SlmCallOutcome.MALFORMED_RESPONSE,
            http_status=status,
        )
        raise AssertionError("unreachable") from exc

    if not _envelope_is_complete(envelope):
        # Including a blank or missing model_sha256, which section 3.2 makes representable and
        # meaningful: an envelope that has not implemented the identity field is indistinguishable
        # from a service quietly pointed at a different artifact, and the first is the likelier
        # early-integration state. Refused here so the device never has to tell them apart.
        breaker.record_failure()
        await _fail(
            ctx,
            code=ErrorCode.SLM_MALFORMED_RESPONSE,
            detail="The readback service returned an envelope missing required fields.",
            outcome=SlmCallOutcome.MALFORMED_RESPONSE,
            http_status=status,
        )

    breaker.record_success()
    finish_reason = _as_text(envelope.get("finish_reason"))
    complete = finish_reason in COMPLETE_FINISH_REASONS
    await _record(
        ctx,
        outcome=SlmCallOutcome.SUCCESS if complete else SlmCallOutcome.TRUNCATED,
        error_code=None,
        action=AuditAction.SLM_CALL_FORWARDED,
        http_status=status,
        envelope=envelope,
    )
    return envelope
