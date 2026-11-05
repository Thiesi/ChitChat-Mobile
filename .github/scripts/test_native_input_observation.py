"""No device/process access: preserve separation from the ordinary native gate."""

import unittest
from unittest.mock import patch

import native_input_observation as observation
import native_journeys as gate


class ObservationTests(unittest.TestCase):
    def test_observation_cannot_modify_the_normal_gate_command_or_report(self):
        original = list(gate.GRADLE)
        report = gate.REPORT
        with patch.object(gate, "run_once", return_value=0) as run:
            self.assertEqual(0, observation.main())
        run.assert_called_once_with(observation.COMMAND, observation.REPORT)
        self.assertEqual(original, gate.GRADLE)
        self.assertEqual(report, gate.REPORT)
        self.assertNotEqual(report, observation.REPORT)
        self.assertFalse(any("Arguments.class=" in arg or "traceNativeInput" in arg for arg in gate.GRADLE))
        self.assertEqual(1, sum(arg.startswith("-Pandroid.testInstrumentationRunnerArguments.class=") for arg in observation.COMMAND))
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.class=" + observation.CONTROL, observation.COMMAND)
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.traceNativeInput=true", observation.COMMAND)
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.expectSoftKeyboard=true", observation.COMMAND)

    def test_observation_propagates_failure_once_without_retrying(self):
        for status in (7, 143):
            with self.subTest(status=status), patch.object(gate, "run_once", return_value=status) as run:
                self.assertEqual(status, observation.main())
                run.assert_called_once_with(observation.COMMAND, observation.REPORT)


if __name__ == "__main__":
    unittest.main()
