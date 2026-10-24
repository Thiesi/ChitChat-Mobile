"""One fixed diagnostic cohort; never substitutes for the unfiltered native gate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import native_journeys as journey

ROOT = Path(__file__).resolve().parents[2]
SOURCE = "d302706ab4588270310d304ef70de22e4ac8a612"
COMPARISON = "io.github.thiesi.chitchat.testing.NativeInputComparisonTest"
CONTROL = ("io.github.thiesi.chitchat.testing.NativeInputDialogJourneyTest",
           "hundredNativeInputDialogCyclesPreserveBodyWithoutInventingEdits")
PREFIX = [(COMPARISON, name) for name in (
    "legacyBasicTextFieldSingleTapShowsTheKeyboard",
    "legacyMaterialTextFieldSingleTapShowsTheKeyboard",
    "platformEditTextSingleTapShowsTheKeyboard",
    "observedLegacyMaterialTextFieldSingleTapShowsTheKeyboard")]


def expected(mode):
    if mode == "isolated":
        return [CONTROL]
    if mode == "after-comparison":
        return PREFIX + [CONTROL]
    raise ValueError("Unknown history cohort")


def selector(mode):
    expected(mode)
    control = "#".join(CONTROL)
    return control if mode == "isolated" else COMPARISON + "," + control


def summarize(raw, mode, adb_exit, timed_out=False, collector_errors=()):
    wanted = expected(mode)
    records = []
    previous = 0
    problems = []
    for match in re.finditer(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\r?$", raw, re.M):
        block = raw[previous:match.start()]
        previous = match.end()
        classes = re.findall(r"^INSTRUMENTATION_STATUS: class=([^\r\n]+)", block, re.M)
        methods = re.findall(r"^INSTRUMENTATION_STATUS: test=([^\r\n]+)", block, re.M)
        identity = (classes[-1], methods[-1]) if classes and methods else None
        if identity not in wanted:
            problems.append("unknown_test_identity")
            identity = None  # Never serialize an unexpected arbitrary payload.
        records.append({"test": identity, "code": int(match[1])})
    starts = [r["test"] for r in records if r["code"] == 1]
    terminals = [r for r in records if r["code"] <= 0]
    if starts != wanted:
        problems.append("unexpected_start_order")
    if [r["test"] for r in terminals] != wanted:
        problems.append("missing_duplicate_or_reordered_terminal_results")
    if any(r["code"] not in (1, 0, -1, -2, -3, -4) for r in records):
        problems.append("unknown_status_code")
    passed = sum(r["code"] == 0 for r in terminals)
    failed = sum(r["code"] in (-1, -2) for r in terminals)
    skipped = sum(r["code"] in (-3, -4) for r in terminals)
    ok = re.findall(r"^OK \((\d+) tests?\)\r?$", raw, re.M)
    native_pass = (adb_exit == 0 and not timed_out and not problems and
                   passed == len(wanted) and failed == 0 and skipped == 0 and
                   ok == [str(len(wanted))])
    if not failed and not skipped and ok != [str(len(wanted))]:
        problems.append("missing_or_wrong_success_summary")
    return {"mode": mode, "expectedTests": len(wanted), "startedOrder": starts,
            "terminalResults": terminals, "passed": passed, "failed": failed,
            "skipped": skipped, "adbExit": adb_exit, "timedOut": timed_out,
            "integrityErrors": sorted(set(problems)),
            "collectorErrors": list(collector_errors), "nativePass": native_pass,
            "exitCode": 1 if not native_pass else 2 if collector_errors else 0,
            "fullGateReplaced": False}


def command(args, timeout=60):
    return subprocess.run(args, cwd=ROOT, check=True, capture_output=True,
                          text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout.strip()


def retire_collector(collector):
    errors = []
    for operation in ("stop", "save"):
        try:
            getattr(collector, operation)()
        except Exception:
            errors.append(operation + "_failed")
    return list(dict.fromkeys([*collector.errors, *errors]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("isolated", "after-comparison"))
    mode = parser.parse_args().mode
    out = ROOT / "app/build/reports/ime-history" / mode
    out.mkdir(parents=True, exist_ok=False)
    report = {"mode": mode, "nativeSourceAnchor": SOURCE, "phase": "setup",
              "nativeStarted": False, "fullGateReplaced": False}
    collector = None
    try:
        if os.environ.get("GITHUB_ACTIONS") != "true":
            raise RuntimeError("This launcher requires the owned hosted-CI environment")
        report["head"] = command(["git", "rev-parse", "HEAD"])
        report["tree"] = command(["git", "rev-parse", "HEAD^{tree}"])
        command(["git", "diff", "--exit-code", SOURCE, "HEAD", "--", "app/src",
                 "app/build.gradle.kts", "build.gradle.kts", "gradle", "gradle.properties",
                 "settings.gradle.kts", "gradlew", "gradlew.bat"])
        devices = [line.split() for line in command(["adb", "devices"]).splitlines()[1:] if line.strip()]
        if len(devices) != 1 or len(devices[0]) != 2 or devices[0][1] != "device" or not devices[0][0].startswith("emulator-"):
            raise RuntimeError("Exactly one owned CI emulator is required")
        adb = ["adb", "-s", devices[0][0]]
        report["serial"] = devices[0][0]
        report["sdkFull"] = command(adb + ["shell", "getprop", "ro.build.version.sdk_full"])
        report["fingerprint"] = command(adb + ["shell", "getprop", "ro.build.fingerprint"])
        if report["sdkFull"] != "36.0":
            raise RuntimeError("Image outside the observed API36.0 scope")
        report["phase"] = "build"
        with (out / "build.log").open("w", encoding="utf-8") as log:
            build = subprocess.run(["./gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                                    "--max-workers=2", "--console=plain"], cwd=ROOT,
                                   stdout=log, stderr=subprocess.STDOUT, timeout=600, check=False)
        report["buildExit"] = build.returncode
        if build.returncode:
            raise RuntimeError("APK build failed")
        apks = [("io.github.thiesi.chitchat.debug", ROOT / "app/build/outputs/apk/debug/app-debug.apk"),
                ("io.github.thiesi.chitchat.debug.test", ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]
        signer = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0/apksigner"
        certificates = []
        report["apks"] = []
        report["phase"] = "install"
        for package, apk in apks:
            certificate = re.findall(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})",
                                     command([str(signer), "verify", "--print-certs", str(apk)]))
            if len(certificate) != 1:
                raise RuntimeError("Unknown APK signer")
            certificates.append(certificate[0].upper())
            report["apks"].append({"name": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest().upper()})
        if len(set(certificates)) != 1:
            raise RuntimeError("App/test signing mismatch")
        report["sameSigner"] = True
        for package, apk in apks:
            if "package:" + package in command(adb + ["shell", "pm", "list", "packages", package]).splitlines():
                raise RuntimeError("Fresh CI device already contains the application")
            command(adb + ["install", str(apk)], timeout=90)
            verified = command(adb + ["shell", "cmd", "package", "compile", "-m", "verify", "-f", package], timeout=240)
            if "Success" not in verified:
                raise RuntimeError("Package verification failed")
        report["packageVerifyPreparation"] = True
        journey.DEVICE_TIME = adb + journey.DEVICE_TIME[1:]
        journey.LOGCAT = adb + journey.LOGCAT[1:]
        journey.REPORT = out / "ime-lifecycle.txt"
        collector = journey.TraceCollector()
        collector.start()
        if collector.errors:
            raise RuntimeError("Collector setup failed")
        report["phase"] = "native"
        report["nativeStarted"] = True
        timed_out = False
        with (out / "native.log").open("w", encoding="utf-8") as log:
            try:
                native = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", selector(mode),
                    "-e", "expectSoftKeyboard", "true",
                    "io.github.thiesi.chitchat.debug.test/androidx.test.runner.AndroidJUnitRunner"],
                    cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600, check=False)
                adb_exit = native.returncode
            except subprocess.TimeoutExpired:
                timed_out, adb_exit = True, None
                report["timeoutCleanupErrors"] = []
                for package, _ in apks:
                    try:
                        command(adb + ["shell", "am", "force-stop", package], timeout=30)
                    except (OSError, subprocess.SubprocessError):
                        report["timeoutCleanupErrors"].append(package)
        report["adbExit"], report["timedOut"] = adb_exit, timed_out
        collector_errors = retire_collector(collector)
        collector = None
        if (out / "native.log").stat().st_size > 8 * 1024 * 1024:
            raise RuntimeError("Native output exceeded the bounded parser input")
        result = summarize((out / "native.log").read_text(encoding="utf-8"), mode, adb_exit, timed_out, collector_errors)
        report.update(result)
        report["phase"] = "complete"
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
        report.update(errorClass=type(error).__name__, exitCode=2)
    finally:
        if collector is not None and report["phase"] != "complete":
            report["collectorErrors"] = retire_collector(collector)
        (out / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report, indent=2))
    return report["exitCode"]


if __name__ == "__main__":
    raise SystemExit(main())
