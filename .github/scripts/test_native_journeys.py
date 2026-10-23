"""Credential/device-free tests: no subprocess, Gradle, adb or report writes are real."""

from pathlib import Path
import io
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
        self.collector_type = self.enterContext(patch.object(runner, "TraceCollector"))
        self.collector = self.collector_type.return_value

    def test_success_runs_once_and_preserves_bounded_collector_evidence(self):
        self.run.return_value = subprocess.CompletedProcess(runner.GRADLE, 0)
        self.assertEqual(0, runner.main())
        self.run.assert_called_once_with(runner.GRADLE, cwd=runner.ROOT, check=False)
        self.collector.start.assert_called_once_with()
        self.collector.stop.assert_called_once_with()
        self.collector.save.assert_called_once_with()

    def test_failure_keeps_original_status_without_a_post_suite_logcat_dump(self):
        self.run.return_value = subprocess.CompletedProcess(runner.GRADLE, 7)
        self.assertEqual(7, runner.main())
        self.run.assert_called_once_with(runner.GRADLE, cwd=runner.ROOT, check=False)
        self.collector.stop.assert_called_once_with()
        self.collector.save.assert_called_once_with()

    def test_collector_start_cleanup_and_save_failures_never_mask_gradle_failure(self):
        for phase in ('start', 'stop', 'save'):
            with self.subTest(phase=phase):
                self.collector.reset_mock(side_effect=True)
                getattr(self.collector, phase).side_effect = RuntimeError('private-body-canary')
                self.run.return_value = subprocess.CompletedProcess(runner.GRADLE, 9)
                self.assertEqual(9, runner.main())

    def test_signal_failure_stays_nonzero(self):
        self.run.return_value = subprocess.CompletedProcess(runner.GRADLE, -15)
        self.assertEqual(143, runner.main())

    def test_launch_failure_stays_nonzero(self):
        self.run.side_effect = OSError()
        self.assertEqual(1, runner.main())

    def test_gradle_exception_still_stops_and_saves_collector(self):
        self.run.side_effect = KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            runner.main()
        self.collector.stop.assert_called_once_with()
        self.collector.save.assert_called_once_with()


class WindowTests(unittest.TestCase):
    def test_first_failure_context_survives_thousands_of_later_safe_records(self):
        window = runner.TraceWindow()
        safe = runner.filtered_trace(TRACE).splitlines(keepends=True)
        for line in safe:
            window.accept(line)
        for _ in range(5000):
            window.accept(safe[0])
        self.assertTrue(window.text().startswith(''.join(safe)))
        self.assertEqual(3 + runner.AFTER_FAILURE, len(window.text().splitlines()))
        self.assertEqual(5003, window.seen)

    def test_prefailure_history_is_bounded_and_second_failure_cannot_replace_first(self):
        window = runner.TraceWindow()
        show, _, failure = runner.filtered_trace(TRACE).splitlines(keepends=True)
        for _ in range(5000):
            window.accept(show)
        self.assertEqual(runner.BEFORE_FAILURE, len(window.recent))
        window.accept(failure)
        frozen = window.text()
        for _ in range(runner.AFTER_FAILURE + 1):
            window.accept(show)
        window.accept(failure.replace('keyboardTap', 'laterFailure'))
        self.assertTrue(window.text().startswith(frozen))
        self.assertNotIn('laterFailure', window.text())
        self.assertEqual(runner.BEFORE_FAILURE + runner.AFTER_FAILURE, len(window.text().splitlines()))


