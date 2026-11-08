"""Default-branch-only reporting of bounded API36 results; no checkout or test replay."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from api36_observation import NAME, SHA

MAXIMUM = 131072
ARTIFACT = "api36-observation-status"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, target):
        return None


def bounded(response, maximum):
    value = response.read(maximum + 1)
    if len(value) > maximum:
        raise ValueError("Oversized GitHub response")
    return value


def request(repo, route, body=None):
    data = json.dumps(body).encode() if body is not None else None
    return urllib.request.Request("https://api.github.com/repos/" + repo + "/" + route,
        data=data, headers={"Authorization": "Bearer " + os.environ["GITHUB_TOKEN"],
            "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json"})


def api(repo, route, body=None):
    with urllib.request.urlopen(request(repo, route, body), timeout=30) as response:
        return json.loads(bounded(response, 2_097_152))


def artifact_bytes(repo, artifact):
    size = artifact["size_in_bytes"]
    if type(size) is not int or not 0 < size <= MAXIMUM or artifact.get("expired"):
        raise ValueError("Unavailable observation artifact")
    # GitHub redirects artifact ZIPs. Do not forward the write token to blob storage.
    try:
        response = urllib.request.build_opener(NoRedirect()).open(
            request(repo, "actions/artifacts/" + str(int(artifact["id"])) + "/zip"), timeout=30)
    except urllib.error.HTTPError as redirect:
        if redirect.code != 302:
            raise
        try:
            target = redirect.headers["Location"]
        finally:
            redirect.close()
        url = urllib.parse.urlsplit(target)
        if (url.scheme != "https" or not url.hostname or url.username or url.password
            or not url.hostname.endswith((".blob.core.windows.net", ".githubusercontent.com", ".actions.githubusercontent.com"))):
            raise ValueError("Unexpected artifact host")
        response = urllib.request.build_opener(NoRedirect()).open(urllib.request.Request(target), timeout=30)
    with response:
        data = bounded(response, size)
    digest = artifact.get("digest", "")
    if len(data) != size or digest != "sha256:" + hashlib.sha256(data).hexdigest():
        raise ValueError("Artifact verification failed")
    return data


def validate_record(value, head):
    if not isinstance(value, dict) or value.get("schema") != 1 or value.get("head") != head:
        raise ValueError("Wrong observation source")
    disposition = value.get("disposition")
    if disposition not in {"passed", "known_ime", "other_failure", "incomplete"}:
        raise ValueError("Unknown disposition")
    counts = {key: value.get(key) for key in ("tests", "failures", "skipped")}
    if not all(type(n) is int and 0 <= n <= 4096 for n in counts.values()):
        raise ValueError("Invalid counts")
    if counts["failures"] > counts["tests"] or counts["skipped"] > counts["tests"]:
        raise ValueError("Contradictory counts")
    failed = value.get("failedTests")
    if not isinstance(failed, list) or len(failed) != counts["failures"]:
        raise ValueError("Incomplete failures")
    safe = []
    for row in failed:
        if (not isinstance(row, dict) or not all(isinstance(row.get(k), str) and NAME.fullmatch(row[k]) for k in ("className", "test"))
            or type(row.get("knownImeSignature")) is not bool):
            raise ValueError("Invalid failure identity")
        safe.append({k: row[k] for k in ("className", "test", "knownImeSignature")})
    outcome = value.get("originalOutcome")
    if outcome not in {"success", "failure", "cancelled", "skipped", "unknown"}:
        raise ValueError("Unknown original result")
    if disposition == "passed" and (outcome != "success" or counts["failures"] or counts["skipped"] or counts["tests"] < 248):
        raise ValueError("Contradictory pass")
    if disposition in {"known_ime", "other_failure"} and (outcome != "failure" or not safe or counts["skipped"]):
        raise ValueError("Contradictory failure")
    if disposition == "known_ime" and not all(row["knownImeSignature"] for row in safe):
        raise ValueError("Mixed failures")
    return dict(head=head, disposition=disposition, originalOutcome=outcome, **counts, failedTests=safe)


def load_record(repo, run):
    artifacts = api(repo, "actions/runs/" + str(run["id"]) + "/artifacts?name=" + ARTIFACT)["artifacts"]
    if len(artifacts) != 1 or artifacts[0]["name"] != ARTIFACT:
        raise ValueError("Missing or ambiguous status")
    with zipfile.ZipFile(io.BytesIO(artifact_bytes(repo, artifacts[0]))) as archive:
        members = archive.infolist()
        if len(members) != 1 or members[0].filename != "api36-observation.json" or members[0].file_size > MAXIMUM:
            raise ValueError("Unexpected status archive")
        return validate_record(json.loads(archive.read(members[0])), run["head_sha"])


def body(repo, run, record):
    marker = "<!-- api36-observation: " + str(run["id"]) + " -->"
    lines = [marker, "**API36 observation: " + record["disposition"] + "** for `" + record["head"] + "`.",
        "", f"Original emulator/test step: **{record['originalOutcome']}**; {record['tests']} tests, "
        f"{record['failures']} failures, {record['skipped']} skipped. "
        f"[Original run and retained reports](https://github.com/{repo}/actions/runs/{run['id']}).", ""]
    for row in record["failedTests"][:5]:
        lines.append("- `" + row["className"] + "." + row["test"] + "`: " +
            ("known strict IME signature" if row["knownImeSignature"] else "other failure; delivery blocked"))
    if len(record["failedTests"]) > 5:
        lines.append("- Remaining failures are in the retained reports.")
    lines += ["", "The observation is separate from the API35 merge gate. Original failures remain failed; no test was retried. "
        "Known-signature classification is not a root-cause or app-correctness proof: any demonstrated API36 product defect still blocks. "
        "Other or incomplete failures block via api36-product-defects. API36/current-Android release acceptance remains required."]
    return "\n".join(lines)


def report(repo, run_id):
    run = api(repo, "actions/runs/" + str(run_id))
    if run["event"] != "pull_request":
        return "not_a_pr"
    if (run["id"] != run_id or run["name"] != "Android" or run["path"] != ".github/workflows/android.yml"
        or run["status"] != "completed" or run["repository"]["full_name"] != repo or not SHA.fullmatch(run["head_sha"])):
        raise ValueError("Wrong source workflow")
    prs = run["pull_requests"]
    if len(prs) != 1 or type(prs[0]["number"]) is not int or prs[0]["number"] <= 0:
        raise ValueError("Ambiguous original PR")
    number = prs[0]["number"]
    try:
        record = load_record(repo, run)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile):
        record = dict(head=run["head_sha"], disposition="incomplete", originalOutcome="unknown", tests=0,
                      failures=0, skipped=0, failedTests=[])
    if record["disposition"] == "passed":
        return "passed_no_comment"
    comment = body(repo, run, record)
    marker = comment.splitlines()[0]
    # Bound history and make manual reporting of the same run idempotent.
    for page in range(1, 21):
        comments = api(repo, f"issues/{number}/comments?per_page=100&page={page}")
        if any(c["user"]["login"] == "github-actions[bot]" and marker in c["body"] for c in comments):
            return "already_reported"
        if len(comments) < 100:
            break
    else:
        raise ValueError("Comment history exceeds reporting bound")
    api(repo, "issues/" + str(number) + "/comments", dict(body=comment))
    return "failure_reported"


def main():
    repo = os.environ["GITHUB_REPOSITORY"];run = os.environ["OBSERVATION_RUN_ID"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or not re.fullmatch(r"[1-9][0-9]{0,19}", run):
        raise ValueError("Invalid reporting target")
    result = report(repo, int(run))
    print("API36 reporting: " + result)  # No response bodies, secrets or raw exceptions.
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
        summary.write("API36 reporting: " + result + "\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("API36 reporting unavailable: " + type(error).__name__)
        raise SystemExit(1)
