"""Credential/device-free tests: no subprocess, Gradle, adb or report writes are real."""

from pathlib import Path
import subprocess
import json
import io
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import native_journeys as runner


TRACE = """10-21 05:20:00.498  4531  4531 I ImeTracker: io.github.thiesi.chitchat.debug:4cecf77: onRequestShow at ORIGIN_CLIENT reason SHOW_SOFT_INPUT_BY_INSETS_API fromUser false
10-21 05:20:05.532 712 830 I ImeTracker: system_server:abc: onCancelled at PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT
10-21 05:20:06.439 4531 4531 I TestRunner: failed: keyboardTap(io.github.thiesi.chitchat.testing.NativeInputWindowTest)
"""


class TraceTests(unittest.TestCase):
    def test_cleanup_timeout_retains_only_fixed_last_wait_scalars(self):
        raw = "11-09 19:30:00.000 123 456 W CCMCompletionIme: sample=last_wait_predicate compose=0 native1=hidden native2=visible\n"
        self.assertEqual("11-09 19:30:00.000 CCMCompletionIme: sample=last_wait_predicate compose=0 native1=hidden native2=visible\n", runner.filtered_trace(raw))
        self.assertIn("CCMCompletionIme:W", runner.LOGCAT)

    def test_cleanup_unknown_and_unobserved_are_distinct_and_tails_are_rejected(self):
        prefix = "11-09 19:30:00.000 123 456 W CCMCompletionIme: "
        good = "sample=last_wait_predicate compose=unobserved native1=unknown native2=unobserved"
        self.assertIn(good, runner.filtered_trace(prefix + good))
        for bad in [good + " private-body-canary", good.replace("unknown", "private-body-canary"),
                    good.replace("compose=unobserved", "compose=2147483648"),
                    good.replace("compose=unobserved", "compose=-2147483649"),
                    good.replace("last_wait_predicate", "post_timeout"),
                    good.replace("native1=unknown", "native2=unknown")]:
            self.assertEqual("", runner.filtered_trace(prefix + bad))

    def test_cleanup_records_share_the_existing_cap_and_failure_window_priority(self):
        prefix = "11-09 19:30:00.000 123 456 I "
        name = "presentationDarkFont200(io.github.thiesi.chitchat.feature.completion.CompletionJourneyTest)"
        scalar = "CCMCompletionIme: sample=last_wait_predicate compose=0 native1=hidden native2=visible"
        raw = prefix + "TestRunner: started: " + name + "\n" + prefix + scalar + "\n"
        raw += prefix + "TestRunner: failed: " + name + "\n" + prefix + "TestRunner: finished: " + name + "\n"
        raw += (prefix + "ImeTracker: system_server:abc: onProgress PHASE_SERVER_IME_INVOKER\n") * 2500
        retained = runner.filtered_trace(raw)
        self.assertEqual(2000, len(retained.splitlines()))
        self.assertIn(scalar, retained)

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
        self.unlink = self.enterContext(patch.object(Path, "unlink"))
        self.sources = self.enterContext(patch.object(runner, "save_failure_sources"))

    def test_success_runs_once_without_capture_or_report_writes(self):
        self.run.return_value = subprocess.CompletedProcess(runner.GRADLE, 0)
        self.assertEqual(0, runner.main())
        self.run.assert_called_once_with(runner.GRADLE, cwd=runner.ROOT, check=False)
        self.mkdir.assert_not_called(); self.write.assert_not_called()
        self.unlink.assert_called_once_with(missing_ok=True)
        self.sources.assert_not_called()

    def test_stale_source_cleanup_failure_does_not_prevent_or_rewrite_the_gate(self):
        self.unlink.side_effect = OSError("private-body-canary")
        self.run.return_value = subprocess.CompletedProcess(runner.GRADLE, 0)
        with patch("sys.stderr", new_callable=io.StringIO) as stderr:
            self.assertEqual(0, runner.main())
        self.run.assert_called_once_with(runner.GRADLE, cwd=runner.ROOT, check=False)
        self.sources.assert_not_called()
        self.assertIn("verify invocation timestamps", stderr.getvalue())
        self.assertNotIn("canary", stderr.getvalue())

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

    def test_source_metadata_failure_cannot_replace_original_status(self):
        self.run.side_effect = [subprocess.CompletedProcess(runner.GRADLE, 41), OSError()]
        self.sources.side_effect = RuntimeError("private-body-canary")
        self.assertEqual(41, runner.main())
        self.sources.assert_called_once()


