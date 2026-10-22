# Failure-only native IME server snapshot

Test observability for [issue31](https://github.com/Thiesi/ChitChat-Mobile/issues/31), not a keyboard correction or product feature. Production code, dependencies, focus/IME behavior, existing predicates and five-second deadlines are unchanged.

## Evidence and purpose

PR49 head30d05 passed hosted149/149 but failed its local146 gate at `NativeInputDialogJourneyTest` cycle index13: one OS-routed input tap acquired focus, yet the IME inset stayed0. The same APKs subsequently passed the isolated100-cycle comparison on the dedicated Android17/API37 phone in144.2s, with unchanged normal animations. These comparisons do not erase the failed full gate or establish an emulator-only bug.

The emulator's existing `ImeTracker` buffer was correlated with the original monotonic snapshot, without rerunning the test. At uptime45375.145s it recorded a show request, with no shown event before the assertion snapshot at45380.175s. Cleanup requested hide at45380.180s; the original show was aborted at45380.187s. Provider metadata at that abort reported serverVisible=false/isDrawn=false. That is **after** timeout and during cleanup: it does not prove that either flag stayed false for the preceding five seconds. [Android16's provider source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/services/core/java/com/android/server/wm/ImeInsetsSourceProvider.java) explains its eligibility/abort logging, but the branch is not proof of the exact emulator binary's implementation. Raw scalar evidence and hashes are recorded on issue31/PR49.

## Boundary

The shared `tapNativeInput` catch path now snapshots the server **after the original assertion timeout but before throwing into test cleanup**. Successful input paths do not run a command or gain an extra wait. Existing failed assertions remain failed; the original timeout is retained as their cause. The client-side inset/session observations are frozen before the server read, so a later teardown or asynchronous state change cannot relabel them.

`dumpsys -t 2 input_method` bounds the service wait. Only65,536 transient characters are read, then the descriptor is closed; at most40 fixed Boolean/numeric flags survive. No raw dump, title, EditorInfo, account, text, exception or arbitrary fallback is stored/logged. Unknown fields, permission failures and unsupported formats become unavailable (`flags=null`), not a false hidden-state claim. Start/finish uptime and a buffer-limit indicator describe the observation's timing/coverage. The dump itself can affect timing **after an already failed assertion** and is not a production fix.

Use only synthetic test fixtures. The explicit collector self-test also invokes it to validate safe capture; that is not an input readiness check. It never requests focus, calls IMM getters that flush focus, shows/hides/restarts input, retries taps, changes settings or repeats a failed gate.

## Verification

The five-task local build passed in8m53s: debug/release/test APKs,598 freshly executed JVM tests (zero failures/errors/skips) and lint0. R8 was reused from the valid build cache, not freshly executed. The unchanged ten Python wrapper tests passed in0.017s. Six new instrumented cases passed6/6 in0.434s, covering fixed fields, unknown/private canaries, malformed/partial values, unavailable metadata, bounded records and a real capture. The self-test returned three actual server flags in117ms with `limitReached=true`; fields beyond the character cap are not observed or invented. This self-test is not a captured failed-input handoff.

The full143 native run and exact-head hosted CI/initial/fresh Claude results are recorded with the delivery PR and issue31 after execution; completed successful gates remain merge prerequisites. PR49 and issue31 remain unresolved, and PR49's failed full result cannot be replaced by these diagnostic tests or this branch's different suite. No phone/live-server/integration-file/backend-checkout access was used to implement this slice.
