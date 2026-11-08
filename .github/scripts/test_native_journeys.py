"""Credential/device-free tests: no subprocess, Gradle, adb or report writes are real."""

from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import native_journeys as runner


TRACE = """10-21 05:20:00.498  4531  4531 I ImeTracker: io.github.thiesi.chitchat.debug:4cecf77: onRequestShow at ORIGIN_CLIENT reason SHOW_SOFT_INPUT_BY_INSETS_API fromUser false
10-21 05:20:05.532 712 830 I ImeTracker: system_server:abc: onCancelled at PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT
10-21 05:20:06.439 4531 4531 I TestRunner: failed: keyboardTap(io.github.thiesi.chitchat.testing.NativeInputWindowTest)
"""


class TraceTests(unittest.TestCase):
    @staticmethod
    def failed_window(name="failure"):
        return [(name + "-start", "started:", name), (name + "-progress", "onProgress", None),
                (name + "-failure", "failed:", name), (name + "-cleanup", "onHidden", None),
                (name + "-end", "finished:", name)]

    def test_failed_window_keeps_cleanup_and_drops_outside_progress_before_other_records(self):
        window = self.failed_window()
        ordinary = [(f"ordinary-{i}", "onShown", None) for i in range(1980)]
        progress = [(f"progress-{i}", "onProgress", None) for i in range(100)]
        selected = runner.selected_records(window + ordinary + progress)
        self.assertEqual(2000, len(selected))
        self.assertEqual([row[0] for row in window + ordinary], selected[:1985])
        self.assertNotIn("progress-84", selected)
        self.assertEqual("progress-99", selected[-1])

    def test_then_drops_oldest_outside_records_preserving_failure_window_and_order(self):
        window = self.failed_window()
        outside = [(f"outside-{i}", "onShown", None) for i in range(2500)]
        selected = runner.selected_records(window + outside)
        self.assertEqual([row[0] for row in window + outside[505:]], selected)

    def test_multiple_failures_and_available_unfinished_cleanup_take_priority(self):
        first = self.failed_window("first")
        outside = [(f"progress-{i}", "onProgress", None) for i in range(2500)]
        unfinished = self.failed_window("second")[:-1]
        selected = runner.selected_records(first + outside + unfinished)
        self.assertEqual(2000, len(selected))
        self.assertEqual([row[0] for row in first], selected[:5])
        self.assertEqual([row[0] for row in unfinished], selected[-4:])

    def test_failed_window_alone_over_cap_retains_newest_records_without_raising_cap(self):
        records = [("start", "started:", "failed")] + [
            (f"progress-{i}", "onProgress", None) for i in range(2100)] + [
            ("failure", "failed:", "failed"), ("cleanup", "onHidden", None), ("end", "finished:", "failed")]
        self.assertEqual([row[0] for row in records[-2000:]], runner.selected_records(records))

    def test_missing_failure_marker_retains_original_newest_records_policy(self):
        records = [(f"shown-{i}", "onShown", None) for i in range(100)] + [
            (f"progress-{i}", "onProgress", None) for i in range(2100)]
        self.assertEqual([row[0] for row in records[-2000:]], runner.selected_records(records))

    def test_progress_and_rescue_reason_are_fixed_platform_names_only(self):
        raw = ("10-21 05:20:00.498 4531 4531 I ImeTracker: system_server:abc: "
               "onProgress at PHASE_SERVER_IME_INVOKER PHASE_PRIVATE_BODY_CANARY private-body-canary\n"
               "10-21 05:20:00.499 4531 4531 I ImeTracker: system_server:def: "
               "onRequestShow ORIGIN_SERVER SHOW_INPUT_TARGET_CHANGED SHOW_PRIVATE_BODY_CANARY\n")
        self.assertEqual("10-21 05:20:00.498 ImeTracker: system_server:abc: onProgress PHASE_SERVER_IME_INVOKER\n"
                         "10-21 05:20:00.499 ImeTracker: system_server:def: onRequestShow ORIGIN_SERVER SHOW_INPUT_TARGET_CHANGED\n",
                         runner.filtered_trace(raw))

    def test_keeps_fixed_phase_reason_event_and_native_test_identity(self):
        trace = runner.filtered_trace(TRACE)
        self.assertEqual(3, len(trace.splitlines()))
        self.assertIn("onRequestShow ORIGIN_CLIENT SHOW_SOFT_INPUT_BY_INSETS_API", trace)
        self.assertIn("onCancelled PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT", trace)
        self.assertIn("failed: keyboardTap(io.github.thiesi.chitchat.testing.NativeInputWindowTest)", trace)
        self.assertNotIn("4531", trace)

    def test_excludes_other_packages_tags_exception_payloads_and_arbitrary_tails(self):
        raw = TRACE + """10-21 05:20:06.440 4531 4531 I TestRunner: private-body-canary
10-21 05:20:06.441 4531 4531 I App: private-body-canary
10-21 05:20:06.442 4531 4531 I ImeTracker: other.package:abc: onShown private-body-canary
10-21 05:20:06.443 4531 4531 I ImeTracker: io.github.thiesi.chitchat.debug:abc: private-body-canary
10-21 05:20:06.444 4531 4531 I ImeTracker: io.github.thiesi.chitchat.debug:abc: onShown private-body-canary PHASE_PRIVATE_BODY_CANARY
10-21 05:20:06.445 4531 4531 I TestRunner: started: parameter[private-body-canary](io.github.thiesi.chitchat.Test)
10-21 05:20:06.446 4531 4531 I TestRunner: started: safe(io.github.thiesi.chitchat.Test) private-body-canary
invalid private-body-canary I ImeTracker: io.github.thiesi.chitchat.debug:abc: onShown
"""
        trace = runner.filtered_trace(raw)
        self.assertNotIn("canary", trace.lower())
        self.assertNotIn("other.package", trace)
        self.assertNotIn("App:", trace)
        self.assertEqual(5, len(trace.splitlines()))

    def test_limits_filtered_records_not_raw_lines(self):
        trace = runner.filtered_trace(TRACE * 1000 + "private-body-canary\n" * 3000)
        self.assertEqual(2000, len(trace.splitlines()))
        self.assertTrue(trace.endswith(TRACE.splitlines()[-1].split("I TestRunner: ")[1] + "\n"))

    def test_empty_unknown_input_has_no_raw_fallback(self):
        self.assertEqual("", runner.filtered_trace("private-body-canary\n"))


