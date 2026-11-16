"""No real device, adb or file writes."""

import io
import unittest

import early_logcat as early


class EarlyLogcatTests(unittest.TestCase):
    def test_only_allowlisted_markers_are_kept_and_truncated(self):
        kept = early.keep("11-16 11:04:33.755 I/TestRunner( 123): started: storageRetry(io.example.FoundationJourneyTest)")
        self.assertEqual("11-16 11:04:33.755 TestRunner: started: storageRetry(io.example.FoundationJourneyTest)", kept)
        self.assertIn("ANR in com.example.app", early.keep("11-16 11:04:30.000 E/ActivityManager( 99): ANR in com.example.app (reason text here)"))
        for dropped in ("11-16 11:04:33.755 I/Chat( 1): private message body", "D/OkHttp( 2): Cookie: secret",
                        "I/TestRunner( 3): run started: 426 tests", "random"):
            with self.subTest(dropped=dropped):
                self.assertIsNone(early.keep(dropped))
        self.assertLessEqual(len(early.keep("11-16 11:04:33.755 Displayed " + "a" * 400)), early.LINE_CHARS)

    def test_the_window_stops_after_the_first_few_tests_or_its_line_cap(self):
        sink = io.StringIO(); window = early.Window(sink, clock=lambda: 0)
        for n in range(early.MAX_TESTS + 3):
            window.logcat(f"11-16 11:04:{n:02d}.000 I/TestRunner( 1): started: t{n}(io.example.T)")
        self.assertEqual(early.MAX_TESTS, len(sink.getvalue().splitlines()))  # The first five test starts.
        self.assertTrue(window.done())
        capped = io.StringIO(); bounded = early.Window(capped, clock=lambda: 0)
        for n in range(early.MAX_LINES + 50): bounded.logcat("11-16 11:04:00.000 Displayed com.example/.A")
        self.assertEqual(early.MAX_LINES, len(capped.getvalue().splitlines()))

    def test_focus_samples_hold_only_a_component_or_dialog_class(self):
        sink = io.StringIO(); window = early.Window(sink, clock=lambda: 0)
        window.focus("  mCurrentFocus=Window{3f2a1b u0 Application Not Responding: com.example.private}")
        window.focus("  mCurrentFocus=Window{3f2a1b u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}")
        window.focus(None)
        self.assertEqual(["focus anr at=0s", "focus com.google.android.apps.nexuslauncher/.NexusLauncherActivity at=0s",
                          "focus none at=0s"], sink.getvalue().splitlines())
        self.assertNotIn("private", sink.getvalue())


if __name__ == "__main__":
    unittest.main()
