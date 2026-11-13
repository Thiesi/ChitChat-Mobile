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

    def theme_files(self, files):
        self.glob.side_effect = lambda pattern: ([Path("capture.png")]
            if pattern == "ccm-completion-presentation/*.png" else iter(files))

    def test_theme_images_and_configuration_keep_the_single_failed_gate_status(self):
        self.results(7)
        files=[Path("theme-dark-font200-home.png"),Path("theme-dark-font200-configuration.json")]
        self.theme_files(files)
        self.assertEqual(7,runner.main());self.assert_once()
        self.assertEqual(3,self.copy.call_count)
        for path in files:self.copy.assert_any_call(path,runner.THEME_DESTINATION/path.name)

    def test_excess_theme_output_cannot_copy_or_replay(self):
        self.results(7)
        self.theme_files([Path(f"theme-light-default-{n}.png") for n in range(73)])
        self.assertEqual(7,runner.main());self.assert_once()
        self.assertEqual(1,self.copy.call_count)
        self.mkdir.assert_called_once()

    def test_ambiguous_or_other_app_theme_files_are_rejected_before_copy(self):
        self.results(7)
        for files in ([Path("a/theme-dark-default-home.png"),Path("b/theme-dark-default-home.png")],
            [Path("other-app.png")]):
            self.copy.reset_mock();self.mkdir.reset_mock();self.theme_files(files)
            self.assertEqual(7,runner.main())
            self.assertEqual(1,self.copy.call_count);self.mkdir.assert_called_once()
        self.assertEqual(2,self.run.call_count)

    def test_theme_collection_error_never_replaces_the_original_result_or_logs_details(self):
        self.results(7);self.theme_files([Path("theme-black-default-home.png")])
        def copy(source,destination):
            if destination.parent==runner.THEME_DESTINATION:raise OSError("private-canary")
        self.copy.side_effect=copy
        self.assertEqual(7,runner.main());self.assert_once()
        self.assertNotIn("private-canary",self.stderr.getvalue())

    def push_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-push-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_push_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("push-light-off.png"), Path("push-dark-explain.png")]
        self.push_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.PUSH_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_push_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"push-light-on-{n}.png") for n in range(17)],
            [Path("a/push-dark-on.png"), Path("b/push-dark-on.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.push_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()
        self.assertEqual(3, self.run.call_count)

    def test_push_copy_error_never_replaces_the_result_or_logs_details(self):
        self.results(7); self.push_files([Path("push-dark-refused.png")])
        def copy(source, destination):
            if destination.parent == runner.PUSH_DESTINATION: raise OSError("private-canary")
        self.copy.side_effect = copy
        self.assertEqual(7, runner.main()); self.assert_once()
        self.assertNotIn("private-canary", self.stderr.getvalue())

    def licences_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-licences-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_licences_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("licences-light-default-list.png"), Path("licences-dark-font200-detail.png")]
        self.licences_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.LICENCES_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_licences_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"licences-light-default-list-{n}.png") for n in range(9)],
            [Path("a/licences-dark-default-list.png"), Path("b/licences-dark-default-list.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.licences_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()


if __name__ == "__main__":
    unittest.main()
