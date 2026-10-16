# SLM parity comparator

`compare.py` implements `docs/design/on-device-slm-memo.md` section 11.5 as amended by C-1, R9-b and
the Y-13 language rule. Standard library only.

```
python3 -I tools/slm-parity/compare.py \
    --golden golden/prompts-v1.jsonl --control-tokens golden/control-tokens.txt \
    --token-parity runs/token-parity.jsonl \
    --server runs/server-bf16.jsonl --desktop runs/desktop-cpu.jsonl \
    --gpu runs/pixel-gpu-v2.jsonl --cpu runs/pixel-cpu-v2.jsonl \
    --gpu-rep runs/pixel-gpu-v2-rep.jsonl --cpu-rep runs/pixel-cpu-v2-rep.jsonl \
    --report PARITY_REPORT.md --review-dir review
```

Exit code 1 if any hard gate fails (H1 content gates at 100 percent on every local run, H2
non-inferiority, H3 refusal parity of the over-2048-token prompts, H4 refusal-rate gap of at most 10
points, H5 token-id parity). Server and desktop gate results, similarity to the server (exact match,
chrF with character 6-grams and beta 2) and per-backend repeatability are reported, not gated.

`PARITY_REPORT.md` holds counts, ids and verdicts only. `review/blind-sample.csv` (30 server versus
pixel-gpu pairs, stratified by language, labels stripped, fixed seed) and its key file carry text and
are for clinician review; neither is ever committed.

Tests: `python3 -I tools/slm-parity/test_compare.py`. The grounding, control-token and sanitizer
ports are checked against `app/src/test/resources/slm/grounding-fixture-v1.json`, written by the real
Kotlin functions; `ParityFixtureTest` fails if Kotlin changes without the fixture, and the Python
test fails if the port drifts. Regenerate the fixture with
`SAMD_PARITY_FIXTURE_DIR=<dir> ./gradlew :app:testDevDebugUnitTest --rerun --tests '*ParityFixtureTest*'`.
