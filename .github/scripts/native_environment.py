"""One suite-level fingerprint/precondition check on the dedicated CI emulator."""

import collections
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

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
# Android's interim home, shown right after boot until the real launcher is ready. It's a healthy boot state, not an
# obstruction: main run 43938459454 recorded it with boot completed and the IME selected, while launcher processes were
# still starting (TL ruling: allowlist this exact component, no settle wait).
BOOT_HOME = "com.android.settings/com.android.settings.FallbackHome"
# A fixed settle after the runner reports boot completed, then the single read as before (TL ruling, #245): three
# boot-state verdicts (two FallbackHome, one null focus) were all read about 2s after boot. A fixed delay, never a poll,
# a re-read or a wait until healthy.
SETTLE_SECONDS = 15
# Evidence only, on the unhealthy path: focus samples for a fixed window after the verdict, so the record shows whether a
# launcher took focus moments later. The verdict, the read and the no-retry rule are unchanged.
EVIDENCE_SECONDS = 10
EVIDENCE_EVERY_SECONDS = 2
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


def keep_boot_window(dump: str | None):
    """Writes the boot buffer's allowlisted markers to the early-logcat file; optional evidence that never fails setup."""
    if dump is None: return
    import early_logcat
    try:
        early_logcat.OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        # Filter first, then keep the last MAX_LINES markers: boot logs verbosely (androidboot.logcat=*:V), so capping raw
        # lines first kept only about half a second of boot (PR #291's first run).
        kept = collections.deque((marker for marker in map(early_logcat.keep, dump.splitlines()) if marker is not None),
                                 maxlen=early_logcat.MAX_LINES)
        with early_logcat.OUTPUT.open("w", encoding="utf-8") as sink:
            window = early_logcat.Window(sink)
            for marker in kept: window.write(marker)
    except OSError:
        pass


def keep_focus_after_verdict(sleep=None):
    """Appends scalar focus samples for EVIDENCE_SECONDS after an unhealthy verdict; never changes the outcome."""
    sleep = sleep or time.sleep
    import early_logcat
    try:
        early_logcat.OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        with early_logcat.OUTPUT.open("a", encoding="utf-8") as sink:
            for elapsed in range(0, EVIDENCE_SECONDS + 1, EVIDENCE_EVERY_SECONDS):
                if elapsed: sleep(EVIDENCE_EVERY_SECONDS)
                focus = focus_class(read(["adb", "shell", "dumpsys", "window"])) or "none"
                sink.write(f"focus {focus} at=+{elapsed}s after verdict\n"); sink.flush()
    except OSError:
        pass


def health(data: dict) -> list[str]:
    """Why the emulator is unusable before instrumentation (owner-approved health check); empty when healthy."""
    reasons = []
    if data.get("bootCompleted") != "1":
        reasons.append("boot_not_completed")
    focus = data.get("currentFocus")
    if focus is None: reasons.append("no_window_focus")
    elif focus == "anr": reasons.append("anr_dialog_has_focus")
    elif focus == "app_error": reasons.append("error_dialog_has_focus")
    elif focus != BOOT_HOME and focus.split("/", 1)[0] not in LAUNCHERS: reasons.append("launcher_not_focused")
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


def main(sleep=None) -> int:
    sleep = sleep or time.sleep  # Resolved at call time, so tests can patch time.sleep.
    api = requested_api()
    sleep(SETTLE_SECONDS)
    # The boot buffer is read first: resizing the ring (-G) discards it on these images, which left run 44000691076's
    # early logcat empty (#245). One allowlisted read, written before the resize, the clear and any verdict.
    keep_boot_window(read(["adb", "logcat", "-d", "-v", "time"]))
    # Enlarging/clearing the ring is suite setup, never a replay of instrumentation. Nothing else reads logcat during
    # it: the early-logcat helper starts only after this check (a concurrent reader made API36's setup fail, PR #282).
    if read(["adb", "logcat", "-G", "16M"]) is None:
        print("Native environment setup failed; instrumentation not started.", file=sys.stderr)
        return 1
    if read(["adb", "logcat", "-c"]) is None:
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
        keep_focus_after_verdict(sleep)  # The workflow stops on this exit, so the early-logcat helper never starts.
        return 1
    if failures:
        print("Native environment precondition failed; instrumentation not started.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