class FailureSourceTests(unittest.TestCase):
    CLASS = "io.github.thiesi.chitchat.feature.completion.CompletionJourneyTest"

    def page(self, stack, cls=CLASS, test="visibleComponentKeysSelectAndDismissWithoutSending"):
        import xml.etree.ElementTree as ET
        suite = ET.Element("testsuite")
        case = ET.SubElement(suite, "testcase", classname=cls, name=test)
        ET.SubElement(case, "failure").text = stack
        return ET.tostring(suite)

    def test_retains_cleanup_frame_behind_assert_fail_without_any_error_payload(self):
        stack = ("java.lang.AssertionError: expected:false actual:true private-body-canary\n"
            "\tat org.junit.Assert.fail(Assert.java:89)\n"
            f"\tat {self.CLASS}.close(CompletionJourneyTest.kt:167)\n")
        summary = runner.failure_sources([self.page(stack)])
        self.assertEqual([{"file": "CompletionJourneyTest.kt", "line": 167}], summary["cases"][0]["frames"])
        self.assertNotIn("canary", json.dumps(summary))
        self.assertNotIn("Assert.java", json.dumps(summary))

    def test_wrong_namespace_filename_or_parameter_content_is_never_serialized(self):
        stack = (f"\tat {self.CLASS}.privateCanary(privateCanary.kt:7)\n"
                 "\tat other.package.CompletionJourneyTest.close(CompletionJourneyTest.kt:167)\n")
        summary = runner.failure_sources([self.page(stack), self.page(stack, test="parameter[privateCanary]")])
        self.assertEqual([], summary["cases"][0]["frames"])
        self.assertEqual(1, summary["omittedCases"])
        self.assertNotIn("privateCanary", json.dumps(summary))

    def test_source_frames_are_deduplicated_bounded_and_truncation_is_explicit(self):
        stack = "\n".join(f"\tat {self.CLASS}.close(CompletionJourneyTest.kt:{line})" for line in (1,1,2,3,4,5))
        case = runner.failure_sources([self.page(stack)])["cases"][0]
        self.assertEqual([1,2,3,4], [frame["line"] for frame in case["frames"]])
        self.assertTrue(case["framesTruncated"])

    def test_malformed_entities_and_excess_input_fail_closed(self):
        with self.assertRaises(ValueError): runner.failure_sources([b"<!DOCTYPE testsuite><testsuite/>"])
        with patch.object(runner, "MAX_SOURCE_BYTES", 2):
            with self.assertRaises(ValueError): runner.failure_sources([b"<testsuite/>"])

    def test_old_xml_is_not_read_after_a_new_launch_failure(self):
        from unittest.mock import Mock
        path = Mock()
        path.is_symlink.return_value = False
        path.is_file.return_value = True
        path.stat.return_value = SimpleNamespace(st_mtime_ns=9, st_size=1)
        with patch.object(Path, "rglob", return_value=[path]):
            with self.assertRaises(ValueError): runner.save_failure_sources(10, 20)
        path.open.assert_not_called()

    def test_fresh_cleanup_location_is_saved_and_printed_without_payload(self):
        from unittest.mock import Mock
        page = self.page(f"java.lang.AssertionError: private-body-canary\n\tat {self.CLASS}.close(CompletionJourneyTest.kt:167)\n")
        path = Mock()
        path.is_symlink.return_value = False
        path.is_file.return_value = True
        path.stat.return_value = SimpleNamespace(st_mtime_ns=15, st_size=len(page))
        path.open.return_value = io.BytesIO(page)
        with patch.object(Path, "rglob", return_value=[path]), patch.object(Path, "mkdir"), \
                patch.object(Path, "write_text") as write, patch("builtins.print") as emit:
            runner.save_failure_sources(10, 20)
            saved = write.call_args.args[0]
            self.assertNotIn("canary", saved)
            self.assertEqual(167, json.loads(saved)["cases"][0]["frames"][0]["line"])
            self.assertEqual(10, json.loads(saved)["invocationStartedUnixNs"])
            self.assertEqual(20, json.loads(saved)["invocationFinishedUnixNs"])
            self.assertIn("CompletionJourneyTest.kt:167", emit.call_args.args[0])

    def test_file_count_is_bounded_before_reading_any_input(self):
        from unittest.mock import Mock
        path = Mock()
        with patch.object(Path, "rglob", return_value=iter([path] * 65)):
            with self.assertRaises(ValueError): runner.save_failure_sources(10, 20)
        path.stat.assert_not_called()


if __name__ == "__main__":
    unittest.main()
