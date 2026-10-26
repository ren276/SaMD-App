"""Desktop CPU reference run of the generic .litertlm on the golden prompts (memo 11.3).

For the GPU machine's venv with litert-lm 0.18.0. One new conversation per prompt, the bundled
chat template (chat_template is not set), every sampler field explicit, thinking off, speculative
decoding off, automatic tool calling off, max output 512, max_num_tokens 2560: the same
configuration as LiteRtLmRuntime.kt (memo 5.1 to 5.5).

    python3 -I scripts/slm-parity/desktop_run.py --in golden/prompts-v1.jsonl \
        --model <gemma-4-E2B-it.litertlm> --out runs/desktop-cpu.jsonl

`finish_reason` is derived from the decode count the way the device derives it. `outcome` is
"refused_prefill" when the Y-20 backstop would refuse (prefill unreadable or over 2048): the text
is then not a shown output and the comparator must treat the row as REFUSED. Python has no
`channels` option, so the message's channel text is recorded in `channels` for the comparator.
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import slm_parity_common as common  # noqa: E402


def message_text(message):
    return str(message.contents)


def run_one(engine, litert_lm, prompt):
    sampler = litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0, seed=common.SEED)
    thinking = litert_lm.ThinkingConfig(enable_thinking=False, thinking_token_budget=0)
    with engine.create_conversation(
        sampler_config=sampler,
        thinking_config=thinking,
        automatic_tool_calling=False,
        max_output_tokens=common.MAX_OUTPUT_TOKENS,
        enable_speculative_decoding=False,
    ) as conversation:
        started = time.monotonic()
        message = conversation.send_message(prompt)
        wall_ms = round((time.monotonic() - started) * 1000)
        info = conversation.get_benchmark_info()
    return message, info, wall_ms


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--in", dest="golden", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--threads", type=int, default=4, help="CPU threads (the device uses 4)")
    parser.add_argument("--cache-dir", default=None)
    args = parser.parse_args(argv)

    import litert_lm  # noqa: PLC0415 (GPU-machine only)

    records = common.check_golden(common.read_jsonl(args.golden))
    counts = {"ok": 0, "refused_prefill": 0, "error": 0}
    with litert_lm.Engine(
        args.model,
        backend=litert_lm.Backend.CPU(thread_count=args.threads),
        max_num_tokens=2560,
        cache_dir=args.cache_dir,
        enable_benchmark=True,
        enable_speculative_decoding=False,
    ) as engine, common.JsonlWriter(args.out) as writer:
        for record in records:
            row = {"id": record["id"], "text": None, "channels": None, "finish_reason": None,
                   "prefill_tokens": None, "decode_tokens": None, "ms": None, "outcome": "error", "error": None}
            try:
                message, info, wall_ms = run_one(engine, litert_lm, record["prompt"])
                prefill, decode = info.last_prefill_token_count, info.last_decode_token_count
                within = common.prefill_within_limit(prefill)
                row.update(
                    text=message_text(message),
                    channels=dict(message.channels) if message.channels else None,
                    finish_reason=common.finish_reason_for(decode),
                    prefill_tokens=prefill,
                    decode_tokens=decode,
                    ms=wall_ms,
                    outcome="ok" if within else "refused_prefill",
                )
            except Exception as exc:  # noqa: BLE001 - one bad prompt must not end the run
                row["error"] = type(exc).__name__
            counts[row["outcome"]] += 1
            writer.write(row)
    print(f"{len(records)} prompts: {counts['ok']} ok, {counts['refused_prefill']} refused by the "
          f"prefill backstop, {counts['error']} errors; wrote {args.out}")
    return 0 if counts["error"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
