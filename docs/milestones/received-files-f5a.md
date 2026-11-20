# Received files F5a: Save, Share and Open, protocol and state

F5a is the engineering half of F5 (the TL plan and rulings of 2026-11-20). It adds no UI: F5b puts Save, Share and Open
on the cards, the viewer and the message actions, with the PL's copy. Until then the existing disclosures stay accurate.
The presentation contract is [files-media.md](../design/files-media.md) "Save" and "Share out"; the disk use matches
[privacy-policy.md](../design/privacy-policy.md) "What the app stores on your phone".

## Bytes on disk, never in memory

- `AttachmentDownloads.stream` is F1's path unchanged in substance: rendered-session access, the ID-only original path,
  exact length, the SHA-256 hashed on the way, typed refusals, the session-wide 429 wait, never resent. It holds no memory
  lease and no whole-file buffer, only the transport's 64 KiB chunk, and has no size cap of its own.
- **Lazy open:** the destination is opened once, when the first body byte arrives. A failure before the body (403, 410,
  429, network, a session change) never opens, creates or truncates it. A document the person picked may be an existing
  file, so this is the only safe order (TL).
- **A fence per chunk:** the caller's check runs before every chunk; a stale session stops the write.
- **Time:** streamed downloads may run 60 minutes overall; display loads keep 10. Both keep the 30-second idle read.
- **Space:** a Share or Open copy needs `getAllocatableBytes` on the cache volume of at least the size plus 10%, or it
  stops with `NO_SPACE` before anything is written.

## Save

- `FileTransfers.requestSave` binds a pending request to the rendered session (origin, account, generation) and the
  attachment, before the picker opens (M2b2's document-picker rule). Only that request's `saveChosen` starts the download.
  A result after a session change, or with no pending request (a process death), does nothing.
- `DocumentSaveDestination` takes only the `content:` URI the app's own `ACTION_CREATE_DOCUMENT` returned, opens it with
  "wt" at the first body byte and keeps no grant. The suggested title is the sanitized server name; the MIME type is the
  server's (octet-stream when missing).
- **The document is never deleted**, in any case (the exports rule). A failure reports whether any byte was written
  (`wroteBody`): false means a picked existing file is unchanged. An integrity mismatch is `DAMAGED`, with the document
  kept.

## Share and Open copies

- **Layout:** `cache/shared/<accountKey>/<random 128-bit>/<sanitized name>`. The account key is a SHA-256 of origin and
  account ID, so no identifier is written to disk.
- **FileProvider:** androidx.core, authority `<applicationId>.files`, not exported, `share_paths.xml` exposing only
  `cache/shared`. Intents carry only `FLAG_GRANT_READ_URI_PERMISSION`: never write, persistable or prefix grants, never a
  global grant. Share is `ACTION_SEND` with `EXTRA_STREAM`, ClipData and the server's MIME type in a chooser. Open is
  `ACTION_VIEW` with that type, or a plain chooser when it's missing or octet-stream.
- **Verified before handoff:** a failed, cancelled or mismatching copy is deleted and never offered. A copy is deleted
  only once its cancelled download has really ended (the transport returns after its producer stops), so a file is never
  deleted while bytes may still go into it.
- **Lifetime** (TL decision of 2026-11-08; privacy-policy.md): kept 24 hours after handoff, then deleted at the next app
  start or the next share or open. A grant is revoked before its file is deleted.
- **The account's session:** `ShareCopyTeardown` runs at exactly push's hook points, inside the session lock. `retire`
  (sign-out, an ended session, the deletion teardown) deletes that account's whole folder, revoking first.
  `sessionChanged` keeps only the current account's folder, using push's own predicate (a session needs both a user ID
  and a sign-in time). A cold start, a revalidation and the update screen never reach it without the user, so they
  keep the current account's copies.
- **Start-up sweep:** off Main, the expired copies and every folder but the vault's stored account (no network, no
  published user). If a session event has already decided the current account, only the expiry part runs.
- **In flight:** sweeps never delete a copy still being written; the transfer's own session fence cancels it, and the
  copy is then discarded.

## Names and APKs

- `FileNames.sanitizedFileName`: no path separators or `..`, no control or Unicode format characters (category Cf,
  including the bidi overrides and isolates, so `photo<RLO>gnp.apk` can't pose as `photo…apk.png`), no characters common
  file systems refuse, and at most 120 UTF-16 units with the extension kept and no split surrogate pair.
- `FileNames.isApkLike`: the APK MIME type, or a sanitized name ending in .apk, .apks, .xapk or .apkm (any case). Such a
  file is Save-only: `prepare` refuses Share and Open, and **F3 no longer previews it** even under an image type (until
  F5a, a previewable `pic.apk` showed as a photo frame); it gets a card.
- `FileNames.withoutBidiControls` is for F5b's displayed card names (the PL's list; ZWJ and ZWNJ kept).

## State

`FileTransfersViewModel` (app-scoped, like `AttachmentImagesViewModel`) owns one transfer at a time: `Running` with
progress from the destination's own writes, `Ready` (a verified copy waiting for its handoff), `Saved`, or `Stopped` with
a reason (gone, not allowed, rate limited, failed, damaged, storage, no space, no app, cancelled, background). A session
change retires everything silently. A genuine background stops whatever runs: a Save reports what was left, while a
Share or Open ends silently with its copy deleted. Nothing starts during the session's 429 wait.

## Verification

- **JVM:**
  - `FileNamesTest` (7): traversal, control and format characters, reserved characters, fallbacks, long names, the APK
    rule (including an image type and a bidi override), bidi stripping for display.
  - `ShareCopiesTest` (9): the layout, expiry, in-flight protection, discard, account deletion, `keepOnly`, the
    start-up sweep and its race with a session event, and the teardown (a cold start and restore keep the folder, a
    revalidation keeps it, a sign-out deletes it, a switch to another account deletes the old one).
  - `FileTransfersTest` (17): one at a time; Save to the chosen document; a pre-body failure never opens it; damaged
    keeps it; a late or orphaned picker result does nothing; a cancelled picker starts nothing; the 429 wait; a verified
    copy and its handoff; failed or mismatching copies deleted; no space; APKs never copied; no app; background for
    Share and for Save; a session change; a revalidation keeps the transfer; progress.
  - `AttachmentDownloadsTest` (18): adds a pre-body 410 and 429 never opening the destination, a mid-body failure
    keeping what was written and closing, and the per-chunk fence.
- **Instrumented:**
  - `ShareProviderTest`: only `cache/shared` resolves; intents carry only a read grant; a grant given to another app
    is denied after the account's session ends, and the URI no longer opens.
  - `ReceivedFilesJourneyTest.anImageTypedApkIsACardAndIsNeverPreviewed`.
