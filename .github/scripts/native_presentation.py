"""Invoke the unchanged native gate once, then collect owned fixture PNGs."""

from pathlib import Path
from itertools import islice
import json
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
FORM_DIALOG_DESTINATION = ROOT / "app/build/reports/androidTests/form-dialog-presentation"
FORM_DIALOG_LINE = re.compile(r"case=[a-z0-9-]{1,64}(?: [A-Za-z0-9=.,;:()/ -]{1,1200})?")
FORM_DIALOG_MAX_LINES = 32
FORM_DIALOG_MAX_BYTES = 16384
# #331a: the text-field accessibility node probe (text-field-names.md), one JSON file per API level, fixture strings only.
A11Y_NODES_DESTINATION = ROOT / "app/build/reports/androidTests/a11y-nodes"
A11Y_NODES_NAME = re.compile(r"text-field-nodes-api[0-9]{2}[.]json")
A11Y_NODES_MAX_BYTES = 262144
A11Y_NODES_MAX_ENTRIES = 64
A11Y_NODES_KEYS = {"case", "kind", "state", "className", "text", "hintText", "contentDescription", "labeledBy",
    "showingHintText", "password", "preceding", "stateDescription", "tooltipText", "error", "descendants"}
# #331b step 0: each field's descendant subtree, bounded.
A11Y_NODES_CHILD_KEYS = {"depth", "className", "text", "contentDescription", "important", "visible", "focusable",
    "screenReaderFocusable"}
A11Y_NODES_MAX_CHILDREN = 32
# Fixture strings and the app's own string resources only (typographic quotes, ellipsis, bullet masks); never user text.
A11Y_NODES_TEXT = re.compile(r"[A-Za-z0-9 .,:;!?'()/@*\u2018\u2019\u201c\u201d\u2026\u2022-]{0,160}")
CLEANUP_MAX_BYTES = 256
FIND_PEOPLE_DESTINATION = ROOT / "app/build/reports/androidTests/find-people-presentation"
POLICY_DESTINATION = ROOT / "app/build/reports/androidTests/policy-presentation"
POLICY_NAME = re.compile(r"policy-(light|dark)-(default|font200)-(hosted|external|unpublished|httpdialog|loading|failed)[.]png")
RECOVERY_DESTINATION = ROOT / "app/build/reports/androidTests/recovery-presentation"
STEP_UP_DESTINATION = ROOT / "app/build/reports/androidTests/step-up-presentation"
DELETION_DESTINATION = ROOT / "app/build/reports/androidTests/deletion-presentation"
DELETION_NAME = re.compile(r"deletion-(light|dark)-(default|font200)-(row|explanation|explanation-actions|dialog|stepup|unknown|notdeleted|waiting|endedunknown|checkfailed|signedout|scheduled|scheduled-longdate|endedrefusal)[.]png")
STEP_UP_NAME = re.compile(r"stepup-(?:(light|dark)-(default|font200)-(export|replace|create|transfer|recovery)|"
    r"light-font200-landscape-(top|end))[.]png")
RECOVERY_NAME = re.compile(r"recovery-(light|dark)-(default|font200)-(display(-api32|-reset)?(-end)?|dialog|exists|notset|notice)[.]png")
RESET_DESTINATION = ROOT / "app/build/reports/androidTests/reset-presentation"
REPORT_DESTINATION = ROOT / "app/build/reports/androidTests/report-presentation"
REPORT_NAME = re.compile(r"report-(?:light-(?:default|font200)-(?:actions-room|actions-dm|actions-own|form-room|form-dm|form-photo|"
    r"form-file|form-filled|form-toolong|sending|rate|unknown|unknown-nodetails|sent-sheet-room|sent-sheet-dm|already|refused|"
    r"notreportable|sending-line|review|busy)|light-font200-form-room-end|"
    r"light-font200-diag-landscape-(?:form|form-field|form-end|unknown|stepup)|light-default-form-file-end|"
    r"dark-default-(?:form-room|sent-sheet-room))[.]png")
PERSON_REPORT_DESTINATION = ROOT / "app/build/reports/androidTests/person-report-presentation"
PERSON_REPORT_NAME = re.compile(r"report-person-(?:light-(?:default|font200)-(?:card-entry|card-reported|form-room|form-room-end|"
    r"unknown|didnotarrive|didnotarrive-notice|sheet-room|sent-room|sheet-dm|sent-dm|already-sheet|already|refused|notfound|review|snapshot)|"
    r"light-default-(?:form-global|form-nopicture|sending|rate|failed|picture-gone)|"
    r"light-font200-(?:form-room-landscape|form-room-landscape-end|form-room-keyboard|form-global-end|form-nopicture-end|form-longname|"
    r"card-entry-end|card-reported-end)|"
    r"dark-default-(?:card-entry|card-reported|form-room|form-room-end|unknown|didnotarrive|didnotarrive-notice|sheet-room|sheet-dm))[.]png")
