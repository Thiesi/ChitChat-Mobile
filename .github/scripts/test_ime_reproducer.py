"""Credential/device-free validation of standalone measurement records and dispatch boundaries."""
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import ime_reproducer as runner
import native_environment
import native_journeys
from pin_reproducer_image import ARMS


def cycle(number):
    return dict(kind="cycle", cycle=number, startedUptimeMillis=1, finishedUptimeMillis=2,
                tapAttempted=True, focusImeProved=True, cleanupComplete=True, deadlineMillis=5000, failureClass=None)


def output(failed=None):
    lines = []
    for number in range(1, 51):
        record = cycle(number)
        if number == failed:
            record.update(focusImeProved=False, failureClass="ComposeTimeoutException")
        lines += ["INSTRUMENTATION_STATUS: numtests=50",
                  "INSTRUMENTATION_STATUS: class=" + runner.CLASS,
                  f"INSTRUMENTATION_STATUS: test=cycle{number:02}",
                  "INSTRUMENTATION_STATUS_CODE: 1",
                  "INSTRUMENTATION_STATUS: imeReproducer=" + json.dumps(record),
                  "INSTRUMENTATION_STATUS_CODE: 0",
                  "INSTRUMENTATION_STATUS: class=" + runner.CLASS,
                  f"INSTRUMENTATION_STATUS: test=cycle{number:02}",
                  "INSTRUMENTATION_STATUS_CODE: " + ("-2" if number == failed else "0")]
    lines += ["FAILURES!!!" if failed else "OK (50 tests)", "INSTRUMENTATION_CODE: -1"]
    return ("\n".join(lines) + "\n").encode()


