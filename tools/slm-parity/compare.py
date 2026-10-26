"""Deterministic parity comparator for the on-device SLM (on-device SLM memo 11.5, C-1, R9-b, Y-13).

Standard library only; run as `python3 -I tools/slm-parity/compare.py ...`. It judges what would be
SHOWN: each engine's output after the sanitizer and an emulation of the seam's post-generation gates
(finish STOP, served-model identity, grounding for the non-physician tiers). It then applies four
deterministic content gates to every shown output and the hard gates below.

From batch 11 (V-1) the gated local run is the CPU run; a GPU run, if given, is reported only and
labelled "not selected".

Hard gates (exit code 1 if any fails):
  H1 content: the four gates pass for 100 percent of every gated LOCAL run's shown outputs
  H2 non-inferiority: no prompt where the server's shown output passes all four gates while a
     LOCAL shown output fails any
  H3 refusal parity (R9-b): the over-2048-token prompts are REFUSED on all four runs
  H4 refusal rate: a LOCAL run's refusal rate is not more than 10 points above the server's
  H5 token parity (G-6): every prompt not over 2048 tokens has identical ids and rendering
Reported, not gated: server and desktop gate results (server failures are readback-safety
findings), exact-match rate and chrF against the server, per-backend repeatability.

The report holds counts, ids and verdicts only: no prompt, question or output text. The blind
review sample (30 server versus pixel-cpu pairs) is the one file that carries text, and it is for
clinician review only.
"""

import argparse
import csv
import json
import os
import random
import re
import sys

# ---------------------------------------------------------------- constants mirrored from Kotlin
# Mirrored by value; tools/slm-parity/test_compare.py checks the grounding port against a fixture
# written by the real Kotlin functions (app/src/test/resources/slm/grounding-fixture-v1.json).

SANITIZER_TARGET_MODEL_ID = "google/gemma-4-E2B-it"
LOCAL_SERVED_MODEL_ID = (
    "litert-community/gemma-4-E2B-it-litert-lm@b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
    "#181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
)
SERVED_MODEL_ALLOWLIST = frozenset({SANITIZER_TARGET_MODEL_ID, LOCAL_SERVED_MODEL_ID})
COMPLETE_FINISH_REASONS = frozenset({"stop", "stop_sequence"})
CHANNEL_OPEN = "<|channel>"
CHANNEL_CLOSE = "<channel|>"
INPUT_TOO_LONG_TEXT = "Input token ids are too long"
SEED = 20260918
MAX_REFUSAL_GAP_POINTS = 10.0

TOKEN_RE = re.compile(r"[a-z]+|[0-9]+(?:\.[0-9]+)?")
DRUG_SUFFIXES = (
    "cillin", "pril", "profen", "olol", "statin", "mycin", "azole", "dipine", "sartan",
    "cycline", "oxacin", "tidine", "prazole", "formin", "parin", "caine", "zepam", "triptan",
    "vastatin", "codone", "cephalexin", "thiazide", "semide", "sone", "solone",
)
DRUG_WORDS = frozenset({
    "aspirin", "paracetamol", "acetaminophen", "insulin", "warfarin", "digoxin", "salbutamol",
    "levothyroxine", "diclofenac", "tramadol", "codeine", "heparin", "penicillin", "ceftriaxone",
    "cefixime", "ivermectin", "albendazole", "chloroquine", "metformin", "amlodipine", "ors",
})

DEVANAGARI_DIGITS = "०१२३४५६७८९"
DIGIT_TABLE = {ord(c): str(i) for i, c in enumerate(DEVANAGARI_DIGITS)}
NUMBER_RE = re.compile("[0-9" + DEVANAGARI_DIGITS + "]+(?:[.,/][0-9" + DEVANAGARI_DIGITS + "]+)*")
ENUMERATION_MARKER_RE = re.compile(r"^\s*[0-9]+[.)]\s", re.MULTILINE)
BRACKET_SHAPE_RE = re.compile(r"<[^\s<>\[\]]+>|\[[^\s<>\[\]]+\]")
# \w alone splits a Devanagari word at every vowel sign and virama (they are marks, not letters), so
# the Devanagari block up to U+0963 joins it. The danda marks (U+0964, U+0965) still end a word.
WORD_RE = re.compile(r"[\w\u0900-\u0963]+")


