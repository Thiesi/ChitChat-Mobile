# M5g5d: explicit room-upload reconciliation state

This consumes [M5g5c's original room upload](M5g5c-room-attachment-upload-state.md)
without adding native controls or another upload. Files/media remains unavailable
in the app. DM upload/reconciliation, native pickers/cards/modals, downloads,
sharing and provider/live/device acceptance follow separately.

## Explicit history and evidence

Check history requires the original UNKNOWN view/attempt, private slot/selection,
owner, current foreground room and fresh root actual-entry permission. It joins
the existing room request serialization, excluding active writes and read POSTs.
One bounded original-session context/history/pings pass uses existing operations;
there is no paging loop, background query, extra client or POST. Ordinary refresh,
entry, SSE and text-send reconciliation never create file evidence or risk consent.

Each check has a new reference. Failed/cancelled/stale checks cannot grant choices,
overwrite successors or retire their busy state. A failed pre-action session
revalidation becomes Failed rather than leaving Checking stuck. No retry occurs
without a new explicit Check history. Definite access loss keeps the existing
canonical retirement behavior and the private unknown draft.

Only a non-deleted message in the exact room, authored by the viewer, with the
same final-upload-byte SHA and a parseable server timestamp strictly after the
attempt start is evidence. Missing/empty SHA, invalid dates, equal/earlier times,
other authors/rooms and deleted rows are excluded. This uses no text/name match,
clock-skew guess or digest deduplication. The bounded returned page is not proof
that an absent upload was never committed.

A match stays UNKNOWN until the person confirms that exact evidence choice.
Confirmation rechecks the original review, row reference, current canonical row,
owner/slot/attempt and fresh actual entry. It releases only the original draft
allocation and clears only its original content token. Later caption/reply edits,
including edit-away/back, survive. The existing canonical message supplies the
bubble: no synthetic receipt or message is invented, and no POST is sent.

No match, or explicit rejection of offered evidence, creates a NEW duplicate-risk
choice. Its fresh confirmation only returns the original immutable file to READY;
the person must make a later fresh Send. File/caption/reply stay attached and
independent text/author/reaction/read uncertainty is not cleared. Old evidence,
risk and close presses cannot act on a refreshed review or a successor attempt.

## Entry, lifecycle and privacy

The state is private VM memory with opaque string representations, no saved state,
logs, provider URI/reopen, bytes in review objects or retained UI entry probes.
Names/timestamps are only the bounded candidate facts needed for the later UI.
Navigation, genuine background, discard and session retirement clear the private
review; the original UNKNOWN file survives unless explicitly discarded/retired.
Late GET completion cannot repopulate another room or owner.

Configuration retains the same stable-entry request/result. Fresh display and
action callbacks must recheck the actual root entry/STARTED/owner. Foreground
restoration with an existing review never replaces it with an automatic GET.
Closing private choices remains possible while session revalidation is required;
display/confirmation/risk actions still require a ready original owner. Close is
neither a fresh viewport proof nor retry/send consent.

Core state suppresses viewport read proof and other composer/mutation/target
actions while the review is open, and queues automatic canonical invalidations.
An admitted ordinary manual refresh retires the review without unlocking the file.
The separate native consumer must render this as the root-suppressing modal,
include it in typing/input suppression and bind every displayed/callback entry.
No modal, copy, layout, focus, keyboard or typing-publication change ships here.

## Evidence and remaining gates

Two lifecycle regressions failed before correction: restoring foreground erased
the completed private check, and closing choices required successful revalidation.
Both failed at their dedicated assertions with no additional errors. Source/log/XML
archive SHA256:
`4553c22d3868298f55a2c92f80db3e04352c6649f0ede7b68cff2d1dcb7f884f`.

After correction, all 194 conversation-package JVM tests passed: 22 new review,
20 upload, 24 attachment-draft, 18 draft-retention, 21 conversation, 39 realtime,
3 ignore-policy, 23 author and 24 reaction cases; zero failures/errors/skips.
Current three Kotlin files match the CRC-verified source/log/XML archive SHA256:
`6ed8ea88e67b23d1c4a246311d3eb515df9e2b3d97632184c2a87b4bc355b6c1`.
The focused wrapper run used JDK17/Kotlin2.3.21, offline/shared homes/fixed scratch,
two CPU/workers and no native/live invocation. Earlier compilation stopped at two
Long/Int test literals before any test ran; that source/log archive remains
`9b981f0f155205980922bf4592a5207ae454d7434203801681e481d2e0706233`.

These are VM fixtures, not Android configuration, provider, device or live proof.
Full current-head JVM/lint/builds, unchanged unfiltered native gates and both actual
written reviews remain required, with original results/dispositions on the PR.
There is no native predicate/deadline/workflow or backend-baseline change.
