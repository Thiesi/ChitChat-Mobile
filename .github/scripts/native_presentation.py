"""Invoke the unchanged native gate once, then collect owned fixture PNGs."""

from pathlib import Path
from itertools import islice
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
NATIVE = [sys.executable, "-B", str(Path(__file__).with_name("native_journeys.py"))]
DESTINATION = ROOT / "app/build/reports/androidTests/completion-presentation"
SOURCE = ROOT / "app/build/outputs/connected_android_test_additional_output"


def main() -> int:
    try:
        status = subprocess.run(NATIVE, cwd=ROOT, check=False).returncode
    except OSError:
        print("Native gate process could not start.", file=sys.stderr)
        status = 1
    try:
        DESTINATION.mkdir(parents=True, exist_ok=True)
        # AGP exports its runner-provided output directory before uninstalling APKs.
        files = list(islice(SOURCE.rglob("ccm-completion-presentation/*.png"), 9))
        if not files or len(files) > 8 or len({p.name for p in files}) != len(files):
            raise ValueError("Missing or ambiguous presentation output")
        for path in files:
            shutil.copyfile(path, DESTINATION / path.name)
        print(f"Presentation captures collected: {len(files)} of 8.")
    except Exception:
        # Optional presentation evidence must never replace the original result.
        # Do not print exception text, invoke another test or collect other-app UI.
        print("Presentation captures unavailable; original native status retained.", file=sys.stderr)
    return 128 - status if status < 0 else status


if __name__ == "__main__":
    sys.exit(main())
