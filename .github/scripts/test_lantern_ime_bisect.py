"""Device-free checks of the bounded comparison's refusal/dispatch boundaries."""
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
import lantern_ime_bisect as diagnostic


class LanternBisectTests(unittest.TestCase):
    def test_only_original_reply_composer_class_is_selected(self):
        command = diagnostic.command()
        self.assertEqual(1, command.count(':app:connectedDebugAndroidTest'))
        self.assertEqual(['-Pandroid.testInstrumentationRunnerArguments.class=' + diagnostic.CLASS],
                         [arg for arg in command if 'Arguments.class=' in arg])
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.expectSoftKeyboard=true', command)
        self.assertFalse(any('retry' in arg.lower() or 'traceNativeInput' in arg for arg in command))

    def test_unapproved_arm_cannot_reach_source_or_device(self):
        with patch.object(diagnostic, 'verify_source') as source, patch.object(diagnostic.environment, 'main') as env:
            with self.assertRaises(ValueError): diagnostic.main('a')
            source.assert_not_called()
            env.assert_not_called()

    def test_existing_measurement_record_cannot_start_device_checks(self):
        with patch.object(diagnostic, 'verify_source', return_value=('head', {})), \
                patch.object(Path, 'mkdir'), patch.object(Path, 'exists', return_value=True), \
                patch.object(diagnostic.environment, 'main') as env:
            with self.assertRaises(ValueError): diagnostic.main('before')
            env.assert_not_called()

    def test_single_failed_invocation_keeps_its_original_exit_code(self):
        with patch.object(diagnostic, 'verify_source', return_value=('head', {})), \
                patch.object(Path, 'mkdir'), patch.object(Path, 'exists', return_value=False), \
                patch.object(Path, 'write_text') as write, \
                patch.object(diagnostic.subprocess, 'check_output', return_value='tooling'), \
                patch.object(diagnostic.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)), \
                patch.object(diagnostic.environment, 'ROOT'), patch.object(diagnostic.environment, 'REPORT'), \
                patch.object(diagnostic.journeys, 'ROOT'), \
                patch.object(diagnostic.environment, 'main', return_value=0), \
                patch.object(diagnostic.journeys, 'run_once', return_value=41) as run:
            self.assertEqual(41, diagnostic.main('current'))
            run.assert_called_once()
            self.assertIn('"nativeInvocations": 1', write.call_args_list[1].args[0])
            self.assertIn('"status": null', write.call_args_list[1].args[0])
            self.assertIn('"status": 41', write.call_args.args[0])

    def test_failed_environment_precondition_never_runs_instrumentation(self):
        with patch.object(diagnostic, 'verify_source', return_value=('head', {})), \
                patch.object(Path, 'mkdir'), patch.object(Path, 'exists', return_value=False), \
                patch.object(Path, 'write_text'), \
                patch.object(diagnostic.subprocess, 'check_output', return_value='tooling'), \
                patch.object(diagnostic.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)), \
                patch.object(diagnostic.environment, 'ROOT'), patch.object(diagnostic.environment, 'REPORT'), \
                patch.object(diagnostic.journeys, 'ROOT'), \
                patch.object(diagnostic.environment, 'main', return_value=1), \
                patch.object(diagnostic.journeys, 'run_once') as run:
            self.assertEqual(1, diagnostic.main('before'))
            run.assert_not_called()


if __name__ == '__main__': unittest.main()