class ReproducerRecordsTests(unittest.TestCase):
    def test_complete_pass_requires_exactly_fifty_independent_finishes_and_proofs(self):
        result = runner.summarize(output(), 0)
        self.assertTrue(result["measurementComplete"])
        self.assertTrue(result["originalTestPass"])
        self.assertEqual(50, result["focusImePasses"])
        self.assertFalse(result["acceptanceGate"])

    def test_failed_cycle_stays_failed_even_when_transport_succeeds(self):
        result = runner.summarize(output(7), 0)
        self.assertTrue(result["measurementComplete"])
        self.assertFalse(result["originalTestPass"])
        self.assertEqual(49, result["focusImePasses"])
        self.assertEqual(-2, result["finishedCases"][6]["status"])

    def test_incomplete_duplicate_overflow_or_wrong_deadline_never_claims_complete(self):
        original = output()
        variants = [original.replace(b"test=cycle50", b"test=cycle49"),
                    original.replace(b'"cycle": 50', b'"cycle": 49'),
                    original.replace(b'"deadlineMillis": 5000', b'"deadlineMillis": 10000'),
                    original.replace(b"numtests=50", b"numtests=49"),
                    original.replace(b"INSTRUMENTATION_CODE: -1", b"INSTRUMENTATION_CODE: 0")]
        for value in variants:
            with self.subTest(value=value[-100:]):
                self.assertFalse(runner.summarize(value, 0)["measurementComplete"])
        self.assertFalse(runner.summarize(original, 124)["measurementComplete"])
        self.assertFalse(runner.summarize(original, 0, True)["measurementComplete"])

    def test_failure_records_retain_only_fixed_scalars_flags_and_frame_names(self):
        record = dict(kind="failure", cycle=1, stage="FOCUS_IME", failureClass="AssertionError",
            detectedUptimeMillis=5, body="private-body-canary",
            beforeTap=dict(windowFocused=True, title="private-body-canary"),
            imeServer=dict(inputMethod=dict(flags=["mInputShown=true", "private-body-canary"])),
            imeMainThread=dict(available=True, incomplete=False,
                frames=["android.os.MessageQueue.nativePollOnce"]))
        safe = runner.sanitize(record)
        self.assertNotIn("canary", json.dumps(safe))
        self.assertIsNone(safe["imeServer"]["inputMethod"]["flags"])
        self.assertEqual(["android.os.MessageQueue.nativePollOnce"], safe["imeMainThread"]["frames"])
        record["imeMainThread"]["frames"] = ["android.os.Looper.loop(Private.java:1)"]
        self.assertFalse(runner.sanitize(record)["imeMainThread"]["available"])
        record["imeMainThread"].update(frames=["android.os.Looper.loop"], incomplete=True)
        self.assertIsNone(runner.sanitize(record)["imeMainThread"]["frames"])
        self.assertIsNone(runner.sanitize(dict(kind="cycle", cycle=True)))

    def test_standalone_identity_does_not_expand_the_ordinary_collector(self):
        raw = ("10-21 05:20:00.498 4531 4531 I ImeTracker: " + runner.APP +
               ":abc: onProgress PHASE_SERVER_IME_INVOKER private-body-canary\n")
        self.assertEqual("", native_journeys.filtered_trace(raw))
        filtered = native_journeys.filtered_trace(raw, token_pattern=runner.TOKEN)
        self.assertIn(runner.APP, filtered)
        self.assertNotIn("canary", filtered)
        self.assertEqual(2000, len(native_journeys.filtered_trace(raw * 3000, token_pattern=runner.TOKEN).splitlines()))

    def test_first_attempt_only_before_any_mutation(self):
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_RUN_ATTEMPT": "2"}), \
             patch.object(runner.subprocess, "run") as run, patch.object(runner.Path, "mkdir") as mkdir:
            with self.assertRaises(RuntimeError):
                runner.run("a")
            run.assert_not_called()
            mkdir.assert_not_called()

    def test_one_instrumentation_call_has_only_the_standalone_class_and_strict_arguments(self):
        process = unittest.mock.Mock()
        process.stdout.read.side_effect = [output(), b""]
        process.wait.return_value = 0
        with patch.object(runner.subprocess, "Popen", return_value=process) as launch:
            result = runner.instrument_once()
        self.assertTrue(result["originalTestPass"])
        command = launch.call_args.args[0]
        self.assertEqual(1, command.count("instrument"))
        self.assertEqual(runner.CLASS, command[command.index("class") + 1])
        self.assertIn("expectSoftKeyboard", command)
        self.assertNotIn("traceNativeInput", command)
        process.wait.assert_called_once_with(timeout=1200)
        process.kill.assert_not_called()

    def test_timeout_is_retained_without_a_second_instrumentation(self):
        process = unittest.mock.Mock()
        process.stdout.read.side_effect = [b"", b""]
        process.wait.side_effect = [subprocess.TimeoutExpired("fixture", 1200), 0]
        with patch.object(runner.subprocess, "Popen", return_value=process) as launch:
            result = runner.instrument_once()
        self.assertFalse(result["originalTestPass"])
        self.assertEqual(124, result["transportExitCode"])
        self.assertEqual(1, launch.call_count)
        process.kill.assert_called_once()

    def test_arms_keep_baseline_resources_and_change_only_declared_dimensions(self):
        self.assertEqual(dict(api=36, revision="7", cores=2, ram=None), ARMS["a"])
        self.assertEqual(dict(api=35, revision="9", cores=2, ram=None), ARMS["b"])
        self.assertEqual(dict(api=36, revision="7", cores=4, ram="4096M"), ARMS["c"])
        data = dict(defaultIme=native_environment.EXPECTED_IME, enabledImes=[native_environment.EXPECTED_IME],
                    emulatorBuild=native_environment.EMULATOR_BUILD, emulatorVersion=native_environment.EMULATOR_VERSION,
                    systemImageRevision="9", showImeWithHardKeyboard="1", animations={"window": "0"})
        self.assertEqual([], native_environment.preconditions(data, image_revision="9"))
        self.assertEqual(["pinned_image_revision_mismatch"], native_environment.preconditions(data))



