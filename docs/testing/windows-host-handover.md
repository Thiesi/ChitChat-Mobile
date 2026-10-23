# Windows 11 test-host handover

Task: run one controlled full native gate and return evidence. Do not develop product features, merge PR60 or stop unrelated processes. Read AGENTS.md and [the investigation](../milestones/native-ime-input-comparison.md) first. This task needs no credentials, live ChitChat server, Firebase project, phone or private files from the original host. Never consume `.env.integration` or run `liveSessionTest`.

## Source and known results

Fetch **Android only**, then check out `origin/test/ime-failure-retention` in an isolated clean worktree. PR: https://github.com/Thiesi/ChitChat-Mobile/pull/60. Tested-source anchor: `ffdad3b45e65cc04e4e040fc92f5d39832dd2a33`; base: `c84eed135b029964efc82cce4f9d3950c1faec90`. This handover follows as documentation only. Verify the anchor is an ancestor and later commits change only documentation; record actual HEAD/tree and stop on unexpected source/base changes. Do not silently rebase or discard work. Build/run that actual clean head, recording source hashes before and after.

Prior headf3ac3c6 passed195/195 Linux native tests and two actual reviews. Original Windows full run:73 positive/one negative status,75th started,1200s host timeout. New non-exempt Reply failure; observer diagnostic pair failed both Room and DM. Metadata10/10 passed after a separate pre-test startup ANR/package-verify preparation. Phone fixture regression20/20 passed. None replaces the failed full run. Original host measured98.66%, then99.82% CPU busy after comparison emulators stopped. Cause of earlier failures remains unknown. Original artifacts stay on that host; do not claim you inspected them.

## Prepare one owned emulator

- Use JDK17, checked-in Gradle8.13/AGP8.13.2/Kotlin2.3.21, SDK36/build-tools35.0.0, Python3, platform-tools. Follow [setup](../build-test-release.md); no dependency upgrades or signing-key replacement. Keep tool/cache/user-home paths local and ignored. Use the SAME `ANDROID_USER_HOME` for both APK builds/installations; preserve signatures/data.
- Match emulator37.2.12/build16428233 and Google APIs x86_64 API36.0 image revision7/fingerprint from the investigation. Record actual versions, `-accel-check` and startup accelerator/renderer. Missing/different image, failed acceleration, admin/reboot requirements or access restrictions are setup blockers, not permission to alter the host globally.
- Create a new workspace-owned AVD; never wipe/reuse an existing one. Match320x640 physical pixels,160dpi,4 cores,2048MiB, portrait, hardware keyboard off, main keys on, rear camera emulated/front none. Launch Hidden from stable repository root: `-no-window -no-audio -no-snapshot -gpu swiftshader -cores 4 -memory 2048`. Record other guest differences. Runtime heaps on original:576m/192m; verify rather than force them.
- Require only that owned emulator in ADB. Do not disconnect, inspect or interact with phones/other devices. Record its identity/port, image, actual portrait config, default IME and awake/unlocked state. On THIS fresh guest only: font1, free rotation/default fixed-rotation policy, accelerometer_rotation1/user_rotation1, show_ime_with_hard_keyboard1, all three animation scales0. Preserve/record policies. No application or keyboard focus/show workaround.
- Take two fixed10s host CPU samples before the gate; record core count/free RAM and emulator CPU separately. If either exceeds80% overall busy, stop setup without running the gate. Do not poll until quiet or kill unrelated tasks. Cold boot has a separate1200s bound (the original software cold boot took632s); failure preserves evidence, without restart-until-green. Do not pre-run keyboard cohorts or warm the fixture.

## Build and run once

Run checked-in host checks and debug/release build, JVM tests, lint as in `.github/workflows/android.yml`. Record executed versus cached/up-to-date tasks, counts and APK/source SHA256s. Resolve setup/build failures before native execution; retain each outcome. No live tasks.

Run the COMPLETE195-method gate ONCE, unfiltered, with `expectSoftKeyboard=true`. No `traceNativeInput`, `observeReplyHandoff`, class/method filters, phone/autofill overrides or extra rounds. Individual5000ms input deadlines and all native tap/focus/IME assertions remain unchanged.

Reuse `.github/scripts/native_journeys.py` and its allowlisted collector. Its command uses Unix `./gradlew`; on Windows use an IGNORED launcher importing that module, changing ONLY `GRADLE[0]` to the absolute `gradlew.bat`, then calling `main()`. Do not edit the tracked collector or suppress its exit status. Have exactly one ADB target and SDK platform-tools on PATH. Use a6000s outer host bound: the prior partial rate projects about52min for195 methods, so100min permits complete coverage without extending any UI assertion. On timeout retain partial evidence, mark FAILED/incomplete and stop only the owned invocation/app; never rerun to obtain green.

Retain full build/native output, XML/HTML reports, APK/source hashes, filtered `device/ime-lifecycle.txt` AND `.status.txt`, actual test counts/order, elapsed times, exit codes and environment/load metadata under ignored `.tooling/windows-host-<UTC>/`. No broad raw logcat/window/IME dumps, private data or screenshots of unrelated UI. Missing collector evidence stays unknown; process exit0 alone is not a test pass.

## Return

Write a concise `report.md` plus an evidence ZIP/SHA256 in that ignored folder. Report actual HEAD/tree/toolchain, setup deviations, command/flags, builds/JVM/lint provenance, native expected195 versus actual passed/failed/skipped, first failure/phase, timeout/capture errors and before/after source cleanliness. Preserve negative/aborted attempts individually. Return paths and report text to the operator; do not commit binaries/secrets or push/merge without coordination. A pass is alternate-host evidence, not erasure of previous failures or a production keyboard fix. If negative, preserve it and recommend the next bounded investigation; do not alter product focus/timing, relax validation, grant a new exception or retry until green.
