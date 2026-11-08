"""No real SDK, device, subprocess or report writes."""

from pathlib import Path
import unittest
from unittest.mock import patch

import native_environment as env


def ready():
    return dict(defaultIme=env.EXPECTED_IME, enabledImes=[env.EXPECTED_IME],
                showImeWithHardKeyboard="1", animations={"window": "0", "transition": "0.0", "animator": "0"})


class EnvironmentTests(unittest.TestCase):
    def test_preconditions_reject_each_missing_wrong_or_unknown_value(self):
        self.assertEqual([], env.preconditions(ready()))
        for key, value in (("defaultIme", None), ("defaultIme", "other/.Ime"),
                           ("enabledImes", []), ("showImeWithHardKeyboard", "0"),
                           ("animations", {"window": None}), ("animations", {"window": "1.0"})):
            data = ready(); data[key] = value
            with self.subTest(key=key, value=value):
                self.assertTrue(env.preconditions(data))

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
                fingerprint.assert_called_once_with(); write.assert_called_once()
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


if __name__ == "__main__":
    unittest.main()
