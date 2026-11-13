"""Invoke the unchanged native gate once, then collect owned fixture PNGs."""

from pathlib import Path
from itertools import islice
import re
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
NATIVE = [sys.executable, "-B", str(Path(__file__).with_name("native_journeys.py"))]
DESTINATION = ROOT / "app/build/reports/androidTests/completion-presentation"
THEME_DESTINATION = ROOT / "app/build/reports/androidTests/theme-foundation"
SOURCE = ROOT / "app/build/outputs/connected_android_test_additional_output"
PUSH_DESTINATION = ROOT / "app/build/reports/androidTests/push-presentation"
PUSH_NAME = re.compile(r"push-(light|dark)-(off|waiting|on|refused|needs-setup|explain|google|google-instead)\.png")
STATUS_DESTINATION = ROOT / "app/build/reports/androidTests/status-presentation"
STATUS_NAME = re.compile(r"status-(light|dark)-(?:(?:default|font200)-"
    r"(?:storage|offline|tls|incompatible|stopped|refresh|rate|rate-unknown|offline-edge|waiting)|default-details)\.png")
LICENCES_DESTINATION = ROOT / "app/build/reports/androidTests/licences-presentation"
LICENCES_NAME = re.compile(r"licences-(light|dark)-(default|font200)-(list|detail)\.png")
THEME_NAME = re.compile(r"theme-(light|dark|black)-(default|font200)-(?:"
    r"(?:server|auth|home|settings|rooms|room|direct|thread|activity|account|search)\.png|configuration\.json)")


def collect_theme():
    # Six new presentation cases: eleven PNG scenes and one scalar manifest each.
    files = list(islice(SOURCE.rglob("ccm-theme-presentation/*"), 73))
    if (not files or len(files) > 72 or len({p.name for p in files}) != len(files)
        or any(THEME_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous theme presentation output")
    THEME_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, THEME_DESTINATION / path.name)
    print(f"Theme presentation files collected: {len(files)} of 72.")


def collect_push():
    # Two presentation cases (light, dark): six notification-card scenes each.
    files = list(islice(SOURCE.rglob("ccm-push-presentation/*"), 17))
    if (not files or len(files) > 16 or len({p.name for p in files}) != len(files)
        or any(PUSH_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous push presentation output")
    PUSH_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, PUSH_DESTINATION / path.name)
    print(f"Push presentation captures collected: {len(files)} of 16.")


def collect_status():
    # Two presentation cases (light, dark): eight slot rows and the offline live-edge caption at two font
    # sizes, plus the Details placement.
    files = list(islice(SOURCE.rglob("ccm-status-presentation/*"), 43))
    if (not files or len(files) > 42 or len({p.name for p in files}) != len(files)
        or any(STATUS_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous status presentation output")
    STATUS_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, STATUS_DESTINATION / path.name)
    print(f"Status presentation captures collected: {len(files)} of 42.")


def collect_licences():
    # Two presentation cases (light, dark): list and detail at default and 2.0 font each.
    files = list(islice(SOURCE.rglob("ccm-licences-presentation/*"), 9))
    if (not files or len(files) > 8 or len({p.name for p in files}) != len(files)
        or any(LICENCES_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous licences presentation output")
    LICENCES_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, LICENCES_DESTINATION / path.name)
    print(f"Licences presentation captures collected: {len(files)} of 8.")


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
    try:
        collect_theme()
    except Exception:
        print("Theme presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_push()
    except Exception:
        print("Push presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_licences()
    except Exception:
        print("Licences presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_status()
    except Exception:
        print("Status presentation captures unavailable; original native status retained.", file=sys.stderr)
    return 128 - status if status < 0 else status


if __name__ == "__main__":
    sys.exit(main())