AUTHOR_DESTINATION = ROOT / "app/build/reports/androidTests/author-presentation"
AUTHOR_NAME = re.compile(r"author-edit-(?:room-(?:light|dark)-(?:default|font200)-(?:unknown|reviewed|editing)|"
    r"room-light-(?:default|font200)-first|room-dark-font200-first|room-light-font150-(?:unknown|reviewed)|"
    r"room-light-font200-editing-landscape|dm-light-default-reviewed)[.]png")
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
    r"light-font200-viewer-(?:caption-open|failed)-landscape|light-default-viewer-(?:preview|caption)-landscape|"
    r"light-font200-viewer-chrome-(?:caption-)?landscape(?:-hidden)?|light-default-viewer-(?:caption-hidden|talkback-tap|"
    r"hidden-failed)|"
    r"dark-default-viewer-preview|"
    r"light-(?:default|font200)-transfer-(?:cards|save-progress|share-progress|saved|save-nothing|save-incomplete|"
    r"save-background|no-space|share-gone|actions-photo|actions-file|actions-apk|note|viewer-bar|viewer-offline|"
    r"viewer-progress)|dark-default-transfer-cards|"
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


def collect_form_dialog_metrics():
    # The form-dialog scaffold's measured keyboard geometry (form-dialog-scaffold.md), for the PL's J record: one file
    # of scalar lines, each starting with its case; anything foreign, malformed, oversized or excess rejects it all.
    files = list(islice(SOURCE.rglob("ccm-form-dialog-presentation/*"), 2))
    if not files:
        return
    if len(files) != 1 or files[0].name != "form-dialog-metrics.txt" or files[0].stat().st_size > FORM_DIALOG_MAX_BYTES:
        raise ValueError("Foreign, excess or oversized form-dialog metrics")
    lines = files[0].read_text(encoding="utf-8").splitlines()
    if not lines or len(lines) > FORM_DIALOG_MAX_LINES or any(FORM_DIALOG_LINE.fullmatch(line) is None for line in lines):
        raise ValueError("Malformed form-dialog metrics")
    FORM_DIALOG_DESTINATION.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(files[0], FORM_DIALOG_DESTINATION / files[0].name)
    print(f"Form-dialog metrics collected: {len(lines)} lines.")


