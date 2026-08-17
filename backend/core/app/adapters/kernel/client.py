"""httpx wrapper for the XGBoost clinical kernel.

Path asymmetry, not a mistake to fix (api-contract.md section 5.1):

    Backend endpoint              Kernel endpoint
    POST /api/v1/assess       ->  POST {KERNEL_BASE_URL}/v1/assess
    POST /api/v1/evaluate     ->  POST {KERNEL_BASE_URL}/api/v1/evaluate

Both paths are written out explicitly below rather than built from a shared prefix with a
conditional, so a future reader sees the asymmetry in the code instead of having to derive it.

One client instance for the app lifetime, created in the FastAPI lifespan and held on
app.state.kernel_client. A client created per request leaks connections and defeats keep-alive.

No retries, anywhere in this module. A retried inference call is a second inference call, and
kernel_call_log must not show one clinical event as two. If a call fails, it fails, and the
caller (the device, via the app) decides whether to resubmit.
"""

from __future__ import annotations

import httpx

from app.models.enums import KernelEndpoint

_PATHS: dict[KernelEndpoint, str] = {
    KernelEndpoint.ASSESS: "/v1/assess",
    KernelEndpoint.EVALUATE: "/api/v1/evaluate",
}


def build_kernel_client(
    *, base_url: str, connect_timeout_seconds: float, read_timeout_seconds: float
) -> httpx.AsyncClient:
    """Construct the shared client. Called once, from the lifespan."""
    return httpx.AsyncClient(
        base_url=base_url,
        timeout=httpx.Timeout(
            connect=connect_timeout_seconds,
            read=read_timeout_seconds,
            write=read_timeout_seconds,
            pool=connect_timeout_seconds,
        ),
    )


async def call_kernel(
    client: httpx.AsyncClient, *, endpoint: KernelEndpoint, json_body: dict[str, object]
) -> httpx.Response:
    """Forward one request. Raises httpx exceptions verbatim; the caller maps them to error codes.

    No retry, no exception translation here. Translation belongs in the service layer, which is
    also where the audit and kernel_call_log writes happen and needs to observe the raw failure
    to classify it correctly (connect vs read timeout vs 4xx vs 5xx).
    """
    return await client.post(_PATHS[endpoint], json=json_body)
