"""Pure diagnostic-result validation; never starts Gradle, adb or a device."""
import json
import unittest
from types import SimpleNamespace
from unittest.mock import Mock

import ime_history_cohorts as cohort


def output(mode, codes=None, order=None):
    identities = order or cohort.expected(mode)
    codes = codes or [0] * len(identities)
    rows = []
    for (name, method), code in zip(identities, codes):
        for status in (1, code):
            rows += [f"INSTRUMENTATION_STATUS: class={name}", f"INSTRUMENTATION_STATUS: test={method}",
                     "INSTRUMENTATION_STATUS: stack=private-body-canary", f"INSTRUMENTATION_STATUS_CODE: {status}"]
    if all(code == 0 for code in codes):
        rows.append(f"OK ({len(identities)} {'test' if len(identities) == 1 else 'tests'})")
    return "\n".join(rows) + "\n"


class HistoryCohortTests(unittest.TestCase):
    def view_output(self, rounds=None):
        records = rounds if rounds is not None else [(i, True) for i in range(25)]
        return output("view-only") + "INSTRUMENTATION_RESULT: imeComparisonVIEW_ONLY=" + "\n".join(
            f"kind=VIEW_ONLY,round={number},passed={str(passed).lower()},state=private-body-canary"
            for number, passed in records) + "\nINSTRUMENTATION_CODE: -1\n"

    def test_view_only_has_one_existing_method_and_exact_fixed_round_argument(self):
        self.assertEqual([cohort.VIEW_ONLY], cohort.expected("view-only"))
        self.assertEqual(["-e", "class", "#".join(cohort.VIEW_ONLY), "-e", "expectSoftKeyboard", "true",
                          "-e", "imeComparisonRounds", "25"], cohort.instrumentation_arguments("view-only"))
        for mode in ("isolated", "after-comparison"):
            self.assertNotIn("imeComparisonRounds", cohort.instrumentation_arguments(mode))

    def test_view_only_success_requires_all_ordered_round_receipts(self):
        result = cohort.summarize(self.view_output(), "view-only", 0)
        self.assertTrue(result["nativePass"])
        self.assertEqual(25, len(result["viewOnlyRounds"]))
        self.assertNotIn("canary", json.dumps(result))

    def test_one_terminal_pass_cannot_conceal_missing_duplicate_or_failed_rounds(self):
        correct = [(i, True) for i in range(25)]
        for records in ([], correct[:-1], correct + [correct[-1]], list(reversed(correct)),
                        correct[:-1] + [(24, False)]):
            result = cohort.summarize(self.view_output(records), "view-only", 0)
            self.assertFalse(result["nativePass"])
            self.assertIn("missing_duplicate_or_failed_view_rounds", result["integrityErrors"])

    def test_view_records_in_a_stack_do_not_replace_missing_bundle_receipts(self):
        raw = self.view_output().replace("INSTRUMENTATION_RESULT: imeComparisonVIEW_ONLY=",
                                        "INSTRUMENTATION_STATUS: stack=")
        self.assertFalse(cohort.summarize(raw, "view-only", 0)["nativePass"])

    def test_duplicate_result_bundles_cannot_pass(self):
        self.assertFalse(cohort.summarize(self.view_output() * 2, "view-only", 0)["nativePass"])

    def test_failed_view_round_remains_negative_with_successful_adb_exit(self):
        raw = self.view_output([(0, True), (1, False)]).replace(
            "INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2").replace("OK (1 test)\n", "")
        result = cohort.summarize(raw, "view-only", 0)
        self.assertFalse(result["nativePass"])
        self.assertEqual(1, result["failed"])
        self.assertEqual([{"round": 0, "passed": True}, {"round": 1, "passed": False}], result["viewOnlyRounds"])

    def test_failed_cleanup_still_saves_and_retains_only_fixed_error_metadata(self):
        collector = SimpleNamespace(errors=["read_failed"],
            stop=Mock(side_effect=RuntimeError("private-body-canary")), save=Mock())
        errors = cohort.retire_collector(collector)
        self.assertEqual(["read_failed", "stop_failed"], errors)
        collector.save.assert_called_once_with()
        self.assertNotIn("canary", json.dumps(errors))

    def test_selections_are_fixed_and_unknown_plans_fail_closed(self):
        self.assertEqual("#".join(cohort.CONTROL), cohort.selector("isolated"))
        self.assertEqual(5, len(cohort.expected("after-comparison")))
        with self.assertRaisesRegex(ValueError, "^Unknown history cohort$"):
            cohort.selector("private-body-canary")

    def test_isolated_and_observed_prefix_pass_only_complete_matching_runs(self):
        for mode in ("isolated", "after-comparison"):
            result = cohort.summarize(output(mode), mode, 0)
            self.assertTrue(result["nativePass"])
            self.assertEqual(0, result["exitCode"])
            self.assertNotIn("canary", json.dumps(result))
            self.assertFalse(result["fullGateReplaced"])

    def test_adb_success_does_not_override_a_failed_native_case(self):
        result = cohort.summarize(output("after-comparison", [0, 0, 0, 0, -2]), "after-comparison", 0)
        self.assertEqual(4, result["passed"])
        self.assertEqual(1, result["failed"])
        self.assertEqual(1, result["exitCode"])
        self.assertFalse(result["nativePass"])

    def test_skipped_control_is_not_a_passing_experiment(self):
        result = cohort.summarize(output("isolated", [-3]), "isolated", 0)
        self.assertEqual(1, result["skipped"])
        self.assertFalse(result["nativePass"])

    def test_duplicate_missing_and_wrong_order_cannot_satisfy_coverage(self):
        expected = cohort.expected("after-comparison")
        for order in (expected[:-1], expected + [expected[-1]], list(reversed(expected))):
            result = cohort.summarize(output("after-comparison", order=order), "after-comparison", 0)
            self.assertTrue(result["integrityErrors"])
            self.assertFalse(result["nativePass"])

    def test_unknown_identities_are_not_serialized_or_accepted(self):
        raw = output("isolated").replace(cohort.CONTROL[0], "private-body-canary")
        result = cohort.summarize(raw, "isolated", 0)
        self.assertFalse(result["nativePass"])
        self.assertNotIn("canary", json.dumps(result))

    def test_missing_success_summary_process_failure_and_timeout_stay_failed(self):
        raw = output("isolated")
        for text, code, timeout in ((raw.replace("OK (1 test)", ""), 0, False),
                                    (raw, 1, False), (raw, None, True)):
            result = cohort.summarize(text, "isolated", code, timeout)
            self.assertFalse(result["nativePass"])
            self.assertNotEqual(0, result["exitCode"])

    def test_collection_failure_remains_distinct_and_cannot_rescue_native_failure(self):
        passing = cohort.summarize(output("isolated"), "isolated", 0, collector_errors=("read_failed",))
        self.assertTrue(passing["nativePass"])
        self.assertEqual(2, passing["exitCode"])
        negative = cohort.summarize(output("isolated", [-2]), "isolated", 0, collector_errors=("read_failed",))
        self.assertFalse(negative["nativePass"])
        self.assertEqual(1, negative["exitCode"])
