"""Prompt token-id parity, HF tokenizer against the litertlm tokenizer (G-6, memo 11.2).

For the GPU machine's venv: transformers 5.5.0 and litert-lm 0.18.0. Nothing is downloaded: the HF
tokenizer is read from a local cache at the pinned revision (local_files_only=True).

    python3 -I scripts/slm-parity/token_parity.py \
        --in golden/prompts-v1.jsonl --hf-cache-dir <HF cache dir> --model <gemma-4-E2B-it.litertlm> \
        --render-out golden/server-render-v1.jsonl --out runs/token-parity.jsonl

Server side: `apply_chat_template` with one user message and add_generation_prompt=True (no thinking
flag), then the tokenizer with add_special_tokens=False, which is what apply_chat_template does when
it tokenizes (transformers 5.5.0, tokenization_utils_base.py:3083), so the leading <bos> in the
rendering is the only BOS. litertlm side: the bundled template (render_message_to_string), tokenized
by the engine, with BOS id 2 prepended (G2 REPORT: the runtime prepends BOS, the rendering has none).

Exits non-zero if any prompt that is not over 2048 server tokens has different ids, or if a
rendering differs. Prompts over 2048 tokens are expected refusals on both engines (C-1).
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import slm_parity_common as common  # noqa: E402

HF_MODEL_ID = common.SERVICE_MODEL_ID
BOM = "<bos>"


def server_render(tokenizer, prompt):
    """The service's rendering: its chat template over a single user turn, generation prompt on."""
    return tokenizer.apply_chat_template(
        [{"role": "user", "content": prompt}], add_generation_prompt=True, tokenize=False
    )


def server_ids(tokenizer, rendering):
    return list(tokenizer(rendering, add_special_tokens=False)["input_ids"])


def strip_bos(rendering):
    if not rendering.startswith(BOM):
        raise ValueError("the server rendering does not start with <bos>")
    return rendering[len(BOM):]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--in", dest="golden", required=True)
    parser.add_argument("--hf-cache-dir", required=True, help="local Hugging Face cache; never downloads")
    parser.add_argument("--model", required=True, help="path to the generic .litertlm")
    parser.add_argument("--render-out", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--revision", default=common.TOKENIZER_REVISION)
    parser.add_argument("--cache-dir", default=None, help="litert-lm engine cache dir")
    args = parser.parse_args(argv)

    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    from transformers import AutoTokenizer  # noqa: PLC0415 (heavy, GPU-machine only)
    import litert_lm  # noqa: PLC0415

    tokenizer = AutoTokenizer.from_pretrained(
        HF_MODEL_ID, cache_dir=args.hf_cache_dir, local_files_only=True, revision=args.revision
    )
    records = common.check_golden(common.read_jsonl(args.golden))

    renders, parity = [], []
    with litert_lm.Engine(
        args.model,
        backend=litert_lm.Backend.CPU(),
        max_num_tokens=2560,
        cache_dir=args.cache_dir,
    ) as engine:
        for record in records:
            prompt = record["prompt"]
            rendering = server_render(tokenizer, prompt)
            ids_server = server_ids(tokenizer, rendering)
            renders.append({"id": record["id"], "rendering": strip_bos(rendering)})

            with engine.create_conversation(automatic_tool_calling=False) as conversation:
                litertlm_rendering = conversation.render_message_to_string(prompt)
            ids_litertlm = [common.BOS_ID] + engine.tokenize(litertlm_rendering)
            parity.append(
                common.token_parity_row(
                    record["id"], ids_server, ids_litertlm, litertlm_rendering == renders[-1]["rendering"]
                )
            )

    common.write_jsonl(args.render_out, renders)
    common.write_jsonl(args.out, parity)

    failures = common.parity_failures(parity)
    render_failures = [r["id"] for r in parity if not r["over_2048"] and not r["render_identical"]]
    over = sum(1 for r in parity if r["over_2048"])
    print(f"{len(parity)} prompts, {over} over 2048 server tokens (expected refusals), "
          f"{len(failures)} id mismatches, {len(render_failures)} rendering mismatches")
    if failures or render_failures:
        print("FAIL: " + ", ".join(sorted(set(failures + render_failures))), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