# ------------------------------------------------------------------------------ small utilities

def read_jsonl(path):
    with open(path, encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def read_lines(path):
    with open(path, encoding="utf-8") as handle:
        return [line.rstrip("\n") for line in handle if line.rstrip("\n")]


def tokens_of(text):
    return set(TOKEN_RE.findall(text.lower()))


def is_drug_shaped(token):
    return token in DRUG_WORDS or any(len(token) > len(s) and token.endswith(s) for s in DRUG_SUFFIXES)


def is_dose_numeral(token):
    return token[0].isdigit()


def record_text(fields):
    """SlmScopeGate.kt recordText(): medication lines, the diagnosis if any, the decision name."""
    parts = list(fields["medication_lines"])
    if fields.get("diagnosis") is not None:
        parts.append(fields["diagnosis"])
    parts.append(fields["kernel_decision"])
    return " ".join(parts)


def output_is_grounded(output, fields):
    """Port of outputIsGrounded (SlmScopeGate.kt:155-162): a verdict, never an edited string."""
    drug_ground = tokens_of(" ".join(fields["medication_lines"]))
    numeral_ground = tokens_of(record_text(fields))
    for token in tokens_of(output):
        if is_drug_shaped(token) and token not in drug_ground:
            return False
        if is_dose_numeral(token) and token not in numeral_ground:
            return False
    return True


# ------------------------------------------------------------------- sanitizer (server, desktop)

def sanitize(text, control_tokens):
    """Whole-string port of SlmStreamSanitizer for outputs that were not sanitized on the device.

    Channel spans (open to close inclusive; an unterminated span is dropped), known control tokens
    and unknown angle-bracket runs shaped like a control token are removed; a trailing proper prefix
    of a known token is dropped. Local runs use the Kotlin sanitizer's own output instead.
    """
    longest = max(len(t) for t in control_tokens)
    shortest = min(len(t) for t in control_tokens)
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c not in "<[":
            out.append(c)
            i += 1
            continue
        matched = None
        for length in range(min(longest, n - i), shortest - 1, -1):
            if text[i:i + length] in control_tokens:
                matched = text[i:i + length]
                break
        if matched is not None:
            if matched == CHANNEL_OPEN:
                close = text.find(CHANNEL_CLOSE, i + len(matched))
                if close < 0:
                    return "".join(out)
                i = close + len(CHANNEL_CLOSE)
            else:
                i += len(matched)
            continue
        if c == "<":
            end = None
            for j in range(i + 1, min(n, i + longest)):
                cj = text[j]
                if cj == ">":
                    end = j
                    break
                if cj.isspace() or cj in "<[]":
                    break
            if end is not None and end - i >= 2:
                i = end + 1
                continue
        out.append(c)
        i += 1
    tail_start = max(text.rfind("<"), text.rfind("["))
    result = "".join(out)
    if tail_start >= 0:
        tail = text[tail_start:]
        if any(t.startswith(tail) and t != tail for t in control_tokens) and result.endswith(tail):
            result = result[: len(result) - len(tail)]
    return result


# ------------------------------------------------------------------------------- content gates

def normalise_digits(text):
    return text.translate(DIGIT_TABLE)


def numbers_in(text):
    """Numbers in [text], Devanagari digits normalised to ASCII, line-leading enumeration markers excluded."""
    stripped = ENUMERATION_MARKER_RE.sub(" ", text)
    return [normalise_digits(m) for m in NUMBER_RE.findall(stripped)]


def record_section(prompt):
    """The APPROVED RECORD part of a golden prompt: from its heading to the QUESTION heading."""
    start = prompt.find("APPROVED RECORD")
    end = prompt.rfind("\nQUESTION\n")
    if start < 0 or end < 0 or end < start:
        raise ValueError("prompt has no APPROVED RECORD / QUESTION sections")
    return prompt[start:end]


def slash_parts(number):
    """V-3: a value joined by '/' (148/92) also contributes each part. Only '/' splits; the whole stays."""
    return number.split("/") if "/" in number else []


def numbers_gate(output, section):
    record = numbers_in(section)
    allowed = set(record)
    for n in record:
        allowed.update(slash_parts(n))
    return [n for n in numbers_in(output) if n not in allowed]


def control_token_gate(output, control_tokens):
    hits = [t for t in control_tokens if t in output]
    return hits + BRACKET_SHAPE_RE.findall(output)


def script_of(text):
    # Every Devanagari letter and sign counts, vowel signs included (they are not isalpha()). The
    # danda marks and the digits (U+0964 to U+096F) do not.
    deva = sum(1 for ch in text if "\u0900" <= ch <= "\u097f" and not "\u0964" <= ch <= "\u096f")
    latin = sum(1 for ch in text if ("a" <= ch <= "z") or ("A" <= ch <= "Z"))
    return deva, latin


def language_gate(output, section, expected_script):
    """Dominant script of the output's letters, after removing words copied from the record (Y-13)."""
    copied = {w.lower() for w in WORD_RE.findall(section)}
    remaining = " ".join(w for w in WORD_RE.findall(output) if w.lower() not in copied)
    deva, latin = script_of(remaining)
    if deva == 0 and latin == 0:
        return True
    return ("Devanagari" if deva > latin else "Latin") == expected_script


def content_gates(output, golden, control_tokens):
    section = record_section(golden["prompt"])
    return {
        "numbers": not numbers_gate(output, section),
        "control_tokens": not control_token_gate(output, control_tokens),
        "grounded": output_is_grounded(output, golden["snapshot_fields"]),
        "language": language_gate(output, section, golden["question_script"]),
    }


# ----------------------------------------------------------------------- normalised run results

class Result:
    """What one engine did with one prompt: SHOWN with text, or REFUSED with a kind."""

    def __init__(self, status, text=None, kind=None, decode_tokens=None):
        self.status = status
        self.text = text
        self.kind = kind
        self.decode_tokens = decode_tokens

    @property
    def shown(self):
        return self.status == "SHOWN"


def seam_gates(text, finish, identity_ok, tier, fields):
    """Post-generation gates of the seam, in its order. Returns a refusal kind or None."""
    if not identity_ok:
        return "gate_identity"
    if (finish or "").lower() not in COMPLETE_FINISH_REASONS:
        return "gate_finish"
    if tier != "PHYSICIAN" and not output_is_grounded(text, fields):
        return "gate_grounding"
    return None


def server_results(rows, golden, control_tokens):
    out = {}
    for row in rows:
        g = golden[row["id"]]
        if row.get("http_status") == 200 and row.get("text") is not None:
            text = sanitize(row["text"], control_tokens)
            kind = seam_gates(text, row.get("finish_reason"), row.get("model_id") in SERVED_MODEL_ALLOWLIST, g["tier"], g["snapshot_fields"])
            out[row["id"]] = Result("REFUSED", kind=kind) if kind else Result("SHOWN", text, decode_tokens=(row.get("usage") or {}).get("completion_tokens"))
        elif row.get("error_code") == "SAMD-SLM-8003" or row.get("http_status") == 413:
            out[row["id"]] = Result("REFUSED", kind="over_context")
        elif row.get("http_status") == 200:
            out[row["id"]] = Result("REFUSED", kind="malformed")
        else:
            out[row["id"]] = Result("REFUSED", kind="other")
    return out


def desktop_results(rows, golden, control_tokens, over_2048):
    out = {}
    for row in rows:
        g = golden[row["id"]]
        if row.get("outcome") == "ok" and row.get("text") is not None:
            text = sanitize(row["text"], control_tokens)
            kind = seam_gates(text, row.get("finish_reason"), True, g["tier"], g["snapshot_fields"])
            out[row["id"]] = Result("REFUSED", kind=kind) if kind else Result("SHOWN", text, decode_tokens=row.get("decode_tokens"))
        elif row.get("outcome") == "refused_prefill":
            out[row["id"]] = Result("REFUSED", kind="over_context")
        else:
            error = str(row.get("error") or "")
            # R9-b: the message text, when it was recorded. The run script records the exception
            # class only, so an error on a prompt that the token-parity run marks over 2048 tokens is
            # scored the same way, flagged `inferred`.
            if INPUT_TOO_LONG_TEXT in error:
                out[row["id"]] = Result("REFUSED", kind="over_context")
            elif row["id"] in over_2048:
                out[row["id"]] = Result("REFUSED", kind="over_context_inferred")
            else:
                out[row["id"]] = Result("REFUSED", kind="other")
    return out


def local_results(rows, golden):
    out = {}
    for row in rows:
        g = golden[row["id"]]
        if row.get("outcome") == "ok":
            text = row["sanitized_text"]
            kind = seam_gates(text, row.get("finish_reason"), row.get("served_model_id") in SERVED_MODEL_ALLOWLIST, g["tier"], g["snapshot_fields"])
            out[row["id"]] = Result("REFUSED", kind=kind) if kind else Result("SHOWN", text, decode_tokens=row.get("decode_tokens"))
        else:
            error_kind = row.get("error_kind")
            if error_kind == "PAYLOAD_REJECTED":
                kind = "over_context" if row.get("error_reason") == "input_too_long" else "payload_rejected_other"
            elif error_kind == "TIMEOUT":
                kind = "timeout"
            else:
                kind = "engine_error"
            out[row["id"]] = Result("REFUSED", kind=kind)
    return out


OVER_CONTEXT_KINDS = ("over_context", "over_context_inferred")


def is_refused(result):
    return result.status == "REFUSED"


# ---------------------------------------------------------------------------------- gate logic

def gate_runs(results, golden, excluded, control_tokens):
    """{id: gates} for the SHOWN, non-excluded outputs of one run."""
    return {
        i: content_gates(r.text, golden[i], control_tokens)
        for i, r in results.items()
        if r.shown and i not in excluded
    }


def failing(gates):
    return [name for name, ok in gates.items() if not ok]


def non_inferiority_violations(server_gates, local_results_, local_gates):
    """Prompts where the server's shown output passes all four gates and the local shown output fails any."""
    out = []
    for i, sg in server_gates.items():
        if not failing(sg) and local_results_[i].shown and i in local_gates and failing(local_gates[i]):
            out.append(i)
    return sorted(out)


def refusal_parity(runs, over_2048):
    """{run: [over-2048 ids that were not refused]}; empty lists everywhere means parity."""
    return {name: sorted(i for i in over_2048 if not is_refused(res[i])) for name, res in runs.items()}


def refusal_rate(results, excluded):
    ids = [i for i in results if i not in excluded]
    refused = sum(1 for i in ids if is_refused(results[i]))
    return refused, len(ids), (100.0 * refused / len(ids) if ids else 0.0)


def token_parity_failures(rows):
    return sorted(r["id"] for r in rows if not r["over_2048"] and not (r["identical"] and r.get("render_identical", True)))


# --------------------------------------------------------------------------- reported metrics

def chrf(hypothesis, reference, n=6, beta=2.0):
    """Character n-gram F-score (whitespace removed), n = 6 only, beta = 2."""
    def grams(text):
        s = "".join(text.split())
        counts = {}
        for k in range(len(s) - n + 1):
            counts[s[k:k + n]] = counts.get(s[k:k + n], 0) + 1
        return counts
    h, r = grams(hypothesis), grams(reference)
    if not h or not r:
        return 1.0 if "".join(hypothesis.split()) == "".join(reference.split()) else 0.0
    overlap = sum(min(c, r.get(g, 0)) for g, c in h.items())
    precision = overlap / sum(h.values())
    recall = overlap / sum(r.values())
    if precision + recall == 0:
        return 0.0
    return (1 + beta ** 2) * precision * recall / (beta ** 2 * precision + recall)


def similarity_to_server(server, other, excluded):
    ids = sorted(i for i in server if i not in excluded and server[i].shown and other[i].shown)
    if not ids:
        return {"n": 0, "exact": 0, "exact_rate": None, "chrf_mean": None}
    exact = sum(1 for i in ids if server[i].text == other[i].text)
    scores = [chrf(other[i].text, server[i].text) for i in ids]
    return {"n": len(ids), "exact": exact, "exact_rate": 100.0 * exact / len(ids), "chrf_mean": sum(scores) / len(scores)}


def first_difference(a, b):
    for k, (x, y) in enumerate(zip(a, b)):
        if x != y:
            return k
    return min(len(a), len(b))


def repeatability(base_rows, rep_rows):
    """Identical-of-N between a run and its repeat: byte-identical sanitized text."""
    base = {r["id"]: r for r in base_rows}
    identical, differing, not_comparable = 0, [], []
    for r in rep_rows:
        b = base.get(r["id"])
        if b is None or b.get("outcome") != "ok" or r.get("outcome") != "ok":
            not_comparable.append(r["id"])
        elif b["sanitized_text"] == r["sanitized_text"]:
            identical += 1
        else:
            differing.append({
                "id": r["id"],
                "first_diff": first_difference(b["sanitized_text"], r["sanitized_text"]),
                "decode_tokens": (b.get("decode_tokens"), r.get("decode_tokens")),
            })
    return {"n": len(rep_rows), "identical": identical, "differing": differing, "not_comparable": sorted(not_comparable)}


def blind_sample(server, other, golden, excluded, other_label="pixel-cpu", per_language=10):
    """Disagreements where both engines SHOWN, stratified by language, A/B order randomised, shuffled."""
    rng = random.Random(SEED)
    candidates = {}
    for i in sorted(server):
        if i in excluded or not (server[i].shown and other[i].shown):
            continue
        if server[i].text != other[i].text:
            candidates.setdefault(golden[i]["question_language"], []).append(i)
    picked = []
    for language in sorted(candidates):
        ids = candidates[language]
        picked += ids if len(ids) <= per_language else rng.sample(ids, per_language)
    rng.shuffle(picked)
    rows, key = [], []
    for k, i in enumerate(picked, start=1):
        server_first = rng.random() < 0.5
        a, b = (server[i].text, other[i].text) if server_first else (other[i].text, server[i].text)
        rows.append({
            "row": k, "language": golden[i]["question_language"], "question": golden[i]["question"],
            "record": record_section(golden[i]["prompt"]), "answer_A": a, "answer_B": b,
        })
        key.append({"row": k, "id": i, "A_is": "server" if server_first else other_label, "B_is": other_label if server_first else "server"})
    return rows, key


# ---------------------------------------------------------------------------------- the report

def pct(n, d):
    return "n/a" if not d else f"{100.0 * n / d:.1f}%"


def build_report(ctx):
    L = []
    w = L.append
    w("# SLM parity report")
    w("")
    w("Counts, ids and verdicts only: no prompt, question or output text. Inputs are listed at the end.")
    w("")
    w("## Hard gates")
    w("")
    w("| Gate | Verdict | Detail |")
    w("|---|---|---|")
    for name, ok, detail in ctx["gates"]:
        w(f"| {name} | {'PASS' if ok else 'FAIL'} | {detail} |")
    w("")
    w("## Outcomes per run")
    w("")
    w(f"Prompts: {ctx['total']}; excluded from content scoring (expected input refusal): {len(ctx['excluded'])}.")
    w("")
    w("| Run | SHOWN | REFUSED | refusal kinds | refusal rate (scored prompts) |")
    w("|---|---|---|---|---|")
    for name, res in ctx["runs"].items():
        scored = [i for i in res if i not in ctx["excluded"]]
        shown = sum(1 for i in scored if res[i].shown)
        kinds = {}
        for i in scored:
            if not res[i].shown:
                kinds[res[i].kind] = kinds.get(res[i].kind, 0) + 1
        refused, den, rate = refusal_rate(res, ctx["excluded"])
        w(f"| {name} | {shown} | {refused} | {', '.join(f'{k} {v}' for k, v in sorted(kinds.items())) or 'none'} | {refused}/{den} = {rate:.1f}% |")
    w("")
    w("## Content gates on shown outputs")
    w("")
    w("| Run | shown | numbers | control tokens | grounded | language | all four |")
    w("|---|---|---|---|---|---|---|")
    for name, gates in ctx["gate_results"].items():
        n = len(gates)
        row = [sum(1 for g in gates.values() if g[k]) for k in ("numbers", "control_tokens", "grounded", "language")]
        allp = sum(1 for g in gates.values() if not failing(g))
        role = "LOCAL, gated" if name in ctx["local_names"] else "reported only"
        w(f"| {name} ({role}) | {n} | {row[0]}/{n} | {row[1]}/{n} | {row[2]}/{n} | {row[3]}/{n} | {allp}/{n} |")
    w("")
    for name in ctx["local_names"]:
        bad = {i: failing(g) for i, g in ctx["gate_results"][name].items() if failing(g)}
        w(f"- {name}: failing outputs {len(bad)}" + (": " + ", ".join(f"{i} ({'+'.join(v)})" for i, v in sorted(bad.items())) if bad else ""))
    w("")
    w("### Readback-safety findings (server and desktop, not gated)")
    w("")
    for name in ("server", "desktop"):
        bad = {i: failing(g) for i, g in ctx["gate_results"][name].items() if failing(g)}
        w(f"- {name}: {len(bad)} shown outputs fail a gate" + (": " + ", ".join(f"{i} ({'+'.join(v)})" for i, v in sorted(bad.items())) if bad else ""))
    w("")
    w("## Non-inferiority and refusals")
    w("")
    for name in ctx["local_names"]:
        refused_where_server_answered = sorted(i for i in ctx["runs"][name] if i not in ctx["excluded"] and ctx["runs"]["server"][i].shown and is_refused(ctx["runs"][name][i]))
        w(f"- {name}: violations {len(ctx['violations'][name])} {ctx['violations'][name] or ''}; "
          f"refused where the server answered: {len(refused_where_server_answered)}")
    w("")
    w("Over-2048-token prompts (" + ", ".join(sorted(ctx["over_2048"])) + ") not refused, per run: "
      + "; ".join(f"{k}: {v or 'none'}" for k, v in ctx["parity"].items()))
    w("")
    w("## Token-id parity (G-6)")
    w("")
    tp = ctx["token_parity"]
    w(f"- prompts {len(tp)}; over 2048 tokens {sum(1 for r in tp if r['over_2048'])}; identical ids {sum(1 for r in tp if r['identical'])}; "
      f"identical renderings {sum(1 for r in tp if r.get('render_identical', True))}; failures (not over 2048): {ctx['tp_failures'] or 'none'}")
    w("")
    w("## Reported: similarity to the server (shown on both, scored prompts)")
    w("")
    w("| Run | n | exact match | chrF (6-grams, beta 2) |")
    w("|---|---|---|---|")
    for name, s in ctx["similarity"].items():
        w(f"| {name} | {s['n']} | {s['exact']} ({'n/a' if s['exact_rate'] is None else format(s['exact_rate'], '.1f') + '%'}) | "
          f"{'n/a' if s['chrf_mean'] is None else format(s['chrf_mean'], '.3f')} |")
    w("")
    w("## Reported: repeatability (M-2, not a gate)")
    w("")
    w("| Backend | ids | byte-identical | differing | not comparable |")
    w("|---|---|---|---|---|")
    for name, r in ctx["repeat"].items():
        w(f"| {name} | {r['n']} | {r['identical']} of {r['n']} | {len(r['differing'])} | {len(r['not_comparable'])} |")
    w("")
    for name, r in ctx["repeat"].items():
        for d in r["differing"]:
            w(f"- {name} {d['id']}: first differing offset {d['first_diff']}, decode tokens {d['decode_tokens'][0]} then {d['decode_tokens'][1]}")
        if r["not_comparable"]:
            w(f"- {name} not comparable (a run was not ok): {', '.join(r['not_comparable'])}")
    w("")
    w("## Inputs")
    w("")
    for label, digest in ctx["inputs"]:
        w(f"- {label}: sha256 `{digest}`")
    w("")
    w("Notes: server and desktop texts are sanitized by a Python port of the stream sanitizer; local texts "
      "are the device sanitizer's own output. A desktop error on a prompt that the token-parity run marks "
      "over 2048 tokens is scored `over_context_inferred` because the run script records the exception "
      "class and not its message (G6 recorded the message).")
    return "\n".join(L) + "\n"


def sha256_file(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def run(args):
    golden_rows = read_jsonl(args.golden)
    golden = {r["id"]: r for r in golden_rows}
    control_tokens = set(read_lines(args.control_tokens))
    token_parity = read_jsonl(args.token_parity)
    over_2048 = {r["id"] for r in token_parity if r["over_2048"]}
    excluded = {i for i, g in golden.items() if g["expected_input_refusal"]}

    server = server_results(read_jsonl(args.server), golden, control_tokens)
    desktop = desktop_results(read_jsonl(args.desktop), golden, control_tokens, over_2048)
    cpu_rows = read_jsonl(args.cpu)
    cpu = local_results(cpu_rows, golden)
    gpu = local_results(read_jsonl(args.gpu), golden) if args.gpu else None
    runs = {"server": server, "desktop": desktop, "pixel-cpu": cpu}
    if gpu is not None:
        runs["pixel-gpu (not selected)"] = gpu
    local_names = ["pixel-cpu"]

    gate_results = {name: gate_runs(res, golden, excluded, control_tokens) for name, res in runs.items()}
    violations = {n: non_inferiority_violations(gate_results["server"], runs[n], gate_results[n]) for n in local_names}
    parity = refusal_parity(runs, over_2048)
    tp_failures = token_parity_failures(token_parity)
    server_rate = refusal_rate(server, excluded)[2]

    gates = []
    for n in local_names:
        bad = [i for i, g in gate_results[n].items() if failing(g)]
        gates.append((f"H1 content gates 100% ({n})", not bad, f"{len(gate_results[n]) - len(bad)}/{len(gate_results[n])} shown outputs pass all four"))
    for n in local_names:
        gates.append((f"H2 non-inferiority ({n})", not violations[n], f"{len(violations[n])} violating prompts"))
    gated_parity = {k: v for k, v in parity.items() if "not selected" not in k}
    gates.append(("H3 refusal parity (over-2048 prompts refused on server, desktop and the gated local run)", all(not v for v in gated_parity.values()), f"{len(over_2048)} prompts; not refused: " + "; ".join(f"{k} {len(v)}" for k, v in parity.items())))
    for n in local_names:
        rate = refusal_rate(runs[n], excluded)[2]
        gates.append((f"H4 refusal rate gap <= {MAX_REFUSAL_GAP_POINTS:.0f} points ({n})", rate - server_rate <= MAX_REFUSAL_GAP_POINTS, f"{rate:.1f}% against the server's {server_rate:.1f}% = {rate - server_rate:+.1f} points"))
    gates.append(("H5 token parity (prompts not over 2048 tokens)", not tp_failures, f"{len(tp_failures)} failures of {sum(1 for r in token_parity if not r['over_2048'])}"))

    repeat = {}
    pair_files = []
    for spec in args.repeat or []:
        name, base_path, rep_path = spec.split(",")
        repeat[name] = repeatability(read_jsonl(base_path), read_jsonl(rep_path))
        pair_files += [base_path, rep_path]

    inputs = [(os.path.basename(p), sha256_file(p)) for p in dict.fromkeys((
        args.golden, args.control_tokens, args.token_parity, args.server, args.desktop,
        *([args.gpu] if args.gpu else []), args.cpu, *pair_files,
    ))]
    ctx = {
        "gates": gates, "total": len(golden), "excluded": excluded, "runs": runs, "gate_results": gate_results,
        "local_names": local_names, "violations": violations, "over_2048": over_2048, "parity": parity,
        "token_parity": token_parity, "tp_failures": tp_failures,
        "similarity": {n: similarity_to_server(server, runs[n], excluded) for n in runs if n != "server"},
        "repeat": repeat, "inputs": inputs,
    }
    report = build_report(ctx)
    os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
    with open(args.report, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(report)

    if args.review_dir:
        os.makedirs(args.review_dir, exist_ok=True)
        rows, key = blind_sample(server, cpu, golden, excluded)
        with open(os.path.join(args.review_dir, "blind-sample.csv"), "w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=["row", "language", "question", "record", "answer_A", "answer_B"])
            writer.writeheader()
            writer.writerows(rows)
        with open(os.path.join(args.review_dir, "blind-sample-key.csv"), "w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=["row", "id", "A_is", "B_is"])
            writer.writeheader()
            writer.writerows(key)
    return 0 if all(ok for _, ok, _ in gates) else 1


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--golden", required=True)
    p.add_argument("--control-tokens", required=True)
    p.add_argument("--token-parity", required=True)
    p.add_argument("--server", required=True)
    p.add_argument("--desktop", required=True)
    p.add_argument("--gpu", help="the GPU run: reported only, labelled 'not selected' (V-1)")
    p.add_argument("--cpu", required=True, help="the gated local run")
    p.add_argument("--repeat", action="append", metavar="NAME,BASE,REPEAT",
                   help="repeatability pair: a label, the first run's file, the repeat's file (repeatable)")
    p.add_argument("--report", required=True)
    p.add_argument("--review-dir")
    return run(p.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
