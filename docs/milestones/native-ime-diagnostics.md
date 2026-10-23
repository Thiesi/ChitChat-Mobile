# Native IME failure diagnostics

Historical collector checkpoint: the later [first-failure retention slice](native-ime-failure-retention.md) replaces the post-suite trace tail with a bounded concurrent collector. Its original merged-main failures remain retained; no production keyboard correction or reopening of #31 is implied.

This is a test/CI observability slice for [issue31](https://github.com/Thiesi/ChitChat-Mobile/issues/31), not a production keyboard correction or a parity feature. The backend, dependencies, input implementation and user-visible behavior are unchanged. General notification destinations remain the next functional slice; push and live acceptance are still pending.

## Reason for the slice

PR45's reviewed head passed its required checks (531 JVM/113 native tests) before merge. The independent merged-main run [39541317813](https://github.com/Thiesi/ChitChat-Mobile/actions/runs/39541317813) at `1f551dc9f308cfee013948faa7a8345c6dc63f2e` subsequently passed build checks but failed 112/113 native tests. `ConversationJourneyTest.multilineSendUsesExplicitActionAndAcknowledgedMessageClearsDraft` timed out at the original room-composer pre-injection tap predicate: focused=true, IME=0, sends=0, RESUMED and window-focused. No rerun was requested to replace that result.

The earlier PR45 local full run also failed 112/113 at the second DM-composer tap after quote adoption. One bounded seven-case diagnostic cohort and one independent full confirmation then passed. These failures are retained separately; neither the later passes nor the differing screens establish the underlying cause. Issue31 remains open.

## What is collected

- The shared `androidTest` input helper takes a scalar native Activity/window snapshot before its single tap and after a timeout: monotonic uptime, resumed-Activity count, window/view focus, attachment, text-editor Boolean, native IME visibility/bottom inset and soft-input mode. Ambiguous/unavailable metadata remains null. No Activity/View, editor contents, title or input-method object is retained or logged.
- No focus request, keyboard show/hide/restart, IMM getter that flushes focus, second tap, text injection before readiness, larger timeout or weaker predicate is introduced. The snapshot itself adds a main-thread observation and can affect timing; it must not be called a keyboard fix.
- CI runs the existing connected-test command exactly once through `.github/scripts/native_journeys.py`. Only after failure it attempts a bounded-time logcat read of `ImeTracker` and `TestRunner`. Raw output is never written to reports or printed. The final report keeps at most2000 filtered records: fixed timestamp/token/event scalars, an explicit allowlist of known platform phases/reasons, and native test lifecycle method/class names. Unknown fields, other packages/tags, arbitrary tails and exception payloads are omitted, not dumped as a fallback.
- `app/build/reports/androidTests/device/ime-lifecycle.txt` is included in the existing always-uploaded native-report artifact on failure. No screenshots, full device/window dumps, app HTTP logs or account configuration are collected by this wrapper. Capture/write failure cannot replace the original nonzero Gradle exit status. There is no CI retry or `continue-on-error`.

This collector is for the credential-free fixture CI emulator, not instructions to upload logs from real private conversations. Existing native-test assertion reports retain their existing responsibilities; the filter does not sanitize arbitrary application logging elsewhere.

## Verification

Ten credential/device-free Python tests pass locally: successful single execution/no capture, original exit preservation, launch/signal/capture/timeout/write failures, known metadata, private canaries/unknown input exclusion and the filtered-record cap. These mock subprocess/report I/O; they do not prove real adb capture or device readiness. Two new instrumented tests check real native scalar snapshots and the distinction between an Activity's focus and its covering Compose dialog.

The local five-task gate passed in 9m 48s using the repository's JDK17/SDK36 and unchanged signing configuration: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest --no-daemon --max-workers=2 --console=plain`. Independently parsed JVM XML reports 531 tests, zero failures/errors/skips; lint reports no issues. The release APK was shrunk with R8. Reports and all three APKs were archived with matching SHA256 hashes. Workflow actionlint and 217 relative documentation links also pass.

Both new native tests passed on their first focused execution (2/2, 5.740s). One full strict-IME run then passed 115/115 in 258.502s, zero failures/skips, on the dedicated API36 Google APIs x86_64 emulator: 320x640 portrait, font1.0, system animations disabled and software keyboard enabled. Complete instrumentation output was retained, and actual `OK (115 tests)` plus all 115 success statuses were checked, not merely adb's exit code. No focus/IME forcing, test retry or source change occurred between the build and device gates.

Required current-head CI and completed Claude initial/fresh reviews remain delivery gates. A passing local suite is not resolution of issue31. Live server tests are not invoked, and user-owned `.env.integration` is not read by this slice. The Python tests mock capture I/O; real hosted failure-path capture is not claimed from those tests or the passing native run.

## Next step

Use the additional evidence when a real failure occurs, without inventing a production fix from a later green run. Continue guarded notification routing after this slice's build/review/delivery gates. Real keyboard/TalkBack/device and live chat/push acceptance remain separate.
