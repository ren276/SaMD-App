# SLM parity comparator

`compare.py` implements `docs/design/on-device-slm-memo.md` section 11.5 as amended by C-1, R9-b and
the Y-13 language rule. Standard library only.

```
python3 -I tools/slm-parity/compare.py \
    --golden golden/prompts-v1.jsonl --control-tokens golden/control-tokens.txt \
    --token-parity runs/token-parity.jsonl \
    --server runs/server-bf16.jsonl --desktop runs/desktop-cpu.jsonl \
    --cpu runs/pixel-cpu-v3.jsonl --gpu runs/pixel-gpu-v2.jsonl \
    --repeat pixel-cpu,runs/pixel-cpu-v3.jsonl,runs/pixel-cpu-v2-rep.jsonl \
    --report PARITY_REPORT-v3.md --review-dir review
```

`--cpu` is the gated local run (V-1: CPU is the only backend the engine selects). `--gpu` is optional
and reported only, labelled "not selected". `--repeat NAME,BASE,REPEAT` adds a repeatability pair
(repeatable).

`merge_rerun.py` builds a run file from a base run by replacing named rows with the rows of a re-run
and writes a record of exactly which rows were replaced.

Exit code 1 if any hard gate fails (H1 content gates at 100 percent on every local run, H2
non-inferiority, H3 refusal parity of the over-2048-token prompts, H4 refusal-rate gap of at most 10
points, H5 token-id parity), judged on the gated local run. Server, desktop and GPU gate results, similarity to the server (exact match,
chrF with character 6-grams and beta 2) and per-backend repeatability are reported, not gated.

`PARITY_REPORT.md` holds counts, ids and verdicts only. `review/blind-sample.csv` (30 server versus
pixel-cpu pairs, stratified by language, labels stripped, fixed seed) and its key file carry text and
are for clinician review; neither is ever committed.

Tests: `python3 -I tools/slm-parity/test_compare.py`. The grounding, control-token and sanitizer
ports are checked against `app/src/test/resources/slm/grounding-fixture-v1.json`, written by the real
Kotlin functions; `ParityFixtureTest` fails if Kotlin changes without the fixture, and the Python
test fails if the port drifts. Regenerate the fixture with
`SAMD_PARITY_FIXTURE_DIR=<dir> ./gradlew :app:testDevDebugUnitTest --rerun --tests '*ParityFixtureTest*'`.
