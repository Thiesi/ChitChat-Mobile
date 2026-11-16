"""A bounded, allowlisted early logcat window for the native CI jobs (owner-approved CI health check).

The environment check writes the boot buffer's markers first (one dump before its clear); this helper starts in the
background right after that check and appends through the first few instrumentation tests, so a cancelled or timed-out job still leaves evidence of what took window focus. Only allowlisted lines are kept,
each truncated, with no message text, credentials or test content. Focus is sampled as a component or dialog class
only. Every line is flushed at once, so the file survives cancellation. It never retries, waits for health, dismisses
dialogs or touches apps.
"""

from pathlib import Path
import re
import subprocess
import sys
import threading
import time

import native_environment as env

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "app/build/reports/androidTests/device/early-logcat.txt"
MAX_LINES = 2000
MAX_TESTS = 5
MAX_SECONDS = 15 * 60
FOCUS_EVERY_SECONDS = 5
LINE_CHARS = 240
# Tags and markers only: ANR and crash markers, process starts and deaths, focus or activity changes, and test
# lifecycle. Lines are kept by these patterns alone; everything else is dropped.
ALLOW = re.compile(
    r"(TestRunner(?:\(\s*\d+\))?: (started|finished|failed): [A-Za-z0-9_$.()]+"
    r"|ANR in [A-Za-z0-9_.:]+|Application Not Responding|am_anr|FATAL EXCEPTION|Force finishing activity"
    r"|Start proc \d+:[A-Za-z0-9_.:]+|Process [A-Za-z0-9_.:]+ \(pid \d+\) has died"
    r"|Displayed [A-Za-z0-9_.$/]+|wm_focus_changed|input_focus|Input dispatching timed out)")
STARTED = re.compile(r"TestRunner: started: ")


def keep(line: str) -> str | None:
    """The allowlisted marker of a logcat line, truncated, or None to drop it."""
    match = ALLOW.search(line)
    if match is None: return None
    stamp = line[:18] if re.match(r"\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}", line) else ""
    marker = re.sub(r"^TestRunner\(\s*\d+\):", "TestRunner:", match[0])  # The pid isn't evidence.
    return f"{stamp} {marker}"[:LINE_CHARS].strip()


class Window:
    """Writes kept lines and focus samples until the first few tests have started or a bound is reached."""

    def __init__(self, sink, clock=time.monotonic):
        self.sink, self.clock, self.start = sink, clock, clock()
        self.lines = 0; self.tests = 0; self.lock = threading.Lock()

    def done(self) -> bool:
        return self.lines >= MAX_LINES or self.tests > MAX_TESTS or self.clock() - self.start > MAX_SECONDS

    def write(self, text: str):
        with self.lock:
            if self.done(): return
            self.sink.write(text + "\n"); self.sink.flush(); self.lines += 1

    def logcat(self, line: str):
        kept = keep(line)
        if kept is None: return
        if STARTED.search(kept): self.tests += 1
        self.write(kept)

    def focus(self, dump: str | None):
        self.write(f"focus {env.focus_class(dump) or 'none'} at={round(self.clock() - self.start)}s")


def main() -> int:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("a", encoding="utf-8") as sink:  # After the environment check's boot-window lines.
        window = Window(sink)
        try:
            process = subprocess.Popen(["adb", "logcat", "-v", "time"], stdout=subprocess.PIPE,
                                       stderr=subprocess.DEVNULL, encoding="utf-8", errors="replace")
        except OSError:
            return 0

        def sample():
            while not window.done() and process.poll() is None:
                window.focus(env.read(["adb", "shell", "dumpsys", "window"]))
                time.sleep(FOCUS_EVERY_SECONDS)

        threading.Thread(target=sample, daemon=True).start()
        try:
            for line in process.stdout:
                window.logcat(line)
                if window.done(): break
        finally:
            process.terminate()
    return 0


if __name__ == "__main__":
    sys.exit(main())
