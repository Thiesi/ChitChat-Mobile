"""Run the unchanged native gate once; retain only filtered failure diagnostics."""

from pathlib import Path
import re
import subprocess
import sys

from ime_platform_constants import PHASES, REASONS


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "app/build/reports/androidTests/device/ime-lifecycle.txt"
GRADLE = ["./gradlew", ":app:connectedDebugAndroidTest",
          "-Pandroid.testInstrumentationRunnerArguments.expectSoftKeyboard=true", "--max-workers=2"]
LOGCAT = ["adb", "logcat", "-d", "-v", "threadtime", "-s", "ImeTracker:I", "TestRunner:I", "*:S"]

# Do not serialize raw logcat, exception text, editor objects, window titles or arbitrary tails.
# Unknown platform reasons remain omitted, rather than expanding a privacy boundary implicitly.
DETAILS = PHASES | REASONS | frozenset("ORIGIN_CLIENT ORIGIN_SERVER ORIGIN_IME ORIGIN_WM_SHELL".split())
EVENTS = frozenset("onRequestShow onRequestHide onProgress onShown onHidden onCancelled onFailed setFinished".split())
TOKEN = re.compile(r"(?:io\.github\.thiesi\.chitchat\.debug(?:\.test)?|system_server):[0-9a-fA-F]{1,16}:")
METHOD = re.compile(r"[A-Za-z_][A-Za-z_0-9]*\(io\.github\.thiesi\.chitchat\.[A-Za-z_0-9.$]+\)")
DATE = re.compile(r"[0-9]{2}-[0-9]{2}")
TIME = re.compile(r"[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}")


def selected_records(records: list[tuple[str, str, str | None]]) -> list[str]:
    """Prioritize observed failed-test windows under the unchanged2000-record cap."""
    windows = []
    method = None
    start = 0
    failed = False
    for index, (_, event, identity) in enumerate(records):
        if identity is None:
            continue
        if event == "started:":
            if failed: windows.append((start, index - 1))
            method, start, failed = identity, index, False
        elif event == "failed:":
            if method != identity:
                # Missing start is unknown; do not invent an earlier test boundary.
                if failed: windows.append((start, index - 1))
                method, start = identity, index
            failed = True
        elif event == "finished:" and method == identity:
            if failed: windows.append((start, index))
            method, failed = None, False
    if failed: windows.append((start, len(records) - 1))
    if not windows:
        return [record[0] for record in records[-2000:]]
    protected = set()
    for first, last in windows:
        protected.update(range(first, last + 1))
    excess = max(0, len(records) - 2000)
    outside_progress = [i for i, record in enumerate(records) if i not in protected and record[1] == "onProgress"]
    outside_other = [i for i, record in enumerate(records) if i not in protected and record[1] != "onProgress"]
    # Only if the failed windows themselves exceed the cap, preserve their newest2000
    # records, as the previous tail policy did. Output always retains chronological order.
    drop = set((outside_progress + outside_other + sorted(protected))[:excess])
    return [record[0] for index, record in enumerate(records) if index not in drop]


def filtered_trace(raw: str, *, token_pattern: re.Pattern[str] = TOKEN) -> str:
    """Keep at most2000 allowlisted records; never include a raw fallback line."""
    records: list[tuple[str, str, str | None]] = []
    for line in raw.splitlines():
        fields = line.split()
        if len(fields) < 8 or not DATE.fullmatch(fields[0]) or not TIME.fullmatch(fields[1]):
            continue
        if fields[5] == "ImeTracker:" and token_pattern.fullmatch(fields[6]) and fields[7] in EVENTS:
            safe = fields[:2] + fields[5:8]
            safe.extend(value for value in fields[8:] if value in DETAILS)
            records.append((" ".join(safe), fields[7], None))
        elif (fields[5] == "TestRunner:" and fields[6] in {"started:", "finished:", "failed:"}
              and METHOD.fullmatch(fields[7])):
            records.append((" ".join(fields[:2] + fields[5:8]), fields[6], fields[7]))
    selected = selected_records(records)
    return "\n".join(selected) + ("\n" if selected else "")


def run_once(command: list[str], report: Path) -> int:
    try:
        result = subprocess.run(command, cwd=ROOT, check=False)
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
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(filtered_trace(capture.stdout), encoding="utf-8")
        print("Filtered IME lifecycle metadata saved with native test reports.")
    except (OSError, subprocess.TimeoutExpired):
        print("IME diagnostics unavailable; original native gate failure preserved.", file=sys.stderr)
    # Match shell convention for a child terminated by a signal. Never turn a failure green.
    return 128 - status if status < 0 else status


def main() -> int:
    return run_once(GRADLE, REPORT)


if __name__ == "__main__":
    sys.exit(main())
