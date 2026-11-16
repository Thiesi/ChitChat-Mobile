# CI emulator health check and early logcat

Owner-approved CI infrastructure, after two API35 runs on PR #276 and PR #279 lost window focus from test 4 and timed
out with no device evidence (#245). It changes no test selection, image or emulator pin, deadline or gate meaning.

## Health check before instrumentation

`native_environment.py` adds single reads, with no retry or wait loop, beside its existing pins and settings:
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
  entry, with no settle wait and still one read. ANR, error dialog, no focus and any other window stay unhealthy.
- **On unhealthy:** the job ends at once with `Native emulator unhealthy (reasons); instrumentation not started.`, and
  `device/environment.json` records `classification: emulator_unhealthy` and its reasons.
- **Not done:** it never dismisses a dialog, force-stops an app, retries or waits. Pin and setting mismatches keep the
  existing "precondition failed" result.
- **Classification:** a health failure is a setup collapse. Record it on #245.

## Early logcat, always saved

The boot window comes first: the environment check makes **one** allowlisted `adb logcat -d` dump into
`device/early-logcat.txt` after its resize and **before** its clear. That happens before the health verdict, so an
`emulator_unhealthy` run still keeps its boot markers. `early_logcat.py` then starts in the background **after** the
check and appends through the first five instrumentation tests. Nothing reads logcat during the check's resize or clear:
a concurrent reader made API36's setup fail on PR #282's first head, so the order is pinned by a unit test.
- **What's kept:** only allowlisted markers (test start, finish and failure; ANR, fatal and force-finish markers;
  process starts and deaths; `Displayed`; focus and input-dispatch timeouts), each truncated to 240 characters.
- **Focus samples:** a component or dialog class every 5 seconds.
- **What's never kept:** message text, credentials or test content.
- **Survives cancellation:** every line is flushed at once, so the file exists even when the job is cancelled or hits
  its 30-minute limit. The existing `if: always()` report upload collects it.
- **Bounded:** 2,000 lines, five tests or 15 minutes, whichever comes first.

## Verification

- **`test_native_environment`:** health reasons for each unusable state, focus reduced to a component or class, and
  the distinct unhealthy versus precondition result and report.
- **Boot window:** the `-d` dump comes before `-c`, it's written even when the verdict is `emulator_unhealthy`, and a
  missing dump never fails setup.
- **`test_early_logcat`:** only allowlisted, truncated markers survive; the window stops at five test starts or the line
  cap; focus samples never carry window text.
