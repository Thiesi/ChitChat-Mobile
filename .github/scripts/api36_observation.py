"""Classify one original full API36 observation; never invoke or retry a native test."""
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

import native_environment

ROOT = Path(__file__).resolve().parents[2]
REPORTS = ROOT / "app/build/reports/androidTests"
RESULTS = ROOT / "app/build/outputs/androidTest-results/connected"
STATUS = REPORTS / "api36-observation.json"
NAME = re.compile(r"[A-Za-z_][A-Za-z_0-9.$]{0,255}")
SHA = re.compile(r"[0-9a-f]{40}")
COUNTS = re.compile(r'<div class="infoBox" id="(tests|failures|skipped|ignored)">\s*<div class="counter">([0-9]+)</div>')
TAP = re.compile(r"Tap fixture: tag=[A-Za-z_][A-Za-z_0-9]{0,127}, IME=0, focusedBefore=false, focused=true,")
MINIMUM_CASES = 248  # Preserve the existing full suite; additions require no count edit.


def read_bounded(path, maximum):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > maximum:
        raise ValueError("Missing or oversized observation input")
    return path.read_bytes()


def known_ime(failure):
    text = failure.text or ""
    # Pinned DDMLib omits type; require the explicit root stack class in either format.
    return (failure.tag == "failure" and failure.get("type") in (None, "java.lang.AssertionError")
        and text.startswith("java.lang.AssertionError: Tap fixture: ")
        and len(TAP.findall(text)) == 1
        and text.count("Caused by:") == 1 and "Suppressed:" not in text
        and "Caused by: androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 5000 ms" in text
        and "NativeInputKt.tapNativeInput(NativeInput.kt:20)" in text
        and "NativeInputKt.tapNativeInput(NativeInput.kt:29)" in text)


def analyze(results, reports, outcome):
    record = dict(schema=1, disposition="incomplete", originalOutcome=outcome,
                  tests=0, failures=0, skipped=0, failedTests=[], observationPass=False,
                  acceptanceGate=False, retryPerformed=False)
    try:
        environment = json.loads(read_bounded(reports / "device/environment.json", 65536))
        if environment["failedPreconditions"] or native_environment.preconditions(environment["environment"], image_api=36):
            raise ValueError("Wrong observation environment")
        index = read_bounded(reports / "connected/debug/index.html", 4_194_304).decode("utf-8")
        counts = dict((key, int(value)) for key, value in COUNTS.findall(index))
        if "ignored" in counts:
            counts["skipped"] = counts.pop("ignored")
        if set(counts) != {"tests", "failures", "skipped"} or not MINIMUM_CASES <= counts["tests"] <= 4096:
            raise ValueError("Incomplete full-suite summary")
        files = []
        for path in results.rglob("TEST-*.xml"):
            files.append(path)
            if len(files) > 256:
                raise ValueError("Too many XML reports")
        if not files:
            raise ValueError("Missing original XML reports")
        cases, failed, skipped, identities, total_bytes = 0, [], 0, set(), 0
        for path in files:
            data = read_bounded(path, 4_194_304)
            total_bytes += len(data)
            if total_bytes > 16_777_216 or b"<!DOCTYPE" in data or b"<!ENTITY" in data:
                raise ValueError("Unexpected XML report")
            root = ET.fromstring(data)
            suites = [root] if root.tag == "testsuite" else list(root) if root.tag == "testsuites" else []
            if not suites:
                raise ValueError("Missing test suite")
            for suite in suites:
                rows = suite.findall("testcase")
                if suite.tag != "testsuite" or int(suite.get("tests", "-1")) != len(rows):
                    raise ValueError("Incomplete test cases")
                suite_failed, suite_skipped = 0, 0
                for case in rows:
                    identity = (case.get("classname", ""), case.get("name", ""))
                    if not all(NAME.fullmatch(value) for value in identity) or identity in identities:
                        raise ValueError("Ambiguous test identity")
                    identities.add(identity); cases += 1
                    if cases > 4096:
                        raise ValueError("Too many cases")
                    errors = case.findall("failure") + case.findall("error")
                    if len(errors) > 1:
                        raise ValueError("Ambiguous failure")
                    if errors:
                        suite_failed += 1
                        failed.append(dict(className=identity[0], test=identity[1], knownImeSignature=known_ime(errors[0])))
                    if case.find("skipped") is not None:
                        skipped += 1; suite_skipped += 1
                if (int(suite.get("failures", "0")) + int(suite.get("errors", "0")) != suite_failed
                    or int(suite.get("skipped", "0")) != suite_skipped):
                    raise ValueError("Contradictory XML counts")
        if counts != dict(tests=cases, failures=len(failed), skipped=skipped) or skipped:
            raise ValueError("Incomplete or skipped observation")
        record.update(tests=cases, failures=len(failed), skipped=skipped, failedTests=failed)
        if outcome == "success" and not failed:
            record.update(disposition="passed", observationPass=True)
        elif outcome == "failure" and failed:
            record["disposition"] = "known_ime" if all(row["knownImeSignature"] for row in failed) else "other_failure"
        # Success XML after a failed process/boot/cleanup remains incomplete.
    except (OSError, ValueError, KeyError, TypeError, AttributeError, ET.ParseError):
        pass  # Never serialize raw report text or exceptions.
    return record


def main():
    head = os.environ["PR_HEAD_SHA"]
    if not SHA.fullmatch(head):
        raise ValueError("Exact PR source required")
    record = analyze(RESULTS, REPORTS, os.environ.get("OBSERVATION_OUTCOME", "unknown"))
    record["head"] = head
    STATUS.parent.mkdir(parents=True, exist_ok=True)
    STATUS.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        output.write("disposition=" + record["disposition"] + "\n")
    message = (f"API36 observation: **{record['disposition']}**, original emulator/test step "
        f"**{record['originalOutcome']}**, {record['tests']} tests/{record['failures']} failures. "
        "Original results and artifacts are retained; this is not release acceptance.\n")
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
        summary.write(message)
    print(message)


if __name__ == "__main__":
    main()
