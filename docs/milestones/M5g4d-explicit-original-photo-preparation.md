# M5g4d: explicit original-photo preparation

This state prerequisite lets an explicit original-bound privacy choice prepare
unchanged photo bytes in the real room and DM private draft owners. It creates
no native button, upload call or send authority. The separate approved copy for
unsupported images and failed supported cleaning remains in
[files/media](../design/files-media.md).

## One original choice and worker

Starting requires fresh actual-entry proof, the current owner/target/private slot,
the exact current PRIVACY_CHOICE view object and its frozen selection. The initial
read producer must have completed its resource handoff. Stale, fabricated,
double-used, replaced, retired, background and wrong-target actions are rejected.
Current composer, actor/write, uncertainty, membership/mute and unusable-policy
checks remain in force. This choice is no general or per-send privacy opt-out.

An accepted action creates a new worker identity for the same original selection
and private slot. It moves the existing opaque raw allocation exclusively; no
provider is reopened. The final payload retains unchanged bytes, metadata hints
and their SHA-256. Its privacy fact is UNCHANGED_AFTER_CHOICE, never
LOCATION_REMOVED. Both CLEANING_FAILED and UNSUPPORTED_IMAGE require this action;
neither failure automatically starts it. Local budget/empty/read failures do not
become privacy choices or trigger another attempt.

Completion rechecks original session/server/account/generation/version, target,
slot, selection and work identity before updating only the private memo. It keeps
the latest caption/reply/uncertainty and does not replace the active pane. Per the
[accepted lifecycle decision](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6743408023),
already-started local work may finish after navigation/background. Replace,
discard or session retirement invalidates adoption first. Display/actions still
require fresh actual-entry proof. No HTTP, read, typing, history reconciliation,
quota attempt, POST or automatic Send anyway follows private completion.

Policy observations now use unchanged final size only after this READY result.
A raw choice's known original size is not a final-size policy fact. A known
server-size/type restriction is never a reason to bypass cleaning; current
transport policy and the actual server answer still govern the later upload.
Text-only Send remains blocked while attached, including after this preparation.

## Allocation ownership and cancellation

The retained handle atomically owns its preparation and reservation together.
Only the exact private NeedsChoice can transfer that ownership. Old handles are
emptied rather than left with a closable alias to the new owner's reservation.
The original read producer now cleans up the retained handle once constructed,
and its unwrapped reservation only before that point. Otherwise an old rejecting
callback could free bytes already moved to a successor.

Before hashing, the new task grows the shared process reservation to two rounded
byte sets plus4MiB workspace. Denial produces CLIENT_BUDGET, no ready payload or
retry. After the actual Default hash worker ends it retains two rounded sets
plus1MiB overhead. Replace/discard cancellation holds the charge until the actual
job completes; later selection still waits for the producer barrier. This is
exclusive ownership for current opaque private choices. Future thumbnail and
transport byte users must coordinate their actual lifetime before any transfer;
this is not a general shared-byte reference-counting API.

The ordinary Main-immediate launch owns the source even if cancelled before its
body enters. A terminal completion handler releases any unmoved source; it only
closes thread-safe atomic ownership/ledger state. It is not an early cancellation
handler and never touches UI or waits. Main adoption is synchronous, once, with
no suspension after the decision; rejected or throwing adoption releases the
result. These semantics are pinned to the existing coroutines1.10.2
[DEFAULT start source](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/CoroutineStart.kt)
and [Job completion contract](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Job.kt).
No dependency upgrade is included.

## Diagnostic-main integration

Original PR17614ac780 remains FAILED: API35 had the covered ReplyComposer
signature plus an uncovered Completion.presentationDarkFont200 combined cleanup
timeout; the second review never started because of GitHub internal errors.
No old-head retry, review replay or exception applies. PR178 delivered bounded
last-in-wait scalar diagnostics at a0a1fd6 after its own full gates/two reviews.
The requested four main reports contained0/4 recurrences; no cause or fix follows.

Per [disposition](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6749161777),
this branch incorporates that diagnostic main as new content, preserving published
history with a normal merge under the no-force-push rule. App production and JVM
test Kotlin remain identical to14ac780; the new native diagnostics/cases are exact
main content. Only the three documentation-prefix conflicts needed resolution,
retaining both contracts and recording delivery here. This head needs ordinary
fresh full CI and both written reviews. A recurring Completion timeout means STOP
with its new scalars and no further heads for this; original negatives stay failed.

## Verification and boundaries

Negative first: a real initial read produced a GIF choice; its callback moved
the allocation and rejected the old result. The original cleanup incorrectly
freed the successor's charge. The one-case FAILED result and XML are preserved
(SHA256 ead828e89ed52fab04210693577cfa7b3c05d9e32180a41cedcb5cd925822656).
After the cleanup correction, that regression plus the12 existing preparation
task cases passed13/13 (SHA256
56f9805b63ae751507e511ce5b692b3ba1a8e2a8a6252e1d4688715a840e3de6).
These are focused JVM observations, not native or live acceptance.

The corrected combined run passed68/68 with0 failures/errors/skips:1 ownership
regression,12 existing task cases,7 new original-choice task cases and24 cases
for each real room/DM owner (14 new state cases total). The wrapper/JDK17/
Kotlin2.3.21 run was offline, bounded to two processors/workers and used shared
homes/fixed scratch, without native/emulator/live tasks. Source/log/XML were
SHA256/ZIP-CRC verified; final focused SHA256:
580eccb58c4f2dd4f942295584b5765eccba08d1d105399e9d77f0cbe69413d6.

The first combined run passed66/68: two new tests mistakenly compared GET counts
after ordinary foreground reconciliation. Their assertions were moved to before
foreground, where local completion must make no request; production was unchanged.
That original FAILED archive remains SHA256
61a27131b20d219bbdfd6ac6f8f1e07dab40c8293b6f16ea5c8749ab9aaa1d5a.

Task tests check both privacy failures, exact unchanged digest/metadata, one-shot
use, stale handles, budget rejection, throw/reject cleanup and deterministic
pre-entry/already-retired cancellation. Real room/DM tests cover entry/owner/
identity guards, final-policy size, private navigation/background completion,
latest caption/reply retention, replace/discard/session retirement, shared ledger
cleanup and no network/send effects. Current-head full JVM/lint/debug/release,
unchanged unfiltered native jobs, product guard and both actual written reviews
remain required and are recorded on the PR. No input assertion, deadline,
workflow, layout, copy or native source changed.

Native/provider/device/live and release acceptance remain separate. Serialized
attachment POST, unknown-delivery reconciliation, actual multipart termination
proof, picker/consent/card controls, downloads and sharing follow; no usable
files/media completion is claimed.
