"""Bounded privileged reporting, source binding, idempotency and token isolation."""
import hashlib
import io
import json
import os
import unittest
import urllib.error
from unittest.mock import Mock, patch

import report_api36_observation as reporter

HEAD = "a" * 40
REPO = "Thiesi/ChitChat-Mobile"


def value():
    return dict(schema=1, head=HEAD, disposition="known_ime", originalOutcome="failure", tests=248,
        failures=1, skipped=0, failedTests=[dict(className="io.github.Example", test="editor", knownImeSignature=True)],
        privateBody="private-body-canary")


def run():
    return dict(id=12, event="pull_request", name="Android", path=".github/workflows/android.yml", status="completed",
        repository=dict(full_name=REPO), head_sha=HEAD, pull_requests=[dict(number=13)])


class ReportingTests(unittest.TestCase):
    def test_only_bound_scalars_and_safe_identities_reach_the_comment(self):
        record=reporter.validate_record(value(),HEAD)
        comment=reporter.body(REPO,run(),record)
        self.assertNotIn("canary",json.dumps(record)+comment)
        self.assertIn("known_ime",comment);self.assertIn(HEAD,comment)
        self.assertIn("Original failures remain failed",comment)
        self.assertIn("/actions/runs/12",comment)

    def test_wrong_source_mixed_failures_and_contradictory_passes_rejected(self):
        variants=[]
        for key,item in [("head","b"*40),("tests",True),("failures",249),("disposition","private-body-canary")]:
            data=value();data[key]=item;variants.append(data)
        data=value();data["failedTests"][0]["test"]="@claude private-body-canary";variants.append(data)
        data=value();data["failedTests"][0]["knownImeSignature"]=False;variants.append(data)
        data=value();data["disposition"]="passed";variants.append(data)
        for data in variants:
            with self.assertRaises(ValueError):reporter.validate_record(data,HEAD)

    def test_original_failure_is_posted_once_and_rerun_does_not_duplicate(self):
        record=reporter.validate_record(value(),HEAD)
        with patch.object(reporter,"api",side_effect=[run(),[],dict(id=14)]) as api, \
                patch.object(reporter,"load_record",return_value=record):
            self.assertEqual("failure_reported",reporter.report(REPO,12))
            self.assertEqual("issues/13/comments",api.call_args.args[1])
            self.assertNotIn("canary",str(api.call_args))
        existing=[dict(user=dict(login="github-actions[bot]"),body="<!-- api36-observation: 12 -->")]
        with patch.object(reporter,"api",side_effect=[run(),existing]) as api, \
                patch.object(reporter,"load_record",return_value=record):
            self.assertEqual("already_reported",reporter.report(REPO,12))
            self.assertEqual(2,api.call_count)

    def test_missing_artifact_reports_incomplete_and_pass_needs_no_comment(self):
        with patch.object(reporter,"api",side_effect=[run(),[],dict(id=14)]) as api, \
                patch.object(reporter,"load_record",side_effect=ValueError("private-body-canary")):
            self.assertEqual("failure_reported",reporter.report(REPO,12))
            comment=api.call_args.args[2]["body"]
            self.assertIn("incomplete",comment);self.assertNotIn("canary",comment)
        good=value();good.update(disposition="passed",originalOutcome="success",failures=0,failedTests=[])
        with patch.object(reporter,"api",return_value=run()) as api, \
                patch.object(reporter,"load_record",return_value=reporter.validate_record(good,HEAD)):
            self.assertEqual("passed_no_comment",reporter.report(REPO,12));api.assert_called_once()

    def test_wrong_workflow_or_ambiguous_pr_never_posts(self):
        for field,item in [("path",".github/workflows/other.yml"),("status","in_progress"),("pull_requests",[])]:
            data=run();data[field]=item
            with patch.object(reporter,"api",return_value=data) as api, self.assertRaises(ValueError):
                reporter.report(REPO,12)
            api.assert_called_once()

    def test_artifact_redirect_uses_no_token_on_blob_request(self):
        payload=b"fixture-zip"
        redirect=urllib.error.HTTPError("https://api.github.com/",302,"",{"Location":"https://fixture.blob.core.windows.net/artifact?temporary=canary"},None)
        first=Mock();first.open.side_effect=redirect
        response=Mock();response.read.return_value=payload;response.__enter__=Mock(return_value=response);response.__exit__=Mock(return_value=False)
        second=Mock();second.open.return_value=response
        metadata=dict(id=3,size_in_bytes=len(payload),expired=False,digest="sha256:"+hashlib.sha256(payload).hexdigest())
        with patch.dict(os.environ,{"GITHUB_TOKEN":"private-token-canary"}), \
                patch.object(reporter.urllib.request,"build_opener",side_effect=[first,second]):
            self.assertEqual(payload,reporter.artifact_bytes(REPO,metadata))
        self.assertIn("Authorization",dict(first.open.call_args.args[0].header_items()))
        self.assertNotIn("Authorization",dict(second.open.call_args.args[0].header_items()))

    def test_redirect_and_size_boundaries_reject_untrusted_targets(self):
        for target in ("http://fixture.blob.core.windows.net/x","https://attacker.invalid/x","https://user:secret@fixture.blob.core.windows.net/x"):
            opener=Mock();opener.open.side_effect=urllib.error.HTTPError("https://api.github.com/",302,"",{"Location":target},None)
            with patch.dict(os.environ,{"GITHUB_TOKEN":"private-token-canary"}), \
                    patch.object(reporter.urllib.request,"build_opener",return_value=opener), self.assertRaises(ValueError):
                reporter.artifact_bytes(REPO,dict(id=3,size_in_bytes=1,expired=False))
        with self.assertRaises(ValueError):reporter.bounded(io.BytesIO(b"too large"),2)


if __name__ == "__main__":
    unittest.main()
