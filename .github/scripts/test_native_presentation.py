"""No real subprocess, device, credentials or report-directory writes."""

import io
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

import native_presentation as runner


class PresentationGateTests(unittest.TestCase):
    def setUp(self):
        self.run = self.enterContext(patch.object(runner.subprocess, "run"))
        self.mkdir = self.enterContext(patch.object(Path, "mkdir"))
        self.glob = self.enterContext(patch.object(Path, "rglob", return_value=[Path("capture.png")]))
        self.copy = self.enterContext(patch.object(runner.shutil, "copyfile"))
        self.stderr = self.enterContext(patch("sys.stderr", new_callable=io.StringIO))

    def results(self, status):
        self.run.return_value = subprocess.CompletedProcess(runner.NATIVE, status)

    def assert_once(self):
        self.run.assert_called_once_with(
            [sys.executable, "-B", str(runner.ROOT / ".github/scripts/native_journeys.py")],
            cwd=runner.ROOT, check=False)

    def test_success_is_copied_without_another_gate(self):
        self.results(0)
        self.assertEqual(0, runner.main()); self.assert_once()
        self.mkdir.assert_called_once_with(parents=True, exist_ok=True)
        self.copy.assert_called_once_with(Path("capture.png"), runner.DESTINATION / "capture.png")

    def test_failed_gate_keeps_exact_status_and_still_collects(self):
        self.results(7)
        self.assertEqual(7, runner.main()); self.assert_once()

    def test_copy_failure_does_not_replace_a_successful_gate(self):
        self.results(0); self.copy.side_effect = OSError("private-canary")
        self.assertEqual(0, runner.main()); self.assert_once()
        self.assertNotIn("private-canary", self.stderr.getvalue())

    def test_copy_failure_cannot_turn_a_failed_gate_green(self):
        self.results(7); self.copy.side_effect = OSError("private-canary")
        self.assertEqual(7, runner.main()); self.assert_once()

    def test_missing_captures_retain_the_failure_without_a_device_command(self):
        self.results(7); self.glob.return_value = []
        self.assertEqual(7, runner.main()); self.assert_once()
        self.copy.assert_not_called()

    def test_directory_failure_never_falls_through_to_copy_or_replay(self):
        self.run.return_value = subprocess.CompletedProcess(runner.NATIVE, 7)
        self.mkdir.side_effect = OSError("private-canary")
        self.assertEqual(7, runner.main()); self.run.assert_called_once()
        self.assertNotIn("private-canary", self.stderr.getvalue())

    def test_unexpected_capture_error_still_keeps_the_original_status(self):
        self.results(7); self.glob.side_effect = ValueError("private-canary")
        self.assertEqual(7, runner.main()); self.assert_once()
        self.assertNotIn("private-canary", self.stderr.getvalue())

    def test_native_spawn_failure_is_nonzero_without_retry(self):
        self.run.side_effect = OSError("private-canary")
        self.assertEqual(1, runner.main()); self.assert_once()
        self.assertNotIn("private-canary", self.stderr.getvalue())

    def test_native_signal_cannot_wrap_into_success(self):
        self.results(-9)
        self.assertEqual(137, runner.main()); self.assert_once()


if __name__ == "__main__":
    unittest.main()
