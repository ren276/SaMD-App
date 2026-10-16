# SLM parity scripts (GPU machine)

Scripts for the on-device SLM parity run (`docs/design/on-device-slm-memo.md`, section 11). They read
`golden/prompts-v1.jsonl`, written by the dev unit test `GoldenPromptExport` on the main machine.

## Layout

```
golden/prompts-v1.jsonl        input, copied from the main machine
golden/control-tokens.txt      the device's exact CONTROL_TOKENS (for the comparator)
golden/html-tag-pieces.txt     the 70 litertlm HTML tag pieces (for the comparator)
golden/server-render-v1.jsonl  written by token_parity.py (rendering minus the leading <bos>)
runs/server-bf16.jsonl         written by run_server.py
runs/token-parity.jsonl        written by token_parity.py
runs/desktop-cpu.jsonl         written by desktop_run.py
```

Every script is run as `python3 -I scripts/slm-parity/<script>.py`. The folder `scripts/slm-parity/`
is the whole working set; copy it with `golden/`.

## 1. Server run (`run_server.py`, standard library only)

**The service token must never reach a Claude Code session.** The operator runs this in his own
terminal. The token is read only from the `SLM_SERVICE_TOKEN` environment variable, sent in the
`X-SLM-Service-Token` header, and never printed, logged, written to a file or put in an error.

```
read -rs SLM_SERVICE_TOKEN; export SLM_SERVICE_TOKEN
python3 -I scripts/slm-parity/run_server.py --base-url <service URL> \
    --in golden/prompts-v1.jsonl --out runs/server-bf16.jsonl
unset SLM_SERVICE_TOKEN
```

It posts to `<base-url>/v1/generate` the body the backend sends (`services/slm.py:371-380`):
`prompt`, `model_id` `google/gemma-4-E2B-it`, `prompt_template_version` from the record,
`max_tokens` 512, `temperature` 0, `do_sample` false, `seed` 20260918, `stop` `[]`. One request at a
time, no retry, nothing skipped: prompts the product would refuse before sending are sent too, and
their HTTP status and `SAMD-SLM-*` code are recorded. Output per prompt: `id`, `text`,
`finish_reason`, `model_id`, `model_sha256`, `usage`, `http_status`, `error_code`,
`transport_error` (class name only), `wall_ms`.

## 2. Token parity (`token_parity.py`, transformers 5.5.0 and litert-lm 0.18.0)

```
python3 -I scripts/slm-parity/token_parity.py --in golden/prompts-v1.jsonl \
    --hf-cache-dir <HF cache holding google/gemma-4-E2B-it at 3e22461f65e89153144f8adb70e3b8c2cc9845a7> \
    --model <gemma-4-E2B-it.litertlm> \
    --render-out golden/server-render-v1.jsonl --out runs/token-parity.jsonl
```

Nothing is downloaded (`local_files_only=True`, `HF_HUB_OFFLINE=1`). Server side: the HF chat
template over one user message with `add_generation_prompt=True` and no thinking flag, tokenized
with `add_special_tokens=False` (what `apply_chat_template` itself does when it tokenizes, so the
leading `<bos>` is the only BOS). litertlm side: the bundled template via
`render_message_to_string`, tokenized by the engine, BOS id 2 prepended. Exit code 1 if any prompt
that is **not** over 2048 server tokens has different ids or a different rendering. Prompts over
2048 tokens are expected refusals on both engines (C-1), scored as such by the comparator.

## 3. Desktop reference run (`desktop_run.py`, litert-lm 0.18.0)

```
python3 -I scripts/slm-parity/desktop_run.py --in golden/prompts-v1.jsonl \
    --model <gemma-4-E2B-it.litertlm> --out runs/desktop-cpu.jsonl
```

CPU backend (4 threads, like the device), bundled chat template, one new conversation per prompt,
sampler `top_k` 1, `top_p` 1.0, `temperature` 0.0, `seed` 20260918, thinking off, speculative
decoding off, automatic tool calling off, `max_output_tokens` 512, `max_num_tokens` 2560.
`finish_reason` is derived from the decode count as the device derives it; `outcome` is
`refused_prefill` when the prefill count is unreadable or over 2048 (the Y-20 backstop), and the
comparator must treat that row as REFUSED.

Python has no `channels` setting (the device runs with `channels = emptyList()` so channel text
reaches the sanitizer). The message's channel text, if the runtime parsed any, is recorded in
`channels` so the comparator can see it.

## Tests that run without a GPU

```
python3 -I scripts/slm-parity/test_slm_parity.py
```

Covers the JSONL readers and writers, the golden digest check, the over-2048 flag, `first_diff`,
the failure rule, the finish-reason derivation, the call shape of the HF side (against a stub
tokenizer) and `run_server.py` end to end against a local fake service, including that the token
never reaches the output file, the console or an error.

## Verified against

The pinned sources, not memory: `python/litert_lm/{engine,conversation,interfaces}.py` at the
LiteRT-LM tag `v0.18.0` and `tokenization_utils_base.py` at transformers `v5.5.0`. The litert-lm
and transformers paths of `token_parity.py` and `desktop_run.py` have **not been run** on the main
machine (neither package is installed there); they are checked against those sources only.
