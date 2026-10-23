# Platform, Compose and observer input comparison

Standing authority: build/test/CI/emulator/integration investigation, diagnostic instrumentation, regression coverage, bounded comparisons and approved-scope corrections are routine engineering work. No repeated approval is required. Escalation is reserved for product/scope/validation/risk/access decisions. Preserve every result; a diagnostic repetition never erases a failed gate or establishes a cause by itself.

## Question and fixed plan

PR60's first Linux run failed179/180 in the existing minimal traced Material3 field, before parent recomposition. The connection and show request existed, but post-timeout IME-window metadata showed NO_SURFACE. Its later show abort followed cleanup, not an established initiating cause. Original room and tracing failures remain preserved; no keyboard workaround, weakened assertion or exception is authorized by this comparison.

The new credential-free comparison uses the same ComponentActivity, Compose owner,280x56dp field bounds, one system-routed finger tap and5000ms focus/display/positive-IME predicate. Four cases compare AndroidView/EditText, legacy BasicTextField, legacy Material3 OutlinedTextField, and that same Material3 field with the existing explicit observer. Native fields use their real native focus callback; Compose fields additionally require semantic focus. Native window focus/editor/visible-IME/inset are independently checked after success. No text injection, requestFocus/show/restart, new pre-tap readiness condition, authentication, provider, real content, screenshots or device-setting change.

Normal CI executes one trial per case. Explicit `imeComparisonRounds` is bounded1..25; the first local diagnostic cohort is20 planned rounds per case, without stopping on a desired success. Each case stops and fails at its FIRST negative outcome; the other independently scheduled cases still execute. Successful rounds close the keyboard using the existing control's cleanup boundary and recreate only their field. A cleanup failure also fails the case. This is not a rescue or repeat tap within a trial. Scalar-only outcomes (including before/after state) are retained through instrumentation results; failed cases include all prior rounds and their first failure in the assertion. No arbitrary exception tails/raw dumps/editor contents are serialized by the comparison.

Case scheduling/order, native auto-focus and the AndroidView bridge are potential confounders; before-tap metadata makes them visible, not silently equalized by forcing focus. This comparison alone cannot establish statistical equivalence, eliminate a platform bug, or prove that traced and original untraced failures share a cause. Interpret failures at the actual phase before considering a product correction.

No existing case, deadline, predicate or CI filter changes. The existing failed runs stay failed. New comparison/planning tests must compile and run before committing the next PR60 revision; then obtain that revision's CI and full initial/fresh review cycle. No production/dependency/backend change is part of this probe.

## Build evidence

The first comparison build compiled the test APK but failed lint's `ComposableNaming` check: the new helper was named `field`. Its log, fixture source, lint XML and fresh JVM results were archived before correction. Build log SHA256`1AE562BE57A95758F5A0D7749C0B6BAE9364942428B2C13FF88418C3A530DFA2`; fixture SHA256`D5592D79B2279CA1F94140C111DC2C5D01D5E8CCEB86FEA8365920CB06420E38`. Renaming only that composable to `ComparisonField` addresses the finding without suppressing lint or changing input semantics.

The separately labelled corrected five-task build passed in 6m15s (129 tasks: 11 executed, 118 up-to-date): debug/release/test APK, 648 fresh JVM tests with no failures/errors/skips, lint0. R8 was UP-TO-DATE, not freshly executed. All previous 220 source/build/host inputs were unchanged; the comparison file is the only added compiled input. All 221 copies, reports and three APKs were hash-verified and archived. Production debug/release APKs remain byte-identical to the previous diagnostic build. Build log SHA256`02098C7F0B67354126F03896BBD22FC552115677E14F95E2C3D55C370FC67EE4`; new test APK SHA256`F76BF6560C5BBCFFAC8A656A9C9F11995CAD050B4EFE5550F817C43DD9D0E272`.

An initial archive-helper attempt failed before archive creation/device use because it decoded PowerShell's UTF-16 log as UTF-8. BOM-based decoding corrected only the ignored local helper; original log bytes/hash were preserved. No Android source, environment, predicate or result changed. The existing 19 host tests also passed fresh in 0.116s.

## First local cohort: negative evidence

The first actual four-way invocation ran on dedicated Windows/API36 emulator-5554, with verified installed APKs and no settings/phone/live-server access. Instrumentation ran all six tests in 335.99s: four passed, two failed; adb0 was not treated as success. No invocation timeout, retirement failure or retry occurred. Final instrumentation bundles retained every trial; the first negative retired its case as planned.

| Case | Successful trials before retirement | First failure (zero-based round) |
| --- | ---: | --- |
| AndroidView/EditText | 18 | 18 |
| Legacy BasicTextField | 20 | none in this cohort |
| Legacy Material3 OutlinedTextField | 6 | 6 |
| Explicitly observed Material3 field | 20 | none in this cohort |

Thus 66 trials actually executed: 64 passed and two failed, not 80 successful trials. Both failures had focused/resumed windows BEFORE the tap, then a focused real editor with native IME visibility false/bottom0 and Compose IME0 after the strict deadline. Neither is merely a missing Compose inset. Both later IME-window samples reported NO_SURFACE/not-visible. Input-service flags reported shown, but both service dumps included a timeout marker; those captures are not complete service-state evidence. Samples are sequential/post-timeout, not atomic proof of the initiating fault.

The failure shape therefore occurs without the explicit observer and with a real Android EditText, not just the app's chat composer. EditText is still hosted in AndroidView inside the Compose owner, and all cases still share instrumentation, native tap routing, lifecycle/cleanup, IME and emulator. This does NOT yet prove a framework bug, eliminate Compose-owner/common test effects, establish a common root cause with Linux, or justify a production workaround. Basic/traced successes do not establish immunity. Next isolate a platform Activity/View-only control and compare the same strict contract; routine follow-up investigation needs no approval, while validation/privacy boundaries remain unchanged.

The live collector observed 882 safe records and retained the first failure plus bounded context (596 records), zero errors/overlong lines. Native show requests existed and the retained native failure's show abort followed cleanup, not an established initiating cause. Instrumentation log SHA256`EEAE00F8A341C8D6794EAC4A3569A639CEE6023AF0444ED01EA90587165096B1`; trace SHA256`BE8605A36BC59F86AA7E64A466C90E985E126B2B53D771BECAFC6A95D05BB699`.

No full local suite, current revision's Linux/review result, correction, exemption or release readiness is claimed. All prior negative runs remain preserved and gating.
