# M5g5f: explicit DM-upload reconciliation state

This consumes [M5g5e's serialized DM upload](M5g5e-direct-attachment-upload-state.md)
without adding native controls or another upload. Files/media remains unavailable
in the app. Native pickers/cards/privacy and uncertainty controls, authenticated
downloads, sharing and provider/live/device acceptance follow separately.

## Explicit history and person-confirmed evidence

Check history requires the exact original UNKNOWN view/attempt, selection/private
slot, current owner/peer, stable entry and fresh root actual-entry/STARTED permission.
Current DM disclosure is required before admission and before/after each awaited
GET. The check uses the existing single DM job, excluding all active writes and
reads. It fetches one bounded latest context/history page with `consent=false` and
`reconcileBlock=false`: no paging loop, POST, new client, bootstrap or automatic
retry. Ordinary refresh, foreground entry and invalidation never create file
evidence or duplicate-risk consent.

Each check has a new request reference. Failed/cancelled/stale GETs cannot create
choices, overwrite successors or clear their busy state. Pre-action revalidation
failure becomes Failed rather than leaving Checking stuck. Privacy lost while a
GET is held cannot publish evidence or risk consent. Existing definite access-loss
handling retires canonical history, while preserving the private unknown draft.

Only a positive-ID, non-deleted outgoing DM with the exact viewer sender and peer
recipient, matching final-upload-byte SHA and a parseable server timestamp strictly
after the original attempt start is evidence. Missing/empty SHA, invalid/equal/earlier
dates, wrong parties/perspective and deleted rows are excluded. There is no text/name
match, hash deduplication or inferred receipt. The bounded page's absence is not
proof of non-delivery. Clock skew can miss advisory evidence; [issue184](https://github.com/Thiesi/ChitChat-Mobile/issues/184)
requires coordinator/presentation disposition before native evidence controls.
No earlier-row tolerance or changed timestamp contract is introduced here.

A match stays UNKNOWN until the person confirms that exact review/candidate and
current canonical row. Confirmation rechecks owner, original attempt/slot and fresh
entry/privacy, releases only the original file and clears only its original content
token. Later caption/reply changes, including edit-away/back, survive. The canonical
history supplies the message; no synthetic bubble or `confirmedSendId` is invented.

No match, or explicit rejection of offered evidence, creates a NEW duplicate-risk
choice. Its fresh confirmation only returns the same immutable file to READY;
a later fresh Send is separate. Old evidence/risk/close/discard callbacks cannot
act on refreshed choices or a later upload attempt. READ/BLOCK/AUTHOR/REACTION
uncertainty is independent; this file GET/confirmation never reconciles it or
grants text-SEND consent. Existing ordinary relationship refresh semantics remain
unchanged. Blocked-peer history is readable when otherwise authorized; returning
a file to READY grants no permission to send through a blocked relationship.

## Lifecycle, private state and modal boundaries

Results and bounded candidate facts are private VM memory with opaque strings,
no saved state, logs, provider URI, payload bytes or retained UI probes. Navigation,
genuine background, discard and owner retirement clear review choices. A late
non-cancellable GET cannot repopulate a successor peer/owner or clear its busy flag.
The original UNKNOWN file survives unless explicitly discarded or owner-retired.

Configuration retains the same stable-entry request/result. Display/actions need
fresh restored actual-entry permission. Restored foreground with an existing
review does not queue a replacement GET. Closing private choices works during
session revalidation/privacy loss, but remains original owner/entry fenced;
display, evidence and risk confirmation still require ready/current disclosure.
Closing Checking cancels only that owned GET. Closing never supplies read proof.

The core review suppresses viewport read/seen/local-attention proof and other
composer, author, reaction, block, read-retry and target actions, while queueing
automatic invalidation. An admitted ordinary explicit refresh retires the review
without unlocking the file. Opening/closing/confirmation clears visible-newest
proof and live-edge state, so request cleanup or queued GETs cannot synthesize a
whole-peer read. A new real viewport must establish the existing read contract.

The separate native consumer must render these choices as a root-suppressing
modal and include them in typing/input suppression, with fresh actual entry,
STARTED and owner checks for every display/callback. No layout, copy, keyboard,
focus, typing-publication, native predicate/deadline or workflow change ships here.

## Evidence and remaining gates

The dedicated close regression failed before correction: private choices could
not close during session revalidation. That assertion was the sole failure in
two lifecycle cases; same-entry restoration passed. CRC-verified source/log/XML
archive SHA256:
`fa8bdf3356cad35bacb694de9bddbbadcc0f927f1ac3f030c0ac147d6ee8389e`.
An earlier copied fixture still referenced `roomId`; compilation stopped before
any tests ran. Its separate source/log archive remains
`024491d80e7501ee3b108654ceca394efc5a9a636ec855670a0a64fdcd372d1d`.

After correction, all167 DM-package JVM tests passed:30 new review,23 upload,
24 attachment-draft,39 state,25 author and26 reaction cases; zero failures/errors/
skips. The three current Kotlin files match the CRC-verified source/log/XML archive
SHA256 `958c7417167373c00295045b068d6b8d4fe3b3d69d4c0128f2a1ed9fb44b43df`.
The41.62s wrapper run used JDK17/Kotlin2.3.21, offline/shared homes/fixed scratch,
two CPU/workers, and no native/live invocation. Generated build/cache files were
removed after verification and the read-only scratch guard passed.

These are deterministic VM fixtures, not Android configuration, provider, physical
device or live acceptance. Full current-head JVM/lint/builds, unchanged unfiltered
native gates and both written reviews remain required; original results and
dispositions belong on the PR. Backend minimum remains3.5.0.
