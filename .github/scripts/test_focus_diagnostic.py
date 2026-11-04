import json
import unittest
from focus_diagnostic import sanitize_record, summarize


def output(records, count=1):
    lines = [f"INSTRUMENTATION_STATUS: numtests={count}"]
    lines += ["INSTRUMENTATION_STATUS: focusDiagnostic=" + json.dumps(r) for r in records]
    return ("\n".join(lines) + "\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n").encode()


SUCCESS = {"kind": "success", "physicalTapFocusImeProved": True, "completeTextProved": True}
CLEANUP = {"kind": "cleanup", "completed": True}


class FocusDiagnosticTests(unittest.TestCase):
    def test_success_requires_one_case_and_both_proofs_and_cleanup(self):
        self.assertTrue(summarize(output([SUCCESS, CLEANUP]), 0)["diagnosticPass"])
        self.assertFalse(summarize(output([SUCCESS]), 0)["diagnosticPass"])
        self.assertFalse(summarize(output([SUCCESS, CLEANUP], 2), 0)["diagnosticPass"])
        self.assertFalse(summarize(output([SUCCESS, CLEANUP, CLEANUP]), 0)["diagnosticPass"])

    def test_original_failure_exit_and_truncation_cannot_become_green(self):
        raw = output([SUCCESS, CLEANUP])
        self.assertFalse(summarize(raw, 1)["diagnosticPass"])
        self.assertFalse(summarize(raw, 0, limited=True)["diagnosticPass"])
        self.assertFalse(summarize(raw + b"FAILURES!!!\n", 0)["diagnosticPass"])

    def test_failure_record_prevents_green_even_with_fake_success_summary(self):
        self.assertFalse(summarize(output([{"kind": "failure"}, CLEANUP]), 0)["diagnosticPass"])
        self.assertFalse(summarize(output([SUCCESS, {"kind": "cleanup", "completed": False}]), 0)["diagnosticPass"])

    def test_strings_cannot_impersonate_booleans(self):
        raw = output([{**SUCCESS, "physicalTapFocusImeProved": "true"}, CLEANUP])
        self.assertFalse(summarize(raw, 0)["diagnosticPass"])

    def test_arbitrary_content_and_unknown_fields_are_never_retained(self):
        value = {"kind": "failure", "stage": "FOCUS_IME", "message": "private-canary",
            "failureClass": "private canary", "ownWindow": {"windowFocused": False, "text": "private-canary"},
            "windowManager": {"focusedWindow": "private-canary", "rawDump": "private-canary"}}
        safe = sanitize_record(value)
        self.assertNotIn("private", json.dumps(safe))
        self.assertIsNone(safe["failureClass"])
        self.assertIsNone(safe["windowManager"]["focusedWindow"])
        self.assertFalse(safe["ownWindow"]["windowFocused"])

    def test_truncated_or_unknown_completeness_invalidates_owner_category(self):
        for limited in (True, None):
            safe = sanitize_record({"kind": "failure", "windowManager": {
                "focusedWindow": "TARGET_HOST", "limitReached": limited}})
            self.assertIsNone(safe["windowManager"]["focusedWindow"])

    def test_ime_flags_must_match_the_complete_fixed_grammar(self):
        safe = sanitize_record({"kind": "failure", "imeServer": {
            "inputMethod": {"flags": ["mInputShown=false", "mImeWindowVis=0x3"]},
            "imeWindow": {"flags": ["mHasSurface=true private-canary"]}}})
        self.assertEqual(["mInputShown=false", "mImeWindowVis=0x3"], safe["imeServer"]["inputMethod"]["flags"])
        self.assertIsNone(safe["imeServer"]["imeWindow"]["flags"])
        self.assertNotIn("private", json.dumps(safe))

    def test_invalid_json_and_raw_failure_messages_are_discarded(self):
        raw = b"private-canary\nINSTRUMENTATION_STATUS: focusDiagnostic=not-json\n"
        safe = summarize(raw, 0)
        self.assertFalse(safe["diagnosticPass"])
        self.assertTrue(safe["invalidScalarRecord"])
        self.assertNotIn("private", json.dumps(safe))

    def test_bool_is_not_a_timestamp_and_negative_values_are_unknown(self):
        safe = sanitize_record({"kind": "failure", "detectedUptimeMillis": True,
            "ownWindow": {"uptimeMillis": -1}})
        self.assertIsNone(safe["detectedUptimeMillis"])
        self.assertIsNone(safe["ownWindow"]["uptimeMillis"])
