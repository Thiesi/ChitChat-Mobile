"""Run the unchanged native gate once; retain only filtered failure diagnostics."""

from collections import deque
from pathlib import Path
import re
import subprocess
import sys
import threading


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "app/build/reports/androidTests/device/ime-lifecycle.txt"
GRADLE = ["./gradlew", ":app:connectedDebugAndroidTest",
          "-Pandroid.testInstrumentationRunnerArguments.expectSoftKeyboard=true", "--max-workers=2"]
DEVICE_TIME = ["adb", "shell", "date", "+%Y-%m-%dT%H:%M:%S.%3N"]
LOGCAT = ["adb", "logcat", "-v", "threadtime", "-s", "ImeTracker:I", "TestRunner:I", "*:S"]
MAX_LINE = 4096
BEFORE_FAILURE = 2000
AFTER_FAILURE = 200

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
        if len(line) > MAX_LINE:
            continue
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


class TraceWindow:
    """Freeze the first failure's context; later tests cannot evict that evidence."""

    def __init__(self):
        self.recent = deque(maxlen=BEFORE_FAILURE)
        self.failure = None
        self.after = 0
        self.seen = 0

    def accept(self, safe: str):
        self.seen += 1
        if self.failure is None:
            self.recent.append(safe)
            if " TestRunner: failed: " in safe:
                self.failure = list(self.recent)
        elif self.after < AFTER_FAILURE:
            self.failure.append(safe)
            self.after += 1

    def text(self):
        return "".join(self.failure if self.failure is not None else self.recent)


class TraceCollector:
    """Host-side observation only. No raw logcat file, input interception or readiness wait."""

    def __init__(self):
        self.window = TraceWindow()
        self.process = None
        self.thread = None
        self.stopping = threading.Event()
        self.lock = threading.Lock()
        self.errors = []
        self.long_lines = 0

    def start(self):
        try:
            # Use device time rather than a stale last-record tail or a possibly different host
            # clock. This read precedes the build, not an input tap/readiness predicate.
            clock = subprocess.run(DEVICE_TIME, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   text=True, encoding="utf-8", timeout=5, check=False)
            timestamp = clock.stdout.strip()
            if clock.returncode or not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}", timestamp):
                raise OSError()
            self.process = subprocess.Popen(LOGCAT + ["-T", timestamp.replace("T", " ")],
                                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            self.thread = threading.Thread(target=self._read, daemon=True)
            self.thread.start()
        except (OSError, RuntimeError, subprocess.TimeoutExpired):
            self.errors.append("start_failed")

    def _read(self):
        dropping = False
        try:
            while chunk := self.process.stdout.readline(MAX_LINE + 1):
                ended = chunk.endswith(b"\n")
                # Discard the WHOLE overlong record, never parse its arbitrary suffix.
                if dropping or len(chunk) > MAX_LINE:
                    if not dropping:
                        self.long_lines += 1
                    dropping = not ended
                    continue
                safe = filtered_trace(chunk.decode("utf-8", errors="replace"))
                if safe:
                    with self.lock:
                        self.window.accept(safe)
            if not self.stopping.is_set():
                self.errors.append("stream_ended_early")
        except (OSError, ValueError):
            if not self.stopping.is_set():
                self.errors.append("read_failed")

    def stop(self):
        self.stopping.set()
        if self.process is not None:
            try:
                if self.process.poll() is None:
                    self.process.terminate()
                self.process.wait(timeout=3)
            except (OSError, subprocess.TimeoutExpired):
                self.errors.append("terminate_failed")
                try:
                    self.process.kill()
                    self.process.wait(timeout=3)
                except (OSError, subprocess.TimeoutExpired):
                    self.errors.append("kill_failed")
        if self.thread is not None:
            self.thread.join(timeout=2)
            if self.thread.is_alive():
                self.errors.append("reader_not_stopped")
            elif self.process.stdout is not None:
                self.process.stdout.close()
        elif self.process is not None and self.process.stdout is not None:
            self.process.stdout.close()

    def save(self):
        # Fixed diagnostics only: never serialize exceptions, commands, raw lines or editor data.
        with self.lock:
            text = self.window.text()
            metadata = (f"first_failure_observed={self.window.failure is not None}\n"
                        f"safe_records_seen={self.window.seen}\n"
                        f"safe_records_kept={len(text.splitlines())}\n"
                        f"overlong_records_discarded={self.long_lines}\n"
                        f"errors={','.join(self.errors) or 'none'}\n")
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(text, encoding="utf-8")
        REPORT.with_suffix(".status.txt").write_text(metadata, encoding="utf-8")


def main() -> int:
    collector = TraceCollector()
    try:
        collector.start()
    except Exception:
        print("IME collector could not start; native gate will still run once.", file=sys.stderr)
    try:
        try:
            result = subprocess.run(GRADLE, cwd=ROOT, check=False)
            status = result.returncode
        except OSError:
            print("Native gate could not start.", file=sys.stderr)
            status = 1
    finally:
        try:
            collector.stop()
        except Exception:
            print("IME collector cleanup unavailable; native gate result unchanged.", file=sys.stderr)
        try:
            collector.save()
            print("Bounded filtered IME lifecycle metadata saved; native gate result unchanged.")
        except Exception:
            print("IME diagnostics unavailable; native gate result unchanged.", file=sys.stderr)
    # Match shell convention for a child terminated by a signal. Never turn a failure green.
    return 128 - status if status < 0 else status


if __name__ == "__main__":
    sys.exit(main())
