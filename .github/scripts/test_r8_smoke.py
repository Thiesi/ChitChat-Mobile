import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import r8_smoke as smoke

DUMP = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node index="0" text="" class="android.widget.FrameLayout" bounds="[0,0][320,640]">
<node index="1" text="Get started" class="android.widget.TextView" bounds="[16,560][304,608]" />
<node index="2" text="" class="android.widget.EditText" bounds="[16,200][304,256]" />
<node index="3" text="ChitChat only connects securely. Use an address that starts with https, or just the name."
 class="android.widget.TextView" bounds="[16,260][304,300]" />
</node></hierarchy>"""


class Helpers(unittest.TestCase):
    def test_center_finds_by_text_prefix_or_class(self):
        self.assertEqual((160, 584), smoke.center(DUMP, text="Get started"))
        self.assertEqual((160, 228), smoke.center(DUMP, cls="android.widget.EditText"))
        self.assertEqual((160, 280), smoke.center(DUMP, text=smoke.HTTPS_LINE))
        self.assertIsNone(smoke.center(DUMP, text="Continue"))
        self.assertIsNone(smoke.center("not xml", text="Get started"))

    def test_only_this_apps_crashes_and_log_count(self):
        other = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: com.example.other, PID: 9"
        self.assertEqual([], smoke.fatal_lines(other, ""))
        ours = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: de.networkxxiii.chitchat, PID: 7"
        self.assertEqual(2, len(smoke.fatal_lines(ours, "")))
        app = ("I chitchat: started\nW System.err: kotlinx.serialization.SerializationException: Serializer for class 'X'"
               " is not found\nI chitchat: fine")
        self.assertEqual(["W System.err: kotlinx.serialization.SerializationException: Serializer for class 'X' is not found"],
                         smoke.fatal_lines("", app))

    def test_fatal_lines_are_truncated_and_bounded(self):
        app = "\n".join("E x: NoSuchMethodError " + "y" * 400 for _ in range(50))
        lines = smoke.fatal_lines("", app)
        self.assertEqual(smoke.MAX_LINES, len(lines))
        self.assertTrue(all(len(line) == smoke.LINE_CHARS for line in lines))


class Summary(unittest.TestCase):
    def test_the_first_missing_step_and_then_the_log(self):
        full = dict(installed=True, welcome=True, stepTwo=True, httpsLine=True, alive=True, fatal=[])
        self.assertTrue(smoke.summary(full).startswith("passed"))
        self.assertEqual("FAILED at address: the HTTPS line didn't appear after Continue",
                         smoke.summary(dict(full, httpsLine=False, alive=False)))
        self.assertEqual("FAILED at log: 2 fatal line(s) in the app's log", smoke.summary(dict(full, fatal=["a", "b"])))


class Main(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        patcher = mock.patch.object(smoke, "REPORT", Path(self.temp.name) / "result.json")
        patcher.start(); self.addCleanup(patcher.stop)
        for name in ("sign", "adb", "wait_for"):
            patcher = mock.patch.object(smoke, name)
            setattr(self, name, patcher.start()); self.addCleanup(patcher.stop)
        self.sign.return_value = Path("signed.apk")
        mock.patch.object(smoke.time, "sleep").start(); self.addCleanup(mock.patch.stopall)

    def adb_results(self, pid="1234", crash="", app=""):
        def respond(*arguments, timeout=60):
            out = ""
            if arguments[:2] == ("shell", "pidof"): out = pid
            elif arguments[:3] == ("logcat", "-d", "-b"): out = crash
            elif arguments[:2] == ("logcat", "-d"): out = app
            return subprocess.CompletedProcess(arguments, 0, out)
        self.adb.side_effect = respond

    def record(self):
        return json.loads(smoke.REPORT.read_text(encoding="utf-8"))

    def test_the_whole_path_passes(self):
        self.adb_results()
        self.wait_for.return_value = (1, 2)
        self.assertEqual(0, smoke.main(["r8_smoke.py", "app.apk"]))
        record = self.record()
        self.assertTrue(record["passed"])
        self.assertEqual("summary", next(iter(record)), "The one-line summary comes first")
        self.assertTrue(record["summary"].startswith("passed"))
        typed = [call.args for call in self.adb.call_args_list if call.args[:3] == ("shell", "input", "text")]
        self.assertEqual([("shell", "input", "text", smoke.ADDRESS)], typed)
        offline = [call.args for call in self.adb.call_args_list if call.args[:2] == ("shell", "svc")]
        self.assertEqual(2, len(offline))

    def test_no_welcome_fails_and_stops_there(self):
        self.adb_results()
        self.wait_for.return_value = None
        self.assertEqual(1, smoke.main(["r8_smoke.py", "app.apk"]))
        record = self.record()
        self.assertFalse(record["welcome"]); self.assertFalse(record["passed"])
        self.assertEqual("FAILED at welcome: Welcome didn't appear", record["summary"])
        self.assertEqual(1, self.wait_for.call_count)

    def test_a_crash_or_dead_process_fails(self):
        self.adb_results(pid="", crash="E AndroidRuntime: FATAL EXCEPTION: main\nProcess: de.networkxxiii.chitchat")
        self.wait_for.return_value = (1, 2)
        self.assertEqual(1, smoke.main(["r8_smoke.py", "app.apk"]))
        record = self.record()
        self.assertFalse(record["alive"]); self.assertEqual(1, len(record["fatal"]))
        self.assertTrue(record["summary"].startswith("FAILED at process"))

    def test_a_signing_failure_is_recorded_not_hidden(self):
        self.sign.side_effect = RuntimeError("Signing step failed: apksigner")
        self.assertEqual(1, smoke.main(["r8_smoke.py", "app.apk"]))
        self.assertIn("apksigner", self.record()["error"])
        self.assertTrue(self.record()["summary"].startswith("FAILED before launch: RuntimeError"))


if __name__ == "__main__":
    unittest.main()
