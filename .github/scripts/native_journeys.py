"""Run the unchanged native gate once; retain only filtered failure diagnostics."""

from collections import deque
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "app/build/reports/androidTests/device/ime-lifecycle.txt"
GRADLE = ["./gradlew", ":app:connectedDebugAndroidTest",
          "-Pandroid.testInstrumentationRunnerArguments.expectSoftKeyboard=true", "--max-workers=2"]
LOGCAT = ["adb", "logcat", "-d", "-v", "threadtime", "-s", "ImeTracker:I", "TestRunner:I", "*:S"]

# Do not serialize raw logcat, exception text, editor objects, window titles or arbitrary tails.
# Unknown platform reasons remain omitted, rather than expanding a privacy boundary implicitly.
DETAILS = frozenset("""
ORIGIN_CLIENT ORIGIN_SERVER
PHASE_CLIENT_ALREADY_HIDDEN PHASE_CLIENT_ON_CONTROLS_CHANGED
PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES PHASE_CLIENT_VIEW_SERVED
PHASE_SERVER_SHOULD_HIDE PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT PHASE_WM_GET_CONTROL_WITH_LEASH
SHOW_SOFT_INPUT SHOW_SOFT_INPUT_BY_INSETS_API
HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR HIDE_SOFT_INPUT HIDE_SOFT_INPUT_BY_INSETS_API
HIDE_UNSPECIFIED_WINDOW CONTROLS_CHANGED IME_REQUESTED_CHANGED_LISTENER
""".split())
EVENTS = frozenset("onRequestShow onRequestHide onShown onHidden onCancelled onFailed setFinished".split())
TOKEN = re.compile(r"(?:io\.github\.thiesi\.chitchat\.debug(?:\.test)?|system_server):[0-9a-fA-F]{1,16}:")
METHOD = re.compile(r"[A-Za-z_][A-Za-z_0-9]*\(io\.github\.thiesi\.chitchat\.[A-Za-z_0-9.$]+\)")
DATE = re.compile(r"[0-9]{2}-[0-9]{2}")
TIME = re.compile(r"[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}")


def filtered_trace(raw: str) -> str:
    """Keep the last 2000 allowlisted records; never include a raw fallback line."""
    records: deque[str] = deque(maxlen=2000)
    for line in raw.splitlines():
        fields = line.split()
        if len(fields) < 8 or not DATE.fullmatch(fields[0]) or not TIME.fullmatch(fields[1]):
            continue
        if fields[5] == "ImeTracker:" and TOKEN.fullmatch(fields[6]) and fields[7] in EVENTS:
            safe = fields[:2] + fields[5:8]
            safe.extend(value for value in fields[8:] if value in DETAILS)
            records.append(" ".join(safe))
        elif (fields[5] == "TestRunner:" and fields[6] in {"started:", "finished:", "failed:"}
              and METHOD.fullmatch(fields[7])):
            records.append(" ".join(fields[:2] + fields[5:8]))
    return "\n".join(records) + ("\n" if records else "")


def main() -> int:
    try:
        result = subprocess.run(GRADLE, cwd=ROOT, check=False)
        status = result.returncode
    except OSError:
        print("Native gate could not start.", file=sys.stderr)
        status = 1
    if status == 0:
        return 0
    try:
        capture = subprocess.run(LOGCAT, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                 encoding="utf-8", errors="replace", timeout=15, check=False)
        if capture.returncode != 0:
            raise OSError("diagnostic capture unavailable")
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(filtered_trace(capture.stdout), encoding="utf-8")
        print("Filtered IME lifecycle metadata saved with native test reports.")
    except (OSError, subprocess.TimeoutExpired):
        print("IME diagnostics unavailable; original native gate failure preserved.", file=sys.stderr)
    # Match shell convention for a child terminated by a signal. Never turn a failure green.
    return 128 - status if status < 0 else status


if __name__ == "__main__":
    sys.exit(main())
