# M5g7b: room attachment Send

Rooms can now send the attached file. Send uses the existing serialized room actor
(`sendAttachment`) with the exact READY draft, the field text as a literal PHP-trimmed caption
(at most 4,000 code points) and the selected reply. The composer's Send button sends the
attachment when one is attached, and text otherwise. There's still one file per message.

## What the person sees

| Phase | Card | Actions |
|---|---|---|
| Uploading | Determinate ring, "Sending 1.2 of 3.4 MB" from buffered file bytes. The field is locked | **Cancel** while the transport hasn't reported the full body |
| Finishing | Indeterminate ring, "Finishing…" | none |
| Cancelling | "Cancelling…" until the transport actually stops | none |
| Definite failure | The file stays attached with the server's meaning: 413 with or without a limit, type refused, empty, 429 with an "about" wait or "later", membership, a provably incomplete transport failure ("Couldn't send the file. Check your connection…"), a policy change at call time, or another refusal ("Nothing was sent") | Send again later, Remove, Replace |
| Unknown | "Not sure your file arrived." with the warning glyph | none in this slice. M5g7c adds Check history, evidence and "Send again?" |
| Confirmed | "Sent" until a fresh actual entry adopts the canonical row; the card and the matching caption/reply then clear | — |

The progress figure is presentation only. Non-delivery is shown only when the actor has
already returned READY, which it does only when the transport proved the body incomplete
(or the server refused). Everything after a possibly complete body stays UNKNOWN. The 429
wait is phrased from the received value, rounded up, and never starts a resend.

## Unchanged

There's no automatic retry, POST replay, offline queue or new connectivity state. Navigation
keeps the POST, and genuine background follows the existing actor contract. Confirmed
adoption still requires a fresh actual entry and preserves newer caption/reply content. The
field's input, focus and IME contracts and the read/typing gates are unchanged.

## Verification

JVM tests cover the refusal mapping (JSON and non-JSON 413, type, empty, 429 with and
without a delay, membership, statusless transport failures versus HTTP refusals, policy
block) and the caption limit. `RoomAttachmentJourneyTest` adds real-root Send journeys with
a transport fake that holds the READY allocation: progress and Cancel, then Finishing
without Cancel, then a confirmed upload clearing the draft without a text POST; Cancel while
the body is incomplete returning the file to READY with no unknown line; and a 413 refusal
showing the server's limit. This is not live, provider or device evidence.

## Readable capped text (J review K1–K3)

Above the composer, the card's status text and the unavailable-settings notice are capped at 15%
of the pane so the field and Send stay on screen at 2.0 font (A-04). When the text overflows,
the last visible line fades and **More** opens the whole text in a dialog. Consent and limit
sentences are therefore always fully readable before a choice, while the card's actions stay on
the card. The notice is stacked (text full width, actions below) with the `info` glyph. During
an upload, the Send button shows the `send-waiting` glyph, not a second progress ring. Shared-unit
sizes show the unit once ("Sending 1.2 of 3.4 MB"), and status glyphs are 16sp capped at 24dp.
