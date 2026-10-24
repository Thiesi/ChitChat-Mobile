# Fixed IME case-history comparison

Parent [PR60](https://github.com/Thiesi/ChitChat-Mobile/pull/60) head `d302706ab4588270310d304ef70de22e4ac8a612` failed hosted Linux CI40040938477:194/195 native, zero skips. The standalone100-cycle control failed at its first tap. The retained trace confirms the immediate preceding methods were Basic, Material, AndroidView/EditText, traced Material (all passed), then the control. New target-window metadata observed its own focused native editor/window and IME0 at the later receipt; later IME-window sampling recorded NO_SURFACE. These are sequential observations, not a cause or internal-deadline proof. The Windows-only #55 exception does not cover a failed required hosted check.

Hypothesis: preceding test-window/input-session history may affect the control. Two independent fresh-emulator CI jobs run exactly once, fail-fast disabled:

| Arm | Selected native methods | Required observed order |
| --- | --- | --- |
| isolated | Original standalone control | Control |
| after-comparison | Four original NativeInputComparisonTest methods, then the same control | Basic, Material, AndroidView/EditText, traced Material, Control |

The harness verifies the native source/toolchain files are unchanged from d302, builds one debug/test APK pair with matching signing certificates, requires one fresh CI emulator/API36.0 and records source/image/APK provenance. Equal package-verify preparation addresses the separately captured pre-test DexFile-verification ANR; it is not a keyboard warmup. No app/test source, physical tap, focus/display/positive-IME/5000ms predicate, tracing flag, retirement policy or100-cycle loop changes. The four-method predecessor deliberately includes its existing traced control; the standalone control remains untraced.

Each job has a30min outer bound; APK build600s and one native instrumentation invocation600s. Native failures/partial outcomes stay failed. Status validation requires exact start/terminal identities/order/counts and a complete success summary; ADB exit0 alone is insufficient. Skips, duplicates, missing results and unknown identities cannot pass. Collector errors are separately reported and cannot rescue a native failure. The unchanged allowlisted TraceCollector emits bounded lifecycle metadata; no broad raw device dump, live account, phone, backend or private configuration is used.

Workflow artifacts `ime-history-isolated`/`ime-history-after-comparison` contain source/environment/APK metadata, build/native output, filtered lifecycle/status and result.json. Native stdout provides full selected-method order even when the bounded lifecycle window drops older records. Do not alter/replay an arm until green. Both arms passing is non-reproduction; differences are association with fresh-instance/host/order/cache/timing confounders, not a correction. Incomplete arms prevent a complete comparison.

This is diagnostic-only CI infrastructure. Existing unfiltered195-method CI remains unchanged and independently required; subset passes never replace its194/195 or the alternate Windows193/195 negatives. Both PRs remain unmerged while non-exempt failures/required checks are unresolved. The manual GPU comparison is deferred pending this immediately observed cross-host case-history question. No production fix, exception, release readiness or added parity is claimed.

Local preparation at87b96:28 credential/device-free Python tests passed fresh (0.053s); portable actionlint1.7.12 validated the workflow. Optional ShellCheck/Pyflakes were unavailable and disabled only for that local validator invocation; no project check was removed. All222 Android compiled-input hashes match the parent build/archive exactly. No new local Android build or device experiment is claimed; the parent build/inert11-method proof is reused for unchanged native code.

## Recorded first comparison

Immutable head `87b96c261faf1f886b78ef826b4721de9c0de81b`, tree `63a018fc71fe2308bf34b0c6735dcdeb6122a13b`, [diagnostic run40045554051](https://github.com/Thiesi/ChitChat-Mobile/actions/runs/40045554051):

| Arm | Outcome | Evidence integrity |
| --- | --- | --- |
| isolated |0/1 methods passed; original control failed at cycle43 | Exact one-method order, ADB exit0 but native failure, no host timeout, no collector/integrity errors |
| after-comparison |4/5 methods passed; original control failed at cycle7 | Exact four predecessors all passed, then control failed; no host timeout or collector/integrity errors |

Each arm built successfully, passed the unchanged-source guard, used API36.0 and matching app/test signers within that arm. Independent debug builds/signers produced different APK bytes between arms; they are not byte-identical controls. The failure receipts show focused native editor/window and IME0, with later NO_SURFACE observations; none proves a cause or internal deadline state. The four predecessors are not necessary to reproduce the control failure. Different failing cycles do not establish a causal effect. Both failed arms are retained and will not be replayed to obtain green.

The independent [normal run40045554028](https://github.com/Thiesi/ChitChat-Mobile/actions/runs/40045554028) passed195/195 native with zero skips (7m25.73s), lint0, and648 JVM results from Gradle cache (not fresh execution). Debug/release builds passed with R8 also cached. Filtered collector8252 safe records/2000 retained, zero errors. This full pass is recorded alongside the failed diagnostics and older Windows/parent negatives; it does not resolve the investigation. Both actual-head Claude reviews40045553895/40045861945 returned no worthwhile fixable findings; their full input/head/tool audits were verified.

## Next fixed View-only comparison

The next workflow revision runs only `view-only`, once on a fresh Linux CI emulator: existing `ViewOnlyInputComparisonTest#nativeEditTextWithoutComposeOwnerShowsKeyboardAfterOneTap` with `imeComparisonRounds=25`. This is the fixture's existing maximum, with its default Espresso retirement/replacement hierarchy, shared native tap injector and strict5000ms wall-clock predicate. No Compose test rule, composition, AndroidView or Compose owner is installed. No source, tap dwell, focus/show, assertion, polling interval, tracing or retirement behavior changes. The first failure ends the method; there is no rescue or continuation after a failure.

This asks whether the Compose/modal pipeline is necessary on hosted Linux. Earlier Windows View-only failures are already retained in the parent investigation; a hosted25-round pass would be bounded non-reproduction, not a correction or proof that Compose is responsible. Shared injector, Espresso cleanup, Android input/IME and host/image conditions remain possible factors. No new phone/manual host run is needed for this arm.

The launcher retains the original two mode definitions for interpreting pinned history, but the workflow matrix selects only this new arm, avoiding replay of the failed pair. Validation requires exactly one expected method and, for a claimed pass, exactly25 ordered successful round receipts0..24 from the fixture's result bundle. Stack copies, missing/duplicate/out-of-order receipts, a failed round, skipped method, process failure or timeout cannot pass. Round summaries retain only ordinal/Boolean values. The normal unfiltered195-method gate is unchanged and remains independent. Local preparation for this revision:34 fresh Python checks passed (0.175s), actionlint passed; native inputs remain unchanged. Hosted results for this next revision are pending, never assumed.