def collect_a11y_nodes():
    # #331a: the probe's measured node facts for each text-field pattern. One file, validated whole: a fixed name, a
    # size cap, exact keys, scalar values and fixture-only text. Anything foreign, malformed or excess rejects it all.
    files = list(islice(SOURCE.rglob("ccm-a11y-nodes/*"), 2))
    if not files:
        return
    if len(files) != 1 or A11Y_NODES_NAME.fullmatch(files[0].name) is None or files[0].stat().st_size > A11Y_NODES_MAX_BYTES:
        raise ValueError("Foreign, excess or oversized node probe output")
    data = json.loads(files[0].read_text(encoding="utf-8"))
    entries = data.get("entries") if isinstance(data, dict) else None
    if (not isinstance(data, dict) or set(data) != {"api", "entries"} or not isinstance(data["api"], int)
            or not isinstance(entries, list) or not entries or len(entries) > A11Y_NODES_MAX_ENTRIES):
        raise ValueError("Malformed node probe output")
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != A11Y_NODES_KEYS:
            raise ValueError("Malformed node probe entry")
        for key, value in entry.items():
            if key in ("showingHintText", "password"):
                if not isinstance(value, bool):
                    raise ValueError("Malformed node probe flag")
            elif key == "descendants":
                if not isinstance(value, list) or len(value) > A11Y_NODES_MAX_CHILDREN:
                    raise ValueError("Malformed node probe subtree")
                for child in value:
                    if not isinstance(child, dict) or set(child) != A11Y_NODES_CHILD_KEYS:
                        raise ValueError("Malformed node probe child")
                    for ckey, cvalue in child.items():
                        if ckey == "depth":
                            if not isinstance(cvalue, int) or isinstance(cvalue, bool) or not 1 <= cvalue <= 16:
                                raise ValueError("Malformed node probe depth")
                        elif ckey in ("important", "visible", "focusable", "screenReaderFocusable"):
                            if not isinstance(cvalue, bool):
                                raise ValueError("Malformed node probe child flag")
                        elif cvalue is not None and (not isinstance(cvalue, str) or A11Y_NODES_TEXT.fullmatch(cvalue) is None):
                            raise ValueError("Malformed node probe child text")
            elif value is not None and (not isinstance(value, str) or A11Y_NODES_TEXT.fullmatch(value) is None):
                raise ValueError("Malformed node probe text")
    A11Y_NODES_DESTINATION.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(files[0], A11Y_NODES_DESTINATION / files[0].name)
    print(f"Text-field node probe collected: {len(entries)} entries.")


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
    # default and 200%, light and dark (20), plus the form-dialog scaffold's 200% landscape top and end (2).
    files = list(islice(SOURCE.rglob("ccm-step-up-presentation/*"), 23))
    if (not files or len(files) > 22 or len({p.name for p in files}) != len(files)
        or any(STEP_UP_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous step-up presentation output")
    STEP_UP_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, STEP_UP_DESTINATION / path.name)
    print(f"Step-up presentation captures collected: {len(files)} of 22.")


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
    # (36), landscape: a 200% expanded caption and failed original plus a default preview and caption (4), and one dark
    # viewer (1). F5: Save, Share and Open (cards, notices, actions, the first-use note, the viewer bar), light at default
    # and 200% (32), and one dark card capture (1). F6: the chrome shown and hidden in landscape at 200% (4) and three
    # portrait captures at default (3).
    files = list(islice(SOURCE.rglob("ccm-files-presentation/*"), 176))
    if (not files or len(files) > 175 or len({p.name for p in files}) != len(files)
        or any(FILES_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous files presentation output")
    FILES_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, FILES_DESTINATION / path.name)
    print(f"Files presentation captures collected: {len(files)} of 175.")


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


def collect_report():
    # Reporting R2c: the message report (actions, the form in each state, the stack lines and the follow-up sheet), light
    # at default and 200% (42), the 200% form scrolled to its end (1), dark at default for the form and the sheet (2), and
    # the TL's 200% landscape geometry evidence: the form, the form at its field and the StepUpDialog (3), and the PL's
    # file form scrolled to its attachment line, light default (1).
    files = list(islice(SOURCE.rglob("ccm-report-presentation/*"), 52))
    if (not files or len(files) > 51 or len({p.name for p in files}) != len(files)
        or any(REPORT_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous report presentation output")
    REPORT_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, REPORT_DESTINATION / path.name)
    print(f"Report presentation captures collected: {len(files)} of 51.")


def collect_person_report():
    # Reporting R3c: the person report (PL list of 2026-11-22). The card's entry and caption, the form in a room, global
    # and without a picture (top, end, landscape, keyboard, a long name), its in-form lines, Unknown with Check and its
    # didn't-arrive (and its notice in full), the stack outcomes with their sheets, and the card's row and caption at
    # 200% scrolled to: light at default and 200% (48), dark at default (9).
    files = list(islice(SOURCE.rglob("ccm-person-report-presentation/*"), 58))
    if (not files or len(files) > 57 or len({p.name for p in files}) != len(files)
        or any(PERSON_REPORT_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous person report presentation output")
    PERSON_REPORT_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, PERSON_REPORT_DESTINATION / path.name)
    print(f"Person report presentation captures collected: {len(files)} of 57.")


def collect_author():
    # #70/#284 (PL list and ruling B, 2026-11-23): the author edit dialog in the real app root. Room light and dark at
    # default and 200% (unknown, reviewed, editing: 12), first edits (light default and 200%, dark 200%: 3), light 150%
    # unknown and reviewed (2), the 200% landscape editing (1) and a DM reviewed (1).
    files = list(islice(SOURCE.rglob("ccm-author-presentation/*"), 20))
    if (not files or len(files) > 19 or len({p.name for p in files}) != len(files)
        or any(AUTHOR_NAME.fullmatch(p.name) is None for p in files)):
        raise ValueError("Missing or ambiguous author presentation output")
    AUTHOR_DESTINATION.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, AUTHOR_DESTINATION / path.name)
    print(f"Author presentation captures collected: {len(files)} of 19.")


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
        collect_form_dialog_metrics()
    except Exception:
        print("Form-dialog metrics unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_a11y_nodes()
    except Exception:
        print("Text-field node probe unavailable; original native status retained.", file=sys.stderr)
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
        collect_report()
    except Exception:
        print("Report presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_person_report()
    except Exception:
        print("Person report presentation captures unavailable; original native status retained.", file=sys.stderr)
    try:
        collect_author()
    except Exception:
        print("Author presentation captures unavailable; original native status retained.", file=sys.stderr)
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
