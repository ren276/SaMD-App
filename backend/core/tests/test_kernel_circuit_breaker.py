"""The kernel circuit breaker. Pure in-memory logic, no DB, no HTTP."""

from __future__ import annotations

import time

from app.adapters.kernel.circuit_breaker import CircuitBreaker, CircuitState, KernelCircuitBreakers

THRESHOLD = 3
COOLDOWN = 0.05  # seconds, kept short so the half-open test does not slow the suite


def _breaker() -> CircuitBreaker:
    return CircuitBreaker(threshold=THRESHOLD, cooldown_seconds=COOLDOWN)


def test_starts_closed() -> None:
    breaker = _breaker()
    assert breaker.state is CircuitState.CLOSED
    assert breaker.allow() is True


def test_stays_closed_below_threshold() -> None:
    breaker = _breaker()
    for _ in range(THRESHOLD - 1):
        breaker.record_failure()
    assert breaker.state is CircuitState.CLOSED
    assert breaker.allow() is True


def test_opens_at_threshold_consecutive_failures() -> None:
    breaker = _breaker()
    for _ in range(THRESHOLD):
        breaker.record_failure()
    assert breaker.state is CircuitState.OPEN
    assert breaker.allow() is False


def test_a_success_resets_the_failure_count() -> None:
    breaker = _breaker()
    for _ in range(THRESHOLD - 1):
        breaker.record_failure()
    breaker.record_success()
    for _ in range(THRESHOLD - 1):
        breaker.record_failure()
    assert breaker.state is CircuitState.CLOSED


def test_half_opens_after_cooldown() -> None:
    breaker = _breaker()
    for _ in range(THRESHOLD):
        breaker.record_failure()
    assert breaker.allow() is False

    time.sleep(COOLDOWN * 1.5)
    assert breaker.state is CircuitState.HALF_OPEN
    assert breaker.allow() is True


def test_half_open_success_closes_the_circuit() -> None:
    breaker = _breaker()
    for _ in range(THRESHOLD):
        breaker.record_failure()
    time.sleep(COOLDOWN * 1.5)
    assert breaker.state is CircuitState.HALF_OPEN

    breaker.record_success()
    assert breaker.state is CircuitState.CLOSED
    assert breaker.allow() is True


def test_half_open_failure_reopens_immediately() -> None:
    """A single failed trial reopens the circuit; it does not need a fresh threshold's worth."""
    breaker = _breaker()
    for _ in range(THRESHOLD):
        breaker.record_failure()
    time.sleep(COOLDOWN * 1.5)
    assert breaker.state is CircuitState.HALF_OPEN

    breaker.record_failure()
    assert breaker.state is CircuitState.OPEN
    assert breaker.allow() is False


def test_assess_and_evaluate_breakers_are_independent() -> None:
    """The kernel's two endpoints fail independently: a broken /evaluate must not fail-fast the
    unrelated /assess leg, which has no mock fallback of its own (REQ-EVL-01, H-09)."""
    breakers = KernelCircuitBreakers(threshold=THRESHOLD, cooldown_seconds=COOLDOWN)
    for _ in range(THRESHOLD):
        breakers.for_endpoint("EVALUATE").record_failure()

    assert breakers.for_endpoint("EVALUATE").allow() is False
    assert breakers.for_endpoint("ASSESS").allow() is True


def test_for_endpoint_rejects_an_unknown_name() -> None:
    breakers = KernelCircuitBreakers(threshold=THRESHOLD, cooldown_seconds=COOLDOWN)
    try:
        breakers.for_endpoint("NOT_A_REAL_ENDPOINT")
    except ValueError as exc:
        assert "NOT_A_REAL_ENDPOINT" in str(exc)
    else:
        raise AssertionError("expected ValueError")
