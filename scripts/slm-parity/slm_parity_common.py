"""Shared helpers for the SLM parity scripts (on-device SLM memo 11). Standard library only.

The scripts run as `python3 -I script.py`, which does not put the script's folder on sys.path, so
each script inserts its own folder before importing this module.
"""

import hashlib
import json
import os

MAX_PROMPT_TOKENS = 2048
MAX_OUTPUT_TOKENS = 512
SEED = 20260918
SERVICE_MODEL_ID = "google/gemma-4-E2B-it"
TOKENIZER_REVISION = "3e22461f65e89153144f8adb70e3b8c2cc9845a7"
BOS_ID = 2


def read_jsonl(path):
    """One JSON object per non-empty line, UTF-8. Raises ValueError naming the line on bad JSON."""
    rows = []
    with open(path, encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{number}: not JSON ({error.msg})") from None
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{number}: not a JSON object")
            rows.append(row)
    return rows


class JsonlWriter:
    """Appends one line per row and flushes, so an interrupted run keeps what it finished."""

    def __init__(self, path):
        directory = os.path.dirname(os.path.abspath(path))
        os.makedirs(directory, exist_ok=True)
        self._handle = open(path, "w", encoding="utf-8", newline="\n")

    def write(self, row):
        self._handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
        self._handle.flush()

    def close(self):
        self._handle.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def write_jsonl(path, rows):
    with JsonlWriter(path) as writer:
        for row in rows:
            writer.write(row)


def sha256_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def check_golden(rows):
    """The golden file must be what the exporter wrote: ids unique, prompt digests intact."""
    seen = set()
    for row in rows:
        for key in ("id", "prompt", "prompt_sha256", "template_version"):
            if key not in row:
                raise ValueError(f"golden row without '{key}'")
        if row["id"] in seen:
            raise ValueError(f"duplicate id {row['id']}")
        seen.add(row["id"])
        if sha256_text(row["prompt"]) != row["prompt_sha256"]:
            raise ValueError(f"{row['id']}: prompt does not match its prompt_sha256")
    return rows


def over_2048(token_count):
    """The server refuses a prompt of more than 2048 tokens (SAMD-SLM-8003); so does the device."""
    return token_count > MAX_PROMPT_TOKENS


def first_diff(left, right):
    """Index of the first position where two id lists differ; None when identical."""
    for index, (a, b) in enumerate(zip(left, right)):
        if a != b:
            return index
    return None if len(left) == len(right) else min(len(left), len(right))


def finish_reason_for(decode_tokens, max_output_tokens=MAX_OUTPUT_TOKENS):
    """Derived exactly as the device does (LocalSlmEngine.finishReasonFor, memo 5.3).

    The runtime reports no finish reason. At or above the cap is length, which also covers a
    generation that ended naturally on its last token: the fail-closed direction.
    """
    if decode_tokens is None or decode_tokens < 0:
        return None
    return "stop" if decode_tokens < max_output_tokens else "length"


def prefill_within_limit(prefill_tokens):
    """The Y-20 backstop: unreadable or over 2048 prefill tokens refuses (device: PAYLOAD_REJECTED)."""
    return prefill_tokens is not None and 1 <= prefill_tokens <= MAX_PROMPT_TOKENS


def token_parity_row(row_id, server_ids, litertlm_ids, render_identical):
    server_len = len(server_ids)
    return {
        "id": row_id,
        "server_len": server_len,
        "litertlm_len": len(litertlm_ids),
        "identical": list(server_ids) == list(litertlm_ids),
        "first_diff": first_diff(server_ids, litertlm_ids),
        "over_2048": over_2048(server_len),
        "render_identical": render_identical,
    }


def parity_failures(parity_rows):
    """Rows that must fail the run: not over 2048 server tokens, and ids differ (G-6)."""
    return [r["id"] for r in parity_rows if not r["over_2048"] and not r["identical"]]
