"""Invoke the unchanged native gate once, then collect owned fixture PNGs."""

from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
NATIVE = [sys.executable, "-B", str(Path(__file__).with_name("native_journeys.py"))]
DESTINATION = ROOT / "app/build/reports/androidTests/completion-presentation"
PULL = ["adb", "pull",
        "/sdcard/Android/data/io.github.thiesi.chitchat.debug/files/ccm-completion-presentation/.",
        str(DESTINATION) + "/"]


def main() -> int:
    try:
        status = subprocess.run(NATIVE, cwd=ROOT, check=False).returncode
    except OSError:
        print("Native gate process could not start.", file=sys.stderr)
        status = 1
    try:
        DESTINATION.mkdir(parents=True, exist_ok=True)
        subprocess.run(PULL, cwd=ROOT, check=True, timeout=30)
    except Exception:
        # Optional presentation evidence must never replace the original result.
        # Do not print exception text, invoke another test or collect other-app UI.
        print("Presentation captures unavailable; original native status retained.", file=sys.stderr)
    return 128 - status if status < 0 else status


if __name__ == "__main__":
    sys.exit(main())
