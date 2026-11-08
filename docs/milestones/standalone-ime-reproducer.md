# Standalone fresh-window IME reproducer (#69)

This is a diagnostic measurement, never a native acceptance gate or product keyboard fix.
Authority: [plan](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6713567378)
and [start now alongside held PR114](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6718647530).

## Fixed fixture and boundary

Opt-in `:ime-reproducer` builds its own app and test APK. It does not target or install CCM,
load its Application, use server fixtures, hold credentials or read .env.integration.
Both manifests exclude network permission. It uses the existing Compose/toolchain versions.

Fifty fixed JUnit cases cycle01–cycle50 each launch a fresh ComponentActivity and its empty
OutlinedTextField, make exactly one original system-routed native tap, and require displayed
focus plus IME bottom >0 within the existing strict5000ms wait. No text injection, focus/IME
request, pre-tap readiness repair, second tap or retry. A failed case remains failed while
the remaining independent cases measure their own fresh windows. Named cases preserve
real TestRunner start/failure/cleanup/finish windows for PR A's existing selector.

Each case closes the IME and completes the combined Compose/native hidden check inside5000ms
before finishing its Activity. Cleanup errors cannot replace the primary error. Failure-only
metadata is captured before cleanup: separately timestamped native scalars, the shared
fixed-field IME dumps, and at most64 IME-main-thread Java class/method names. One bounded
best-effort debuggerd Java-dump request uses no root; denied/missing/multiple/truncated data
stays unavailable. No raw thread/dump content, arguments or source locations are retained.
These are sequential post-failure observations, never atomic or earlier deadline proof.

## Once-per-arm workflow

The already-registered manual `native-input-observation.yml` accepts `reproducer_arm=a|b|c`.
This routes only to a separate, untraced standalone job, artifact and concurrency group.
The original consumed100-cycle control's method/script/default behavior is unchanged and
is **not** invoked by these arms. Never dispatch the default/none for this investigation.

| Arm | Image | Emulator | Resources |
|---|---|---|---|
| a | Google APIs API36 x86_64 revision7 |37.2.13.0 /16740729|2cores, automatic RAM |
| b | Google APIs API35 x86_64 revision9 |same|2cores, automatic RAM |
| c | Google APIs API36 x86_64 revision7 |same|4cores,4096M RAM |

Pins come from Google's official image manifest; revision/size/checksum are fixed.
One suite setup uses16MiB log ring/one clear, the existing IME/animation/hardware-keyboard
preconditions, plus actual API/core checks. Default IME/version, actual memory and platform
fingerprint are recorded. No spellchecker/autofill/service changes or platform flags.

Normal PR CI only compiles/signature-checks the new APKs, runs pure parser/helper tests and
lint. Its ordinary app native suite stays unfiltered, untraced and unchanged.
APK verification pins the approved build-tools35.0.0 rather than picking the host's newest
version. Only after cryptographic verification succeeds, signer fingerprints accept the
documented legacy/SDK-range labels and hexadecimal case, requiring exactly one unique
certificate and an identical app/test certificate. Missing/multiple keys still stop setup.
Preparation status is retained even before successful APK collection; there is no signature bypass.
Manual arms use signature/hash-verified standalone APKs and one am-instrument call each; failed JUnit
results remain failed even if adb exits0. A duplicate/missing case, wrong count/deadline,
truncated output or transport interruption cannot claim a complete measurement. Status
bundles are sanitized; raw instrumentation output is never saved. Allowlisted IME lifecycle
metadata retains the same2000-record cap/failure-window selection; only this new consumer
selects the standalone package identity. Normal collector identity defaults stay unchanged.

Artifacts include APKs/certificates/hashes, environment, per-case outcomes and failure
observations, a filtered lifecycle ledger and source revision. Retention is seven days.
Each arm runs once, independently; keep original failures and setup negatives. No automatic
or manual replay without fresh explicit authority. GITHUB_RUN_ATTEMPT>1 fails before mutation.
Report the three original results on#69. Results do not waive PR114 or select the gate platform;
that remains the maintainer's decision.

No real device run is planned. A later Firebase Test Lab option remains conditional on
[the separate infrastructure-only decision](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6713683537),
after arms a–c and maintainer-owned project/auth/model availability. No Firebase enters the app.
