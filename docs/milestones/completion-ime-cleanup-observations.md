# Completion combined-cleanup timeout observations

Diagnostic-only follow-up to PR165 and [investigation177](https://github.com/Thiesi/ChitChat-Mobile/issues/177).
PR176 stays held at14ac780; its original API35 failure remains FAILED. No old-head
retry or new exception is authorized. This change supplies no keyboard/product fix.

## Original observation and cohort

PR176 run42671065924 attempt1 failed API35 with Completion.presentationDarkFont200:
ComposeTimeoutException after5000ms, cleanup168 -> helper13 -> cleanup167, no cause
or suppressed exception. The old report cannot identify which combined predicate
failed. API36 passed253/253; its product guard passed. The Completion allowance
ended with165 and does not cover this failure.

Read-only original main push reports were audited for the four requested heads:
323df42/run42640743505, e0657e9/run42649879015, 1ab8acc/run42657292243 and
8c8be36/run42664980691. Each ran19 Completion cases with no failures/skips.
The same cleanup signature occurred in0/4 runs (0/76 cases). All four report ZIPs
passed published size/SHA256/CRC checks. These HTML artifacts contain no raw TEST
XML. The cohort is bounded, not a statistical flake rate or evidence of causation.
It does not erase PR176's negative or cover uninspected main heads.

## Failure-only scalar contract

The original5000ms wait still evaluates Compose IME bottom0 AND native hidden AND
native hidden, with identical short-circuit probe order/count. Each evaluation
only stores the results of its existing reads. The single existing closeSoftKeyboard
is unchanged. No extra probe, wait, deadline reset, hide, tap, focus action or retry.

Only a ComposeTimeoutException emits one fixed CCMCompletionIme record, then the
same exception object is rethrown. Logging failure cannot mask it or add a cause/
suppression. Success and non-timeout failures emit nothing. Records contain only
sample=last_wait_predicate, compose=Int-or-unobserved and native1/native2 values
hidden/visible/unknown/unobserved. Per-evaluation reset prevents stale probe data.

Unobserved means short-circuited or never evaluated; unknown means a native probe
actually returned null. Values are the last actual evaluation INSIDE the wait,
not a new post-timeout sample, atomic snapshot or proof of exact deadline state.
No current state is read after timeout. No text, URI, View, title or exception
payload is emitted.

The wrapper requests this exact log tag and accepts only all four ordered fields,
known scalar names, Int32 bounds and no trailing data. It uses the existing
failure-centered2000-record cap; no raw fallback or broader log retention.

## File channel for the sample (2026-11-16)

On PR #267 (CI run 43774242948) a #177 occurrence's sample was missing from `ime-lifecycle.txt`. The cap wasn't the
cause: `selected_records()` protects failed-test windows. The wrapper reads logcat with `adb logcat -d` once, after the
whole suite. The emulator's logcat ring buffer had rotated past a failure about eleven minutes earlier, so the record
never reached the wrapper. The retained window started after it. The owner ruled that occurrence covered once; it isn't
a precedent.

So the emitter also writes the same allowlisted line to a small file in the instrumentation's additional output
(`ccm-completion-cleanup/<Class>.<method>.txt`). The collector copies it beside the lifecycle file
(`reports/androidTests/device/completion-cleanup/`).
- **Content:** exactly `test=<Class>.<method>` plus the existing `sample=last_wait_predicate compose=… native1=…
  native2=…` line, failure-only. No text, raw dumps, view names or exception payload. `CompletionSampleFile` refuses any
  other line or identity, and the collector accepts only one exact line whose test identity matches its file name.
- **Bounds:** at most 256 bytes per file and 64 files per run. The collector rejects the whole set before copying if
  anything is foreign, malformed, mismatched, oversized or excess. It never changes the original native status.
- **Unchanged:** the 5000ms deadline, the short-circuit order, both native-hidden probes, the single existing hide, the
  logcat record and the 2000-record cap. Writing runs inside the same `runCatching` as the log, so it can't replace or
  suppress the timeout. No CI emulator setup change (no `logcat -G`). Measurement only.
- **Tests:** an Android case pins that the file holds only the allowlisted line with its identity and refuses free
  text, view names, path-like identities and the per-run excess. Collector script tests cover copying (status kept),
  rejection of foreign, malformed, mismatched, oversized and 65-file sets, and a quiet run with no samples.

## Verification and continuation

New allowlist tests first failed3/28 before the narrow parser/tag addition; all28
then passed. They check canonical fields, unknown/unobserved, garbage/tails,
out-of-range integers and the unchanged cap/failure-window priority. Six new
Android scalar-component cases check short-circuit reset, both native positions,
unknown/not-reached, never-evaluated timeout, emitter failure, original exception
identity, no added probes and silent success/non-timeout paths. Existing four
cleanup cases remain enabled. Compilation is not execution of these native tests.

All104 credential/device-free Python script tests passed locally (including28
journey-wrapper cases). The checked-in JDK17/Kotlin2.3.21 wrapper compiled the
Android test source successfully, offline with two processors/workers. No local
native/emulator/live task ran. Logs/source were CRC/SHA256 verified before output
cleanup: Python archive582f9b2c8d61fe5611c37e891c997661ccb6e6dd5a5bf7c8a1491698613f2065;
compile archiveec9eef2fda2a47251070eccca52516ff82fa21bd07b0f45b00d6a226422d77d7.

Normal full current-head JVM/lint/builds, original unfiltered native jobs and two
actual written reviews are required. No assertion or classifier rule is weakened.
After delivery, PR176 incorporates diagnostic main as new content and receives
fresh ordinary CI/reviews. A recurrence on that head requires STOP with these
scalars, no further heads for it. GitHub review-startup failures are tracked
separately; no additional request is allowed on14ac780. Two more startup failures
on new heads require technical-lead escalation. Native/device/live/release gates
remain separate.
