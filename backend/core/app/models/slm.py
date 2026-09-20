"""The SLM readback proxy's operational table. Server-owned, never synced.

ONE TABLE, NOT TWO, and that is a deliberate divergence from the kernel proxy's shape rather
than a simplification of it. kernel_call_log records that a call happened and kernel_assessments
holds what came back, verbatim, because the kernel's answer is structured model output that a
later read has to derive from. The SLM's answer is generated clinical prose, and the one thing
this system must not do with it is keep a server-side copy: the drafted H-28 row's controls say
every row about this hop carries measured metadata only and never the question text, the
diagnosis text or the generated text. There is therefore no response body to store, no second
table to store it in, and no way for a call to be logged without its response row or the reverse.

What a row carries: who called, from which facility, about which case, which template and which
model_id were asked for, how long the prompt was, the hash of the exact bytes that were sent, and
then, for a call that returned, which model actually answered, how it finished, how many tokens
it spent and the hash of the envelope. What it never carries: the prompt, the generated text, or
the shared secret on the outbound hop.

outcome IS NULLABLE, and the NULL means something. It is the outcome OF A CALL. Three rejections
happen before this proxy has a call to describe (the readback hop is not configured, the case
does not resolve inside the caller's facility, or the PHI guard trips on the prompt), and on
those paths the row carries outcome NULL with error_code saying why. Writing a value from
SlmCallOutcome there would make the row assert something about a call that was never attempted,
and the contract's section 4.2 vocabulary is exhaustive for calls precisely so it cannot be
stretched. `WHERE outcome IS NULL` is the operator's query for "requests this backend refused to
send", which is also the cross-facility probe query.

case_record_id and case_token are nullable for the same reason: on a resolve failure there is no
case to name, and naming the id the caller asked for would put an unvalidated string behind a
foreign key. The audit row written on that path is the record that the attempt happened.

Retention follows kernel_call_log: 24 months (D-5). Purge is an operational job, not built here.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, Identity, Index, Integer, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import SlmCallOutcome
from app.models.mixins import CLIENT_ID_LENGTH, FACILITY_ID_LENGTH, enum_check


class SlmCallLog(Base):
    """One row per POST /api/v1/slm/readback, whatever happened to it."""

    __tablename__ = "slm_call_log"
    __table_args__ = (
        # NULL passes a CHECK (SQL three-valued logic: NULL IN (...) is NULL, not FALSE), which
        # is what lets the pre-call rows above carry no outcome while every non-NULL value still
        # has to be in the vocabulary.
        enum_check("outcome", SlmCallOutcome, "ck_slm_call_log_outcome"),
        Index("ix_slm_call_log_facility_started", "facility_id", "started_at"),
        Index("ix_slm_call_log_case_record_id", "case_record_id"),
        Index("ix_slm_call_log_request_id", "request_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=False), primary_key=True)
    request_id: Mapped[str] = mapped_column(String(36), nullable=False)
    # NULL when the case did not resolve inside the caller's facility. The FK is still declared:
    # a NULL column is not checked by it, a non-NULL one is.
    case_record_id: Mapped[str | None] = mapped_column(
        String(CLIENT_ID_LENGTH), ForeignKey("case_records.id", ondelete="RESTRICT")
    )
    # The HMAC pseudonym, computed for this row alone. Unlike the kernel hop, NOTHING carries it
    # outbound: the service's request schema (contract section 2.1) has no identifier field at
    # all and rejects unknown fields, so there is no identifier to substitute and none to restore.
    # Recorded here so an operator can correlate an SLM row with a kernel row for the same case
    # by the same recomputable token.
    case_token: Mapped[str | None] = mapped_column(String(CLIENT_ID_LENGTH))
    worker_id: Mapped[str] = mapped_column(String(16), nullable=False)
    facility_id: Mapped[str] = mapped_column(
        String(FACILITY_ID_LENGTH), ForeignKey("facilities.id", ondelete="RESTRICT"), nullable=False
    )
    # dev/staging/prod point at different services; which host answered changes the story.
    slm_base_url: Mapped[str] = mapped_column(String(255), nullable=False)
    # Asked for, by the device. The served identity is model_id_served below, and the whole point
    # of keeping both is that a difference between them is the finding.
    prompt_template_version: Mapped[str] = mapped_column(String(40), nullable=False)
    model_id_requested: Mapped[str] = mapped_column(String(80), nullable=False)
    # Length, not content. The size distribution on a real PHC link is unmeasured (H-28), and
    # this is the column that will measure it.
    prompt_chars: Mapped[int] = mapped_column(Integer, nullable=False)
    input_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    outcome: Mapped[str | None] = mapped_column(String(20))
    error_code: Mapped[str | None] = mapped_column(String(20))
    http_status: Mapped[int | None] = mapped_column(Integer)
    # Everything below is copied out of the response envelope, never computed from it.
    generation_id: Mapped[str | None] = mapped_column(String(64))
    model_id_served: Mapped[str | None] = mapped_column(String(80))
    model_sha256: Mapped[str | None] = mapped_column(String(64))
    finish_reason: Mapped[str | None] = mapped_column(String(20))
    prompt_tokens: Mapped[int | None] = mapped_column(Integer)
    completion_tokens: Mapped[int | None] = mapped_column(Integer)
    total_tokens: Mapped[int | None] = mapped_column(Integer)
    output_sha256: Mapped[str | None] = mapped_column(String(64))
    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    duration_ms: Mapped[int | None] = mapped_column(Integer)
