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

Local preparation:28 credential/device-free Python tests passed fresh (0.053s); portable actionlint1.7.12 validated the workflow. Optional ShellCheck/Pyflakes were unavailable and disabled only for that local validator invocation; no project check was removed. All222 Android compiled-input hashes match the parent build/archive exactly. No new local Android build or device experiment is claimed; the parent build/inert11-method proof is reused for unchanged native code. Paired and normal hosted results remain pending until their actual runs complete.
