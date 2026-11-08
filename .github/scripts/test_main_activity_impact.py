"""Device-free checks of the impact collector's content and invocation boundary."""
import unittest
from unittest.mock import patch
import main_activity_impact as diagnostic


class ImpactTests(unittest.TestCase):
    def sample(self):
        return {'phase': 'geometry', **{k: 1 for k in diagnostic.INTS},
                **{k: True for k in diagnostic.BOOLS}, **{k: 48.0 for k in diagnostic.BOUNDS}}

    def test_only_scalar_allowlist_survives(self):
        self.assertEqual(self.sample(), diagnostic.safe_record(self.sample()))
        for change in ({'body': 'private-canary'}, {'windowFocused': 'true'}, {'imeBottom': '259'},
                       {'phase': 'private-canary'}, {'inputTop': float('nan')}):
            self.assertIsNone(diagnostic.safe_record({**self.sample(), **change}))

    def test_one_class_opt_in_and_strict_soft_keyboard_argument(self):
        command = diagnostic.command()
        self.assertEqual(1, command.count(':app:connectedDebugAndroidTest'))
        self.assertIn('-PmainActivityImpact=true', command)
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.expectSoftKeyboard=true', command)
        self.assertEqual(['-Pandroid.testInstrumentationRunnerArguments.class=' + diagnostic.CLASS],
                         [v for v in command if 'Arguments.class=' in v])

    def test_existing_intent_blocks_before_environment_or_instrumentation(self):
        from pathlib import Path
        import json
        with patch.object(Path, 'read_text', return_value=json.dumps({'artifacts': {}})), \
                patch.object(Path, 'exists', return_value=True), \
                patch.object(diagnostic.environment, 'main') as env, \
                patch.object(diagnostic.journeys, 'run_once') as run:
            with self.assertRaises(ValueError): diagnostic.run()
            env.assert_not_called()
            run.assert_not_called()

    def test_metadata_failure_preserves_original_failure_and_single_invocation(self):
        from pathlib import Path
        import json
        with patch.object(Path, 'read_text', return_value=json.dumps({'artifacts': {}})), \
                patch.object(Path, 'exists', return_value=False), patch.object(Path, 'write_text') as write, \
                patch.object(Path, 'rglob', return_value=[]), \
                patch.object(diagnostic.subprocess, 'check_output', return_value='head'), \
                patch.object(diagnostic.environment, 'main', return_value=0), \
                patch.object(diagnostic.journeys, 'run_once', return_value=41) as run, \
                patch.object(diagnostic, 'collect', side_effect=OSError):
            self.assertEqual(41, diagnostic.run())
            run.assert_called_once()
            self.assertEqual(1, json.loads(write.call_args_list[1].args[0])['nativeInvocations'])
            self.assertEqual(41, json.loads(write.call_args_list[2].args[0])['originalStatus'])
            self.assertEqual(41, json.loads(write.call_args.args[0])['originalStatus'])


if __name__ == '__main__': unittest.main()
