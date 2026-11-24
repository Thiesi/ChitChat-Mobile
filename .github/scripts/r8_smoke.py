"""R8 smoke check (TL, 2026-11-23): the minified release build launches and parses an address, offline.

The release APK from the build job is zip-aligned and signed here with a throwaway key made for this run only (never the
Play upload key, which never enters CI), installed on the CI emulator with every network off, and driven through one
path: launch to Welcome, one tap on Get started into "Where do you chat?", an http address typed into the field and
Continue, which must show the HTTPS line (ServerAddress.parse under R8; no request can be made). A fatal crash, an
R8/serialization failure in the app's log, a missing screen or a dead process fails the check. The job is visible and
non-gating. Only allowlisted, truncated log lines are kept (never raw logcat, never field content).
"""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "app/build/reports/r8-smoke/result.json"
PACKAGE = "de.networkxxiii.chitchat"
ACTIVITY = f"{PACKAGE}/io.github.thiesi.chitchat.MainActivity"
WELCOME = "Get started"
CONTINUE = "Continue"
HTTPS_LINE = "ChitChat only connects securely."
ADDRESS = "http://smoke.invalid"
# A wait for a positive condition, bounded; never a retry of an action.
WAIT_SECONDS = 30
POLL_SECONDS = 2
# The log signatures that mean the minified build broke (kept truncated, at most this many).
FATAL = re.compile(r"FATAL EXCEPTION|AndroidRuntime|SerializationException|ClassNotFoundException|NoSuchMethodError|"
                   r"NoSuchFieldError|NoClassDefFoundError|ClassCastException|VerifyError|AbstractMethodError")
MAX_LINES = 20
LINE_CHARS = 240
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def run(command: list[str], timeout: int = 60) -> subprocess.CompletedProcess:
    return subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8",
                          errors="replace", timeout=timeout, check=False)


def adb(*arguments: str, timeout: int = 60) -> subprocess.CompletedProcess:
    return run(["adb", *arguments], timeout=timeout)


def build_tool(name: str) -> str:
    """The newest build-tools binary from the runner's SDK."""
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/usr/local/lib/android/sdk")
    versions = sorted((p for p in (sdk / "build-tools").iterdir() if (p / name).exists()),
                      key=lambda p: [int(part) if part.isdigit() else 0 for part in re.split(r"[.-]", p.name)])
    if not versions:
        raise RuntimeError(f"No build-tools {name}")
    return str(versions[-1] / name)


def center(xml: str, text: str | None = None, cls: str | None = None) -> tuple[int, int] | None:
    """The center of the first node whose text starts with [text] (or whose class is [cls]) in a uiautomator dump."""
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        if (text is not None and node.get("text", "").startswith(text)) or (cls is not None and node.get("class") == cls):
            match = BOUNDS.fullmatch(node.get("bounds", ""))
            if match:
                left, top, right, bottom = map(int, match.groups())
                return (left + right) // 2, (top + bottom) // 2
    return None


def fatal_lines(crash: str, app: str) -> list[str]:
    """Allowlisted fatal signatures, truncated and bounded: from the crash buffer only when a crash there names this
    package (another process's crash isn't ours), and from the app process's own log."""
    found = [line for line in crash.splitlines() if FATAL.search(line)] if PACKAGE in crash else []
    found += [line for line in app.splitlines() if FATAL.search(line)]
    return [line[:LINE_CHARS] for line in found][:MAX_LINES]


STEPS = (("installed", "install", "the signed APK didn't install"),
         ("welcome", "welcome", "Welcome didn't appear"),
         ("stepTwo", "step 2", "\"Where do you chat?\" didn't appear after Get started"),
         ("httpsLine", "address", "the HTTPS line didn't appear after Continue"),
         ("alive", "process", "the app process wasn't running at the end"))


def summary(record: dict) -> str:
    """One line (TL): pass or fail, the step reached and the reason."""
    if record.get("error"):
        return f"FAILED before launch: {record['error']}" if not record.get("installed") else f"FAILED: {record['error']}"
    for key, step, reason in STEPS:
        if not record.get(key):
            return f"FAILED at {step}: {reason}"
    if record.get("fatal"):
        return f"FAILED at log: {len(record['fatal'])} fatal line(s) in the app's log"
    return "passed: Welcome, step 2 and the HTTPS line, offline, with no fatal log"


