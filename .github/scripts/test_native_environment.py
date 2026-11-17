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


class Device:
    """A fake emulator whose logcat ring behaves as on the CI images: resizing it (-G) discards the buffer."""

    def __init__(self, buffer: str, focus: str = "com.google.android.apps.nexuslauncher/.NexusLauncherActivity"):
        self.buffer, self.focus, self.commands = buffer, focus, []

    def read(self, command):
        self.commands.append(command)
        if command[:3] == ["adb", "logcat", "-G"] or command[:3] == ["adb", "logcat", "-c"]:
            self.buffer = ""; return ""
        if command[:3] == ["adb", "logcat", "-d"]: return self.buffer
        if command[:4] == ["adb", "shell", "dumpsys", "window"]:
            return f"  mCurrentFocus=Window{{a1b2 u0 {self.focus}}}"
        return ""


class EnvironmentTests(unittest.TestCase):
    def setUp(self):
        # A fake clock: the fixed settle and evidence delays cost no wall time and their durations are asserted. Tests
        # that don't pass one still never wait for real (the guard below records them too).
        self.sleeps = []
        guard = patch.object(env.time, "sleep", side_effect=self.sleeps.append)
        guard.start(); self.addCleanup(guard.stop)

    def test_an_unhealthy_verdict_keeps_the_boot_dump_although_the_resize_discards_the_ring(self):
        # Run 44000691076: the dump came after -G, which discards the buffer, so the file was empty (#245).
        import contextlib, io
        device = Device("11-17 02:09:14.000 I/ActivityManager( 1): Start proc 905:com.android.systemui\n"
                        "11-17 02:09:15.000 I/ActivityTaskManager( 1): Displayed com.android.settings/.FallbackHome")
        sink = io.StringIO()
        with patch.object(env, "read", side_effect=device.read), \
                patch.object(env, "fingerprint", return_value=dict(ready(), currentFocus=None)), patch.object(Path, "mkdir"), \
                patch.object(Path, "write_text") as write, patch.object(Path, "open", return_value=contextlib.nullcontext(sink)):
            self.assertEqual(1, env.main(sleep=self.sleeps.append))
        self.assertIn('"classification": "emulator_unhealthy"', write.call_args.args[0])
        self.assertIn("Start proc 905:com.android.systemui", sink.getvalue())
        self.assertIn("Displayed com.android.settings/.FallbackHome", sink.getvalue())
        commands = device.commands
        dump = commands.index(["adb", "logcat", "-d", "-v", "time"])
        self.assertLess(dump, commands.index(["adb", "logcat", "-G", "16M"]))
        self.assertLess(dump, commands.index(["adb", "logcat", "-c"]))

    def test_the_boot_window_keeps_early_markers_through_verbose_boot_noise(self):
        # Boot logs verbosely: capping raw lines before the allowlist kept only the last half second (PR #291).
        import contextlib, io
        noise = [f"11-17 04:09:2{i % 10}.000 V/Chatty( 3): verbose noise {i}" for i in range(3000)]
        early = "11-17 04:09:00.100 I/ActivityManager( 1): Start proc 512:com.android.systemui"
        late = "11-17 04:09:23.352 I/ActivityManager( 1): Start proc 3098:com.google.android.apps.messaging"
        device = Device("\n".join([early] + noise + [late]))
        sink = io.StringIO()
        with patch.object(env, "read", side_effect=device.read), patch.object(env, "fingerprint", return_value=ready()), \
                patch.object(Path, "mkdir"), patch.object(Path, "write_text"), \
                patch.object(Path, "open", return_value=contextlib.nullcontext(sink)):
            self.assertEqual(0, env.main(sleep=self.sleeps.append))
        self.assertIn("Start proc 512:com.android.systemui", sink.getvalue())
        self.assertIn("Start proc 3098:com.google.android.apps.messaging", sink.getvalue())
        self.assertNotIn("verbose noise", sink.getvalue())

    def test_the_single_read_follows_one_fixed_settle_and_is_never_repeated(self):
        device = Device("")
        with patch.object(env, "read", side_effect=device.read), \
                patch.object(env, "fingerprint", return_value=ready()) as fingerprint, \
                patch.object(Path, "mkdir"), patch.object(Path, "write_text"):
            self.assertEqual(0, env.main(sleep=self.sleeps.append))
        self.assertEqual([env.SETTLE_SECONDS], self.sleeps)
        self.assertEqual(15, env.SETTLE_SECONDS)
        fingerprint.assert_called_once()
        self.assertNotIn(["adb", "shell", "dumpsys", "window"], device.commands)  # No evidence samples when healthy.

    def test_an_unhealthy_verdict_samples_focus_for_a_fixed_window_without_changing_the_outcome(self):
        import contextlib, io
        device = Device("", focus="com.google.android.apps.nexuslauncher/.NexusLauncherActivity")
        sink = io.StringIO()
        with patch.object(env, "read", side_effect=device.read), \
                patch.object(env, "fingerprint", return_value=dict(ready(), currentFocus=None)) as fingerprint, \
                patch.object(Path, "mkdir"), patch.object(Path, "write_text"), \
                patch.object(Path, "open", return_value=contextlib.nullcontext(sink)):
            self.assertEqual(1, env.main(sleep=self.sleeps.append))  # A launcher arriving later never rescues the verdict.
        fingerprint.assert_called_once()
        samples = [line for line in sink.getvalue().splitlines() if line.startswith("focus ")]
        self.assertEqual([f"focus com.google.android.apps.nexuslauncher/.NexusLauncherActivity at=+{s}s after verdict"
                          for s in range(0, 11, 2)], samples)
        self.assertEqual([env.SETTLE_SECONDS] + [env.EVIDENCE_EVERY_SECONDS] * 5, self.sleeps)

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
        # The interim boot home is healthy; any other Settings window still isn't.
        data = ready(); data["currentFocus"] = "com.android.settings/com.android.settings.FallbackHome"
        self.assertEqual([], env.health(data))
        data = ready(); data["currentFocus"] = "com.android.settings/com.android.settings.Settings"
        self.assertEqual(["launcher_not_focused"], env.health(data))

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

    def test_the_boot_window_is_one_allowlisted_dump_before_the_clear_and_never_fails_setup(self):
        dump = "11-16 18:18:24.140 I/ActivityManager( 1): Start proc 905:com.android.systemui\n11-16 18:18:25.000 I/Chat( 2): private text"
        sink = __import__("io").StringIO()
        with patch.object(env, "read", side_effect=lambda c: dump if c[:3] == ["adb", "logcat", "-d"] else "") as read, \
                patch.object(env, "fingerprint", return_value=ready()), patch.object(Path, "mkdir"), \
                patch.object(Path, "write_text"), patch.object(Path, "open", return_value=__import__("contextlib").nullcontext(sink)):
            self.assertEqual(0, env.main())
        calls = [call.args[0] for call in read.call_args_list]
        self.assertLess(calls.index(["adb", "logcat", "-d", "-v", "time"]), calls.index(["adb", "logcat", "-G", "16M"]))
        self.assertLess(calls.index(["adb", "logcat", "-d", "-v", "time"]), calls.index(["adb", "logcat", "-c"]))
        self.assertIn("Start proc 905:com.android.systemui", sink.getvalue()); self.assertNotIn("private", sink.getvalue())
        with patch.object(env, "read", side_effect=lambda c: None if c[:3] == ["adb", "logcat", "-d"] else ""), \
                patch.object(env, "fingerprint", return_value=ready()), patch.object(Path, "mkdir"), patch.object(Path, "write_text"):
            self.assertEqual(0, env.main())  # A missing dump is only missing evidence.

    def test_the_boot_window_is_written_before_an_unhealthy_verdict_ends_the_job(self):
        import contextlib, io
        dump = "11-16 18:18:24.140 E/ActivityManager( 1): ANR in com.android.systemui"
        sink = io.StringIO()
        with patch.object(env, "read", side_effect=lambda c: dump if c[:3] == ["adb", "logcat", "-d"] else ""), \
                patch.object(env, "fingerprint", return_value=dict(ready(), currentFocus="anr")), patch.object(Path, "mkdir"), \
                patch.object(Path, "write_text") as write, patch.object(Path, "open", return_value=contextlib.nullcontext(sink)):
            self.assertEqual(1, env.main())
        self.assertIn('"classification": "emulator_unhealthy"', write.call_args.args[0])
        self.assertIn("ANR in com.android.systemui", sink.getvalue())

    def test_setup_failure_stops_without_fingerprint_or_second_command(self):
        with patch.object(env, "read", return_value=None) as read, patch.object(env, "fingerprint") as fingerprint:
            self.assertEqual(1, env.main())
            # The evidence-only boot dump, then the failed resize; nothing after it.
            self.assertEqual([["adb", "logcat", "-d", "-v", "time"], ["adb", "logcat", "-G", "16M"]],
                             [call.args[0] for call in read.call_args_list])
            fingerprint.assert_not_called()

    def test_one_fingerprint_and_report_before_any_possible_instrumentation(self):
        for wrong in (False, True):
            data = ready()
            if wrong: data["showImeWithHardKeyboard"] = None
            with patch.object(env, "read", return_value="") as read, \
                    patch.object(env, "fingerprint", return_value=data) as fingerprint, \
                    patch.object(Path, "mkdir"), patch.object(Path, "write_text") as write:
                self.assertEqual(int(wrong), env.main())
                # The boot dump first: the resize discards the ring (#245).
                self.assertEqual([["adb", "logcat", "-d", "-v", "time"], ["adb", "logcat", "-G", "16M"], ["adb", "logcat", "-c"]],
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
