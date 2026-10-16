"""Tests for what can run without a GPU, a model or the service. Standard library only.

    python3 -I scripts/slm-parity/test_slm_parity.py
"""

import contextlib
import http.server
import io
import json
import os
import sys
import tempfile
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_server  # noqa: E402
import slm_parity_common as common  # noqa: E402
import token_parity  # noqa: E402

TEST_TOKEN = "tok-DO-NOT-LEAK-7f3a"


def golden_row(row_id, prompt):
    return {"id": row_id, "template_version": "slm-readback-v1", "prompt": prompt,
            "prompt_sha256": common.sha256_text(prompt)}


class JsonlTests(unittest.TestCase):
    def test_round_trip_keeps_unicode_and_order_independent_keys(self):
        rows = [{"id": "a", "text": "दवा लें"}, {"id": "b", "n": 1, "text": None}]
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "nested", "x.jsonl")
            common.write_jsonl(path, rows)
            self.assertEqual(rows, common.read_jsonl(path))
            with open(path, encoding="utf-8") as handle:
                self.assertIn("दवा लें", handle.read())

    def test_a_bad_line_is_named(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "x.jsonl")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write('{"id": 1}\n{oops\n')
            with self.assertRaisesRegex(ValueError, r"x\.jsonl:2"):
                common.read_jsonl(path)

    def test_blank_lines_are_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "x.jsonl")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write('{"id": 1}\n\n{"id": 2}\n')
            self.assertEqual([1, 2], [r["id"] for r in common.read_jsonl(path)])

    def test_the_golden_check_catches_a_tampered_prompt_and_a_duplicate_id(self):
        good = golden_row("g-1", "prompt one")
        self.assertEqual([good], common.check_golden([good]))
        with self.assertRaisesRegex(ValueError, "does not match"):
            common.check_golden([dict(good, prompt="prompt two")])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            common.check_golden([good, good])
        with self.assertRaisesRegex(ValueError, "without 'prompt_sha256'"):
            common.check_golden([{"id": "x", "prompt": "p", "template_version": "v"}])


class FlaggingTests(unittest.TestCase):
    def test_over_2048_is_strictly_greater(self):
        self.assertFalse(common.over_2048(2048))
        self.assertTrue(common.over_2048(2049))
        self.assertFalse(common.over_2048(0))

    def test_first_diff(self):
        self.assertIsNone(common.first_diff([1, 2, 3], [1, 2, 3]))
        self.assertEqual(1, common.first_diff([1, 2, 3], [1, 9, 3]))
        self.assertEqual(2, common.first_diff([1, 2], [1, 2, 3]))
        self.assertEqual(0, common.first_diff([], [5]))

    def test_the_parity_row_and_the_failure_rule(self):
        same = common.token_parity_row("a", [2, 5, 6], [2, 5, 6], True)
        self.assertEqual({"id": "a", "server_len": 3, "litertlm_len": 3, "identical": True,
                          "first_diff": None, "over_2048": False, "render_identical": True}, same)
        differs = common.token_parity_row("b", [2, 5, 6], [2, 5, 7], True)
        self.assertEqual(2, differs["first_diff"])
        long_differs = common.token_parity_row("c", list(range(2049)), [0], True)
        self.assertTrue(long_differs["over_2048"])
        # Only a prompt that is NOT over 2048 tokens and differs fails the run (G-6, C-1).
        self.assertEqual(["b"], common.parity_failures([same, differs, long_differs]))

    def test_finish_reason_is_derived_like_the_device(self):
        self.assertEqual("stop", common.finish_reason_for(0))
        self.assertEqual("stop", common.finish_reason_for(511))
        self.assertEqual("length", common.finish_reason_for(512))
        self.assertEqual("length", common.finish_reason_for(513))
        self.assertIsNone(common.finish_reason_for(None))
        self.assertIsNone(common.finish_reason_for(-1))

    def test_the_prefill_backstop(self):
        self.assertTrue(common.prefill_within_limit(1))
        self.assertTrue(common.prefill_within_limit(2048))
        self.assertFalse(common.prefill_within_limit(2049))
        self.assertFalse(common.prefill_within_limit(0))
        self.assertFalse(common.prefill_within_limit(None))


class FakeTokenizer:
    """Stands in for the HF tokenizer: records the calls so the call shape is checked."""

    def __init__(self):
        self.calls = []

    def apply_chat_template(self, conversation, **kwargs):
        self.calls.append(("template", conversation, kwargs))
        return "<bos><|turn>user\n" + conversation[0]["content"].strip() + "<turn|>\n<|turn>model\n"

    def __call__(self, text, **kwargs):
        self.calls.append(("tokenize", text, kwargs))
        return {"input_ids": [2] + [ord(c) % 50 for c in text.removeprefix("<bos>")]}


