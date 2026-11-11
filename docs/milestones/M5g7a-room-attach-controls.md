# M5g7a: room attach controls

The first on-screen attachment slice. A room member can pick one file, see it as a pending card
above the composer, and remove or replace it. **Sending files is not part of this slice:** the
text Send stays disabled while a file is attached (unchanged since M5g4a), and the card says so.
Room Send with progress/Cancel (M5g7b) and unknown-delivery reconciliation (M5g7c) follow, then DMs.

## What's on screen

- A 48dp paperclip between the message field and Send. It's hidden for guests, an explicit-null
  policy, non-members and muted members. It sits beside the field, not inside its decoration box,
  so the field's semantics, focus and IME contracts are unchanged.
- `max_bytes: 0` keeps the paperclip ("Attach, not available on this server") and shows "This
  server doesn't accept files." instead of a picker. An Invalid policy shows the accepted
  "couldn't read {installation}'s file settings" line with **Check again**, which is the existing
  explicit session refresh. The second sentence appears only after a Check again that's still
  Invalid. {installation} is the chosen server's host.
- The choice sheet has Photo (system Photo Picker) and File (system document picker), filtered
  to the advertised types, plus the advertised rate-limit line. Photo is left out when no image
  type is allowed. With a missing policy there's no filter.
- The card covers preparing, ready, "Location removed", the explicit privacy choice (**Send anyway**
  only prepares the unchanged photo through the existing original-bound action; it never uploads),
  the local unreadable and memory-budget failures, and the size and type pre-checks with the
  server's figures. The placeholder becomes "Add a caption". In compact heights the card lives
  in Details and the Details badge shows attention.
- A second pick asks "Replace the attached file?" first. The Replace binding is screen memory only.
  A configuration change during picking loses it, and the VM then refuses the result rather than
  replacing a file without consent.

## Picking contract

A picker result is frozen into one `PickedAttachmentRead`: display name and type are untrusted
hints, normalized to a valid basename and a concrete MIME type or null. The URI is opened at most
once, inside the existing preparation task. It is never persisted, logged or reopened. The result
can arrive while the returning pane reloads or re-enters presence, so the screen waits (with a
visible line and a cancel) until the VM reports it can begin an attachment, then calls the
original-bound `selectAttachment` exactly once. A refusal drops the unopened read and asks the
person to choose again. Nothing is retried, and no network request is made.

## Not yet

Image thumbnails on the card (they need decoded-bitmap memory accounting), room Send, progress,
Cancel, refusal notices and reconciliation UI, DM controls, downloads, previews, Save/Share and
share-in. Provider and device acceptance stay separate.

## Verification

JVM tests cover picker filters, basename and type normalization, decimal size text with
disambiguation, refused-kind phrasing and rate windows. `RoomAttachmentJourneyTest` drives the
real root and room pane with a synthetic one-shot picker: pick, card, caption placeholder, text
Send held off, Remove releasing memory, Replace confirmation, `max_bytes: 0`, Invalid with Check
again, and the too-large pre-check with no POST. It's not system-picker, provider or live evidence.
