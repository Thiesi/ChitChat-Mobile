"""Issue69: one unchanged ReplyComposer class per frozen source, never a gate."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import native_environment as environment
import native_journeys as journeys

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {'before': '83b448089e69f2fc755e280f24908d6ee71b6a75',
           'current': 'c49e04766d257d0578d95172abe7d9885479a04b'}
CLASS = 'io.github.thiesi.chitchat.feature.conversation.ReplyComposerJourneyTest'
TEST_PATH = 'app/src/androidTest/java/io/github/thiesi/chitchat/feature/conversation/ReplyComposerJourneyTest.kt'
TOOL_PATHS = ('gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar',
              'gradle/wrapper/gradle-wrapper.properties', 'gradle/libs.versions.toml',
              'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties')


def command():
    return journeys.GRADLE + ['-Pandroid.testInstrumentationRunnerArguments.class=' + CLASS]


def verify_source(arm, observed):
    expected = SOURCES[arm]
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=observed, text=True).strip()
    if head != expected:
        raise ValueError('Observed source does not match the frozen arm')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=observed):
        raise ValueError('Observed source must be clean before instrumentation')
    # The original test class and build toolchain are identical across this boundary.
    # Read the pinned current-main Git blobs, not potentially dirty local files.
    hashes = {}
    for path in (*TOOL_PATHS, TEST_PATH):
        before = subprocess.check_output(['git', 'show', SOURCES['before'] + ':' + path], cwd=ROOT)
        current = subprocess.check_output(['git', 'show', SOURCES['current'] + ':' + path], cwd=ROOT)
        if before != current:
            raise ValueError('Comparison tooling or original test differs: ' + path)
        hashes[path] = hashlib.sha256(current).hexdigest()
    return head, hashes


def main(arm):
    if arm not in SOURCES:
        raise ValueError('Only the two authorized frozen arms are supported')
    observed = ROOT / 'observed'
    head, hashes = verify_source(arm, observed)
    report = observed / 'app/build/reports/androidTests/device'
    report.mkdir(parents=True, exist_ok=True)
    result = report / 'lantern-bisect.json'
    if result.exists():
        raise ValueError('An invocation record already exists; do not replay')
    ledger = dict(arm=arm, source=head, toolingHead=subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        commonSourceSha256=hashes, measurementOnly=True, nativeInvocations=0,
        classFilter=CLASS, status=None)
    result.write_text(json.dumps(ledger, indent=2) + '\n')
    environment.ROOT = observed
    environment.REPORT = report / 'environment.json'
    journeys.ROOT = observed
    status = environment.main()
    if status == 0:
        ledger['nativeInvocations'] = 1
        # Persist intent before the single Gradle instrumentation invocation.
        result.write_text(json.dumps(ledger, indent=2) + '\n')
        status = journeys.run_once(command(), report / 'ime-lifecycle.txt')
    ledger['status'] = status
    ledger['sourceUnchanged'] = subprocess.run(['git', 'diff', '--exit-code', 'HEAD'],
                                             cwd=observed, check=False).returncode == 0
    result.write_text(json.dumps(ledger, indent=2) + '\n')
    return status if status != 0 else (0 if ledger['sourceUnchanged'] else 1)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
