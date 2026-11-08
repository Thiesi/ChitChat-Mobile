"""One suite-level fingerprint/precondition check on the dedicated CI emulator."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys

from pin_journey_image import EMULATOR_BUILD, EMULATOR_VERSION, IMAGE_REVISION, IMAGE_PINS, requested_api

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "app/build/reports/androidTests/device/environment.json"
EXPECTED_IME = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
COMPONENT = re.compile(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
SCALAR = re.compile(r"[A-Za-z0-9_./:+@-]{1,256}")


def read(command: list[str]) -> str | None:
    try:
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                encoding="utf-8", errors="replace", timeout=5, check=False)
        return result.stdout.strip() if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def scalar(raw: str | None) -> str | None:
    return raw if raw is not None and raw != "null" and SCALAR.fullmatch(raw) else None


def setting(namespace: str, name: str) -> str | None:
    return scalar(read(["adb", "shell", "settings", "get", namespace, name]))


def fingerprint(*, image_api: int = 36) -> dict:
    # Each read happens once. No retry, readiness loop, per-tap use or service mutation.
    selected = setting("secure", "default_input_method")
    selected = selected if selected and COMPONENT.fullmatch(selected) else None
    enabled_raw = read(["adb", "shell", "ime", "list", "-s"])
    enabled = ([line.strip() for line in enabled_raw.splitlines()
                if COMPONENT.fullmatch(line.strip())] if enabled_raw is not None else None)
    version = None
    if selected:
        package = selected.split("/", 1)[0]
        details = read(["adb", "shell", "dumpsys", "package", package]) or ""
        match = re.search(r"(?m)^\s*versionName=([^\s]+)\s*$", details)
        version = scalar(match[1]) if match else None
    config = read(["adb", "shell", "cmd", "activity", "get-config"]) or ""
    keyboard = re.findall(r"(?<=-)(?:nokeys|qwerty|12key)(?=-|\s|$)", config)
    hidden = re.findall(r"(?<=-)(?:keysexposed|keyshidden|keyssoft)(?=-|\s|$)", config)
    emulator = read([str(Path(os.environ.get("ANDROID_HOME", "")) / "emulator/emulator"), "-no-window", "-version"]) or ""
    emulator_version = re.search(r"Android emulator version ([0-9.]+) \(build_id ([0-9]+)\)", emulator)
    revision = None
    source = Path(os.environ.get("ANDROID_HOME", "")) / f"system-images/android-{image_api}/google_apis/x86_64/source.properties"
    try:
        match = re.search(r"(?m)^Pkg\.Revision\s*=\s*([0-9.]+)\s*$", source.read_text())
        revision = match[1] if match else None
    except OSError:
        pass
    return dict(defaultIme=selected, enabledImes=enabled, imeVersionName=version,
                buildFingerprint=scalar(read(["adb", "shell", "getprop", "ro.build.fingerprint"])),
                securityPatch=scalar(read(["adb", "shell", "getprop", "ro.build.version.security_patch"])),
                systemImageRevision=revision,
                sdk=scalar(read(["adb", "shell", "getprop", "ro.build.version.sdk"])),
                emulatorVersion=emulator_version[1] if emulator_version else None,
                emulatorBuild=emulator_version[2] if emulator_version else None,
                showImeWithHardKeyboard=setting("secure", "show_ime_with_hard_keyboard"),
                keyboard=keyboard[0] if len(keyboard) == 1 else None,
                hardKeyboardHidden=hidden[0] if len(hidden) == 1 else None,
                spellCheckerEnabled=setting("secure", "spell_checker_enabled"),
                selectedSpellChecker=setting("secure", "selected_spell_checker"),
                autofillService=setting("secure", "autofill_service"),
                animations={name: setting("global", name) for name in
                            ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")})


def preconditions(data: dict, *, image_revision: str = IMAGE_REVISION, image_api: int | None = None) -> list[str]:
    failures = []
    # The action can continue after a failed pre-launch hook. Recheck both pins here
    # so its error can never lead to instrumentation on an unintended environment.
    if data["emulatorBuild"] != EMULATOR_BUILD or data["emulatorVersion"] != EMULATOR_VERSION:
        failures.append("pinned_emulator_mismatch")
    if data["systemImageRevision"] != image_revision:
        failures.append("pinned_image_revision_mismatch")
    if image_api is not None and data.get("sdk") != str(image_api):
        failures.append("pinned_api_mismatch")
    if data["defaultIme"] != EXPECTED_IME or EXPECTED_IME not in (data["enabledImes"] or []):
        failures.append("expected_ime_not_enabled_and_selected")
    if data["showImeWithHardKeyboard"] != "1":
        failures.append("show_ime_with_hard_keyboard_not_one")
    if any(value not in ("0", "0.0") for value in data["animations"].values()):
        failures.append("animations_not_disabled")
    return failures


def main() -> int:
    api = requested_api()
    # Enlarging/clearing the ring is suite setup, never a replay of instrumentation.
    for command in (["adb", "logcat", "-G", "16M"], ["adb", "logcat", "-c"]):
        if read(command) is None:
            print("Native environment setup failed; instrumentation not started.", file=sys.stderr)
            return 1
    data = fingerprint(image_api=api)
    failures = preconditions(data, image_revision=IMAGE_PINS[api]["revision"], image_api=api)
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(dict(environment=data, failedPreconditions=failures), indent=2) + "\n",
                      encoding="utf-8")
    if failures:
        print("Native environment precondition failed; instrumentation not started.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
