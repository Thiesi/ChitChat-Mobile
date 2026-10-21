# System-routed keyboard test taps

Status: locally verified test-only slice; current-head CI and completed initial/fresh reviews remain merge prerequisites. No production editor, window policy, authentication or transport changes. Issue31 and PR49's original failed checks remain unresolved; no phone/live acceptance is claimed.

## Why

Matching `ui-test-android:1.11.4` source shows Android `performClick` creates touchscreen MotionEvents but dispatches them directly to the Compose root View. That is not the OS input route. Keyboard/device journeys must distinguish View-level event tests, semantics actions and system-routed taps.

Controlled owned-API36 experiments preserved the original failures: the model-owned editor failed at cycle19; AndroidView/EditText controls failed at13 with a Compose dialog and60 with a native alert; OS routing only for the input tap failed at54. A pure-native screen/control passed100 cycles, and routing all input/Menu/Close taps through the OS passed100 cycles in228.142seconds with the unchanged state-editor production APK. The wrong-root first native-dialog attempt is inconclusive, not keyboard evidence. These are diagnostic comparisons, not acceptance of the model-owned editor, a causal proof, a production fix or a waived gate. See [issue31](https://github.com/Thiesi/ChitChat-Mobile/issues/31).

## Contract

`performNativeTap` snapshots the clipped target center translated from actual window to screen coordinates, then injects one finger down/up through UiAutomation. It rejects missing/non-default native-root display ownership before injection; it must not send a secondary-display target to the default screen. No focus/IME requests, readiness waits, retries, virtual-clock advancement or window flags. Empty/detached/non-finite coordinates fail before injection. Events are recycled. Failed injection stays failed; one cancellation retires a possibly held pointer and cannot mask the original failure.

`tapNativeInput` keeps its existing display/focus/optional-strict-IME assertions and original5-second deadline, using that system tap. Semantic text injection still follows, never manufactures the initial focus proof. The100-cycle production-legacy-editor fixture uses consistent OS routing for all three controls. One warmup plus99 post-dialog taps is one test, not100 independently executed JUnit cases. No diagnostic-mode branch/fake production behavior is involved.

The seven native helper tests cover coordinate mapping/clipping, invalid targets, single down/up properties, no replay on rejection, cancellation and original-error preservation. Full debug/release/JVM/lint/test-APK builds, focused and full native gates, wrapper tests, CI and completed initial/fresh exact-head Claude reviews remain required. Record actual totals/results before delivery; a passing isolated fixture does not replace the full application gate.

## Verification so far, not delivery

The first debug/JVM/test-APK build succeeded in4m16seconds; unchanged598 JVM results were restored FROM-CACHE, not freshly executed. Actual focused native execution passed8/8 in251.999seconds, with zero negative status records: seven helper cases plus the100-cycle legacy-production-editor case. Discovery enumeration was checked separately and is not execution. The strict IME argument was enabled; no diagnostic flags or retries were used. Initial APKs were SHA256 `751A5B0EB4E9F6C06A15439C2800C13992658CC340C2C572A2B8F36BF76FAC97` (debug) and `C7D8670DF7A3925684563984A6A024232A5DCC332F2E385EE7A5473D83F7BE1B` (test).

The ten credential/device-free Python wrapper tests passed in0.050seconds. The first five-task build then succeeded in6m37seconds:598 JVM tests freshly executed using the per-task `--rerun`,0 failures/errors/skips, lint0, debug/release/test APKs built. R8/release compilation and unchanged lint analyses reused cache; Android-test lint ran. Debug/test hashes matched the focused pair; unsigned release SHA256 was `668017AD747FFC51A8C153828DB80453ED0B1B1444542B1DEC66F4B330E4CDAE`.

After those results, the helper was tightened to enforce actual default-display ownership, extending an existing native negative-test method without changing the8 added-case total. The amended five-task build passed in4m18seconds with598 freshly executed JVM tests (0 failures/errors/skips), lint0 and all three APKs built. Debug and unsigned release hashes stayed unchanged; the amended test APK SHA256 is `55E9E280C692385823BE748D53DDE6A5F5B89D2291804F654B3B1F53B057804F`. Native Kotlin/test lint executed; unchanged production/release work was up-to-date.

The amended full native suite then passed137/137 in809.942seconds, with137 success statuses, zero negative statuses and exit0. It included the seven amended helper tests,100-cycle legacy-editor case and129 existing cases; separate discovery137 was enumeration only. Normal strict IME configuration was enabled, with no suite-wide diagnostic flags, autofill overrides, exclusions or retries. The earlier focused8 remain earlier-revision results, not substituted for this full gate. Final reports/test results, three hash-verified APKs and both native logs were archived locally. Current-head CI and completed initial/fresh reviews remain required before merge. No phone, credentials, live ChitChat calls, backend writes, dependency/toolchain or emulator-policy changes were used.
