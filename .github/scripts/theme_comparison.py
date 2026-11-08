"""One presentation comparison, never a full native gate or input diagnostic."""
from pathlib import Path
from itertools import islice
import hashlib,json,re,shutil,struct,subprocess,sys

ROOT=Path(__file__).resolve().parents[2]
BEFORE='3093e71b4236bb27689cef130f3a7b788794b005'
FIXTURE='app/src/androidTest/java/io/github/thiesi/chitchat/theme/ThemePresentationJourneyTest.kt'
TARGET=ROOT/'.tooling/worktrees/theme-baseline'
REPORT=ROOT/'app/build/reports/theme-comparison'
SCENES=('server','auth','home','settings','rooms','room','direct','thread','activity','account','search')

def collect(source,destination,kind):
    folder='ccm-theme-baseline' if kind=='before' else 'ccm-theme-presentation'
    prefix='baseline' if kind=='before' else 'theme'
    palettes=('light','dark') if kind=='before' else ('light','dark','black')
    expected={f'{prefix}-{palette}-{scale}-{scene}.png'
        for palette in palettes for scale in ('default','font200') for scene in SCENES}
    expected|={f'{prefix}-{palette}-{scale}-configuration.json'
        for palette in palettes for scale in ('default','font200')}
    files=list(islice(source.rglob(folder+'/*'),81))
    if len(files)>80 or len({p.name for p in files})!=len(files):
        raise ValueError('Ambiguous or excess capture output')
    records=[]
    for path in files:
        if path.name not in expected or not path.is_file() or path.stat().st_size>16*1024*1024:
            raise ValueError('Unexpected capture output')
        content=path.read_bytes()
        item=dict(name=path.name,bytes=len(content),sha256=hashlib.sha256(content).hexdigest())
        if path.suffix=='.png':
            if content[:8]!=b'\x89PNG\r\n\x1a\n' or len(content)<24:
                raise ValueError('Invalid capture image')
            width,height=struct.unpack('>II',content[16:24])
            if (width,height)!=(360,640):raise ValueError('Wrong comparison dimensions')
            item.update(widthPx=width,heightPx=height)
        else:
            config=json.loads(content)
            expected_scale=2 if '-font200-' in path.name else 1
            if (abs(config['widthDp']-360)>0.5 or abs(config['heightDp']-640)>0.5
                or abs(config['fontScale']-expected_scale)>0.01):
                raise ValueError('Wrong comparison configuration')
        shutil.copyfile(path,destination/path.name)
        records.append(item)
    return records,{p.name for p in files}==expected

def git(*args,cwd=ROOT):
    return subprocess.check_output(['git',*args],cwd=cwd,text=True).strip()

def prepare(kind):
    assert kind in ('before','after')
    head=git('rev-parse','HEAD');assert re.fullmatch('[0-9a-f]{40}',head)
    if kind=='before':
        assert not TARGET.exists(),'Never overwrite a baseline checkout'
        TARGET.parent.mkdir(parents=True,exist_ok=True)
        subprocess.run(['git','worktree','add','--detach',str(TARGET),BEFORE],cwd=ROOT,check=True)
        text=(ROOT/FIXTURE).read_text(encoding='utf-8')
        assert text.count('// THEME_ONLY')==7,'Review baseline adaptation after fixture edits'
        text='\n'.join(line for line in text.splitlines() if '// THEME_ONLY' not in line)+'\n'
        text=text.replace('ThemePresentationJourneyTest','ThemeBaselineCaptureTest')
        text=text.replace('ccm-theme-presentation','ccm-theme-baseline').replace('"theme-$label','"baseline-$label')
        assert 'pureBlack' not in text and 'enableAccessibilityChecks' not in text
        destination=TARGET/FIXTURE.replace('ThemePresentationJourneyTest','ThemeBaselineCaptureTest')
        destination.parent.mkdir(parents=True,exist_ok=True);destination.write_text(text,encoding='utf-8')
        assert not git('diff','--name-only','--','app/src/main','app/build.gradle.kts','gradle',cwd=TARGET)
        work=TARGET;class_name='io.github.thiesi.chitchat.theme.ThemeBaselineCaptureTest'
    else:
        work=ROOT;class_name='io.github.thiesi.chitchat.theme.ThemePresentationJourneyTest'
    REPORT.mkdir(parents=True,exist_ok=True)
    record=dict(kind=kind,productionRef=BEFORE if kind=='before' else head,fixtureRef=head,
        productionUnmodified=kind=='before',testClass=class_name,worktree=str(work),
        fullNativeGate=False,expectedCases=4 if kind=='before' else 6,
        label='Synthetic owned-window captures only; no live, manual-device or keyboard acceptance')
    (REPORT/'provenance.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(record))

def run(kind):
    record=json.loads((REPORT/'provenance.json').read_text());assert record['kind']==kind
    work=Path(record['worktree'])
    status=1
    try:
        # Exactly one new capture-class invocation. Never invoke/retry the ordinary full suite.
        status=subprocess.run(['./gradlew',':app:connectedDebugAndroidTest',
            '-Pandroid.testInstrumentationRunnerArguments.class='+record['testClass'],
            '-Pandroid.testInstrumentationRunnerArguments.expectedCaptureWidthDp=360',
            '-Pandroid.testInstrumentationRunnerArguments.expectedCaptureHeightDp=640',
            '--max-workers=2','--console=plain'],cwd=work,check=False).returncode
    finally:
        record['originalCaptureStatus']=status
        record['captures']=[]
        record['apks']=[]
        record['captureCollectionComplete']=False
        try:
            source=work/'app/build/outputs/connected_android_test_additional_output'
            record['captures'],record['captureCollectionComplete']=collect(source,REPORT,kind)
            for name in ('reports/androidTests','outputs/apk/debug','outputs/apk/androidTest/debug'):
                src=work/'app/build'/name
                if src.exists():shutil.copytree(src,REPORT/name,dirs_exist_ok=True)
            for path in islice((REPORT/'outputs/apk').rglob('*.apk'),3):
                record['apks'].append(dict(path=str(path.relative_to(REPORT)),bytes=path.stat().st_size,
                    sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        except Exception:
            record['captureCollectionIncomplete']=True
            print('Capture collection incomplete; original capture status retained.',file=sys.stderr)
        (REPORT/'provenance.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
    # This is a capture-only job: missing evidence fails this job, never the native gate.
    return (128-status if status<0 else status) or (0 if record['captureCollectionComplete'] else 1)

if __name__=='__main__':
    assert len(sys.argv)==3 and sys.argv[1] in ('prepare','run') and sys.argv[2] in ('before','after')
    if sys.argv[1]=='prepare':prepare(sys.argv[2])
    else:sys.exit(run(sys.argv[2]))
