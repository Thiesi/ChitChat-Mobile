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
    r"(?:storage|offline|tls|incompatible|stopped|refresh|rate|rate-unknown|offline-edge|waiting-room|waiting-dm)|default-details)\.png")
NAV_DESTINATION = ROOT / "app/build/reports/androidTests/nav-presentation"
ONBOARDING_DESTINATION = ROOT / "app/build/reports/androidTests/onboarding-presentation"
CLEANUP_DESTINATION = ROOT / "app/build/reports/androidTests/device/completion-cleanup"
CLEANUP_NAME = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,63}[.][A-Za-z][A-Za-z0-9_]{0,127}[.]txt")
CLEANUP_LINE = re.compile(r"test=([A-Za-z][A-Za-z0-9_]{0,63}[.][A-Za-z][A-Za-z0-9_]{0,127}) "
    r"sample=last_wait_predicate compose=(?:unobserved|-?(?:0|[1-9][0-9]{0,9})) "
    r"native1=(?:unobserved|unknown|hidden|visible) native2=(?:unobserved|unknown|hidden|visible)\n")
CLEANUP_MAX_FILES = 64
CLEANUP_MAX_BYTES = 256
FIND_PEOPLE_DESTINATION = ROOT / "app/build/reports/androidTests/find-people-presentation"
POLICY_DESTINATION = ROOT / "app/build/reports/androidTests/policy-presentation"
POLICY_NAME = re.compile(r"policy-(light|dark)-(default|font200)-(hosted|external|unpublished|httpdialog|loading|failed)[.]png")
RECOVERY_DESTINATION = ROOT / "app/build/reports/androidTests/recovery-presentation"
STEP_UP_DESTINATION = ROOT / "app/build/reports/androidTests/step-up-presentation"
DELETION_DESTINATION = ROOT / "app/build/reports/androidTests/deletion-presentation"
DELETION_NAME = re.compile(r"deletion-(light|dark)-(default|font200)-(row|explanation|explanation-actions|dialog|stepup|unknown|notdeleted|waiting|endedunknown|checkfailed|signedout|scheduled|scheduled-longdate|endedrefusal)[.]png")
STEP_UP_NAME = re.compile(r"stepup-(light|dark)-(default|font200)-(export|replace|create|transfer|recovery)[.]png")
RECOVERY_NAME = re.compile(r"recovery-(light|dark)-(default|font200)-(display(-api32|-reset)?(-end)?|dialog|exists|notset|notice)[.]png")
RESET_DESTINATION = ROOT / "app/build/reports/androidTests/reset-presentation"
RESET_NAME = re.compile(r"reset-(light|dark)-(default|font200)-(signin-forgot|form|mfa|stop|cancelled|passkey|failed|unknown|cancel-unknown)[.]png")
AUTH_DESTINATION = ROOT / "app/build/reports/androidTests/auth-presentation"
AUTH_NAME = re.compile(r"auth-(light|dark)-((default|font200)-(signin|create)-keyboard|font200-create-block)[.]png")
RULES_DESTINATION = ROOT / "app/build/reports/androidTests/rules-presentation"
RULES_NAME = re.compile(r"rules-(light|dark)-((default|font200)-dialog(-expanded)?|font200-banner(-offline)?)[.]png")
GUIDELINES_DESTINATION = ROOT / "app/build/reports/androidTests/guidelines-presentation"
GUIDELINES_NAME = re.compile(r"guidelines-(light|dark)-(default|font200)-((plain|rules|outdated)(-actions)?|create(-dialog)?)[.]png")
FIND_PEOPLE_NAME = re.compile(r"find-people-(light|dark)-(default|font200)-(top|joined|failed|uncertain)[.]png")
ONBOARDING_NAME = re.compile(r"onboarding-(light|dark)-(default|font200)-(welcome|address|foundtop|found|failure)[.]png")
UPDATE_DESTINATION = ROOT / "app/build/reports/androidTests/update-presentation"
UPDATE_NAME = re.compile(r"update-(light|dark)-(default|font200)-(setup|session)(-actions)?[.]png")
A11Y_DESTINATION = ROOT / "app/build/reports/androidTests/a11y-presentation"
FILES_DESTINATION = ROOT / "app/build/reports/androidTests/files-presentation"
FILES_NAME = re.compile(r"files-(?:(?:light|dark)-(?:default|font200)-"
    r"(?:notloaded|loading|failed|nomemory|toolarge|gone|notallowed|offline|normal|panorama|panorama-show|panorama-retry|tall|small|"
    r"nodims|gif|"
    r"card-short|card-long|card-apk|reply|dm-in-short|dm-out-long|preview-sheet)|"
    r"light-(?:default|font200)-(?:viewer-(?:preview|loading|original|failed|nomemory|toolarge-preview|toolarge|gif|gif-offline|"
    r"caption|caption-open|zoomed|longsender|gif-api27|offline|waiting)|frame-gif|frame-gif-api27)|"
    r"light-font200-viewer-caption-open-landscape|dark-default-viewer-preview|"
    r"black-default-(?:normal|notloaded))[.]png")
