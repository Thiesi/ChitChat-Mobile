# M5g7d: DM attachment controls

Direct messages get the same attachment controls as rooms (M5g7a–c), bound to the delivered DM
actor (M5g4b/M5g5e/M5g5f): paperclip, Photo/File sheet, pending card with privacy choice, local
failures and pre-checks, Remove and Replace, Send with progress and Cancel, definite-refusal copy,
"Not sure your file arrived", Check history, person-confirmed evidence and the "Send again?" dialog.

## DM-specific rules

- The paperclip shows only when messaging with this person is available and the account isn't
  muted everywhere. A blocked conversation shows no paperclip. A server `403
  direct_message_unavailable` reads "You can't send files to this person right now. Nothing was sent."
- The choice sheet shows the DM route's advertised rate limit, not the room's.
- The DM actor rechecks the current DM disclosure and final-byte policy before transport. The
  existing non-end-to-end-encryption and retention disclosure stays where it is; no new warning
  is invented.
- SEND, READ, BLOCK, AUTHOR, REACTION and upload uncertainty stay independent. The upload
  dialogs suppress read and attention proof like the other composer dialogs.

## Shared code

The picker host, controls state, notices, refusal mapping and caption limit moved to
`core/ui/AttachmentPickerHost.kt`. Rooms and DMs bind it to their own original-bound VM calls
and rate-limit route. Neither feature's authorization, jobs or state moved.

## Verification

JVM tests cover the shared refusal mapping, including `direct_message_unavailable`.
`DirectAttachmentJourneyTest` drives the real root and DM thread with a synthetic one-shot picker
and a transport fake: pick, card, caption placeholder, Send, Cancel visible while sending, and
confirmed adoption clearing the draft without a text POST. A conversation with messaging
unavailable shows no paperclip. Room journeys are unchanged. This is not live, provider or device
evidence.
