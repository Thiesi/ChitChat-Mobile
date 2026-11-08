"""Original-result classification, complete-suite and privacy regressions; no native calls."""
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

import api36_observation as observation
import native_environment

IME = ("java.lang.AssertionError: Tap fixture: tag=author_edit_body, IME=0, focusedBefore=false, focused=true, "
       "private-body-canary\n at io.github.thiesi.chitchat.testing.NativeInputKt.tapNativeInput(NativeInput.kt:29)\n"
       "Caused by: androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 5000 ms\n"
       " at io.github.thiesi.chitchat.testing.NativeInputKt.tapNativeInput(NativeInput.kt:20)")


class ObservationTests(unittest.TestCase):
    def fixture(self, failures=(), skipped=0):
        root = Path(self.enterContext(tempfile.TemporaryDirectory(prefix="ccm-api36-unit-")))
        reports = root / "reports"; results = root / "results";results.mkdir()
        (reports / "device").mkdir(parents=True)
        (reports / "connected/debug").mkdir(parents=True)
        environment = dict(emulatorBuild=native_environment.EMULATOR_BUILD, emulatorVersion=native_environment.EMULATOR_VERSION,
            systemImageRevision="7", sdk="36", defaultIme=native_environment.EXPECTED_IME,
            enabledImes=[native_environment.EXPECTED_IME], showImeWithHardKeyboard="1", animations={"window": "0"})
        (reports / "device/environment.json").write_text(json.dumps(dict(environment=environment, failedPreconditions=[])))
        counts = dict(tests=248, failures=len(failures), ignored=skipped)
        (reports / "connected/debug/index.html").write_text("".join(
            f'<div class="infoBox" id="{key}"><div class="counter">{value}</div></div>' for key,value in counts.items()))
        suite = ET.Element("testsuite", tests="248", failures=str(len(failures)), errors="0", skipped=str(skipped))
        for number in range(248):
            case = ET.SubElement(suite, "testcase", classname="io.github.thiesi.chitchat.ExampleTest", name=f"case{number}")
            if number < len(failures):ET.SubElement(case, "failure", type="java.lang.AssertionError").text = failures[number]
            if number >= 248-skipped:ET.SubElement(case, "skipped")
        path=results / "TEST-fixture.xml";ET.ElementTree(suite).write(path,encoding="utf-8")
        return results,reports,path

    def test_complete_pass_requires_original_success(self):
        results,reports,_ = self.fixture()
        self.assertEqual("passed", observation.analyze(results,reports,"success")["disposition"])
        self.assertEqual("incomplete", observation.analyze(results,reports,"failure")["disposition"])
        self.assertEqual("incomplete", observation.analyze(results,reports,"cancelled")["disposition"])

    def test_known_ime_retains_original_failure_and_only_scalar_identity(self):
        results,reports,_ = self.fixture([IME])
        value=observation.analyze(results,reports,"failure")
        self.assertEqual("known_ime",value["disposition"])
        self.assertEqual(248,value["tests"]);self.assertEqual(1,value["failures"])
        self.assertFalse(value["observationPass"]);self.assertEqual("failure",value["originalOutcome"])
        self.assertNotIn("canary",json.dumps(value))

    def test_mixed_or_different_failures_block(self):
        variants=[IME.replace("5000 ms","5001 ms"),IME.replace("IME=0","IME=1"),
                  IME.replace("focused=true","focused=false"),"private-body-canary: upload lost"]
        for text in variants:
            results,reports,_=self.fixture([IME,text])
            with self.subTest(text=text[-20:]):
                self.assertEqual("other_failure",observation.analyze(results,reports,"failure")["disposition"])

    def test_same_case_additional_error_chain_is_not_only_known_ime(self):
        for suffix in ("\nSuppressed: java.lang.IllegalStateException: fixture cleanup failed",
                       "\nCaused by: java.lang.IllegalStateException: additional fixture failure"):
            results,reports,_=self.fixture([IME+suffix])
            with self.subTest(kind=suffix.split(":")[0].strip()):
                self.assertEqual("other_failure",observation.analyze(results,reports,"failure")["disposition"])

    def test_pinned_ddmlib_failure_without_type_uses_explicit_stack_root(self):
        # DDMLib31.13.2 XmlTestRunListener.printFailedTest writes text, no type attribute.
        for stack, expected in ((IME, "known_ime"),
            ("org.junit.runners.model.MultipleFailureException: additional failure\n" + IME, "other_failure"),
            (IME + "\nSuppressed: java.lang.IllegalStateException: cleanup", "other_failure")):
            results,reports,path=self.fixture([stack])
            tree=ET.parse(path)
            tree.getroot().find("testcase/failure").attrib.pop("type")
            tree.write(path,encoding="utf-8")
            with self.subTest(expected=expected):
                self.assertEqual(expected,observation.analyze(results,reports,"failure")["disposition"])

    def test_contradictory_explicit_exception_type_still_blocks(self):
        for declared in ("", "java.lang.IllegalStateException"):
            results,reports,path=self.fixture([IME])
            tree=ET.parse(path); tree.getroot().find("testcase/failure").set("type",declared)
            tree.write(path,encoding="utf-8")
            with self.subTest(declared=declared):
                self.assertEqual("other_failure",observation.analyze(results,reports,"failure")["disposition"])

    def test_skipped_missing_truncated_or_duplicate_cases_never_count_complete(self):
        results,reports,path=self.fixture(skipped=1)
        self.assertEqual("incomplete",observation.analyze(results,reports,"success")["disposition"])
        for content in (b"<testsuite>", b'<!DOCTYPE canary><testsuite tests="0"/>'):
            path.write_bytes(content)
            self.assertEqual("incomplete",observation.analyze(results,reports,"failure")["disposition"])
        results,reports,path=self.fixture([IME])
        duplicate=results/"TEST-duplicate.xml";duplicate.write_bytes(path.read_bytes())
        self.assertEqual("incomplete",observation.analyze(results,reports,"failure")["disposition"])
        path.unlink();duplicate.unlink()
        self.assertEqual("incomplete",observation.analyze(results,reports,"failure")["disposition"])

    def test_wrong_environment_and_mismatched_summary_block(self):
        results,reports,_=self.fixture([IME])
        path=reports/"device/environment.json";value=json.loads(path.read_text());value["environment"]["sdk"]="35";path.write_text(json.dumps(value))
        self.assertEqual("incomplete",observation.analyze(results,reports,"failure")["disposition"])
        results,reports,_=self.fixture([IME])
        index=reports/"connected/debug/index.html";index.write_text(index.read_text().replace('>248<','>247<'))
        self.assertEqual("incomplete",observation.analyze(results,reports,"failure")["disposition"])


if __name__ == "__main__":
    unittest.main()
