# M5g7c: room attachment reconciliation controls

The native consumer of M5g5d. A room upload that ended UNKNOWN ("Not sure your file arrived.")
now has **Check history** on its card. That's the existing explicit bounded history GET, bound
to the original UNKNOWN attempt, slot and actual entry.

## Outcomes

- **Checking:** a dialog with a static ring and Cancel. Cancel ends the GET; the file stays UNKNOWN.
- **Evidence:** each own message with the same final-byte SHA-256, strictly after the attempt
  started, is offered as "We found your file "{name}" sent at {time}. Is this it?" with
  **Yes, that's it**. Only the person's confirmation resolves it: the card and the matching
  caption/reply clear, and newer content is kept. **None of these** leads to the risk dialog.
- **No match:** the accepted "Send again?" dialog (#184): "We couldn't find it in recent
  messages. It may still have arrived. Sending again may post it twice." **Send again** only
  returns the original file to READY. The person must press Send again for a new, separate
  upload. **Keep as draft** closes the dialog and leaves the file UNKNOWN and locked.
- **Couldn't check:** the GET failed. The file is still UNKNOWN; the dialog offers Check history
  again (one explicit GET per press) or Cancel.

The dialog suppresses read and typing proof like the other composer dialogs. Configuration keeps
the request; navigation, genuine background, discard and retirement clear it (M5g5d). Nothing is
posted from a check, a confirmation or risk acceptance, and nothing is retried automatically. A
missing match isn't proof of non-delivery. The bounded page and clock limits stay engineering
notes, not UI claims.

## Verification

`RoomAttachmentJourneyTest` drives an upload to UNKNOWN through a complete body followed by a
network failure, then covers Check history to the no-match dialog. Send again unlocks the file
without any POST and enables a later Send. Keep as draft leaves the file UNKNOWN and locked.
Evidence matching, confirmation and stale-callback fences are covered by the existing
`RoomAttachmentReviewTest` JVM suite. This is not live, provider or device evidence.
