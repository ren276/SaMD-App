"""httpx wrapper for the SLM generation service.

Shaped after app/adapters/kernel/client.py, with one difference that is the whole reason this is
a separate module rather than a second base URL on the kernel client: THIS HOP IS
AUTHENTICATED.

The kernel hop is not (measured: build_kernel_client sets base_url and timeout, no headers, no
token, and the kernel is reached over a plain configured URL on the LAN). That is tolerable for a
hop carrying eight numeric features under a pseudonym. It is not tolerable for a hop carrying a
physician's free-text working diagnosis and a full prescription line set, which is what a
readback prompt is. The service contract's section 4.4 states the requirement; the memo records
it as R-2 and as "the one place where copying the kernel precedent exactly would be wrong".

MECHANISM: a shared secret in a request header, set once on the client and sent on every call,
checked by the callee. The floor, not the ceiling: it authenticates the caller to the service and
nothing else, it is not a per-request signature, it does not bind the body, and it is only as
good as the network the service is bound to (memo R-1: a private compose network, no published
port). It is named here so that PR-5 implements the matching check.

    header : X-SLM-Service-Token
    value  : Settings.slm_service_token, from SLM_SERVICE_TOKEN in the environment

The value is read from settings and handed to httpx. It is never logged, never put in an error
detail, never in an audit payload, and never in a slm_call_log row. app.config.REDACTED_KEYS
covers "token" and "authorization" in structlog output; this header's value never reaches a log
line in the first place because nothing here logs the request.

FAIL CLOSED. build_slm_client raises if the token is empty, and the lifespan therefore leaves
app.state.slm_client as None rather than constructing an unauthenticated client. The route then
answers 503 / SAMD-SLM-8020 with no outbound call. An unauthenticated call on this hop is not a
degraded mode, it is the thing the hop exists to prevent.

TIMEOUTS: contract section 4.3. connect 5 s, read 50 s, write 50 s, pool 5 s, strictly inside the
device's 10/55/60, which is strictly outside the service's own 40 s wall clock. The nesting is
the point: a timeout must be classified at the innermost layer that can still see the cause.

NO RETRIES, for the kernel's reason and one more. A retried generation is a second generation,
and slm_call_log must not show one clinical event as two. The extra reason here is cost: the
service is a single worker holding one resident model (contract section 5.2), so a retry does not
just duplicate a log row, it queues behind the generation that is still running.
"""

from __future__ import annotations

import httpx

# Named here rather than inline so PR-5 and this module cannot drift apart silently.
# S105 is a false positive: this is the header NAME, not a secret. The value never appears
# in this file, in any log line or in any row.
SERVICE_TOKEN_HEADER = "X-SLM-Service-Token"  # noqa: S105
GENERATE_PATH = "/v1/generate"


def build_slm_client(
    *,
    base_url: str,
    service_token: str,
    connect_timeout_seconds: float,
    read_timeout_seconds: float,
    transport: httpx.AsyncBaseTransport | None = None,
) -> httpx.AsyncClient:
    """Construct the shared client. Called once, from the lifespan.

    Raises ValueError when the token is empty: there is no unauthenticated mode for this hop.

    transport is for tests, which script the service with httpx.MockTransport. It exists so a
    test can exercise THIS builder, headers, timeouts and all, rather than a client it assembled
    itself; a suite that builds its own client cannot notice the day this one stops sending the
    auth header. Production passes nothing and gets httpx's default.
    """
    if not service_token:
        raise ValueError(
            "SLM_SERVICE_TOKEN is empty. The backend-to-SLM hop carries a physician's free-text "
            "diagnosis and a full prescription line set and must be authenticated "
            "(slm-service-contract.md section 4.4). Refusing to build an unauthenticated client."
        )
    return httpx.AsyncClient(
        base_url=base_url,
        timeout=httpx.Timeout(
            connect=connect_timeout_seconds,
            read=read_timeout_seconds,
            write=read_timeout_seconds,
            pool=connect_timeout_seconds,
        ),
        headers={SERVICE_TOKEN_HEADER: service_token},
        transport=transport,
    )


async def call_slm(client: httpx.AsyncClient, *, json_body: dict[str, object]) -> httpx.Response:
    """Forward one generation request. Raises httpx exceptions verbatim.

    No retry and no exception translation here. Translation belongs in app/services/slm.py, which
    is also where the slm_call_log and audit writes happen and which needs the raw failure to
    tell a connect timeout from a read timeout from a 4xx from a 5xx.
    """
    return await client.post(GENERATE_PATH, json=json_body)
