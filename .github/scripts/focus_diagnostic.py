"""Run one declared standalone condition; retain only allowlisted scalar observations."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import zipfile

APP = "io.github.thiesi.chitchat.debug"
DIAGNOSTIC = "io.github.thiesi.chitchat.diagnostic.focus"
OWNERS = {"TARGET_HOST", "SYSTEM_UI", "OTHER_COMPONENT", "NO_WINDOW"}
STAGES = {"COMPOSE", "NATIVE_TAP", "FOCUS_IME", "TEXT_PROOF"}
FLAG = re.compile(r"(?:mShowRequested|mShowExplicitlyRequested|mShowForced|mInputShown|mShowInputRequested|"
    r"mIsInputViewShown|mDecorViewVisible|mWindowVisible|mInShowWindow|mWindowWasVisible|"
    r"mGivenInsetsPending|mHasSurface|isReadyForDisplay\(\)|mPolicyVisibility|mAppOpVisibility|"
    r"mSurfaceShown|isOnScreen|isVisible|mLastHidden)=(?:true|false)|"
    r"mImeWindowVis=(?:0x[0-9a-fA-F]{1,8}|[0-9]{1,10})|"
    r"mDrawState=(?:NO_SURFACE|DRAW_PENDING|COMMIT_DRAW_PENDING|READY_TO_SHOW|HAS_DRAWN)")


def scalar(value, kind):
    if kind == "bool":
        return value if type(value) is bool else None
    if kind == "int":
        return value if type(value) is int and 0 <= value < 2**63 else None
    if kind == "owner":
        return value if isinstance(value, str) and value in OWNERS else None
    if kind == "class":
        return value if isinstance(value, str) and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]{0,95}", value) else None


def fields(value, schema):
    source = value if isinstance(value, dict) else {}
    return {key: scalar(source.get(key), kind) for key, kind in schema.items()}


def sanitize_record(value):
    if not isinstance(value, dict):
        return None
    kind = value.get("kind")
    if kind == "success":
        return {"kind": kind, **fields(value, {"physicalTapFocusImeProved": "bool", "completeTextProved": "bool"})}
    if kind == "cleanup":
        return {"kind": kind, **fields(value, {"completed": "bool", "failureClass": "class"})}
    if kind != "failure":
        return None
    result = {"kind": kind, "stage": value.get("stage") if value.get("stage") in STAGES else None,
        **fields(value, {"failureClass": "class", "detectedUptimeMillis": "int"})}
    own_schema = {name: "bool" for name in ("windowFocused", "attached", "viewFocused", "inTouchMode",
        "textEditor", "imeVisible", "imeControllable")}
    own_schema.update({name: "int" for name in ("uptimeMillis", "resumedActivities", "imeBottom", "softInputMode")})
    result["ownWindow"] = fields(value.get("ownWindow"), own_schema)
    stamps = {"startedUptimeMillis": "int", "finishedUptimeMillis": "int"}
    result["keyguard"] = fields(value.get("keyguard"), {**stamps,
        "locked": "bool", "deviceLocked": "bool", "deviceSecure": "bool"})
    result["windowManager"] = fields(value.get("windowManager"), {**stamps, "limitReached": "bool",
        "focusedWindow": "owner", "focusedApp": "owner", "windowFields": "int", "appFields": "int"})
    if result["windowManager"]["limitReached"] is not False:
        result["windowManager"]["focusedWindow"] = None
        result["windowManager"]["focusedApp"] = None
    result["imeServer"] = {}
    server = value.get("imeServer") if isinstance(value.get("imeServer"), dict) else {}
    for name in ("inputMethod", "imeWindow"):
        source = server.get(name) if isinstance(server.get(name), dict) else {}
        entry = fields(source, {**stamps, "limitReached": "bool"})
        flags = source.get("flags")
        entry["flags"] = flags if isinstance(flags, list) and 0 < len(flags) <= 40 and all(
            isinstance(flag, str) and FLAG.fullmatch(flag) for flag in flags) else None
        result["imeServer"][name] = entry
    return result


def summarize(raw, exit_code, limited=False):
    text = raw.decode("utf-8", errors="replace")
    records = []
    invalid = False
    for line in text.splitlines():
        prefix = "INSTRUMENTATION_STATUS: focusDiagnostic="
        if line.startswith(prefix):
            try:
                record = sanitize_record(json.loads(line[len(prefix):]))
            except (ValueError, TypeError):
                record = None
            if record is None or len(records) >= 8:
                invalid = True
            elif len(records) < 8:
                records.append(record)
    counts = {int(n) for n in re.findall(r"^INSTRUMENTATION_STATUS: numtests=([0-9]+)\s*$", text, re.M)}
    terminal = re.findall(r"^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$", text, re.M)
    success, cleanup = [r for r in records if r["kind"] == "success"], [r for r in records if r["kind"] == "cleanup"]
    passed = (exit_code == 0 and not limited and not invalid and counts == {1} and terminal == ["-1"]
        and re.search(r"^OK \(1 test\)\s*$", text, re.M) is not None and "FAILURES!!!" not in text
        and len(records) == 2 and len(success) == len(cleanup) == 1
        and success[0]["physicalTapFocusImeProved"] is True and success[0]["completeTextProved"] is True
        and cleanup[0]["completed"] is True)
    return {"exitCode": exit_code, "outputLimitReached": limited, "invalidScalarRecord": invalid,
        "oneDiagnosticCaseReported": counts == {1}, "records": records, "diagnosticPass": passed,
        "acceptanceGate": False, "rawInstrumentationOutputRetained": False}


def bounded_instrument(command):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    def drain():
        kept = bytearray()
        total = 0
        while True:
            chunk = process.stdout.read(4096)
            if not chunk:
                break
            total += len(chunk)
            kept.extend(chunk[:max(0, 1_048_576 - len(kept))])
        return bytes(kept), total > 1_048_576
    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(drain)
        try:
            code = process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
            code = 124
        raw, limited = future.result(timeout=10)
    return summarize(raw, code, limited)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("plan", "condition", "app-apk", "diagnostic-apk", "sdk"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    plan_path = Path(args.plan).resolve()
    plan = json.loads(plan_path.read_text(encoding="utf-8-sig"))
    conditions = plan["conditions"]
    if not isinstance(conditions, dict) or set(conditions) != {"stable-37.2.12", "stable-37.2.13"}:
        raise RuntimeError("A fixed two-condition version comparison is required")
    condition = conditions[args.condition]
    serial, avd = condition["serial"], condition["avd"]
    if not re.fullmatch(r"emulator-[0-9]+", serial) or not re.fullmatch(r"[A-Za-z0-9_]+", avd):
        raise RuntimeError("Only a named owned emulator is supported")
    sdk = Path(args.sdk).resolve()
    adb_path = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    signer = sdk / "build-tools/35.0.0" / ("apksigner.bat" if os.name == "nt" else "apksigner")
    def adb(*arguments, check=True):
        result = subprocess.run([str(adb_path), "-s", serial, *arguments], stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=30)
        if check:
            result.check_returncode()
        return result
    names = [line.strip() for line in adb("emu", "avd", "name").stdout.decode().splitlines() if line.strip()]
    if names != [avd, "OK"] or adb("shell", "getprop", "ro.build.version.sdk").stdout.strip() != b"36":
        raise RuntimeError("The selected device is not the declared owned API36 guest")
    for namespace, key, expected in (("secure", "show_ime_with_hard_keyboard", 1), ("system", "font_scale", 1),
        ("global", "window_animation_scale", 0), ("global", "transition_animation_scale", 0),
        ("global", "animator_duration_scale", 0)):
        if float(adb("shell", "settings", "get", namespace, key).stdout.strip()) != expected:
            raise RuntimeError("Guest configuration differs from the fixed comparison")
    if (adb("shell", "wm", "fixed-to-user-rotation").stdout.strip() != b"enabled" or
        adb("shell", "wm", "user-rotation").stdout.strip() != b"lock 0" or
        b"-port-" not in adb("shell", "cmd", "activity", "get-config").stdout):
        raise RuntimeError("Declared portrait configuration is not observed")
    certificates, artifacts = [], {}
    for name, argument in (("app", args.app_apk), ("diagnostic", args.diagnostic_apk)):
        path = Path(argument).resolve()
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != plan["artifacts"][name]:
            raise RuntimeError("Artifact differs from the fixed comparison plan")
        certificate = subprocess.run([str(signer), "verify", "--print-certs", str(path)],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30, check=True)
        matches = re.findall(rb"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", certificate.stdout)
        if len(matches) != 1:
            raise RuntimeError("APK certificate identity is unavailable")
        certificates.append(matches[0].decode())
        artifacts[name] = digest
        aapt = sdk / "build-tools/35.0.0" / ("aapt2.exe" if os.name == "nt" else "aapt2")
        metadata = subprocess.run([str(aapt), "dump", "badging", str(path)], stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=30, check=True).stdout
        package = APP if name == "app" else DIAGNOSTIC
        if re.findall(rb"^package: name='([^']+)'", metadata, re.M) != [package.encode()]:
            raise RuntimeError("APK package is not the standalone diagnostic or its target")
        if name == "diagnostic":
            with zipfile.ZipFile(path) as apk:
                for entry in apk.namelist():
                    if re.fullmatch(r"classes[0-9]*\.dex", entry) and re.search(
                        rb"Lio/github/thiesi/chitchat/[A-Za-z0-9_/$]*Test;", apk.read(entry)):
                        raise RuntimeError("Refuse an APK containing existing application test classes")
    if certificates[0] != certificates[1]:
        raise RuntimeError("App and diagnostic signing certificates differ")
    for package in (APP, DIAGNOSTIC):
        if adb("shell", "pm", "path", package, check=False).stdout.strip():
            raise RuntimeError("Refuse to replace a pre-existing package")
    directory = plan_path.parent / args.condition
    directory.mkdir(exist_ok=False)  # One persistent slot per condition, before any mutation.
    receipt = {"condition": args.condition, "serial": serial, "avd": avd, "artifacts": artifacts,
        "certificateSha256": certificates[0], "planSha256": hashlib.sha256(plan_path.read_bytes()).hexdigest(),
        "instrumentationInvocations": 0, "existingSuiteInvocations": 0, "installed": [],
        "installAttempts": [], "cleanupComplete": False}
    marker = directory / "result.json"
    def save():
        marker.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    save()
    try:
        for package, artifact in ((APP, args.app_apk), (DIAGNOSTIC, args.diagnostic_apk)):
            receipt["installAttempts"].append(package)
            save()
            result = adb("install", "-t", str(Path(artifact).resolve()))
            if b"Success" not in result.stdout:
                raise RuntimeError("Diagnostic package install was not confirmed")
            receipt["installed"].append(package)
            save()
        receipt["instrumentationInvocations"] = 1
        save()
        started = time.monotonic()
        receipt["result"] = bounded_instrument([str(adb_path), "-s", serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "focusDiagnostic", "true", "-e", "expectSoftKeyboard", "true",
            DIAGNOSTIC + "/androidx.test.runner.AndroidJUnitRunner"])
        receipt["elapsedSeconds"] = round(time.monotonic() - started, 3)
        save()  # Preserve the original result even if subsequent device cleanup is interrupted.
    finally:
        # Both packages were absent before this run; an interrupted install may still commit.
        cleanup_errors = []
        if APP in receipt["installAttempts"]:
            try:
                adb("shell", "am", "force-stop", APP, check=False)
            except (OSError, subprocess.TimeoutExpired):
                cleanup_errors.append("forceStopUnavailable")
        removed = []
        for package in reversed(receipt["installAttempts"]):
            try:
                if not adb("shell", "pm", "path", package, check=False).stdout.strip():
                    removed.append(True)
                else:
                    result = adb("uninstall", package, check=False)
                    removed.append(b"Success" in result.stdout and not adb("shell", "pm", "path", package, check=False).stdout.strip())
            except (OSError, subprocess.TimeoutExpired):
                removed.append(False)
                cleanup_errors.append("packageCleanupUnavailable")
        receipt["cleanupComplete"] = len(removed) == len(receipt["installAttempts"]) and all(removed) and not cleanup_errors
        receipt["cleanupErrors"] = cleanup_errors
        save()
    passed = receipt.get("result", {}).get("diagnosticPass") is True and receipt["cleanupComplete"]
    print(json.dumps({"condition": args.condition, "diagnosticPass": passed, "evidence": str(marker)}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
