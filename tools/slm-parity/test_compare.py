"""Unit tests for compare.py, standard library only.  Run: python3 -I tools/slm-parity/test_compare.py"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compare as c  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "test", "resources", "slm", "grounding-fixture-v1.json")


def fields(meds=("Amoxicillin (Mox) - 500 mg | oral | three times a day | 5 days | 15",), diagnosis=None, decision="AGREE"):
    return {"medication_lines": list(meds), "diagnosis": diagnosis, "kernel_decision": decision, "suggests_referral": False, "case_record_id": "x"}


def prompt_for(f, question="Explain the prescription"):
    meds = "\n".join("- " + m for m in f["medication_lines"]) or "(no medicines are listed on this approved record)"
    return (
        "You are restating a record a doctor has already approved. Use plain language.\n"
        "Do not add any medical information that is not written below.\n\nAPPROVED RECORD\n"
        f"Doctor's decision on the assessment: {f['kernel_decision']}\nDiagnosis: {f['diagnosis'] or '(not recorded)'}\n"
        f"Medicines:\n{meds}\nReferral suggested: no\n\nQUESTION\n{question}"
    )


def golden_row(row_id, tier="COMMUNITY", script="Latin", language="en", refusal=False, f=None, question="Explain the prescription"):
    f = f or fields()
    return {"id": row_id, "prompt": prompt_for(f, question), "question": question, "question_script": script,
            "question_language": language, "tier": tier, "expected_input_refusal": refusal, "snapshot_fields": f}


CONTROL = {"<pad>", "<bos>", "<|channel>", "<channel|>", "[multimodal]", "<|turn>"} | {f"<unused{i}>" for i in range(50)}


class AgreementWithKotlin(unittest.TestCase):
    """The Python ports give the verdicts of the real Kotlin functions (committed fixture)."""

    @classmethod
    def setUpClass(cls):
        with open(FIXTURE, encoding="utf-8") as handle:
            cls.fixture = json.load(handle)
        cls.tokens = set(cls.fixture["named_control_tokens"]) | {f"<unused{i}>" for i in range(cls.fixture["unused_token_count"])}

    def test_grounding_agrees_on_every_case(self):
        for case in self.fixture["cases"]:
            f = fields(case["medication_lines"], case["diagnosis"], case["decision"])
            self.assertEqual(case["grounded"], c.output_is_grounded(case["output"], f), case["id"])

    def test_control_token_membership_agrees_on_every_case(self):
        for case in self.fixture["cases"]:
            self.assertEqual(case["control_token_hit"], any(t in case["output"] for t in self.tokens), case["id"])

    def test_the_sanitizer_port_agrees_on_every_case(self):
        for case in self.fixture["cases"]:
            self.assertEqual(case["sanitized"], c.sanitize(case["output"], self.tokens), case["id"])

    def test_the_fixture_is_not_vacuous(self):
        self.assertEqual({True, False}, {x["grounded"] for x in self.fixture["cases"]})
        self.assertEqual({True, False}, {x["control_token_hit"] for x in self.fixture["cases"]})
        self.assertTrue(any(x["sanitized"] != x["output"] for x in self.fixture["cases"]))


class NumbersGate(unittest.TestCase):
    section = "APPROVED RECORD\nMedicines:\n- Amoxicillin - 500 mg | oral | 5 days | 15\nDiagnosis: BP 128/84"

    def test_numbers_present_in_the_record_pass(self):
        self.assertEqual([], c.numbers_gate("Take 500 mg for 5 days, 15 in total. BP 128/84.", self.section))

    def test_a_number_absent_from_the_record_fails(self):
        self.assertEqual(["250"], c.numbers_gate("Take 250 mg.", self.section))

    def test_devanagari_digits_are_normalised_both_ways(self):
        self.assertEqual([], c.numbers_gate("५०० mg ५ days", self.section))
        self.assertEqual(["700"], c.numbers_gate("७०० mg", self.section))
        self.assertEqual([], c.numbers_gate("Take 500 mg", self.section.replace("500", "५००")))

    def test_line_leading_enumeration_markers_are_excluded_but_mid_line_numbers_are_not(self):
        self.assertEqual([], c.numbers_gate("1. Take it\n2) Rest\n  3. Drink water", self.section))
        self.assertEqual(["7"], c.numbers_gate("Take 7 tablets", self.section))
        self.assertEqual(["9"], c.numbers_gate("1. Take it\nthen 9 times", self.section))

    def test_decimals_and_slashes_are_single_numbers(self):
        self.assertEqual(["1.5"], c.numbers_gate("Take 1.5 tablets", self.section))
        self.assertEqual(["128/85"], c.numbers_gate("BP 128/85", self.section))


class ControlTokenGate(unittest.TestCase):
    def test_a_literal_fails(self):
        self.assertTrue(c.control_token_gate("take <pad> now", CONTROL))

    def test_the_bracket_shape_rule_fails_a_bracketed_word(self):
        self.assertTrue(c.control_token_gate("See note [2]", CONTROL))
        self.assertTrue(c.control_token_gate("Take <pending>", CONTROL))

    def test_clinical_angle_brackets_pass(self):
        self.assertEqual([], c.control_token_gate("BP <90 and eGFR <60 and >90", CONTROL))


class LanguageGate(unittest.TestCase):
    section = "APPROVED RECORD\n- Amoxicillin - 500 mg | oral | three times a day\nDiagnosis: Acute viral pharyngitis"

    def test_latin_answer_to_a_latin_question_passes(self):
        self.assertTrue(c.language_gate("Take the medicine after food.", self.section, "Latin"))

    def test_latin_answer_to_a_devanagari_question_fails(self):
        self.assertFalse(c.language_gate("Take the medicine after food.", self.section, "Devanagari"))

    def test_devanagari_answer_passes_for_a_devanagari_question(self):
        self.assertTrue(c.language_gate("दवा खाने के बाद लें", self.section, "Devanagari"))

    def test_words_copied_from_the_record_do_not_count(self):
        # Two Devanagari words around a long copied Latin run: only the Devanagari counts once the copy is removed.
        text = "Amoxicillin oral pharyngitis acute viral three times a day दवा लें"
        self.assertTrue(c.language_gate(text, self.section, "Devanagari"))
        self.assertFalse(c.language_gate(text, "APPROVED RECORD\n- other record", "Devanagari"))

    def test_an_answer_with_no_letters_left_passes(self):
        self.assertTrue(c.language_gate("Amoxicillin 500", self.section, "Devanagari"))


class Sections(unittest.TestCase):
    def test_the_record_section_runs_from_its_heading_to_the_question(self):
        g = golden_row("a", question="What is it?")
        section = c.record_section(g["prompt"])
        self.assertTrue(section.startswith("APPROVED RECORD"))
        self.assertNotIn("What is it?", section)
        with self.assertRaises(ValueError):
            c.record_section("no sections here")


class SeamGatesAndResults(unittest.TestCase):
    def test_gate_order_and_tiers(self):
        f = fields()
        self.assertEqual("gate_identity", c.seam_gates("ok", "STOP", False, "COMMUNITY", f))
        self.assertEqual("gate_finish", c.seam_gates("ok", "LENGTH", True, "COMMUNITY", f))
        self.assertIsNone(c.seam_gates("ok", "STOP", True, "COMMUNITY", f))
        self.assertEqual("gate_grounding", c.seam_gates("Take ibuprofen", "STOP", True, "COMMUNITY", f))
        self.assertIsNone(c.seam_gates("Take ibuprofen", "STOP", True, "PHYSICIAN", f))

    def test_server_results(self):
        gold = {i: golden_row(i) for i in ("a", "b", "c", "d")}
        rows = [
            {"id": "a", "http_status": 200, "text": "Take amoxicillin 500 mg", "finish_reason": "stop", "model_id": c.SANITIZER_TARGET_MODEL_ID, "usage": {"completion_tokens": 5}},
            {"id": "b", "http_status": 413, "error_code": "SAMD-SLM-8003", "text": None},
            {"id": "c", "http_status": 200, "text": "x", "finish_reason": "length", "model_id": c.SANITIZER_TARGET_MODEL_ID},
            {"id": "d", "http_status": 503, "error_code": "SAMD-SLM-8005", "text": None},
        ]
        r = c.server_results(rows, gold, CONTROL)
        self.assertTrue(r["a"].shown)
        self.assertEqual(5, r["a"].decode_tokens)
        self.assertEqual(("REFUSED", "over_context"), (r["b"].status, r["b"].kind))
        self.assertEqual("gate_finish", r["c"].kind)
        self.assertEqual("other", r["d"].kind)

    def test_desktop_errors_are_over_context_by_text_or_by_the_token_count(self):
        gold = {i: golden_row(i) for i in ("a", "b", "c", "d")}
        rows = [
            {"id": "a", "outcome": "error", "error": "RuntimeError: INVALID_ARGUMENT: Input token ids are too long"},
            {"id": "b", "outcome": "error", "error": "RuntimeError"},
            {"id": "c", "outcome": "error", "error": "RuntimeError"},
            {"id": "d", "outcome": "refused_prefill"},
        ]
        r = c.desktop_results(rows, gold, CONTROL, over_2048={"b"})
        self.assertEqual("over_context", r["a"].kind)
        self.assertEqual("over_context_inferred", r["b"].kind)
        self.assertEqual("other", r["c"].kind)
        self.assertEqual("over_context", r["d"].kind)

    def test_local_results(self):
        gold = {i: golden_row(i) for i in ("a", "b", "c", "d", "e")}
        ok = {"outcome": "ok", "sanitized_text": "Take amoxicillin", "finish_reason": "STOP", "served_model_id": c.LOCAL_SERVED_MODEL_ID, "decode_tokens": 4}
        rows = [
            dict(ok, id="a"),
            {"id": "b", "outcome": "error", "error_kind": "PAYLOAD_REJECTED", "error_reason": "input_too_long"},
            {"id": "c", "outcome": "error", "error_kind": "TIMEOUT"},
            dict(ok, id="d", served_model_id="some/other"),
            {"id": "e", "outcome": "error", "error_kind": "PAYLOAD_REJECTED", "error_reason": "prefill_over_limit_or_unreadable"},
        ]
        r = c.local_results(rows, gold)
        self.assertTrue(r["a"].shown)
        self.assertEqual(["over_context", "timeout", "gate_identity", "payload_rejected_other"], [r[i].kind for i in "bcde"])


class Gates(unittest.TestCase):
    def setUp(self):
        self.gold = {i: golden_row(i) for i in ("a", "b", "c")}

    def results(self, texts):
        return {i: (c.Result("SHOWN", t) if t is not None else c.Result("REFUSED", kind="timeout")) for i, t in texts.items()}

    def test_refusal_parity_flags_a_shown_over_2048_prompt(self):
        runs = {"server": self.results({"a": None}), "gpu": self.results({"a": "answer"})}
        self.assertEqual({"server": [], "gpu": ["a"]}, c.refusal_parity(runs, {"a"}))

    def test_non_inferiority(self):
        server = self.results({"a": "Take amoxicillin 500 mg", "b": "Take amoxicillin 500 mg", "c": "Take 250 mg"})
        local = self.results({"a": "Take amoxicillin 700 mg", "b": None, "c": "Take 800 mg"})
        sg = c.gate_runs(server, self.gold, set(), CONTROL)
        lg = c.gate_runs(local, self.gold, set(), CONTROL)
        # a: server passes, local fails (a number not in the record); b: local refused (reported, not a violation);
        # c: the server fails too.
        self.assertEqual(["a"], c.non_inferiority_violations(sg, local, lg))

    def test_excluded_prompts_are_not_scored(self):
        local = self.results({"a": "Take 700 mg", "b": "Take amoxicillin"})
        self.assertEqual(["b"], sorted(c.gate_runs(local, self.gold, {"a"}, CONTROL)))

    def test_refusal_rate_uses_scored_prompts_only(self):
        res = self.results({"a": None, "b": "x", "c": None})
        self.assertEqual((1, 2, 50.0), c.refusal_rate(res, {"c"}))

    def test_token_parity_failures_ignore_over_2048_prompts(self):
        rows = [
            {"id": "a", "over_2048": False, "identical": True, "render_identical": True},
            {"id": "b", "over_2048": False, "identical": False, "render_identical": True},
            {"id": "c", "over_2048": True, "identical": False, "render_identical": False},
            {"id": "d", "over_2048": False, "identical": True, "render_identical": False},
        ]
        self.assertEqual(["b", "d"], c.token_parity_failures(rows))


class Metrics(unittest.TestCase):
    def test_chrf(self):
        self.assertEqual(1.0, c.chrf("take the medicine after food", "take the medicine after food"))
        self.assertEqual(0.0, c.chrf("aaaaaaaaaa", "bbbbbbbbbb"))
        partial = c.chrf("take the medicine after food", "take the medicine before food")
        self.assertTrue(0.0 < partial < 1.0)
        self.assertEqual(c.chrf("take the  medicine", "take the medicine"), 1.0)

    def test_first_difference(self):
        self.assertEqual(3, c.first_difference("abcX", "abcY"))
        self.assertEqual(2, c.first_difference("ab", "abc"))

    def test_similarity_counts_only_prompts_shown_on_both(self):
        server = {"a": c.Result("SHOWN", "same text here"), "b": c.Result("SHOWN", "x"), "c": c.Result("REFUSED", kind="timeout")}
        other = {"a": c.Result("SHOWN", "same text here"), "b": c.Result("REFUSED", kind="timeout"), "c": c.Result("SHOWN", "y")}
        s = c.similarity_to_server(server, other, set())
        self.assertEqual((1, 1), (s["n"], s["exact"]))

    def test_repeatability(self):
        base = [{"id": "a", "outcome": "ok", "sanitized_text": "same", "decode_tokens": 1},
                {"id": "b", "outcome": "ok", "sanitized_text": "abcdef", "decode_tokens": 6},
                {"id": "c", "outcome": "error"}]
        rep = [{"id": "a", "outcome": "ok", "sanitized_text": "same", "decode_tokens": 1},
               {"id": "b", "outcome": "ok", "sanitized_text": "abcXef", "decode_tokens": 7},
               {"id": "c", "outcome": "ok", "sanitized_text": "x", "decode_tokens": 1}]
        r = c.repeatability(base, rep)
        self.assertEqual((3, 1), (r["n"], r["identical"]))
        self.assertEqual([{"id": "b", "first_diff": 3, "decode_tokens": (6, 7)}], r["differing"])
        self.assertEqual(["c"], r["not_comparable"])

    def test_the_blind_sample_is_deterministic_stratified_and_keyed(self):
        gold, server, other = {}, {}, {}
        for lang in ("en", "hi-Deva", "hi-Latn"):
            for k in range(14):
                i = f"{lang}-{k}"
                gold[i] = golden_row(i, language=lang)
                server[i] = c.Result("SHOWN", f"server answer {k}")
                other[i] = c.Result("SHOWN", f"local answer {k}")
        rows, key = c.blind_sample(server, other, gold, set())
        rows2, key2 = c.blind_sample(server, other, gold, set())
        self.assertEqual((rows, key), (rows2, key2))
        self.assertEqual(30, len(rows))
        self.assertEqual({"en": 10, "hi-Deva": 10, "hi-Latn": 10}, {l: sum(1 for r in rows if r["language"] == l) for l in ("en", "hi-Deva", "hi-Latn")})
        for r, k in zip(rows, key):
            self.assertEqual(r["row"], k["row"])
            a_is_server = k["A_is"] == "server"
            self.assertEqual("server" in r["answer_A"], a_is_server)
            self.assertEqual("server" in r["answer_B"], not a_is_server)
        self.assertEqual({"server", "pixel-gpu"}, {k["A_is"] for k in key} | {k["B_is"] for k in key})
        # fewer disagreements than the quota: all of them, and identical texts are not disagreements
        small = {"x": c.Result("SHOWN", "same"), "y": c.Result("SHOWN", "one")}
        small_other = {"x": c.Result("SHOWN", "same"), "y": c.Result("SHOWN", "two")}
        rows, _ = c.blind_sample(small, small_other, {"x": golden_row("x"), "y": golden_row("y")}, set())
        self.assertEqual(1, len(rows))


class EndToEnd(unittest.TestCase):
    """The whole tool over a tiny synthetic set: exit codes, and no text in the report."""

    SENTINEL_Q = "QSENTINEL-explain-this"
    SENTINEL_O = "OSENTINEL-take-amoxicillin"

    def write(self, directory, name, rows):
        path = os.path.join(directory, name)
        with open(path, "w", encoding="utf-8") as handle:
            for r in rows:
                handle.write(json.dumps(r, ensure_ascii=False) + "\n")
        return path

    def dataset(self, directory, local_text):
        gold = [golden_row("g-0", tier="PHYSICIAN", question=self.SENTINEL_Q), golden_row("g-1"), golden_row("g-2")]
        gold[2]["expected_input_refusal"] = True
        paths = {"golden": self.write(directory, "golden.jsonl", gold)}
        paths["tokens"] = os.path.join(directory, "control.txt")
        with open(paths["tokens"], "w", encoding="utf-8") as handle:
            handle.write("\n".join(sorted(CONTROL)) + "\n")
        paths["token_parity"] = self.write(directory, "tp.jsonl", [
            {"id": "g-0", "over_2048": False, "identical": True, "render_identical": True},
            {"id": "g-1", "over_2048": True, "identical": False, "render_identical": False},
            {"id": "g-2", "over_2048": False, "identical": True, "render_identical": True},
        ])
        answer = f"{self.SENTINEL_O} 500 mg"
        server = [
            {"id": "g-0", "http_status": 200, "text": answer, "finish_reason": "stop", "model_id": c.SANITIZER_TARGET_MODEL_ID, "usage": {"completion_tokens": 5}},
            {"id": "g-1", "http_status": 413, "error_code": "SAMD-SLM-8003", "text": None},
            {"id": "g-2", "http_status": 200, "text": answer, "finish_reason": "stop", "model_id": c.SANITIZER_TARGET_MODEL_ID},
        ]
        desktop = [
            {"id": "g-0", "outcome": "ok", "text": answer, "finish_reason": "stop", "decode_tokens": 5},
            {"id": "g-1", "outcome": "error", "error": "RuntimeError"},
            {"id": "g-2", "outcome": "ok", "text": answer, "finish_reason": "stop", "decode_tokens": 5},
        ]
        def local(text):
            ok = {"outcome": "ok", "sanitized_text": text, "finish_reason": "STOP", "served_model_id": c.LOCAL_SERVED_MODEL_ID, "decode_tokens": 5}
            return [dict(ok, id="g-0"), {"id": "g-1", "outcome": "error", "error_kind": "PAYLOAD_REJECTED", "error_reason": "input_too_long"}, dict(ok, id="g-2")]
        paths["server"] = self.write(directory, "server.jsonl", server)
        paths["desktop"] = self.write(directory, "desktop.jsonl", desktop)
        paths["gpu"] = self.write(directory, "gpu.jsonl", local(local_text))
        paths["cpu"] = self.write(directory, "cpu.jsonl", local(local_text))
        return paths

    def run_tool(self, directory, local_text, extra=()):
        p = self.dataset(directory, local_text)
        report = os.path.join(directory, "REPORT.md")
        argv = ["--golden", p["golden"], "--control-tokens", p["tokens"], "--token-parity", p["token_parity"], "--server", p["server"],
                "--desktop", p["desktop"], "--gpu", p["gpu"], "--cpu", p["cpu"], "--report", report,
                "--review-dir", os.path.join(directory, "review"), *extra]
        code = c.main(argv)
        with open(report, encoding="utf-8") as handle:
            return code, handle.read()

    def test_a_clean_run_exits_zero_and_the_report_holds_no_text(self):
        with tempfile.TemporaryDirectory() as d:
            code, report = self.run_tool(d, f"{self.SENTINEL_O} 500 mg")
            self.assertEqual(0, code, report)
            for sentinel in (self.SENTINEL_Q, self.SENTINEL_O, "amoxicillin 500"):
                self.assertNotIn(sentinel, report)
            self.assertIn("| H3 refusal parity", report)
            self.assertNotIn("FAIL", report)
            self.assertTrue(os.path.isfile(os.path.join(d, "review", "blind-sample.csv")))
            self.assertTrue(os.path.isfile(os.path.join(d, "review", "blind-sample-key.csv")))

    def test_a_local_output_that_fails_a_gate_exits_one_and_names_the_gates(self):
        with tempfile.TemporaryDirectory() as d:
            code, report = self.run_tool(d, f"{self.SENTINEL_O} 999 mg")
            self.assertEqual(1, code)
            self.assertIn("| H1 content gates 100% (pixel-gpu) | FAIL", report)
            self.assertIn("| H2 non-inferiority (pixel-gpu) | FAIL", report)
            self.assertNotIn(self.SENTINEL_O, report)


if __name__ == "__main__":
    unittest.main()