A11Y_NAME = re.compile(r"a11y-(light|dark)-(default|font200)-(signin|you|dialog)[.]png")
YOU_DESTINATION = ROOT / "app/build/reports/androidTests/you-presentation"
YOU_NAME = re.compile(r"you-(light|dark)-(default|font200)-(session|about|header|rootbar|pushedbar)[.]png")
NAV_NAME = re.compile(r"nav-((light|dark)-(default|font200)|black-default)\.png")
DIALOG_DESTINATION = ROOT / "app/build/reports/androidTests/dialog-presentation"
DIALOG_NAME = re.compile(r"dialog-(light|dark)-(default|font200)-"
    r"(push-explain|push-choose|push-google|push-none|push-phone-off|push-remove|"
    r"forget-server|dm-discard|dm-block|rooms-leave)\.png")
TRANSFER_DESTINATION = ROOT / "app/build/reports/androidTests/transfer-presentation"
TRANSFER_NAME = re.compile(r"transfer-(light|dark)-(default|font200)-(row|none|failed|picker|confirm|success)\.png")
SEARCH_DESTINATION = ROOT / "app/build/reports/androidTests/search-presentation"
SEARCH_NAME = re.compile(r"search-(light|dark)-(default|font200)-results\.png")
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
    files = list(islice(SOURCE.rglob("ccm-status-presentation/*"), 47))
    if (not files or len(files) > 46 or len({p.name for p in files}) != len(files)
        or any(STATUS_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous status presentation output")
    STATUS_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, STATUS_DESTINATION / path.name)
    print(f"Status presentation captures collected: {len(files)} of 46.")


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


def collect_search():
    # Two presentation cases (light, dark): highlighted result rows at default and 2.0 font each.
    files = list(islice(SOURCE.rglob("ccm-search-presentation/*"), 5))
    if (not files or len(files) > 4 or len({p.name for p in files}) != len(files)
        or any(SEARCH_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous search presentation output")
    SEARCH_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, SEARCH_DESTINATION / path.name)
    print(f"Search presentation captures collected: {len(files)} of 4.")


def collect_you():
    # The You hub: both top bars, the header, the session group and About and privacy, default and 2.0, light and dark.
    files = list(islice(SOURCE.rglob("ccm-you-presentation/*"), 21))
    if (not files or len(files) > 20 or len({p.name for p in files}) != len(files)
        or any(YOU_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous You presentation output")
    YOU_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, YOU_DESTINATION / path.name)
    print(f"You presentation captures collected: {len(files)} of 20.")


def collect_a11y():
    # P-221: Sign in footer, the You row and the choice dialog, default and 2.0 font, light and dark.
    files = list(islice(SOURCE.rglob("ccm-a11y-presentation/*"), 13))
    if (not files or len(files) > 12 or len({p.name for p in files}) != len(files)
        or any(A11Y_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous accessibility feedback presentation output")
    A11Y_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, A11Y_DESTINATION / path.name)
    print(f"Accessibility feedback presentation captures collected: {len(files)} of 12.")


def collect_update():
    # "This server needs an update": setup and revalidation, default and 2.0 font, light and dark.
    files = list(islice(SOURCE.rglob("ccm-update-presentation/*"), 17))
    if (not files or len(files) > 16 or len({p.name for p in files}) != len(files)
        or any(UPDATE_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous server update presentation output")
    UPDATE_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, UPDATE_DESTINATION / path.name)
    print(f"Server update presentation captures collected: {len(files)} of 16.")


def collect_cleanup_samples():
    # The Completion cleanup's failure-only sample (completion-ime-cleanup-observations.md): exactly one allowlisted line
    # per file, named for its test; anything foreign, malformed, oversized or excess rejects the whole set before copying.
    files = list(islice(SOURCE.rglob("ccm-completion-cleanup/*"), CLEANUP_MAX_FILES + 1))
    if not files:
        return
    if len(files) > CLEANUP_MAX_FILES or len({p.name for p in files}) != len(files):
        raise ValueError("Excess or ambiguous Completion cleanup samples")
    for path in files:
        if CLEANUP_NAME.fullmatch(path.name) is None or path.stat().st_size > CLEANUP_MAX_BYTES:
            raise ValueError("Foreign or oversized Completion cleanup sample")
        match = CLEANUP_LINE.fullmatch(path.read_text(encoding="utf-8"))
        if match is None or f"{match.group(1)}.txt" != path.name:
            raise ValueError("Malformed Completion cleanup sample")
    CLEANUP_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, CLEANUP_DESTINATION / path.name)
    print(f"Completion cleanup samples collected: {len(files)}.")


def collect_policy():
    # Policy documents (O3a2): hosted, external, not published at both sizes; the http dialog, loading and failure at 2.0.
    files = list(islice(SOURCE.rglob("ccm-policy-presentation/*"), 19))
    if (not files or len(files) > 18 or len({p.name for p in files}) != len(files)
        or any(POLICY_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous policy presentation output")
    POLICY_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, POLICY_DESTINATION / path.name)
    print(f"Policy presentation captures collected: {len(files)} of 18.")


def collect_find_people():
    # Find your people (O2b): top, a confirmed row, a definite and an uncertain failure; default and 2.0, light and dark.
    files = list(islice(SOURCE.rglob("ccm-find-people-presentation/*"), 17))
    if (not files or len(files) > 16 or len({p.name for p in files}) != len(files)
        or any(FIND_PEOPLE_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous Find your people presentation output")
    FIND_PEOPLE_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, FIND_PEOPLE_DESTINATION / path.name)
    print(f"Find your people presentation captures collected: {len(files)} of 16.")


def collect_guidelines():
    # Before you chat (O3b2): without and with server rules, the 409 line (each top and scrolled to the actions) and the
    # Create form's consent line; default and 2.0, light and dark; plus the document dialog over it (dark, 2.0).
    files = list(islice(SOURCE.rglob("ccm-guidelines-presentation/*"), 30))
    if (not files or len(files) > 29 or len({p.name for p in files}) != len(files)
        or any(GUIDELINES_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous guidelines presentation output")
    GUIDELINES_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, GUIDELINES_DESTINATION / path.name)
    print(f"Guidelines presentation captures collected: {len(files)} of 29.")


def collect_rules():
    # Rules re-acceptance (O3b3a): the dialog with "What changed" collapsed and expanded (default and 2.0) and the
    # conversation banner at 2.0, alone and under the offline slot notice; light and dark.
    files = list(islice(SOURCE.rglob("ccm-rules-presentation/*"), 13))
    if (not files or len(files) > 12 or len({p.name for p in files}) != len(files)
        or any(RULES_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous rules presentation output")
    RULES_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, RULES_DESTINATION / path.name)
    print(f"Rules presentation captures collected: {len(files)} of 12.")


def collect_auth():
    # #275: Sign in and Create with the keyboard up (default and 2.0) and Create's pinned block at 2.0; light and dark.
    files = list(islice(SOURCE.rglob("ccm-auth-presentation/*"), 11))
    if (not files or len(files) > 10 or len({p.name for p in files}) != len(files)
        or any(AUTH_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous auth presentation output")
    AUTH_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, AUTH_DESTINATION / path.name)
    print(f"Auth presentation captures collected: {len(files)} of 10.")


def collect_recovery():
    # O4a2a: the key display (top and end) and the confirmation dialog at default and 200% (12). O4a2b: the Recovery key
    # screen with a key and not set up (8), and the notice at both scales (4). O4b2: the display's reset variant (2).
    files = list(islice(SOURCE.rglob("ccm-recovery-presentation/*"), 27))
    if (not files or len(files) > 26 or len({p.name for p in files}) != len(files)
        or any(RECOVERY_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous recovery presentation output")
    RECOVERY_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, RECOVERY_DESTINATION / path.name)
    print(f"Recovery presentation captures collected: {len(files)} of 26.")


def collect_step_up():
    # Each step-up caller's own title (export, Replace key, Create key, room transfer) and the recovery-code warning, at
    # default and 200%, light and dark (20).
    files = list(islice(SOURCE.rglob("ccm-step-up-presentation/*"), 21))
    if (not files or len(files) > 20 or len({p.name for p in files}) != len(files)
        or any(STEP_UP_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous step-up presentation output")
    STEP_UP_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, STEP_UP_DESTINATION / path.name)
    print(f"Step-up presentation captures collected: {len(files)} of 20.")


def collect_deletion():
    # D2: the row, the explanation screen, the decision and step-up dialogs, the live Unknown and "wasn't deleted", and the
    # ended-session screens (Waiting, Unknown, a failed Check, signed out, scheduled, the refusal caption), at default and
    # 200%, light and dark (52), plus one long-date scheduled capture (1).
    files = list(islice(SOURCE.rglob("ccm-deletion-presentation/*"), 54))
    if (not files or len(files) > 53 or len({p.name for p in files}) != len(files)
        or any(DELETION_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous deletion presentation output")
    DELETION_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, DELETION_DESTINATION / path.name)
    print(f"Deletion presentation captures collected: {len(files)} of 53.")


def collect_files():
    # Received files F3: every image state, frame geometry (and short frames in interactive states), the GIF badge, file
    # cards, DM bubbles, a reply quoting a photo and the Welcome preview sheet, light and dark at default and 200% (92),
    # plus black for a loaded frame and the raised box (2). F4: the viewer and the GIF frames, light at default and 200%
    # (36), a landscape 200% expanded caption (1) and one dark viewer (1).
    files = list(islice(SOURCE.rglob("ccm-files-presentation/*"), 133))
    if (not files or len(files) > 132 or len({p.name for p in files}) != len(files)
        or any(FILES_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous files presentation output")
    FILES_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, FILES_DESTINATION / path.name)
    print(f"Files presentation captures collected: {len(files)} of 132.")


def collect_reset():
    # O4b2: step 4a with Forgot password?, the reset form, the MFA step and its outcomes; light and dark.
    files = list(islice(SOURCE.rglob("ccm-reset-presentation/*"), 25))
    if (not files or len(files) > 24 or len({p.name for p in files}) != len(files)
        or any(RESET_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous reset presentation output")
    RESET_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, RESET_DESTINATION / path.name)
    print(f"Reset presentation captures collected: {len(files)} of 24.")


def collect_onboarding():
    # Onboarding O2a: Welcome, Where do you chat?, Server found (top and scrolled), default and 2.0 font, light and dark,
    # plus a step 2 failure notice at 2.0.
    files = list(islice(SOURCE.rglob("ccm-onboarding-presentation/*"), 19))
    if (not files or len(files) > 18 or len({p.name for p in files}) != len(files)
        or any(ONBOARDING_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous onboarding presentation output")
    ONBOARDING_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, ONBOARDING_DESTINATION / path.name)
    print(f"Onboarding presentation captures collected: {len(files)} of 18.")


def collect_nav():
    # Navigation shell bottom bar at default and 2.0 font, light and dark.
    files = list(islice(SOURCE.rglob("ccm-nav-presentation/*"), 6))
    if (not files or len(files) > 5 or len({p.name for p in files}) != len(files)
        or any(NAV_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous navigation presentation output")
    NAV_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, NAV_DESTINATION / path.name)
    print(f"Navigation presentation captures collected: {len(files)} of 5.")


def collect_dialogs():
    # P-220 sweep: each converted dialog at default and 2.0 font, light and dark.
    files = list(islice(SOURCE.rglob("ccm-dialog-presentation/*"), 41))
    if (not files or len(files) > 40 or len({p.name for p in files}) != len(files)
        or any(DIALOG_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous dialog presentation output")
    DIALOG_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, DIALOG_DESTINATION / path.name)
    print(f"Dialog presentation captures collected: {len(files)} of 40.")


def collect_transfer():
    # Two presentation cases (light, dark): six P-217 states at default and 2.0 font each.
    files = list(islice(SOURCE.rglob("ccm-transfer-presentation/*"), 25))
    if (not files or len(files) > 24 or len({p.name for p in files}) != len(files)
        or any(TRANSFER_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous transfer presentation output")
    TRANSFER_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, TRANSFER_DESTINATION / path.name)
    print(f"Transfer presentation captures collected: {len(files)} of 24.")


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
    try:
        collect_search()
    except Exception:
        print("Search presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_transfer()
    except Exception:
        print("Transfer presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_dialogs()
    except Exception:
        print("Dialog presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_you()
    except Exception:
        print("You presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_a11y()
    except Exception:
        print("Accessibility feedback presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_update()
    except Exception:
        print("Server update presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_cleanup_samples()
    except Exception:
        print("Completion cleanup samples unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_policy()
    except Exception:
        print("Policy presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_find_people()
    except Exception:
        print("Find your people presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_guidelines()
    except Exception:
        print("Guidelines presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_rules()
    except Exception:
        print("Rules presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_auth()
    except Exception:
        print("Auth presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_recovery()
    except Exception:
        print("Recovery presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_step_up()
    except Exception:
        print("Step-up presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_deletion()
    except Exception:
        print("Deletion presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_reset()
    except Exception:
        print("Reset presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_files()
    except Exception:
        print("Files presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_onboarding()
    except Exception:
        print("Onboarding presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_nav()
    except Exception:
        print("Navigation presentation captures unavailable; original native status retained.", file=sys.stderr)
    return 128 - status if status < 0 else status


if __name__ == "__main__":
    sys.exit(main())
