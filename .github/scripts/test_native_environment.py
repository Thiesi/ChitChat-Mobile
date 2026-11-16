"""No real SDK, device, subprocess or report writes."""

from pathlib import Path
import os
import unittest
from unittest.mock import patch

import native_environment as env


def ready():
    return dict(bootCompleted="1", currentFocus="com.google.android.apps.nexuslauncher/.NexusLauncherActivity",
                defaultIme=env.EXPECTED_IME, enabledImes=[env.EXPECTED_IME],
                emulatorBuild=env.EMULATOR_BUILD, emulatorVersion=env.EMULATOR_VERSION,
                systemImageRevision=env.IMAGE_REVISION, sdk="36",
                showImeWithHardKeyboard="1", animations={"window": "0", "transition": "0.0", "animator": "0"})


class EnvironmentTests(unittest.TestCase):
    def test_preconditions_reject_each_missing_wrong_or_unknown_value(self):
        self.assertEqual([], env.preconditions(ready()))
        for key, value in (("defaultIme", None), ("defaultIme", "other/.Ime"),
                           ("enabledImes", []), ("showImeWithHardKeyboard", "0"),
                           ("animations", {"window": None}), ("animations", {"window": "1.0"}),
                           ("emulatorBuild", None), ("emulatorVersion", "37.0.0.0"), ("systemImageRevision", "8")):
            data = ready(); data[key] = value
            with self.subTest(key=key, value=value):
                self.assertTrue(env.preconditions(data))

    def test_health_flags_each_unusable_emulator_state_and_accepts_a_focused_launcher(self):
        self.assertEqual([], env.health(ready()))
        for key, value, reason in (("bootCompleted", None, "boot_not_completed"), ("bootCompleted", "0", "boot_not_completed"),
                                   ("currentFocus", None, "no_window_focus"), ("currentFocus", "anr", "anr_dialog_has_focus"),
                                   ("currentFocus", "app_error", "error_dialog_has_focus"),
                                   ("currentFocus", "other", "launcher_not_focused"),
                                   ("currentFocus", "com.android.systemui/.Shade", "launcher_not_focused")):
            data = ready(); data[key] = value
            with self.subTest(key=key, value=value):
                self.assertEqual([reason], env.health(data))
        data = ready(); data["currentFocus"] = "com.android.launcher3/.Launcher"
        self.assertEqual([], env.health(data))

    def test_focus_keeps_only_a_component_or_a_dialog_class_never_window_text(self):
        line = lambda title: f"  mCurrentFocus=Window{{3f2a1b u0 {title}}}\n"
        self.assertEqual("com.google.android.apps.nexuslauncher/.NexusLauncherActivity",
                         env.focus_class(line("com.google.android.apps.nexuslauncher/.NexusLauncherActivity")))
        self.assertEqual("anr", env.focus_class(line("Application Not Responding: com.example.private")))
        self.assertEqual("app_error", env.focus_class(line("Application Error: com.example.private")))
        self.assertEqual("other", env.focus_class(line("Some private title with spaces")))
        self.assertIsNone(env.focus_class("mCurrentFocus=null"))
        self.assertIsNone(env.focus_class(None))

    def test_an_unhealthy_emulator_is_reported_distinctly_and_stops_before_instrumentation(self):
        for data, expected in ((dict(ready(), currentFocus="anr"), "emulator_unhealthy"),
                               (dict(ready(), defaultIme=None), "emulator_unhealthy"),
                               (dict(ready(), systemImageRevision="8"), "precondition_failed"),
                               (ready(), "ok")):
            with patch.object(env, "read", return_value=""), patch.object(env, "fingerprint", return_value=data), \
                    patch.object(Path, "mkdir"), patch.object(Path, "write_text") as write, \
                    patch("sys.stderr") as stderr:
                with self.subTest(expected=expected):
                    self.assertEqual(0 if expected == "ok" else 1, env.main())
                    self.assertIn(f'"classification": "{expected}"', write.call_args.args[0])
                    printed = "".join(str(c.args[0]) for c in stderr.write.call_args_list)
                    if expected == "emulator_unhealthy": self.assertIn("Native emulator unhealthy", printed)
                    if expected == "precondition_failed": self.assertIn("precondition failed", printed)

    def test_setup_failure_stops_without_fingerprint_or_second_command(self):
        with patch.object(env, "read", return_value=None) as read, patch.object(env, "fingerprint") as fingerprint:
            self.assertEqual(1, env.main())
            read.assert_called_once_with(["adb", "logcat", "-G", "16M"])
            fingerprint.assert_not_called()

    def test_one_fingerprint_and_report_before_any_possible_instrumentation(self):
        for wrong in (False, True):
            data = ready()
            if wrong: data["showImeWithHardKeyboard"] = None
            with patch.object(env, "read", return_value="") as read, \
                    patch.object(env, "fingerprint", return_value=data) as fingerprint, \
                    patch.object(Path, "mkdir"), patch.object(Path, "write_text") as write:
                self.assertEqual(int(wrong), env.main())
                self.assertEqual([["adb", "logcat", "-G", "16M"], ["adb", "logcat", "-c"]],
                                 [call.args[0] for call in read.call_args_list])
                fingerprint.assert_called_once_with(image_api=36); write.assert_called_once()
                self.assertIn('"failedPreconditions"', write.call_args.args[0])

    def test_fingerprint_discards_raw_config_dump_text_and_non_component_ime_lines(self):
        def read(command):
            if command[-1] == "default_input_method": return env.EXPECTED_IME
            if command[-2:] == ["list", "-s"]: return env.EXPECTED_IME + "\nprivate-body-canary\n"
            if command[-1] == "get-config": return "config: en-ABC-private-body-canary-nokeys-keyshidden-navhidden"
            if command[-2] == "package": return "private-body-canary\n versionName=16.0.0\n"
            return None
        with patch.object(env, "read", side_effect=read), patch.object(Path, "read_text", side_effect=OSError):
            data = env.fingerprint()
        self.assertEqual([env.EXPECTED_IME], data["enabledImes"])
        self.assertEqual("nokeys", data["keyboard"])
        self.assertEqual("keyshidden", data["hardKeyboardHidden"])
        self.assertEqual("16.0.0", data["imeVersionName"])
        self.assertNotIn("canary", str(data))

    def test_host_version_probe_is_headless_and_missing_setting_is_null(self):
        with patch.object(env, "read", return_value=None) as read, patch.object(Path, "read_text", side_effect=OSError):
            data = env.fingerprint()
        commands = [call.args[0] for call in read.call_args_list]
        self.assertTrue(any(command[-2:] == ["-no-window", "-version"] for command in commands))
        self.assertIsNone(data["emulatorVersion"])
        self.assertIsNone(env.scalar("null"))

    def test_api35_checks_its_revision_and_actual_device_api(self):
        data = ready(); data.update(systemImageRevision="9", sdk="35")
        self.assertEqual([], env.preconditions(data, image_revision="9", image_api=35))
        data["sdk"] = "36"
        self.assertIn("pinned_api_mismatch", env.preconditions(data, image_revision="9", image_api=35))
        with patch.dict(os.environ, {"NATIVE_API": "35"}), patch.object(env, "read", return_value=""), \
                patch.object(env, "fingerprint", return_value=data) as fingerprint, \
                patch.object(Path, "mkdir"), patch.object(Path, "write_text"):
            self.assertEqual(1, env.main())
            fingerprint.assert_called_once_with(image_api=35)

    def test_unapproved_api_stops_before_any_device_command(self):
        with patch.dict(os.environ, {"NATIVE_API": "37"}), patch.object(env, "read") as read:
            with self.assertRaises(ValueError): env.main()
            read.assert_not_called()


if __name__ == "__main__":
    unittest.main()
