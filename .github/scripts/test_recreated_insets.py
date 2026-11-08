"""Bounded/privacy-safe measurement accounting; no Android or native input calls."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import recreated_insets as diagnostic


def case(cycle, failed=False):
    row = dict(sequence=1, uptimeMillis=1000, event="cleanup_end", stage="CLEANUP", activity=1,
        window=2, root=3, attached=True, destroyed=False, windowFocused=True,
        rootMatchesWindow=True, focusedRootMatches=True, composeBottom=0, mirroredBottom=0,
        mirroredProducer=3, nativeVisible=False, nativeBottom=0, holderAccessors=2, applyListenerPresent=None)
    return dict(schema=1, cycle=cycle, failed=failed, cleanupFailed=False, droppedProgress=0, records=[row])


def stream(records, failed=(), finish=True):
    lines = []
    for value in records:
        number = value["cycle"]
        def status(code):
            return ["INSTRUMENTATION_STATUS: class=" + diagnostic.CLASS,
                f"INSTRUMENTATION_STATUS: test=roomRecreationThenDirectReply[cycle{number}]",
                "INSTRUMENTATION_STATUS_CODE: " + code]
        lines += status("1")
        lines += ["INSTRUMENTATION_STATUS: ccmInsetsCycle=" + json.dumps(value), "INSTRUMENTATION_STATUS_CODE: 0"]
        lines += status("-2" if number in failed else "0")
    if finish: lines += ["INSTRUMENTATION_CODE: -1"]
    return io.StringIO("\n".join(lines) + "\n")


class InsetsRecordsTests(unittest.TestCase):
    def test_all_twenty_original_cases_are_required(self):
        value = diagnostic.observe(stream([case(i) for i in range(1,21)]))
        self.assertTrue(value["measurementComplete"]); self.assertTrue(value["originalTestPass"])
        self.assertEqual(20, value["finishedCases"])

    def test_original_failure_is_retained_with_complete_accounting(self):
        value = diagnostic.observe(stream([case(i, i==7) for i in range(1,21)], failed={7}))
        self.assertTrue(value["measurementComplete"]); self.assertFalse(value["originalTestPass"])
        self.assertTrue(value["cases"][6]["failed"])
        self.assertEqual({"cycle":7,"status":-2},value["caseResults"][6])

    def test_missing_duplicate_or_no_runner_finish_never_pass(self):
        values = [case(i) for i in range(1,21)]
        for source in (stream(values[:-1]), stream(values + [case(1)]), stream(values, finish=False)):
            with self.subTest():
                result = diagnostic.observe(source)
                self.assertFalse(result["measurementComplete"]); self.assertFalse(result["originalTestPass"])

    def test_missing_cleanup_or_failed_cleanup_stays_negative(self):
        for variant in ("missing", "failed"):
            values = [case(i) for i in range(1,21)]
            if variant == "missing": values[0]["records"] = []
            else: values[0]["cleanupFailed"] = True
            self.assertFalse(diagnostic.observe(stream(values))["originalTestPass"])

    def test_cleanup_progress_after_terminal_control_is_not_missing_cleanup(self):
        values = [case(i) for i in range(1,21)]
        for value in values:
            progress = dict(value["records"][0], sequence=2, uptimeMillis=1001, event="draw")
            value["records"].append(progress)
        result = diagnostic.observe(stream(values))
        self.assertTrue(result["measurementComplete"])
        self.assertTrue(result["originalTestPass"])

    def test_listener_unavailable_remains_unknown(self):
        value = case(1)
        value["records"][0]["holderAccessors"] = None
        self.assertIsNone(diagnostic.sanitize(value)["records"][0]["applyListenerPresent"])
        self.assertIsNone(diagnostic.sanitize(value)["records"][0]["holderAccessors"])

    def test_extra_private_values_malformed_types_and_bounds_are_rejected(self):
        for transform in (lambda v: v.update(body="private-canary"),
            lambda v: v["records"][0].update(title="private-canary"),
            lambda v: v["records"][0].update(nativeVisible="true"),
            lambda v: v["records"][0].update(event=["draw"]),
            lambda v: v["records"][0].update(nativeBottom=20000),
            lambda v: v.update(cycle=True),
            lambda v: v.update(records=v["records"]*193)):
            value = case(1); transform(value)
            with self.subTest(): self.assertIsNone(diagnostic.sanitize(value))
        value = case(1); value["records"][0]["body"] = "private-canary"
        result = diagnostic.observe(stream([value]))
        self.assertTrue(result["invalidMetadata"])
        self.assertNotIn("private-canary", json.dumps(result))

    def test_out_of_order_records_and_oversized_stream_are_not_accepted(self):
        value = case(1); value["records"] *= 2
        self.assertIsNone(diagnostic.sanitize(value))
        with self.assertRaises(ValueError): diagnostic.observe(io.StringIO("x"*262145))

    def test_wrong_api_never_invokes_instrumentation(self):
        with tempfile.TemporaryDirectory(prefix="ccm-insets-unit-") as folder:
            with patch.object(diagnostic, "REPORT", Path(folder)), patch.dict("os.environ",
                {"GITHUB_ACTIONS":"true", "GITHUB_SHA":"a"*40}), \
                patch.object(diagnostic.environment, "fingerprint", return_value={"sdk":"36"}), \
                patch.object(diagnostic.environment, "preconditions", return_value=["pinned_api_mismatch"]), \
                patch.object(diagnostic.environment, "read", return_value="1"), \
                patch.object(diagnostic, "instrument_once") as invoke:
                self.assertEqual(1, diagnostic.run()); invoke.assert_not_called()
                result = json.loads((Path(folder)/"result.json").read_text())
                self.assertEqual(0, result["instrumentationInvocations"])
                self.assertFalse(result["acceptanceGate"])


if __name__ == "__main__": unittest.main()
