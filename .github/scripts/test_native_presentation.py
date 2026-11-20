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

    def dialog_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-dialog-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_dialog_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("dialog-light-default-push-explain.png"), Path("dialog-dark-font200-rooms-leave.png")]
        self.dialog_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.DIALOG_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_dialog_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"dialog-light-default-push-explain-{n}.png") for n in range(41)],
            [Path("a/dialog-dark-default-push-none.png"), Path("b/dialog-dark-default-push-none.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.dialog_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def you_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-you-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_you_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("you-light-default-header.png"), Path("you-dark-font200-pushedbar.png")]
        self.you_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.YOU_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_you_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"you-light-default-session-{n}.png") for n in range(21)],
            [Path("a/you-dark-default-about.png"), Path("b/you-dark-default-about.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.you_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def a11y_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-a11y-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_a11y_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("a11y-light-default-signin.png"), Path("a11y-dark-font200-dialog.png")]
        self.a11y_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.A11Y_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_a11y_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"a11y-light-default-you-{n}.png") for n in range(13)],
            [Path("a/a11y-dark-default-you.png"), Path("b/a11y-dark-default-you.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.a11y_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def update_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-update-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_update_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("update-light-default-setup.png"), Path("update-dark-font200-session-actions.png")]
        self.update_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.UPDATE_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_update_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"update-light-default-setup-{n}.png") for n in range(17)],
            [Path("a/update-dark-default-setup.png"), Path("b/update-dark-default-setup.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.update_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def cleanup_files(self, contents):
        # Real temporary files (outside any report directory) so the collector's size and content checks run for real.
        import tempfile
        folder = Path(tempfile.mkdtemp(prefix="ccm-cleanup-test-"))
        self.addCleanup(lambda: [f.unlink() for f in folder.iterdir()] and None)
        files = []
        for name, text in contents:
            path = folder / name; path.write_bytes(text.encode("utf-8")); files.append(path)
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-completion-cleanup/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))
        return files

    LINE = "sample=last_wait_predicate compose=259 native1=unobserved native2=unobserved"

    def test_cleanup_samples_are_copied_and_keep_the_original_status(self):
        self.results(4)
        files = self.cleanup_files([("CompletionJourneyTest.a.txt", f"test=CompletionJourneyTest.a {self.LINE}\n")])
        self.assertEqual(4, runner.main()); self.assert_once()
        self.copy.assert_any_call(files[0], runner.CLEANUP_DESTINATION / files[0].name)

    def test_foreign_malformed_mismatched_oversized_or_excess_samples_are_rejected_before_copy(self):
        self.results(0)
        for contents in (
            [("other.png", "x")],
            [("CompletionJourneyTest.a.txt", f"test=CompletionJourneyTest.a {self.LINE} extra\n")],
            [("CompletionJourneyTest.a.txt", "Hello world\n")],
            [("CompletionJourneyTest.a.txt", f"test=CompletionJourneyTest.b {self.LINE}\n")],
            [("CompletionJourneyTest.a.txt", f"test=CompletionJourneyTest.a {self.LINE}\n" + " " * 300)],
            [(f"CompletionJourneyTest.t{n}.txt", f"test=CompletionJourneyTest.t{n} {self.LINE}\n") for n in range(65)],
        ):
            self.copy.reset_mock(); self.cleanup_files(contents)
            self.assertEqual(0, runner.main())
            self.assertFalse(any(call.args[1].parent == runner.CLEANUP_DESTINATION for call in self.copy.call_args_list))

    def test_no_cleanup_samples_is_quiet(self):
        self.results(0); self.cleanup_files([])
        self.assertEqual(0, runner.main())
        self.assertNotIn("Completion cleanup samples unavailable", self.stderr.getvalue())

    def policy_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-policy-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_policy_captures_are_copied_and_keep_the_original_status(self):
        self.results(3)
        files = [Path("policy-light-default-hosted.png"), Path("policy-dark-font200-unpublished.png")]
        self.policy_files(files)
        self.assertEqual(3, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.POLICY_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_policy_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"policy-light-default-hosted-{n}.png") for n in range(19)],
            [Path("a/policy-dark-default-hosted.png"), Path("b/policy-dark-default-hosted.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.policy_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def find_people_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-find-people-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_find_people_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("find-people-light-default-top.png"), Path("find-people-dark-font200-joined.png")]
        self.find_people_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.FIND_PEOPLE_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_find_people_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"find-people-light-default-top-{n}.png") for n in range(17)],
            [Path("a/find-people-dark-default-top.png"), Path("b/find-people-dark-default-top.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.find_people_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def recovery_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-recovery-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_recovery_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("recovery-light-default-display-api32.png"), Path("recovery-dark-font200-display.png"),
            Path("recovery-dark-default-dialog.png"), Path("recovery-light-font200-display-end.png"),
            Path("recovery-light-font200-display-api32-end.png"), Path("recovery-dark-default-notset.png"),
            Path("recovery-light-font200-notice.png")]
        self.recovery_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.RECOVERY_DESTINATION / path.name)
        self.assertEqual(8, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_recovery_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"recovery-light-default-display-{n}.png") for n in range(27)],
            [Path("a/recovery-dark-default-dialog.png"), Path("b/recovery-dark-default-dialog.png")],
            [Path("recovery-dark-default-dialog-api32.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.recovery_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def step_up_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-step-up-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_step_up_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("stepup-light-default-export.png"), Path("stepup-dark-font200-replace.png"),
            Path("stepup-light-font200-create.png"), Path("stepup-dark-default-transfer.png"),
            Path("stepup-light-default-recovery.png")]
        self.step_up_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.STEP_UP_DESTINATION / path.name)
        self.assertEqual(6, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_step_up_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"stepup-light-default-export-{n}.png") for n in range(21)],
            [Path("a/stepup-dark-default-export.png"), Path("b/stepup-dark-default-export.png")],
            [Path("stepup-dark-default-delete.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.step_up_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def deletion_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-deletion-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_deletion_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("deletion-light-default-row.png"), Path("deletion-dark-font200-explanation-actions.png"),
            Path("deletion-light-font200-scheduled-longdate.png"), Path("deletion-dark-default-checkfailed.png"),
            Path("deletion-light-default-endedrefusal.png")]
        self.deletion_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.DELETION_DESTINATION / path.name)
        self.assertEqual(6, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_deletion_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"deletion-light-default-row-{n}.png") for n in range(54)],
            [Path("a/deletion-dark-default-row.png"), Path("b/deletion-dark-default-row.png")],
            [Path("deletion-dark-default-restore.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.deletion_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def received_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-files-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_files_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("files-light-default-notloaded.png"), Path("files-dark-font200-card-long.png"),
            Path("files-light-font200-dm-out-long.png"), Path("files-light-font200-viewer-caption-open.png"),
            Path("files-dark-font200-panorama-retry.png"), Path("files-light-font200-viewer-chrome-caption-landscape-hidden.png")]
        self.received_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.FILES_DESTINATION / path.name)
        self.assertEqual(7, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_files_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"files-light-default-normal-{n}.png") for n in range(176)],
            [Path("a/files-dark-default-gone.png"), Path("b/files-dark-default-gone.png")],
            [Path("files-black-font200-normal.png")], [Path("files-dark-font200-viewer-preview.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.received_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def reset_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-reset-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_reset_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("reset-light-default-signin-forgot.png"), Path("reset-dark-font200-cancel-unknown.png"),
            Path("reset-light-font200-failed.png"), Path("reset-dark-default-stop.png")]
        self.reset_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.RESET_DESTINATION / path.name)
        self.assertEqual(5, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_reset_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"reset-light-default-form-{n}.png") for n in range(25)],
            [Path("a/reset-dark-default-mfa.png"), Path("b/reset-dark-default-mfa.png")],
            [Path("reset-dark-default-key.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.reset_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def report_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-report-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_report_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("report-light-default-form-room.png"), Path("report-light-font200-form-room-end.png"),
            Path("report-dark-default-sent-sheet-room.png"), Path("report-light-font200-busy.png")]
        self.report_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.REPORT_DESTINATION / path.name)
        self.assertEqual(5, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_report_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"report-light-default-form-room-{n}.png") for n in range(46)],
            [Path("a/report-light-default-review.png"), Path("b/report-light-default-review.png")],
            [Path("report-dark-default-review.png")], [Path("report-light-default-form-room-end.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.report_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def auth_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-auth-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_auth_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("auth-light-default-signin-keyboard.png"), Path("auth-dark-font200-create-keyboard.png"),
            Path("auth-light-font200-create-block.png")]
        self.auth_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.AUTH_DESTINATION / path.name)
        self.assertEqual(4, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_auth_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"auth-light-default-signin-keyboard-{n}.png") for n in range(11)],
            [Path("a/auth-dark-default-signin-keyboard.png"), Path("b/auth-dark-default-signin-keyboard.png")],
            [Path("auth-dark-default-create-block.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.auth_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def rules_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-rules-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_rules_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("rules-light-default-dialog.png"), Path("rules-dark-font200-dialog-expanded.png"),
            Path("rules-light-font200-banner.png"), Path("rules-dark-font200-banner-offline.png")]
        self.rules_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.RULES_DESTINATION / path.name)
        self.assertEqual(5, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_rules_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"rules-light-default-dialog-{n}.png") for n in range(13)],
            [Path("a/rules-dark-default-dialog.png"), Path("b/rules-dark-default-dialog.png")],
            [Path("rules-dark-default-banner.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.rules_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def guidelines_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-guidelines-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_guidelines_captures_are_copied_and_keep_the_original_status(self):
        self.results(5)
        files = [Path("guidelines-light-default-plain.png"), Path("guidelines-dark-font200-rules-actions.png"),
            Path("guidelines-light-font200-outdated.png"), Path("guidelines-dark-font200-create-dialog.png")]
        self.guidelines_files(files)
        self.assertEqual(5, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.GUIDELINES_DESTINATION / path.name)
        self.assertEqual(5, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_guidelines_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"guidelines-light-default-plain-{n}.png") for n in range(30)],
            [Path("a/guidelines-dark-default-plain.png"), Path("b/guidelines-dark-default-plain.png")],
            [Path("guidelines-dark-default-create-actions.png")], [Path("other.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.guidelines_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def onboarding_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-onboarding-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_onboarding_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("onboarding-light-default-welcome.png"), Path("onboarding-dark-font200-found.png")]
        self.onboarding_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.ONBOARDING_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_onboarding_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"onboarding-light-default-welcome-{n}.png") for n in range(19)],
            [Path("a/onboarding-dark-default-found.png"), Path("b/onboarding-dark-default-found.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.onboarding_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def nav_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-nav-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_nav_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("nav-light-default.png"), Path("nav-black-default.png")]
        self.nav_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.NAV_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_nav_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"nav-light-default-{n}.png") for n in range(6)],
            [Path("a/nav-dark-default.png"), Path("b/nav-dark-default.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.nav_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def transfer_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-transfer-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_transfer_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("transfer-light-default-row.png"), Path("transfer-dark-font200-success.png")]
        self.transfer_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.TRANSFER_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_transfer_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"transfer-light-default-row-{n}.png") for n in range(25)],
            [Path("a/transfer-dark-default-row.png"), Path("b/transfer-dark-default-row.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.transfer_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def search_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-search-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_search_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("search-light-default-results.png"), Path("search-dark-font200-results.png")]
        self.search_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.SEARCH_DESTINATION / path.name)
        self.assertEqual(3, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_search_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"search-light-default-results-{n}.png") for n in range(5)],
            [Path("a/search-dark-default-results.png"), Path("b/search-dark-default-results.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.search_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()

    def status_files(self, files):
        self.glob.side_effect = lambda pattern: (iter(files) if pattern == "ccm-status-presentation/*"
            else [Path("capture.png")] if pattern == "ccm-completion-presentation/*.png" else iter([]))

    def test_status_captures_are_copied_and_keep_the_original_status(self):
        self.results(7)
        files = [Path("status-light-default-offline.png"), Path("status-dark-font200-rate-unknown.png"),
            Path("status-dark-default-details.png")]
        self.status_files(files)
        self.assertEqual(7, runner.main()); self.assert_once()
        for path in files: self.copy.assert_any_call(path, runner.STATUS_DESTINATION / path.name)
        self.assertEqual(4, self.copy.call_count)

    def test_excess_ambiguous_or_foreign_status_output_is_rejected_before_copy(self):
        self.results(0)
        for files in ([Path(f"status-light-default-offline-{n}.png") for n in range(47)],
            [Path("status-light-font200-details.png")], [Path("other-app.png")]):
            self.copy.reset_mock(); self.mkdir.reset_mock(); self.status_files(files)
            self.assertEqual(0, runner.main())
            self.assertEqual(1, self.copy.call_count); self.mkdir.assert_called_once()


if __name__ == "__main__":
    unittest.main()