class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.clock = self.enterContext(patch.object(runner.subprocess, 'run'))
        self.clock.return_value = subprocess.CompletedProcess(runner.DEVICE_TIME, 0, '2026-10-23T01:00:00.123\n')
        self.popen = self.enterContext(patch.object(runner.subprocess, 'Popen'))
        self.process = self.popen.return_value
        self.process.stdout = io.BytesIO(TRACE.encode())
        self.process.poll.return_value = None
        self.collector = runner.TraceCollector()

    def test_device_clock_bounds_stream_and_only_allowlisted_content_is_retained(self):
        self.process.stdout = io.BytesIO((TRACE + 'private-body-canary\n').encode())
        self.collector.start()
        self.collector.thread.join(timeout=2)
        self.collector.stop()
        self.clock.assert_called_once()
        self.assertEqual(runner.DEVICE_TIME, self.clock.call_args.args[0])
        self.assertEqual(runner.LOGCAT + ['-T', '2026-10-23 01:00:00.123'], self.popen.call_args.args[0])
        self.assertEqual(subprocess.DEVNULL, self.popen.call_args.kwargs['stderr'])
        self.assertEqual(runner.filtered_trace(TRACE), self.collector.window.text())
        self.assertTrue(self.process.stdout.closed)
        self.process.terminate.assert_called_once()
        self.assertIn('stream_ended_early', self.collector.errors)

    def test_overlong_line_and_its_suffix_are_discarded_before_filtering(self):
        tail = TRACE.splitlines(keepends=True)[0]
        self.process.stdout = io.BytesIO(('x' * runner.MAX_LINE + tail + tail).encode())
        self.collector.start()
        self.collector.thread.join(timeout=2)
        self.collector.stop()
        self.assertEqual(1, self.collector.long_lines)
        self.assertEqual(runner.filtered_trace(tail), self.collector.window.text())

    def test_invalid_or_failed_clock_never_uses_raw_or_stale_fallback(self):
        for reply in (subprocess.CompletedProcess(runner.DEVICE_TIME, 0, 'private-body-canary'),
                      subprocess.CompletedProcess(runner.DEVICE_TIME, 1, ''), OSError(),
                      subprocess.TimeoutExpired(runner.DEVICE_TIME, 5)):
            with self.subTest(reply=type(reply).__name__):
                collector = runner.TraceCollector()
                self.clock.side_effect = [reply]
                collector.start(); collector.stop()
                self.assertEqual(['start_failed'], collector.errors)
                self.popen.assert_not_called()

    def test_termination_timeout_kills_only_owned_collector_and_stops_reader(self):
        self.collector.process = self.process
        self.process.wait.side_effect = [subprocess.TimeoutExpired('adb', 3), 0]
        self.collector.stop()
        self.process.terminate.assert_called_once()
        self.process.kill.assert_called_once()
        self.assertEqual(['terminate_failed'], self.collector.errors)

    def test_stream_launch_failure_is_fixed_metadata_not_raw_exception(self):
        self.popen.side_effect = OSError('private-body-canary')
        self.collector.start(); self.collector.stop()
        self.assertEqual(['start_failed'], self.collector.errors)
        self.assertEqual('', self.collector.window.text())

    def test_reader_failure_is_fixed_metadata_and_owned_pipe_is_closed(self):
        with patch.object(self.process.stdout, 'readline', side_effect=OSError('private-body-canary')):
            self.collector.process = self.process
            self.collector._read()
        self.collector.stop()
        self.assertEqual(['read_failed'], self.collector.errors)
        self.assertTrue(self.process.stdout.closed)

    def test_reports_only_safe_records_fixed_counters_and_fixed_errors(self):
        self.collector.window.accept(runner.filtered_trace(TRACE.splitlines()[0]))
        self.collector.errors.append('start_failed')
        with patch.object(Path, 'mkdir'), patch.object(Path, 'write_text') as write:
            self.collector.save()
        self.assertEqual(2, write.call_count)
        combined = ''.join(call.args[0] for call in write.call_args_list)
        self.assertNotIn('private', combined)
        self.assertIn('first_failure_observed=False', combined)
        self.assertIn('safe_records_seen=1', combined)
        self.assertIn('errors=start_failed', combined)


if __name__ == "__main__":
    unittest.main()
