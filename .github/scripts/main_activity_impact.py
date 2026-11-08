"""One authorized real-MainActivity/API35 geometry measurement; no retries."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from ime_reproducer import certificate_digest, tool_output, verification_tools
import native_environment as environment
import native_journeys as journeys

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / 'app/build/reports/mainActivityImpact'
CLASS = 'io.github.thiesi.chitchat.testing.MainActivityImpactDiagnostic'
TEST = 'recreatedRealMainActivityKeepsDirectComposerAboveNativeIme'
APP = 'io.github.thiesi.chitchat.debug'
MARKER = b'Lio/github/thiesi/chitchat/testing/MainActivityImpactDiagnostic;'
PHASES = {'before_recreation', 'before_tap', 'geometry', 'failure', 'cleanup'}
INTS = {'uptimeMillis', 'activity', 'window', 'creations', 'windowHeight', 'decorHeight', 'originX', 'originY', 'imeBottom'}
BOOLS = {'windowFocused', 'attached', 'imeVisible', 'tapAttempted', 'geometryProved', 'cleanupComplete'}
BOUNDS = {'inputTop', 'inputBottom', 'sendTop', 'sendBottom'}


def safe_record(raw):
    if (not isinstance(raw, dict) or set(raw) != {'phase'} | INTS | BOOLS | BOUNDS or
            not isinstance(raw['phase'], str) or raw['phase'] not in PHASES):
        return None
    for key in INTS:
        if raw[key] is not None and (type(raw[key]) is not int or not 0 <= raw[key] <= 2**63 - 1): return None
    for key in BOOLS:
        if raw[key] is not None and type(raw[key]) is not bool: return None
    for key in BOUNDS:
        if raw[key] is not None and (type(raw[key]) not in (int, float) or not math.isfinite(raw[key]) or abs(raw[key]) > 1e7): return None
    return raw


def prepare():
    signer, aapt = verification_tools(Path(os.environ['ANDROID_HOME']))
    artifacts = {}
    certs = set()
    for name, relative, package in (
        ('app', 'app/build/outputs/apk/debug/app-debug.apk', APP),
        ('test', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', APP + '.test')):
        apk = ROOT / relative
        data = apk.read_bytes()
        certs.add(certificate_digest(tool_output(signer, 'verify', '--print-certs', str(apk))))
        badging = tool_output(aapt, 'dump', 'badging', str(apk)).decode()
        if not re.search(r"(?m)^package: name='" + re.escape(package) + "'", badging):
            raise ValueError('Unexpected APK package')
        with zipfile.ZipFile(apk) as archive:
            present = any(MARKER in archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes[0-9]*\.dex', n))
            if present != (name == 'test'): raise ValueError('Opt-in diagnostic must be in test APK only')
        artifacts[name] = dict(path=relative, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), package=package)
    if len(certs) != 1: raise ValueError('Debug/test APK signatures differ')
    REPORT.mkdir(parents=True, exist_ok=True)
    (REPORT / 'apks.json').write_text(json.dumps(dict(certificateSha256=certs.pop(), artifacts=artifacts,
        realMainActivity=True, syntheticOperations=True, nativeInvocations=0), indent=2) + '\n')


def command():
    return journeys.GRADLE + ['-PmainActivityImpact=true', '-Pandroid.testInstrumentationRunnerArguments.class=' + CLASS]


def collect():
    capture = subprocess.run(['adb', 'logcat', '-d', '-v', 'raw', '-s', 'MainActivityImpact:I', '*:S'],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False, timeout=15)
    if capture.returncode != 0 or len(capture.stdout) > 16384: return None
    records = []
    for line in capture.stdout.decode('utf-8', errors='replace').splitlines():
        if not line.startswith('{'): continue
        try: record = safe_record(json.loads(line))
        except (ValueError, TypeError): return None
        if record is None: return None
        records.append(record)
    if len(records) > 5: return None
    return records


def run():
    proof = json.loads((REPORT / 'apks.json').read_text())
    for artifact in proof['artifacts'].values():
        if hashlib.sha256((ROOT / artifact['path']).read_bytes()).hexdigest() != artifact['sha256']:
            raise ValueError('Prepared APK changed')
    record_path = REPORT / 'result.json'
    if record_path.exists(): raise ValueError('Invocation record exists; never replay')
    ledger = dict(measurementOnly=True, nativeInvocations=0, originalStatus=None, records=None, junit=None,
                  source=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip())
    record_path.write_text(json.dumps(ledger, indent=2) + '\n')
    status = environment.main()
    if status == 0:
        ledger['nativeInvocations'] = 1
        record_path.write_text(json.dumps(ledger, indent=2) + '\n')
        status = journeys.run_once(command(), environment.REPORT.parent / 'ime-lifecycle.txt')
        ledger['originalStatus'] = status
        record_path.write_text(json.dumps(ledger, indent=2) + '\n')
        try: ledger['records'] = collect()
        except (OSError, subprocess.TimeoutExpired): pass
        try:
            cases = []
            for path in (ROOT / 'app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'):
                for case in ET.parse(path).getroot().iter('testcase'):
                    cases.append(dict(className=case.get('classname'), test=case.get('name'),
                        failed=case.find('failure') is not None or case.find('error') is not None,
                        skipped=case.find('skipped') is not None))
            ledger['junit'] = cases
        except (OSError, ET.ParseError): pass
    ledger['originalStatus'] = status
    record_path.write_text(json.dumps(ledger, indent=2) + '\n')
    valid_case = ledger['junit'] is not None and len(ledger['junit']) == 1 and all(
        c['className'] == CLASS and c['test'] == TEST and not c['skipped'] for c in ledger['junit'])
    complete = ledger['records'] is not None and [r['phase'] for r in ledger['records']] == [
        'before_recreation', 'before_tap', 'geometry', 'cleanup'] and ledger['records'][-1]['cleanupComplete'] is True
    return status if status != 0 else (0 if valid_case and complete else 1)


if __name__ == '__main__':
    if sys.argv[1] == 'prepare': prepare()
    elif sys.argv[1] == 'run': sys.exit(run())
    else: raise ValueError('Only explicit prepare/run modes exist')
