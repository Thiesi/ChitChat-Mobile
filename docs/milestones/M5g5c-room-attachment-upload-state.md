# M5g5c: original-bound room upload actor core

Internal room state over the delivered preparation/transport ownership. No picker,
card, Cancel button, presentation change or usable files feature is added. UNKNOWN
is locked: explicit history/SHA evidence/person confirmation/duplicate-risk state
is the following consumer, before DM/native controls. No resend or consent-clearing
shortcut exists here. Existing feature-availability disclosures remain accurate.

## Explicit admission and original ownership

Send requires the fresh actual entry, foreground owner, current room/selection/view,
original private slot, a completed producer handoff and exact READY allocation.
Root consumers must include actual entry identity/STARTED and root-modal permission
in their live predicate. Local author/reaction/ping/draft-manager modal guards bind.
Freeze the rendered session, entry, slot/content token, PHP-trimmed literal caption,
reply, final SHA and attempt start. Empty captions are valid; the 4000-codepoint cap
does not parse /ping, emotes or other message commands. Guest/policy/final-size checks
do not grant authority; concrete transport rechecks original access/policy/CSRF.

The process-backed retained transport is injected into the real room ViewModel.
Its narrow interface binds scalar StateFlow facts before HTTP, inside the existing
allocation try/finally. It exposes no Call, RequestBody or bytes. Rejected binding
and typed local-policy changes spend no POST and release only the transport hold.
No replacement client/jar/dispatcher, provider read, bootstrap or replay is added.

The active upload marker joins room serialization: admission waits for other room
writes/read POSTs, fences prior canonical GETs and defers new canonical sync. Navigation, including another
room, preserves the authorized POST and its original private result. Genuine
background cancels it, keeping the marker through actual callback/writer stop.
DEFAULT cancellation before entry retires its queued marker without a POST.

A retired account/generation detaches its actor marker so its successor can work;
the old transport allocation remains charged until physical stop. Its completion
cannot clear the new owner's busy flag or repopulate drafts. No global memory reset.
This differs from same-owner navigation/cancellation, which retains serialization.

## Progress and outcomes

Buffered file bytes are not full request completion. FINISHING uses the actual
requestBodyEnd/full length. A held Cancel rechecks fresh facts; it cannot abort a
known-finished body. Cancellation alone proves nothing. After actual transport stop,
a conservative attempted upper bound smaller than the full multipart length proves
incomplete writing. A possibly complete throwing final chunk or full-body interruption
stays UNKNOWN. No UI ring percentage or later keyboard/window sample is evidence.

Definite refusals retain file/caption/reply and typed server metadata. A 413 remains
definite even after full-body writing. Local policy failure is distinct from a
server refusal; known final size remains observable through upload/unknown phases.
Definite room_forbidden/room_not_found clears the current original transcript while
keeping the private draft; a late refusal cannot clear another room.

Validated 201 is recorded before any fallible GET. The private state is CONFIRMED,
with prepared bytes released after actual stop. Fresh actual-entry adoption clears
only its original file/slot; a content token preserves later edits, including an
edit away and back to the same string. Then the ordinary canonical GET supplies the
bubble. A failed GET never turns the positive ACK into unknown delivery. A result
from a departed entry waits privately for a fresh adoption. Completed entry probes
and job references are cleared; long-lived attempt metadata holds no UI/payload or
full session snapshot. Handles/debug strings remain opaque and unsaved.

UNKNOWN retains the file and frozen attempt metadata. Ordinary refresh/SSE/resume,
text duplicate-risk controls and digest equality grant no file resend consent.
Remove/Replace cannot bypass it. The next explicit reconciliation consumer must
use this attempt, original owner/slot and fresh entry; only own SHA evidence after
attempt start can be offered, never automatically adopted. Existing explicit
whole-draft discard remains original-bound; native unknown/discard copy is pending.
File-only, pending and unknown drafts count against the existing 100 slots.

## Regression evidence and boundaries

JVM negatives were retained before correction:

| Boundary | Original result | Source/log/XML archive SHA256 |
|---|---|---|
| Remove/Replace crossing a send/cancel cycle on the same selection | 2/2 failed at the stale-control assertions |5a6299c2e3c55f92e5e89aa3573eb77d92e6b9d9ef9b3c03eb64859ae6608bac |
| Authoritative room access loss leaving cached transcript visible | 1/1 failed at transcript retirement |7f911f07dec4006df1cf8a74e95cac1f0fe3fc2866b3266448652f8608183a79 |
| Retired transport blocking its successor's room load | 1/1 failed at successor admission |725d7e730f0d250a288e2303c22b7169b64b8cc6537a07f2e3c269510add354f |

An intermediate 104-case run had 103 passes and one pre-existing TLS fixture failure:
it required MockWebServer to record a cancelled upload (expected 3 requests including
login, got 2). Actual writer/charge checks passed. Cancellation can close before
buffered headers/body reach the recorder. The fixture now counts exactly one client
upload interceptor entry and bounds remote receipt to 0..1. Its writer hold, 5000ms
latch, actual-stop and charge checks are unchanged; no flush/wait/deadline repair.
The failed archive remains5aea87757ba69b7483d5946db9614095225c1555b6c9b088b6632f3480d5ab1a.
Earlier 99/104/106-case passes are retained separately, not substituted for final proof.

Final focused run: 107/107, no failures/errors/skips; 20 new actor cases, 9 session/TLS,
21 existing conversation, 24 attachment-draft, 18 draft-retention, 4 allocation,
5 exchange and 6 multipart cases. It adds 22 JVM cases overall. Wrapper/JDK 17/Kotlin 2.3.21,
offline/two CPU/workers/shared homes/fixed scratch; zero native/live invocations.
Current Kotlin source/log/XML SHA/CRC verified; final archive SHA256
c4e82e5a6d0505c803885d86624ada05b0dab6a0ab90dd6afd7673affc08be9e.
State fixtures are not real-provider/native/live proof; companion TLS cases verify
the concrete observer/one-shot/actual-stop seam. No native predicate, input action,
deadline, workflow, presentation or release gate is changed. Full current-head CI
and both written reviews remain required and are recorded on the PR.
