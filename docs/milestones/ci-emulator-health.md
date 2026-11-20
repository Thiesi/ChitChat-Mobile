# CI emulator health check and early logcat

Owner-approved CI infrastructure, after two API35 runs on PR #276 and PR #279 lost window focus from test 4 and timed
out with no device evidence (#245). It changes no test selection, image or emulator pin, deadline or gate meaning.

## Health check before instrumentation

`native_environment.py` first waits a **fixed 15 seconds** after the runner reports boot completed, then adds single
reads, with no retry or wait loop, beside its existing pins and settings:
`sys.boot_completed`, and the focused window from `dumpsys window`. The focused window is reduced to its component, or
to `anr` / `app_error` / `other`, and never stored as raw window text.
- **The emulator counts as unhealthy when:**
  - boot isn't complete;
  - nothing holds focus;
  - an ANR or error dialog has focus;
  - the focused window isn't a launcher (`com.google.android.apps.nexuslauncher`, `com.android.launcher3`);
  - the expected IME isn't selected.
- **Boot home:** the exact component `com.android.settings/com.android.settings.FallbackHome` counts as healthy. It's
  Android's interim home, shown right after boot until the real launcher is ready. Evidence from main run 43938459454
  (e99c73a): `environment.json` had boot completed, focus on FallbackHome and the IME selected, and the early-logcat
  boot dump ended with processes still starting and no launcher process yet. The TL ruled for this exact allowlist
  entry, still one read. ANR, error dialog, no focus and any other window stay unhealthy.
- **Fixed settle** (TL ruling on #245, 2026-11-17): three boot-state verdicts were all read about 2s after
  `Emulator booted`: FallbackHome on main run 43938459454 and on PR #287's run 43949708112, then a null focus on PR
  #289's run 44000691076. So the single read now follows one fixed 15-second delay. It's a delay, never a poll: no loop,
  no re-read, no wait until healthy. 15s is small against 20 to 30 minute runs.
- **The settle is only for transient boot homes** (FallbackHome, a null focus). An ANR or error dialog persists until it's
  dismissed, so the settle doesn't clear it, and it stays a true-positive unhealthy verdict. PR #290's rerun record had
  `anr_dialog_has_focus` about 9s after boot, very likely the same focus-stealing behind the earlier 30-minute API35
  collapses at test 4. Never "fix" an ANR verdict away: no dismissing, no waiting it out, no allowlisting.
- **On unhealthy:** the job ends with `Native emulator unhealthy (reasons); instrumentation not started.`, and
  `device/environment.json` records `classification: emulator_unhealthy` and its reasons. Before exiting, the check
  appends focus samples to `device/early-logcat.txt` every 2 seconds for a fixed 10 seconds (`focus <class> at=+Ns
  after verdict`). That's evidence only: the workflow stops at this exit, so `early_logcat.py` never starts on this
  path, and a launcher arriving later never changes the verdict.
- **Not done:** it never dismisses a dialog, force-stops an app, retries or waits. Pin and setting mismatches keep the
  existing "precondition failed" result.
- **Classification:** a health failure is a setup collapse. Record it on #245.

## Early logcat, always saved

The boot window comes first: after the settle, the environment check makes **one** allowlisted `adb logcat -d` dump
into `device/early-logcat.txt`, **before** its resize (`-G`) and its clear. That happens before the health verdict, so an
`emulator_unhealthy` run still keeps its boot markers. The dump used to come after the resize. On these images the
resize discards the ring, so run 44000691076's file was empty, and healthy runs kept only the second after the resize.
A unit test with a fake device whose resize discards the ring pins the order. The dump is filtered to allowlisted markers
first and then capped at the last 2,000 markers. Capping raw lines first kept only about half a second of the verbose
boot log (PR #291's first run), and a test with 3,000 noise lines pins that the early markers survive.
`early_logcat.py` then starts in the background **after** the
check and appends through the first five instrumentation tests. Nothing reads logcat during the check's resize or clear:
a concurrent reader made API36's setup fail on PR #282's first head, so the order is pinned by a unit test.
- **What's kept:** only allowlisted markers (test start, finish and failure; ANR, fatal and force-finish markers;
  process starts and deaths; `Displayed`; focus and input-dispatch timeouts), each truncated to 240 characters.
- **Focus samples:** a component or dialog class every 5 seconds.
- **What's never kept:** message text, credentials or test content.
- **Survives cancellation:** every line is flushed at once, so the file exists even when the job is cancelled or hits
  its time limit (45 minutes since 2026-11-20; see build-test-release.md). The existing `if: always()` report upload
  collects it.
- **Bounded:** 2,000 lines, five tests or 15 minutes, whichever comes first.

## Verification

- **`test_native_environment`:** health reasons for each unusable state, focus reduced to a component or class, and
  the distinct unhealthy versus precondition result and report.
- **Boot window:** the `-d` dump comes before `-c`, it's written even when the verdict is `emulator_unhealthy`, and a
  missing dump never fails setup.
- **`test_early_logcat`:** only allowlisted, truncated markers survive; the window stops at five test starts or the line
  cap; focus samples never carry window text.
