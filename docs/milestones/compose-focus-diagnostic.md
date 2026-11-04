# Issue65: standalone Compose focus diagnostic

Tracks [#65](https://github.com/Thiesi/ChitChat-Mobile/issues/65), starting from main `c84eed135b029964efc82cce4f9d3950c1faec90`. PR64's Windows70/172 (102 failed,0 skipped) and PR59's independent-main failure remain failed. The [prior investigation](https://github.com/Thiesi/ChitChat-Mobile/pull/64#issuecomment-6622119759) established widespread missing app-window focus but not the responsible OS window or cause. Empty-host observations did not exercise Compose input.

## Scope

`-PfocusDiagnostic=true` includes the separate `com.android.test` module `:focus-diagnostic`. Its APK targets the app's unchanged debug ComponentActivity and contains one new test, not the normal app tests. The normal `:app:connectedDebugAndroidTest` remains unfiltered and unchanged. CI compiles the diagnostic separately without invoking it; the test-only plugin supplies no `lintDebug` task, while the ordinary app lint gate remains intact. The same catalog/toolchain, resolved target-process dependency versions, original debug signing context, and verbatim NativeTap/NativeInputWindow/NativeImeServer sources are used. A generated four-file projection prevents Kotlin from including the surrounding normal test source trees. Its fixed-field parser lives in app unit-test sources and is compiled into the diagnostic only; no diagnostic code is a production dependency.

The new fixture renders one Material3 multiline input in the same Compose v2 harness, with no account/server, app navigation, text-input interception or trace. One `performNativeTap` must establish focused/displayed input, actual window focus and positive IME inset within5000ms **before** semantic text injection. Complete multiline characters are then asserted. No focus/show/hide/readiness request precedes the tap, no second tap occurs, and no deadline is enlarged. The fixture hides its own IME in finally before activity teardown; cleanup failure stays a failure and cannot replace an earlier negative.

Failure is caught inside the test body, before finally/rule teardown. Only allowlisted scalar fields are emitted: detection receipt, native-window lifecycle/input scalars, public keyguard Booleans, bounded OS focus categories, and existing bounded IME server flags. Each sequential source has its own uptime interval. These are after-failure observations, not deadline or atomic-state proof. OS focus matching accepts only platform component positions; missing, malformed, multiple-display or truncated metadata is unknown. Titles, tokens, component names, user IDs and raw dumps/text are never emitted. No screenshot or broad logcat is collected. Rule/setup failures outside the test body may have no capture; the driver fails closed on incomplete output.

## Fixed comparison and execution

Before any device invocation, freeze an ignored plan JSON with common app/diagnostic APK SHA256 values and exactly two condition keys: `stable-37.2.12` and `stable-37.2.13`. Each declares the dedicated owned API36 AVD and emulator serial. Keep the API36 Google APIs image, AVD configuration, launch flags, portrait/font1.0/disabled-animation/soft-keyboard settings, APKs and invocation identical. The deliberate configuration change is the stable emulator binary. Use one disposable guest at a time; preserve the older CI guest. Record launch binary/version/hash, image/config hashes, settings and every result. Run build work before booting the guest. Ambient host scheduling and evolving guest service/first-boot state are not controlled; this sequential comparison alone cannot prove a version-specific cause.

Build with the original `ANDROID_USER_HOME`:

```text
./gradlew -PfocusDiagnostic=true :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :focus-diagnostic:assembleDebug --max-workers=2
```

The driver requires matching verified app/diagnostic signatures, exact plan APK hashes, the named owned API36 emulator and absence of both packages before installing. It refuses package replacement or an APK containing the old app test classes. Each condition has one persistent directory/receipt created before mutation and exactly one unfiltered instrumentation invocation of the standalone APK. No `-e class`, repeated test, old suite, click/focus repair or run-ID nonce is used:

```text
python3 -B .github/scripts/focus_diagnostic.py --plan ABSOLUTE_PLAN_JSON --condition stable-37.2.12 --app-apk ABSOLUTE_APP_APK --diagnostic-apk ABSOLUTE_DIAGNOSTIC_APK --sdk ABSOLUTE_SDK
```

The second condition changes only `--condition` and the separately recorded launcher binary. The driver retains only validated scalar records and result/count/exit metadata from a bounded120-second invocation. Raw instrumentation messages are discarded; malformed/incomplete/truncated output, multiple/missing cases or cleanup failures cannot produce a pass. It removes only packages it installed into previously empty slots. The owned guest launcher separately captures/restores settings, stops the guest and independently verifies its process exit. Archive/hash-verify required evidence before removing generated outputs; preserve unrelated work and original failed evidence.

## Interpretation and stop conditions

Both conditions run once even if the first fails, because this is a declared version comparison, not replay until green. A pass only describes this minimal fixture in that condition. Two passes leave the original mismatch unexplained. A difference does not establish a version fix without supporting boundary evidence and a regression. The runner's `acceptanceGate=false` is deliberate.

If metadata identifies a concrete app/fixture/environment defect, deliver that narrowly with meaningful regression coverage and a fresh separately accounted unfiltered acceptance gate. Otherwise finish the fixed comparison, retain the unresolved issue and bring back an explicit test-platform decision. Do not add more conditions or replay the old full gate to seek a green result. Canary/QoS/affinity/power-plan changes are outside this comparison; the documented Windows headless/hybrid scheduling issue remains a hypothesis.

Current-head full ordinary CI and actual complete-diff Claude review/re-review remain required for the PR. Old local negatives, #55/#56's exact boundaries, issue31's maintainer closure and physical/live/release acceptance are unchanged. No production keyboard fix or human-device acceptance is claimed. No in-app availability copy changes because this slice supplies no user feature.

## Verification

Local verification passed656/656 JVM cases with no failures/errors/skips (including8 focus-parser cases), all19 Python wrapper/collector tests, app lint0, debug/shrunk unsigned release and standalone diagnostic APK builds. R8 executed in the first complete build; the final parser rebuild reused unchanged app/release outputs. Both APKs use the original configured debug signing identity. The generated projection matches its four originals, the diagnostic DEX contains its new fixture and no old app test classes, the172 ordinary native methods and production/native-test sources are unchanged, and273 relative-document links resolve. Prototype dependency/source-projection/task-selection build failures are retained separately; no device invocation occurred in them.

The two planned device outcomes remain pending at this checkpoint. No diagnostic device condition or old full suite has been invoked for this issue.
