"""One manual API35/20-case inset measurement. Never an ordinary gate or retry."""
from concurrent.futures import ThreadPoolExecutor, TimeoutError
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

import ime_reproducer as verified_apks
import native_environment as environment

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "app/build/reports/recreatedInsets"
APP = "io.github.thiesi.chitchat.debug"
CLASS = "io.github.thiesi.chitchat.feature.conversation.RecreatedComposerInsetsDiagnostic"
CYCLES = set(range(1, 21))
EVENTS = {"install", "dispose", "attach", "detach", "draw", "compose", "wait_start", "proved",
          "before_recreate", "after_recreate", "failure", "cleanup_end"}
STAGES = {"START", "ROOM_REPLY", "RECREATE", "DM_REPLY", "CLEANUP"}
INTS = {"sequence", "uptimeMillis", "activity", "window", "root", "composeBottom", "mirroredBottom", "mirroredProducer"}
BOOLS = {"attached", "destroyed", "windowFocused", "rootMatchesWindow", "focusedRootMatches"}
OPTIONAL_INTS = {"nativeBottom", "holderAccessors"}
OPTIONAL_BOOLS = {"nativeVisible", "applyListenerPresent"}
KEYS = INTS | BOOLS | OPTIONAL_INTS | OPTIONAL_BOOLS | {"event", "stage"}
METHOD = re.compile(r"roomRecreationThenDirectReply\[cycle([1-9]|1[0-9]|20)\]")


def sanitize(value):
    """Reject malformed/extra fields; no report/body/error strings cross this boundary."""
    if (not isinstance(value, dict) or set(value) != {"schema", "cycle", "failed", "cleanupFailed", "droppedProgress", "records"}
        or type(value["schema"]) is not int or value["schema"] != 1
        or type(value["cycle"]) is not int or value["cycle"] not in CYCLES
        or type(value["failed"]) is not bool or type(value["cleanupFailed"]) is not bool
        or type(value["droppedProgress"]) is not int or not 0 <= value["droppedProgress"] <= 100000):
        return None
    rows = value["records"]
    if not isinstance(rows, list) or len(rows) > 192:
        return None
    previous_sequence, previous_time = 0, 0
    for row in rows:
        if (not isinstance(row, dict) or set(row) != KEYS
            or type(row["event"]) is not str or type(row["stage"]) is not str
            or row["event"] not in EVENTS or row["stage"] not in STAGES
            or any(type(row[k]) is not int or not 0 <= row[k] <= 2**63-1 for k in INTS)
            or any(type(row[k]) is not bool for k in BOOLS)
            or any(row[k] is not None and (type(row[k]) is not int or not 0 <= row[k] <= 16384) for k in OPTIONAL_INTS)
            or any(row[k] is not None and type(row[k]) is not bool for k in OPTIONAL_BOOLS)
            or row["sequence"] <= previous_sequence or row["uptimeMillis"] < previous_time):
            return None
        previous_sequence, previous_time = row["sequence"], row["uptimeMillis"]
    # Return only already-validated primitives, never a caller's mutable dictionary.
    return json.loads(json.dumps(value))


