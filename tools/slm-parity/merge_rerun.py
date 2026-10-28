"""Build a run file from a base run by replacing named rows with rows from a re-run, and say which.

    python3 -I tools/slm-parity/merge_rerun.py --base runs/pixel-cpu-v2.jsonl \
        --rerun runs/pixel-cpu-v2-rerun.jsonl --out runs/pixel-cpu-v3.jsonl --record runs/pixel-cpu-v3-replaced.txt

Every row of the re-run replaces the base row with the same id; the base row order is kept; a re-run
id that the base does not have is an error. Replaced rows carry `replaced_from` (the re-run file's
name). The record lists, per replaced id, the base outcome and the re-run outcome, and the sha256 of
the three files. Standard library only.
"""

import argparse
import hashlib
import json
import os
import sys


def read_jsonl(path):
    with open(path, encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def outcome(row):
    return row["outcome"] if row.get("outcome") == "ok" else f"{row.get('outcome')}:{row.get('error_kind')}"


def merge(base_rows, rerun_rows, rerun_name):
    by_id = {r["id"]: r for r in rerun_rows}
    if len(by_id) != len(rerun_rows):
        raise ValueError("the re-run has duplicate ids")
    base_ids = [r["id"] for r in base_rows]
    unknown = sorted(set(by_id) - set(base_ids))
    if unknown:
        raise ValueError(f"re-run ids not in the base run: {unknown}")
    merged, replaced = [], []
    for row in base_rows:
        new = by_id.get(row["id"])
        if new is None:
            merged.append(row)
        else:
            merged.append(dict(new, replaced_from=rerun_name))
            replaced.append((row["id"], outcome(row), outcome(new)))
    return merged, replaced


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base", required=True)
    p.add_argument("--rerun", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--record", required=True)
    args = p.parse_args(argv)
    merged, replaced = merge(read_jsonl(args.base), read_jsonl(args.rerun), os.path.basename(args.rerun))
    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        for row in merged:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    lines = [f"merged {len(replaced)} of {len(merged)} rows from {os.path.basename(args.rerun)} into {os.path.basename(args.base)}"]
    lines += [f"{i}: base {before} -> re-run {after}" for i, before, after in replaced]
    lines += [f"{label}: sha256 {sha256_file(path)}" for label, path in (("base", args.base), ("re-run", args.rerun), ("out", args.out))]
    with open(args.record, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
