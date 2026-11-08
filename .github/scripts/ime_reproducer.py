"""One authorized standalone 50-case arm. Measurement only; no production app or retries."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

import focus_diagnostic as safe
import native_environment as environment
import native_journeys
import pin_journey_image
from pin_reproducer_image import ARMS

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "ime-reproducer/build/reports/observation"
APP = "io.github.thiesi.chitchat.imereproducer"
CLASS = APP + ".FreshImeReproducerTest"
TOKEN = re.compile(r"(?:io\.github\.thiesi\.chitchat\.imereproducer(?:\.test)?|system_server):[0-9a-fA-F]{1,16}:")
FRAME = re.compile(r"[A-Za-z_$][A-Za-z_0-9$]*(?:\.[A-Za-z_$][A-Za-z_0-9$]*)+")
CYCLES = set(range(1, 51))
STAGES = {"LAUNCH", "NATIVE_TAP", "FOCUS_IME"}
WINDOW = {**{key: "int" for key in ("uptimeMillis", "resumedActivities", "imeBottom", "softInputMode")},
          **{key: "bool" for key in ("windowFocused", "attached", "viewFocused", "imeVisible")}}
STAMPS = {"startedUptimeMillis": "int", "finishedUptimeMillis": "int"}
BUILD_TOOLS_VERSION = "35.0.0"  # Same verified M1 toolchain, never the host's newest SDK tools.
CERTIFICATE = re.compile(rb"(?m)^Signer (?:#[1-9][0-9]*|\(minSdkVersion=[0-9]+(?: \(dev release=true\))?, maxSdkVersion=[0-9]+\)) certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$")


def certificate_digest(output):
    # Called only after apksigner verify exits successfully. SDK-range labels can repeat
    # the same certificate; multiple distinct keys remain unavailable for this fixture.
    values = {value.decode().lower() for value in CERTIFICATE.findall(output)}
    if len(values) != 1:
        raise RuntimeError("Exactly one verified APK certificate required")
    return values.pop()


def verification_tools(sdk):
    folder = sdk / "build-tools" / BUILD_TOOLS_VERSION
    signer = folder / ("apksigner.bat" if os.name == "nt" else "apksigner")
    aapt = folder / ("aapt2.exe" if os.name == "nt" else "aapt2")
    if not signer.is_file() or not aapt.is_file():
        raise RuntimeError("Approved verification tools unavailable")
    return signer, aapt


def tool_output(tool, *arguments):
    command = (["cmd.exe", "/c"] if tool.suffix.lower() == ".bat" else []) + [str(tool), *arguments]
    return subprocess.check_output(command)


def sanitize(value):
    if not isinstance(value, dict) or type(value.get("cycle")) is not int or value["cycle"] not in CYCLES:
        return None
    result = dict(kind=value.get("kind"), cycle=value["cycle"])
    if result["kind"] == "cycle":
        return {**result, **safe.fields(value, {**STAMPS, "tapAttempted": "bool", "focusImeProved": "bool",
            "cleanupComplete": "bool", "deadlineMillis": "int", "failureClass": "class"})}
    if result["kind"] != "failure":
        return None
    result.update(safe.fields(value, {"detectedUptimeMillis": "int", "failureClass": "class"}))
    result["stage"] = value.get("stage") if value.get("stage") in STAGES else None
    for name in ("beforeTap", "afterFailure"):
        result[name] = safe.fields(value.get(name), WINDOW)
    server = value.get("imeServer") if isinstance(value.get("imeServer"), dict) else {}
    result["imeServer"] = {}
    for name in ("inputMethod", "imeWindow"):
        source = server.get(name) if isinstance(server.get(name), dict) else {}
        entry = safe.fields(source, {**STAMPS, "limitReached": "bool"})
        flags = source.get("flags")
        entry["flags"] = flags if isinstance(flags, list) and 0 < len(flags) <= 40 and all(
            isinstance(flag, str) and safe.FLAG.fullmatch(flag) for flag in flags) else None
        result["imeServer"][name] = entry
    stack = value.get("imeMainThread") if isinstance(value.get("imeMainThread"), dict) else {}
    entry = safe.fields(stack, {**STAMPS, "available": "bool", "incomplete": "bool"})
    frames = stack.get("frames")
    entry["frames"] = frames if entry["available"] is True and entry["incomplete"] is False and (
        isinstance(frames, list) and 0 < len(frames) <= 64 and all(
            isinstance(frame, str) and len(frame) <= 512 and FRAME.fullmatch(frame) for frame in frames)) else None
    if entry["frames"] is None:
        entry["available"] = False
    result["imeMainThread"] = entry
    return result


def summarize(raw, exit_code, limited=False):
    text = raw.decode("utf-8", errors="replace")
    records, finished, counts = [], [], set()
    block = {}
    invalid = False
    for line in text.splitlines():
        prefix = "INSTRUMENTATION_STATUS: "
        if line.startswith(prefix):
            key, _, value = line[len(prefix):].partition("=")
            if key == "imeReproducer":
                try:
                    record = sanitize(json.loads(value))
                except (ValueError, TypeError):
                    record = None
                if record is None or len(records) >= 100:
                    invalid = True
                else:
                    records.append(record)
            elif key in {"test", "class"}:
                block[key] = value
            elif key == "numtests" and value.isdecimal():
                counts.add(int(value))
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.partition(": ")[2]
            method = block.get("test", "")
            if code in {"0", "-1", "-2", "-3", "-4"} and block.get("class") == CLASS:
                if re.fullmatch(r"cycle(?:0[1-9]|[1-4][0-9]|50)", method):
                    finished.append(dict(cycle=int(method[-2:]), status=int(code)))
                else:
                    invalid = True
            block = {}
    terminal = re.findall(r"^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$", text, re.M)
    cycles = [record for record in records if record["kind"] == "cycle"]
    complete = (exit_code == 0 and not limited and not invalid and counts == {50} and terminal == ["-1"]
        and len(cycles) == len(finished) == 50
        and {record["cycle"] for record in cycles} == {record["cycle"] for record in finished} == CYCLES
        and all(record["tapAttempted"] is True and record["deadlineMillis"] == 5000 for record in cycles))
    passed = (complete and all(row["status"] == 0 for row in finished)
        and all(row["focusImeProved"] is True and row["cleanupComplete"] is True and row["failureClass"] is None for row in cycles)
        and re.search(r"^OK \(50 tests\)\s*$", text, re.M) is not None and "FAILURES!!!" not in text)
    return dict(transportExitCode=exit_code, outputLimitReached=limited, invalidRecord=invalid,
        measurementComplete=complete, originalTestPass=passed, finishedCases=finished, records=records,
        focusImePasses=sum(row["focusImeProved"] is True for row in cycles),
        cleanupFailures=sum(row["cleanupComplete"] is not True for row in cycles),
        acceptanceGate=False, rawInstrumentationOutputRetained=False)


def instrument_once():
    command = ["adb", "shell", "am", "instrument", "-w", "-r",
        "-e", "imeReproducer", "true", "-e", "expectSoftKeyboard", "true", "-e", "class", CLASS,
        APP + ".test/androidx.test.runner.AndroidJUnitRunner"]
    process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    def drain():
        kept = bytearray()
        total = 0
        while chunk := process.stdout.read(4096):
            total += len(chunk)
            kept.extend(chunk[:max(0, 8_388_608 - len(kept))])
        return bytes(kept), total > 8_388_608
    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(drain)
        try:
            code = process.wait(timeout=1200)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
            code = 124
        raw, limited = future.result(timeout=10)
    return summarize(raw, code, limited)


def prepare():
    sdk = Path(os.environ["ANDROID_HOME"])
    REPORT.mkdir(parents=True, exist_ok=True)
    preparation = dict(stage="APK_VERIFICATION", status="started", buildToolsVersion=BUILD_TOOLS_VERSION,
                       instrumentationInvocations=0)
    (REPORT / "preparation.json").write_text(json.dumps(preparation, indent=2) + "\n")
    signer, aapt = verification_tools(sdk)
    artifacts = {}
    certificates = []
    for kind, folder, package in (("app", "debug", APP), ("test", "androidTest/debug", APP + ".test")):
        apks = list((ROOT / "ime-reproducer/build/outputs/apk" / folder).glob("*.apk"))
        if len(apks) != 1:
            raise RuntimeError("Exactly one standalone APK required")
        path = apks[0]
        certificate = tool_output(signer, "verify", "--print-certs", str(path))
        certificates.append(certificate_digest(certificate))
        metadata = tool_output(aapt, "dump", "badging", str(path))
        if re.findall(rb"^package: name='([^']+)'", metadata, re.M) != [package.encode()]:
            raise RuntimeError("Unexpected APK identity")
        if b"android.permission.INTERNET" in metadata:
            raise RuntimeError("Reproducer APK must not have network permission")
        with zipfile.ZipFile(path) as apk:
            if apk.testzip() is not None:
                raise RuntimeError("Invalid APK archive")
        destination = REPORT / "artifacts" / (kind + ".apk")
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        artifacts[kind] = dict(path=str(destination.relative_to(ROOT)), bytes=path.stat().st_size,
                              sha256=hashlib.sha256(destination.read_bytes()).hexdigest())
    if certificates[0] != certificates[1]:
        raise RuntimeError("APK signatures differ")
    data = dict(artifacts=artifacts, certificateSha256=certificates[0], standalone=True, networkPermission=False,
                buildToolsVersion=BUILD_TOOLS_VERSION)
    (REPORT / "apks.json").write_text(json.dumps(data, indent=2) + "\n")
    preparation["status"] = "verified"
    (REPORT / "preparation.json").write_text(json.dumps(preparation, indent=2) + "\n")
    return data


def run(arm_name):
    arm = ARMS[arm_name]
    if os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or os.environ.get("GITHUB_RUN_ATTEMPT") != "1":
        raise RuntimeError("Only the first explicit measurement dispatch is allowed")
    head = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise RuntimeError("Exact source revision required")
    packages = []
    result = dict(arm=arm_name, expected=arm, head=head, acceptanceGate=False,
                  instrumentationInvocations=0, cleanupComplete=False)
    REPORT.mkdir(parents=True, exist_ok=True)
    marker = REPORT / "result.json"
    with marker.open("x") as output:
        json.dump(result, output, indent=2)
    def save():
        marker.write_text(json.dumps(result, indent=2) + "\n")
    try:
        for command in (["adb", "logcat", "-G", "16M"], ["adb", "logcat", "-c"]):
            if environment.read(command) is None:
                raise RuntimeError("Log buffer setup unavailable")
        data = environment.fingerprint()
        sdk = Path(os.environ["ANDROID_HOME"])
        data["systemImageRevision"] = pin_journey_image.revision(
            sdk / ("system-images/android-" + str(arm["api"]) + "/google_apis/x86_64/source.properties"))
        data["sdk"] = environment.scalar(environment.read(["adb", "shell", "getprop", "ro.build.version.sdk"]))
        data["cpuPresent"] = environment.scalar(environment.read(["adb", "shell", "cat", "/sys/devices/system/cpu/present"]))
        memory = environment.read(["adb", "shell", "cat", "/proc/meminfo"]) or ""
        match = re.search(r"(?m)^MemTotal:\s+([0-9]{4,9}) kB\s*$", memory)
        data["memTotalKb"] = int(match[1]) if match else None
        failures = environment.preconditions(data, image_revision=arm["revision"])
        if data["sdk"] != str(arm["api"]):
            failures.append("wrong_api")
        if data["cpuPresent"] != "0-" + str(arm["cores"] - 1):
            failures.append("wrong_core_count")
        result.update(environment=data, failedPreconditions=failures)
        save()
        if failures:
            raise RuntimeError("Reproducer preconditions failed; no instrumentation")
        artifacts = json.loads((REPORT / "apks.json").read_text())
        result["apks"] = artifacts
        save()
        for kind, package in (("app", APP), ("test", APP + ".test")):
            if environment.read(["adb", "shell", "pm", "path", package]):
                raise RuntimeError("Refuse to replace an existing package")
            artifact = artifacts["artifacts"][kind]
            path = ROOT / artifact["path"]
            if hashlib.sha256(path.read_bytes()).hexdigest() != artifact["sha256"]:
                raise RuntimeError("Prepared APK changed")
            packages.append(package)  # An interrupted install can still commit.
            subprocess.run(["adb", "install", "-t", str(path)], cwd=ROOT, check=True)
        result["instrumentationInvocations"] = 1
        save()
        result["observation"] = instrument_once()
        save()  # Original result precedes diagnostics collection and cleanup.
        try:
            capture = subprocess.run(native_journeys.LOGCAT, cwd=ROOT, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, encoding="utf-8", errors="replace", timeout=15, check=False)
            if capture.returncode != 0:
                raise OSError("Lifecycle capture unavailable")
            (REPORT / "ime-lifecycle.txt").write_text(
                native_journeys.filtered_trace(capture.stdout, token_pattern=TOKEN), encoding="utf-8")
            result["lifecycleAvailable"] = True
        except (OSError, subprocess.TimeoutExpired):
            result["lifecycleAvailable"] = False
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError) as failure:
        result["runnerFailureClass"] = type(failure).__name__  # Never raw exception content.
    finally:
        removed = []
        for package in reversed(packages):
            try:
                subprocess.run(["adb", "shell", "am", "force-stop", package], cwd=ROOT,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False)
                cleanup = subprocess.run(["adb", "uninstall", package], cwd=ROOT,
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30, check=False)
                removed.append(cleanup.returncode == 0 and b"Success" in cleanup.stdout)
            except (OSError, subprocess.SubprocessError):
                removed.append(False)
        result["cleanupComplete"] = len(removed) == len(packages) and all(removed)
        save()
    observation = result.get("observation", {})
    print(json.dumps(dict(arm=arm_name, instrumentationInvocations=result["instrumentationInvocations"],
        measurementComplete=observation.get("measurementComplete", False),
        originalTestPass=observation.get("originalTestPass", False), acceptanceGate=False)))
    return 0 if observation.get("originalTestPass") and result.get("lifecycleAvailable") and result["cleanupComplete"] else 1


if __name__ == "__main__":
    if sys.argv[1:] == ["prepare"]:
        prepare()
    elif len(sys.argv) == 3 and sys.argv[1] == "run" and sys.argv[2] in ARMS:
        raise SystemExit(run(sys.argv[2]))
    else:
        raise SystemExit("Use prepare or run a|b|c")