class GateTests(unittest.TestCase):
    def setUp(self):
        self.run = self.enterContext(patch.object(runner.subprocess, "run"))
        self.mkdir = self.enterContext(patch.object(Path, "mkdir"))
        self.write = self.enterContext(patch.object(Path, "write_text"))

    def test_success_runs_once_without_capture_or_report_writes(self):
        self.run.return_value = subprocess.CompletedProcess(runner.GRADLE, 0)
        self.assertEqual(0, runner.main())
        self.run.assert_called_once_with(runner.GRADLE, cwd=runner.ROOT, check=False)
        self.mkdir.assert_not_called(); self.write.assert_not_called()

    def test_failure_keeps_original_status_and_saves_only_filtered_capture(self):
        self.run.side_effect = [subprocess.CompletedProcess(runner.GRADLE, 7),
                                subprocess.CompletedProcess(runner.LOGCAT, 0, TRACE + "private-body-canary")]
        self.assertEqual(7, runner.main())
        self.assertEqual(2, self.run.call_count)
        self.assertEqual(runner.LOGCAT, self.run.call_args.args[0])
        self.assertEqual(15, self.run.call_args.kwargs["timeout"])
        self.write.assert_called_once_with(runner.filtered_trace(TRACE), encoding="utf-8")

    def test_capture_failure_never_masks_gradle_failure(self):
        for failure in (OSError(), subprocess.TimeoutExpired(runner.LOGCAT, 15),
                        subprocess.CompletedProcess(runner.LOGCAT, 1, "private-body-canary")):
            with self.subTest(failure=type(failure).__name__):
                self.run.side_effect = [subprocess.CompletedProcess(runner.GRADLE, 9), failure]
                self.assertEqual(9, runner.main())
                self.write.assert_not_called()

    def test_report_write_failure_never_masks_gradle_failure(self):
        self.run.side_effect = [subprocess.CompletedProcess(runner.GRADLE, 4),
                                subprocess.CompletedProcess(runner.LOGCAT, 0, TRACE)]
        self.write.side_effect = OSError()
        self.assertEqual(4, runner.main())

    def test_signal_failure_stays_nonzero(self):
        self.run.side_effect = [subprocess.CompletedProcess(runner.GRADLE, -15), OSError()]
        self.assertEqual(143, runner.main())

    def test_launch_failure_stays_nonzero(self):
        self.run.side_effect = OSError()
        self.assertEqual(1, runner.main())


if __name__ == "__main__":
    unittest.main()