def dump() -> str:
    adb("shell", "uiautomator", "dump", "/sdcard/ccm-r8-smoke.xml", timeout=30)
    return adb("shell", "cat", "/sdcard/ccm-r8-smoke.xml", timeout=30).stdout


def wait_for(text: str | None = None, cls: str | None = None) -> tuple[int, int] | None:
    deadline = time.monotonic() + WAIT_SECONDS
    while time.monotonic() < deadline:
        point = center(dump(), text, cls)
        if point is not None:
            return point
        time.sleep(POLL_SECONDS)
    return None


def sign(unsigned: Path, work: Path) -> Path:
    aligned, signed, keystore = work / "aligned.apk", work / "ccm-r8-smoke.apk", work / "throwaway.jks"
    steps = [
        [build_tool("zipalign"), "-p", "-f", "4", str(unsigned), str(aligned)],
        ["keytool", "-genkeypair", "-keystore", str(keystore), "-storepass", "throwaway", "-keypass", "throwaway",
         "-alias", "smoke", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=CCM R8 smoke"],
        [build_tool("apksigner"), "sign", "--ks", str(keystore), "--ks-pass", "pass:throwaway", "--out", str(signed),
         str(aligned)],
    ]
    for step in steps:
        result = run(step, timeout=120)
        if result.returncode != 0:
            raise RuntimeError(f"Signing step failed: {Path(step[0]).name}")
    return signed


def main(argv: list[str]) -> int:
    record = dict(schema=1, installed=False, welcome=False, stepTwo=False, httpsLine=False, alive=False, fatal=[],
                  passed=False)
    try:
        apk = Path(argv[1]) if len(argv) > 1 else ROOT / "app-release-unsigned.apk"
        with tempfile.TemporaryDirectory(prefix="ccm-r8-smoke-") as work:
            signed = sign(apk, Path(work))
            # Every network off before the first launch: nothing can be requested.
            for command in (["cmd", "connectivity", "airplane-mode", "enable"], ["svc", "wifi", "disable"],
                            ["svc", "data", "disable"]):
                adb("shell", *command)
            record["installed"] = adb("install", "-r", str(signed), timeout=180).returncode == 0
        adb("logcat", "-c")
        adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=60)
        welcome = wait_for(text=WELCOME)
        record["welcome"] = welcome is not None
        if welcome is not None:
            adb("shell", "input", "tap", *map(str, welcome))
            field = wait_for(cls="android.widget.EditText")
            record["stepTwo"] = field is not None
            if field is not None:
                adb("shell", "input", "tap", *map(str, field))
                adb("shell", "input", "text", ADDRESS)
                adb("shell", "input", "keyevent", "KEYCODE_BACK")  # Hide the keyboard so Continue is on screen.
                button = wait_for(text=CONTINUE)
                if button is not None:
                    adb("shell", "input", "tap", *map(str, button))
                    record["httpsLine"] = wait_for(text=HTTPS_LINE) is not None
        pid = adb("shell", "pidof", PACKAGE).stdout.strip()
        record["alive"] = pid.isdigit()
        app_log = adb("logcat", "-d", f"--pid={pid}", timeout=60).stdout if pid.isdigit() else ""
        record["fatal"] = fatal_lines(adb("logcat", "-d", "-b", "crash", timeout=60).stdout, app_log)
        record["passed"] = all(record[key] for key in ("installed", "welcome", "stepTwo", "httpsLine", "alive")) \
            and not record["fatal"]
    except Exception as error:  # Recorded, never hidden: the check fails.
        record["error"] = type(error).__name__ + ": " + str(error)[:LINE_CHARS]
    line = summary(record)
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(dict(summary=line, **record), indent=1), encoding="utf-8")
    print(f"R8 smoke check: {line}")
    for line in record["fatal"]:
        print("  " + line)
    return 0 if record["passed"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