def observe(stream):
    records, started, finished, block = {}, set(), {}, {}
    invalid = False
    total = 0
    runner_code = None
    while True:
        line = stream.readline(262145)
        if not line:
            break
        total += len(line)
        if len(line) > 262144 or total > 16_777_216:
            raise ValueError("Observation stream bound")
        if line.startswith("INSTRUMENTATION_STATUS: ccmInsetsCycle="):
            try:
                value = sanitize(json.loads(line.split("=", 1)[1]))
            except (ValueError, TypeError):
                value = None
            if value is None or value["cycle"] in records:
                invalid = True
            else:
                records[value["cycle"]] = value
        elif line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].strip().partition("=")
            if sep and key in {"class", "test"}:
                block[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            match = METHOD.fullmatch(block.get("test", "")) if block.get("class") == CLASS else None
            if match:
                cycle = int(match[1])
                code = line.split(":", 1)[1].strip()
                if code == "1":
                    if cycle in started: invalid = True
                    started.add(cycle)
                else:
                    if code not in {"0", "-1", "-2", "-3", "-4"} or cycle in finished: invalid = True
                    finished[cycle] = code
            block = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if runner_code is not None: invalid = True
            runner_code = line.split(":", 1)[1].strip()
    controls = [[row for row in case["records"] if row["event"] not in {"draw", "compose"}] for case in records.values()]
    complete = (not invalid and runner_code == "-1" and set(records) == started == set(finished) == CYCLES
                and all(rows and rows[-1]["event"] == "cleanup_end" for rows in controls)
                and all(code in {"0", "-1", "-2"} for code in finished.values()))
    return dict(measurementComplete=complete, invalidMetadata=invalid, startedCases=len(started),
        finishedCases=len(finished), caseResults=[dict(cycle=k, status=int(finished[k])) for k in sorted(finished)],
        originalTestPass=complete and all(code == "0" for code in finished.values()) and
        all(not r["failed"] and not r["cleanupFailed"] for r in records.values()),
        cases=[records[k] for k in sorted(records)])


def prepare():
    REPORT.mkdir(parents=True, exist_ok=True)
    signer, aapt = verified_apks.verification_tools(Path(os.environ["ANDROID_HOME"]))
    artifacts, certificates = {}, []
    for kind, path, package in (("app", ROOT / "app/build/outputs/apk/debug/app-debug.apk", APP),
        ("test", ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", APP + ".test")):
        payload = path.read_bytes()
        with zipfile.ZipFile(path) as archive:
            marker = b'Lio/github/thiesi/chitchat/feature/conversation/RecreatedComposerInsetsDiagnostic;'
            included = any(marker in archive.read(name) for name in archive.namelist()
                           if re.fullmatch(r"classes[0-9]*\.dex", name))
        if included != (kind == "test"):
            raise RuntimeError("Diagnostic class must exist only in the opt-in test APK")
        certificates.append(verified_apks.certificate_digest(verified_apks.tool_output(signer, "verify", "--print-certs", str(path))))
        badging = verified_apks.tool_output(aapt, "dump", "badging", str(path))
        match = re.search(rb"(?m)^package: name='([^']+)'", badging)
        if not match or match[1].decode() != package:
            raise RuntimeError("Diagnostic package mismatch")
        artifacts[kind] = dict(path=path.relative_to(ROOT).as_posix(), bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
    if certificates[0] != certificates[1]:
        raise RuntimeError("Diagnostic APK certificates differ")
    (REPORT / "apks.json").write_text(json.dumps(dict(artifacts=artifacts, certificateSha256=certificates[0]), indent=2) + "\n")


def instrument_once():
    command = ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS,
               "-e", "expectSoftKeyboard", "true", APP + ".test/androidx.test.runner.AndroidJUnitRunner"]
    process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               encoding="utf-8", errors="replace")
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(observe, process.stdout)
        try:
            value = pending.result(timeout=600)
            code = process.wait(timeout=10)
        except (TimeoutError, ValueError, subprocess.TimeoutExpired):
            process.kill()
            process.wait(timeout=10)
            raise RuntimeError("Incomplete bounded measurement") from None
        finally:
            process.stdout.close()
    return dict(value, adbExitCode=code)


def run():
    REPORT.mkdir(parents=True, exist_ok=True)
    result = dict(authority=6726488531, head=os.environ.get("GITHUB_SHA"), cycles=20,
                  instrumentationInvocations=0, acceptanceGate=False, retryPerformed=False)
    installed = []
    def save(): (REPORT / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    save()
    try:
        if os.environ.get("GITHUB_ACTIONS") != "true":
            raise RuntimeError("Manual hosted-emulator measurement only")
        if not re.fullmatch("[0-9a-f]{40}", result["head"] or ""):
            raise RuntimeError("Exact diagnostic source required")
        data = environment.fingerprint(image_api=35)
        failures = environment.preconditions(data, image_revision="9", image_api=35)
        if environment.read(["adb", "shell", "getprop", "ro.kernel.qemu"]) != "1":
            failures.append("owned_emulator_required")
        result.update(environment=data, failedPreconditions=failures); save()
        if failures: raise RuntimeError("Diagnostic preconditions failed")
        apks = json.loads((REPORT / "apks.json").read_text())
        result["apks"] = apks; save()
        for package in (APP, APP + ".test"):
            if environment.read(["adb", "shell", "pm", "path", package]):
                raise RuntimeError("Refuse to replace existing packages")
        for kind, package in (("app", APP), ("test", APP + ".test")):
            artifact = apks["artifacts"][kind]
            path = ROOT / artifact["path"]
            if hashlib.sha256(path.read_bytes()).hexdigest() != artifact["sha256"]:
                raise RuntimeError("Prepared diagnostic APK changed")
            installed.append(package)
            subprocess.run(["adb", "install", "-t", str(path)], cwd=ROOT, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
        result["instrumentationInvocations"] = 1; save()
        result["observation"] = instrument_once(); save()
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as failure:
        result["runnerFailureClass"] = type(failure).__name__
    finally:
        cleanup = []
        for package in reversed(installed):
            try:
                subprocess.run(["adb", "shell", "am", "force-stop", package], cwd=ROOT,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False)
                removed = subprocess.run(["adb", "uninstall", package], cwd=ROOT, stdout=subprocess.PIPE,
                                         stderr=subprocess.DEVNULL, timeout=30, check=False)
                cleanup.append(removed.returncode == 0 and b"Success" in removed.stdout)
            except (OSError, subprocess.SubprocessError): cleanup.append(False)
        result["cleanupComplete"] = len(cleanup) == len(installed) and all(cleanup); save()
    observation = result.get("observation", {})
    print(json.dumps({k: result[k] for k in ("instrumentationInvocations", "acceptanceGate", "cleanupComplete")}))
    return 0 if observation.get("originalTestPass") and result["cleanupComplete"] else 1


if __name__ == "__main__":
    if sys.argv[1:] == ["prepare"]: prepare()
    elif sys.argv[1:] == ["run"]: sys.exit(run())
    else: raise SystemExit("Use prepare or run; this never runs an ordinary gate")
