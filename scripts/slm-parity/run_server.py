"""Send every golden prompt to the SLM generation service, as the backend does (memo 11.2).

Standard library only. Run by the operator, in the operator's own terminal:

    read -rs SLM_SERVICE_TOKEN; export SLM_SERVICE_TOKEN
    python3 -I scripts/slm-parity/run_server.py --base-url <service URL> \
        --in golden/prompts-v1.jsonl --out runs/server-bf16.jsonl
    unset SLM_SERVICE_TOKEN

The token is read only from the SLM_SERVICE_TOKEN environment variable. It is sent in the
X-SLM-Service-Token header and is never printed, logged, written to a file or put in an error.
The request body is exactly what backend/core/app/services/slm.py:371-380 sends. Nothing is
skipped: prompts the product would refuse before sending are sent too, and their outcome recorded.
"""

import argparse
import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import slm_parity_common as common  # noqa: E402

GENERATE_PATH = "/v1/generate"
TOKEN_HEADER = "X-SLM-Service-Token"  # the header NAME; the value is never held beyond the request
TOKEN_ENV = "SLM_SERVICE_TOKEN"
# Strictly inside the backend's 50 s read budget, outside the service's own 40 s wall clock.
TIMEOUT_SECONDS = 55


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """A redirect is an error response: following it would send the token to a host not named."""

    def redirect_request(self, *args, **kwargs):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def request_body(prompt, template_version):
    """Same keys and values as the backend's outbound dict (services/slm.py:371-380)."""
    return {
        "prompt": prompt,
        "model_id": common.SERVICE_MODEL_ID,
        "prompt_template_version": template_version,
        "max_tokens": common.MAX_OUTPUT_TOKENS,
        "temperature": 0,
        "do_sample": False,
        "seed": common.SEED,
        "stop": [],
    }


def post(url, token, body):
    """One request. Returns (http_status, parsed_json_or_None). Never retries or follows a redirect."""
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={"Content-Type": "application/json", TOKEN_HEADER: token},
    )
    try:
        with _OPENER.open(request, timeout=TIMEOUT_SECONDS) as response:
            raw = response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        raw = error.read()
        status = error.code
    try:
        parsed = json.loads(raw.decode("utf-8")) if raw else None
    except (ValueError, UnicodeDecodeError):
        parsed = None
    return status, parsed


def result_row(record, status, parsed, wall_ms, transport_error=None):
    envelope = parsed if isinstance(parsed, dict) else {}
    ok = 200 <= (status or 0) < 300
    return {
        "id": record["id"],
        "text": envelope.get("text") if ok else None,
        "finish_reason": envelope.get("finish_reason") if ok else None,
        "model_id": envelope.get("model_id") if ok else None,
        "model_sha256": envelope.get("model_sha256") if ok else None,
        "usage": envelope.get("usage") if ok else None,
        "http_status": status,
        "error_code": (envelope.get("code") if not ok and isinstance(envelope.get("code"), str) else None),
        "transport_error": transport_error,
        "wall_ms": wall_ms,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--in", dest="golden", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    token = os.environ.get(TOKEN_ENV, "")
    if not token:
        print(f"{TOKEN_ENV} is not set; refusing to send an unauthenticated request.", file=sys.stderr)
        return 2

    records = common.check_golden(common.read_jsonl(args.golden))
    url = args.base_url.rstrip("/") + GENERATE_PATH
    sent = failed = 0
    with common.JsonlWriter(args.out) as writer:
        for record in records:
            body = request_body(record["prompt"], record["template_version"])
            started = time.monotonic()
            try:
                status, parsed = post(url, token, body)
                error = None
            except (urllib.error.URLError, http.client.HTTPException, OSError, TimeoutError) as exc:
                # The class name only: a message can quote the URL, and nothing here may carry the token.
                status, parsed, error = None, None, type(exc).__name__
            wall_ms = round((time.monotonic() - started) * 1000)
            row = result_row(record, status, parsed, wall_ms, error)
            writer.write(row)
            sent += 1
            failed += 0 if row["http_status"] == 200 else 1
    print(f"sent {sent} prompts, {failed} without HTTP 200 (expected for refused prompts); wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