class TokenParityHelperTests(unittest.TestCase):
    def test_the_server_is_rendered_with_one_user_turn_and_no_thinking_flag(self):
        tokenizer = FakeTokenizer()
        rendering = token_parity.server_render(tokenizer, "  hello ")
        self.assertEqual(
            ("template", [{"role": "user", "content": "  hello "}], {"add_generation_prompt": True, "tokenize": False}),
            tokenizer.calls[0],
        )
        self.assertTrue(rendering.startswith("<bos>"))

    def test_the_ids_use_add_special_tokens_false_so_bos_is_not_doubled(self):
        tokenizer = FakeTokenizer()
        ids = token_parity.server_ids(tokenizer, "<bos>abc")
        self.assertEqual({"add_special_tokens": False}, tokenizer.calls[0][2])
        self.assertEqual(2, ids[0])

    def test_strip_bos_requires_a_leading_bos(self):
        self.assertEqual("<|turn>x", token_parity.strip_bos("<bos><|turn>x"))
        with self.assertRaises(ValueError):
            token_parity.strip_bos("<|turn>x")


class Handler(http.server.BaseHTTPRequestHandler):
    seen = []

    def do_POST(self):  # noqa: N802 - http.server naming
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        Handler.seen.append({"path": self.path, "token": self.headers.get(run_server.TOKEN_HEADER),
                             "content_type": self.headers.get("Content-Type"), "body": body})
        if len(body["prompt"]) > 100:
            self._send(422, {"code": "SAMD-SLM-8003", "detail": "echoes the prompt: " + body["prompt"][:20]})
        else:
            self._send(200, {"generation_id": "g", "text": "OK", "model_id": "google/gemma-4-E2B-it",
                             "model_sha256": "abc", "prompt_template_version": body["prompt_template_version"],
                             "finish_reason": "stop", "usage": {"prompt_tokens": 5, "completion_tokens": 1}})

    def _send(self, status, payload):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


class RunServerTests(unittest.TestCase):
    def setUp(self):
        Handler.seen = []
        self.server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.dir = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.dir.cleanup()

    def run_main(self, rows, base=None, env=None):
        golden = os.path.join(self.dir.name, "golden.jsonl")
        out = os.path.join(self.dir.name, "runs", "server.jsonl")
        common.write_jsonl(golden, rows)
        stdout, stderr = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env if env is not None else {run_server.TOKEN_ENV: TEST_TOKEN}):
            if env == {}:
                os.environ.pop(run_server.TOKEN_ENV, None)
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = run_server.main(["--base-url", base or self.base, "--in", golden, "--out", out])
        return code, out, stdout.getvalue(), stderr.getvalue()

    def test_the_body_is_exactly_what_the_backend_sends_and_nothing_is_skipped(self):
        rows = [golden_row("g-1", "short prompt"), golden_row("g-2", "x" * 500)]
        code, out, stdout, stderr = self.run_main(rows)

        self.assertEqual(0, code)
        self.assertEqual(2, len(Handler.seen))
        first = Handler.seen[0]
        self.assertEqual("/v1/generate", first["path"])
        self.assertEqual(TEST_TOKEN, first["token"])
        self.assertEqual("application/json", first["content_type"])
        self.assertEqual(
            {"prompt": "short prompt", "model_id": "google/gemma-4-E2B-it",
             "prompt_template_version": "slm-readback-v1", "max_tokens": 512, "temperature": 0,
             "do_sample": False, "seed": 20260918, "stop": []},
            first["body"],
        )
        results = {r["id"]: r for r in common.read_jsonl(out)}
        self.assertEqual("OK", results["g-1"]["text"])
        self.assertEqual("stop", results["g-1"]["finish_reason"])
        self.assertEqual(200, results["g-1"]["http_status"])
        self.assertEqual("abc", results["g-1"]["model_sha256"])
        # The refused prompt was sent and its outcome recorded, with no text.
        self.assertEqual(422, results["g-2"]["http_status"])
        self.assertEqual("SAMD-SLM-8003", results["g-2"]["error_code"])
        self.assertIsNone(results["g-2"]["text"])
        self.assertIsInstance(results["g-1"]["wall_ms"], int)

    def test_the_token_never_reaches_a_file_the_console_or_an_error(self):
        dead = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        dead_base = f"http://127.0.0.1:{dead.server_port}"
        dead.server_close()  # nothing is listening: a transport error
        for base in (self.base, dead_base):
            code, out, stdout, stderr = self.run_main([golden_row("g-1", "short prompt")], base=base)
            self.assertEqual(0, code)
            with open(out, encoding="utf-8") as handle:
                self.assertNotIn(TEST_TOKEN, handle.read())
            self.assertNotIn(TEST_TOKEN, stdout + stderr)
        row = common.read_jsonl(out)[0]
        self.assertIsNone(row["http_status"])
        self.assertIn(row["transport_error"], {"URLError", "ConnectionRefusedError"})

    def test_a_missing_token_refuses_before_any_request_or_file(self):
        code, out, stdout, stderr = self.run_main([golden_row("g-1", "p")], env={})
        self.assertEqual(2, code)
        self.assertEqual([], Handler.seen)
        self.assertFalse(os.path.exists(out))
        self.assertIn("SLM_SERVICE_TOKEN is not set", stderr)

    def test_a_tampered_golden_file_is_refused_before_any_request(self):
        bad = dict(golden_row("g-1", "p"), prompt="changed")
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.run_main([bad])
        self.assertEqual([], Handler.seen)


if __name__ == "__main__":
    unittest.main()
