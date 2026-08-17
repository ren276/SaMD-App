"""Per-process, in-memory circuit breaker for the kernel adapter.

The kernel is a single external process on a PHC LAN. When it is down, every submission would
otherwise hang for the full read timeout, and a worker sits watching a spinner for something that
was never going to succeed. The breaker turns that into a fast, honest failure.

State is per uvicorn worker process, not shared. A multi-worker deployment has independent
circuit state per worker, which is acceptable: the worst case is that each worker independently
discovers the kernel is down, costing a handful of extra timeouts, not a correctness problem. No
Redis for this.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum, auto


class CircuitState(Enum):
    CLOSED = auto()
    OPEN = auto()
    HALF_OPEN = auto()


@dataclass
class CircuitBreaker:
    """One breaker per kernel endpoint (assess and evaluate fail independently).

    threshold consecutive failures opens the circuit. After cooldown_seconds, the next call is
    let through as a trial (half-open): success closes the circuit, failure reopens it and resets
    the cooldown clock.
    """

    threshold: int
    cooldown_seconds: float
    _consecutive_failures: int = field(default=0, init=False)
    _state: CircuitState = field(default=CircuitState.CLOSED, init=False)
    _opened_at: float = field(default=0.0, init=False)

    def _refresh(self) -> None:
        elapsed = time.monotonic() - self._opened_at
        if self._state is CircuitState.OPEN and elapsed >= self.cooldown_seconds:
            self._state = CircuitState.HALF_OPEN

    def allow(self) -> bool:
        """Call before attempting the outbound request."""
        self._refresh()
        return self._state is not CircuitState.OPEN

    def record_success(self) -> None:
        self._consecutive_failures = 0
        self._state = CircuitState.CLOSED

    def record_failure(self) -> None:
        self._consecutive_failures += 1
        self._refresh()
        if self._state is CircuitState.HALF_OPEN or self._consecutive_failures >= self.threshold:
            self._state = CircuitState.OPEN
            self._opened_at = time.monotonic()

    @property
    def state(self) -> CircuitState:
        self._refresh()
        return self._state


class KernelCircuitBreakers:
    """One breaker per endpoint, held for the process lifetime."""

    def __init__(self, *, threshold: int, cooldown_seconds: float) -> None:
        self.assess = CircuitBreaker(threshold=threshold, cooldown_seconds=cooldown_seconds)
        self.evaluate = CircuitBreaker(threshold=threshold, cooldown_seconds=cooldown_seconds)

    def for_endpoint(self, endpoint: str) -> CircuitBreaker:
        if endpoint == "ASSESS":
            return self.assess
        if endpoint == "EVALUATE":
            return self.evaluate
        raise ValueError(f"Unknown kernel endpoint: {endpoint}")
