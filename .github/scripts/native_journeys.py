"""Run the unchanged native gate once; retain only filtered failure diagnostics."""

from pathlib import Path
import json
from itertools import islice
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from ime_platform_constants import PHASES, REASONS


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "app/build/reports/androidTests/device/ime-lifecycle.txt"
RESULTS = ROOT / "app/build/outputs/androidTest-results/connected"
SOURCES = REPORT.with_name("test-failure-sources.json")
MAX_SOURCE_BYTES = 16 * 1024 * 1024
SOURCE_NAME = re.compile(r"[A-Za-z_][A-Za-z_0-9.$]{0,255}")
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


def failure_sources(pages: list[bytes]) -> dict:
    """Only static test identity/source locations; never retain assertion payloads."""
    if sum(map(len, pages)) > MAX_SOURCE_BYTES or len(pages) > 64:
        raise ValueError("Source summary input bound exceeded")
    cases, observed, omitted = [], 0, 0
    for page in pages:
        if b"<!DOCTYPE" in page: raise ValueError("Unexpected XML declaration")
        for case in ET.fromstring(page).iter("testcase"):
            failures = case.findall("failure") + case.findall("error")
            if not failures: continue
            observed += 1
            cls, name = case.get("classname", ""), case.get("name", "")
            if (len(cases) >= 32 or not SOURCE_NAME.fullmatch(cls) or not SOURCE_NAME.fullmatch(name)
                    or not cls.startswith("io.github.thiesi.chitchat.")):
                omitted += 1
                continue
            source = cls.rsplit(".", 1)[-1].split("$", 1)[0]
            frame = re.compile(r"(?m)^\s*at " + re.escape(cls) +
                r"(?:\$[A-Za-z_0-9$]+)?\.[A-Za-z_0-9$<>]+\((" + re.escape(source) +
                r"\.(?:kt|java)):([1-9][0-9]{0,6})\)\s*$")
            locations = list(dict.fromkeys((file, int(line)) for failure in failures
                for file, line in frame.findall(failure.text or "")))
            cases.append(dict(className=cls, test=name,
                frames=[dict(file=file, line=line) for file, line in locations[:4]],
                framesTruncated=len(locations) > 4))
    return dict(schema=1, failedCasesObserved=observed, omittedCases=omitted, cases=cases)


def save_failure_sources(started_ns: int, finished_ns: int) -> None:
    pages, total = [], 0
    files = list(islice(RESULTS.rglob("TEST-*.xml"), 65))
    if len(files) > 64: raise ValueError("Source summary file bound exceeded")
    for path in sorted(files):
        if path.is_symlink() or not path.is_file(): raise ValueError("Unexpected source input")
        stamp = path.stat()
        # Never attribute a previous invocation's XML after a setup/launch failure.
        if not started_ns <= stamp.st_mtime_ns <= finished_ns: continue
        if len(pages) >= 64 or stamp.st_size > MAX_SOURCE_BYTES - total:
            raise ValueError("Source summary input bound exceeded")
        with path.open("rb") as stream: page = stream.read(MAX_SOURCE_BYTES - total + 1)
        total += len(page)
        if total > MAX_SOURCE_BYTES: raise ValueError("Source summary input grew beyond bound")
        pages.append(page)
    if not pages: raise ValueError("No current-invocation XML available")
    summary = failure_sources(pages)
    summary.update(invocationStartedUnixNs=started_ns, invocationFinishedUnixNs=finished_ns)
    SOURCES.parent.mkdir(parents=True, exist_ok=True)
    SOURCES.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    for case in summary["cases"]:
        for frame in case["frames"]:
            print(f"Native failure source: {case['className']}.{case['test']} ({frame['file']}:{frame['line']})")


def main() -> int:
    try: SOURCES.unlink(missing_ok=True)
    except OSError:
        print("Previous test source summary could not be cleared; verify invocation timestamps.", file=sys.stderr)
    started_ns = time.time_ns()
    status = run_once(GRADLE, REPORT)
    if status != 0:
        try: save_failure_sources(started_ns, time.time_ns())
        except Exception:
            print("Test source summary unavailable; original native failure preserved.", file=sys.stderr)
    return status


if __name__ == "__main__":
    sys.exit(main())
