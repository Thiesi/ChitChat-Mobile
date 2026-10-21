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
