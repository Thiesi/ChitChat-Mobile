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
# Emulator health (owner-approved CI health check): the focused window right after boot must be a launcher. An ANR or
# error dialog, or no focus, means the emulator is unusable for instrumentation. Only a class or component is kept,
# never raw window text.
LAUNCHERS = ("com.google.android.apps.nexuslauncher", "com.android.launcher3")
FOCUS = re.compile(r"mCurrentFocus=Window\{[0-9a-f]+ u\d+ ([^}]*)\}")


def read(command: list[str]) -> str | None:
    try:
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                encoding="utf-8", errors="replace", timeout=5, check=False)
        return result.stdout.strip() if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def scalar(raw: str | None) -> str | None:
    return raw if raw is not None and raw != "null" and SCALAR.fullmatch(raw) else None


def focus_class(dump: str | None) -> str | None:
    """The focused window as a component, "anr", "app_error" or "other"; None when nothing holds focus or it's unreadable."""
    match = FOCUS.search(dump or "")
    if match is None: return None
    title = match[1].strip()
    if title.startswith("Application Not Responding"): return "anr"
    if title.startswith("Application Error"): return "app_error"
    return title if COMPONENT.fullmatch(title) else "other"


def health(data: dict) -> list[str]:
    """Why the emulator is unusable before instrumentation (owner-approved health check); empty when healthy."""
    reasons = []
    if data.get("bootCompleted") != "1":
        reasons.append("boot_not_completed")
    focus = data.get("currentFocus")
    if focus is None: reasons.append("no_window_focus")
    elif focus == "anr": reasons.append("anr_dialog_has_focus")
    elif focus == "app_error": reasons.append("error_dialog_has_focus")
    elif focus.split("/", 1)[0] not in LAUNCHERS: reasons.append("launcher_not_focused")
    return reasons


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
    return dict(bootCompleted=scalar(read(["adb", "shell", "getprop", "sys.boot_completed"])),
                currentFocus=focus_class(read(["adb", "shell", "dumpsys", "window"])),
                defaultIme=selected, enabledImes=enabled, imeVersionName=version,
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
    # An unselected IME right after boot is an emulator state, not a pin mismatch: it counts as unhealthy too.
    unhealthy = health(data) + [f for f in failures if f == "expected_ime_not_enabled_and_selected"]
    classification = "emulator_unhealthy" if unhealthy else "precondition_failed" if failures else "ok"
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(dict(environment=data, failedPreconditions=failures, unhealthy=unhealthy,
                                      classification=classification), indent=2) + "\n", encoding="utf-8")
    if unhealthy:
        # A distinct, fast setup collapse (record it on #245). Never retried, waited out or repaired here.
        print(f"Native emulator unhealthy ({', '.join(unhealthy)}); instrumentation not started.", file=sys.stderr)
        return 1
    if failures:
        print("Native environment precondition failed; instrumentation not started.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