class CertificateTests(unittest.TestCase):
    def test_legacy_and_sdk_range_labels_normalize_one_certificate(self):
        value = "ab" * 32
        for sample in (
            "Signer #1 certificate SHA-256 digest: " + value.upper() + "\r\n",
            "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: " + value + "\n" +
            "Signer (minSdkVersion=26, maxSdkVersion=32) certificate SHA-256 digest: " + value.upper() + "\n",
            "Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647) certificate SHA-256 digest: " + value + "\n",
        ):
            self.assertEqual(value, runner.certificate_digest(sample.encode()))

    def test_missing_malformed_or_multiple_keys_are_rejected(self):
        for sample in (
            b"Source Stamp Signer certificate SHA-256 digest: " + b"a" * 64,
            b"Signer #1 certificate SHA-256 digest: private-body-canary",
            b"Signer #1 certificate SHA-256 digest: " + b"a" * 64 + b"\nSigner #2 certificate SHA-256 digest: " + b"b" * 64,
        ):
            with self.subTest(sample=sample[:50]), self.assertRaises(RuntimeError):
                runner.certificate_digest(sample)

    def fixture(self):
        import tempfile
        import zipfile
        folder = self.enterContext(tempfile.TemporaryDirectory(prefix="ccm-ime-preparer-"))
        root = Path(folder)
        sdk = root / "sdk"
        for version in ("35.0.0", "99.0.0"):
            directory = sdk / "build-tools" / version
            directory.mkdir(parents=True)
            for name in (("apksigner.bat", "aapt2.exe") if os.name == "nt" else ("apksigner", "aapt2")):
                (directory / name).write_bytes(b"not executed")
        for directory in ("debug", "androidTest/debug"):
            target = root / "ime-reproducer/build/outputs/apk" / directory / "fixture.apk"
            target.parent.mkdir(parents=True)
            with zipfile.ZipFile(target, "w") as apk:
                apk.writestr("classes.dex", b"fixture")
        self.enterContext(patch.dict(os.environ, {"ANDROID_HOME": str(sdk)}))
        self.enterContext(patch.object(runner, "ROOT", root))
        self.enterContext(patch.object(runner, "REPORT", root / "reports"))
        return root

    def test_preparation_uses_approved_tools_and_validates_both_apks(self):
        root = self.fixture()
        cert = b"Signer #1 certificate SHA-256 digest: " + b"A" * 64
        responses = [cert, ("package: name='" + runner.APP + "'\n").encode(),
                     cert, ("package: name='" + runner.APP + ".test'\n").encode()]
        with patch.object(runner.subprocess, "check_output", side_effect=responses) as tool:
            result = runner.prepare()
        self.assertEqual("a" * 64, result["certificateSha256"])
        self.assertEqual("35.0.0", result["buildToolsVersion"])
        self.assertFalse(result["networkPermission"])
        self.assertEqual({"app", "test"}, set(result["artifacts"]))
        self.assertNotIn("99.0.0", str(tool.call_args_list))
        self.assertIn("35.0.0", str(tool.call_args_list))
        self.assertEqual("verified", json.loads((root / "reports/preparation.json").read_text())["status"])

    def test_mismatched_certificates_stop_before_verified_metadata(self):
        root = self.fixture()
        responses = [b"Signer #1 certificate SHA-256 digest: " + b"a" * 64,
                     ("package: name='" + runner.APP + "'\n").encode(),
                     b"Signer #1 certificate SHA-256 digest: " + b"b" * 64,
                     ("package: name='" + runner.APP + ".test'\n").encode()]
        with patch.object(runner.subprocess, "check_output", side_effect=responses), self.assertRaises(RuntimeError):
            runner.prepare()
        self.assertFalse((root / "reports/apks.json").exists())
        self.assertEqual(0, json.loads((root / "reports/preparation.json").read_text())["instrumentationInvocations"])

    def test_crypto_failure_and_network_permission_are_never_bypassed(self):
        root = self.fixture()
        with patch.object(runner.subprocess, "check_output", side_effect=subprocess.CalledProcessError(1, "verify")), \
             self.assertRaises(subprocess.CalledProcessError):
            runner.prepare()
        self.assertFalse((root / "reports/apks.json").exists())
        cert = b"Signer #1 certificate SHA-256 digest: " + b"a" * 64
        badging = ("package: name='" + runner.APP + "'\nuses-permission: name='android.permission.INTERNET'\n").encode()
        with patch.object(runner.subprocess, "check_output", side_effect=[cert, badging]), self.assertRaises(RuntimeError):
            runner.prepare()
        self.assertFalse((root / "reports/apks.json").exists())

if __name__ == "__main__":
    unittest.main()
