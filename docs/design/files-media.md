# Files, images and sharing: presentation specification

Status: presentation spec for M5 files, media and sharing, written ahead of the engineering
slices (technical lead's plan, issue #1). It designs only what the existing APIs support and
follows the existing write contracts. Upload and download transport, storage and the
dependency choices are engineering decisions inside those slices. Mockups: `RoomFiles`,
`ShareIn` and `ImageViewer` on the canvas.

## What the server provides (ChitChat v3.2.1)

- **Upload:** one file per request, as multipart.
  - Rooms: `POST attachments/upload.php` with `room_id`, optional `caption` (≤ 4,000
    characters, mentions parsed), optional `reply_to_message_id`, and `file`. Persistent
    membership and the age policy are required.
  - DMs: `POST direct-messages/attachments/upload.php` with `recipient_user_id` and the same
    optional fields. Limited to **10 successful uploads per user per hour**. An empty caption
    makes the filename the body. A block returns `403 direct_message_unavailable`.
  - Success is `201` with the created message. The message, metadata and realtime event commit
    together. **There is no idempotency key**, so an interrupted upload has the same ambiguity
    as a text send.
- **Limits** are server-side: a maximum size (`ATTACHMENT_MAX_BYTES`, default 10 MiB,
  configurable up to 100 MiB) and a MIME allowlist (JSON, PDF, ZIP, GIF, JPEG, PNG, WebP, CSV,
  plain text). **Neither is exposed to clients.** Errors include `attachment_too_large`,
  `attachment_type_not_allowed`, `attachment_type_unknown`, `attachment_empty` and
  `membership_required`.
- **Metadata:** `{id, name, mime_type, size_bytes, sha256, previewable}`. Room history includes
  it, and DM history needs the bounded metadata endpoint for visible IDs, which the client
  already uses. There are **no thumbnails and no image dimensions**.
- **Download:** `GET …/download.php?id=…` with the session cookie, plus `inline=1` for previewable
  images only (JPEG, PNG, GIF, WebP). `410` for deleted. Authorization is re-checked on every
  request. Responses are `no-store`.
- **Not end-to-end encrypted.** DM files follow DM retention. Guests can't upload.

Anything not listed here (multi-file messages, thumbnails, video, audio, client-visible
limits) is out of scope until the server offers it.

## Principles

- **Your file, your explicit send.** Picking or sharing a file never sends it. Nothing is
  uploaded until the person presses Send, and an interrupted upload is never retried
  automatically.
- **Pictures are worth their bytes, but only when wanted.** Without thumbnails, every preview
  downloads the full image. Previews load automatically only when cheap (see Image loading).
- **Files stay in the app unless the person moves them.** Bytes live in memory for display.
  Saving uses a document the person chooses, and sharing uses a temporary grant.

## Composer: attaching

- **Entry:** a paperclip icon button (48dp, label "Attach") inside the message field's
  trailing area, beside the reserved emoji slot. Hidden where uploads aren't allowed: guests,
  reading-only rooms, a blocked DM, or no permission to send.
- **Choice sheet** (modals spec), two rows:
  - **Photo**: the system Photo Picker, images only, no storage permission;
  - **File**: the system document picker.

  The picker MIME filter may list the v3.2.1 allowlist as a convenience, but the server stays
  the authority. A rejected type shows the server's answer.
- **Pending attachment card** above the composer field, in the composer notice stack directly
  under the reply chip:
  - images: a locally decoded, downsampled thumbnail (decoded on the device, never uploaded
    early), filename, size ("2.4 MB") and **Remove** (×, 48dp);
  - other files: a type icon (PDF, archive, text, table, code), filename, type and size, and
    Remove.
  - The field's text becomes the caption. Placeholder while a file is attached: "Add a
    caption".
- **One file per message.** A second pick replaces the first after the decision dialog
  "Replace the attached file?". Multi-select isn't offered, because the API takes one file per
  request.
- The attachment is part of the conversation's in-memory draft, alongside text and reply
  (existing draft rules). It is never persisted. A process death drops it, as it drops text
  drafts today, and the content URI permission wouldn't survive anyway.
- **Large-file hint:** the client doesn't know the server's limit. If the file is larger than
  10 MiB (the default), show a muted line under the card: "This is a large file. Your server
  may refuse it." Never block the send on a guessed limit.

## Sending and delivery states

These extend the composer delivery states in [transcript-restyle.md](transcript-restyle.md)
and [communication.md](communication.md). Nothing renders as a bubble before the server's 201.

| State | What the person sees | Actions |
|---|---|---|
| Uploading, body still sending | The card shows a determinate progress ring with "Sending 1.2 of 3.4 MB". Field and Send are locked | **Cancel**: aborts before the request body completes, so nothing was delivered. The attachment returns to the draft |
| Uploaded, waiting for the server | Indeterminate ring, "Finishing…" | none. Cancelling now couldn't prove whether the server kept it |
| Confirmed (201) | The canonical message appears as a bubble; draft and card clear | — |
| Definite failure (server error before commit, too large, type not allowed, membership, 429 DM limit, block) | A level-2 notice with the server's meaning: "Your server refused this file: it's too large." / "This file type isn't allowed on this server." / "You've reached the upload limit for direct messages. Try again later." The file stays attached | Remove or replace, or send again later |
| **Unknown** (connection lost after the body was sent, timeout, ambiguous transport error) | The `unsureLine` notice: **"Not sure your file arrived."** with **Check history**. The file and caption stay attached | Check history runs the existing explicit reconciliation. If the message isn't found, the duplicate-risk dialog follows: "Send again? We checked and couldn't find it. Sending again may post it twice." |

- **Reconciliation hint (optional, engineering decision):** the client may compute the SHA-256
  of the picked file before upload. During Check history, a message of the viewer's own with
  the same `sha256` sent after the attempt started is offered as **evidence** ("We found your
  file 'diagram.png' sent at 18:02. Is this it?"). It never resolves the state automatically,
  and it isn't text deduplication. The person confirms or chooses to send again, as with
  messages.
- **Send when back online doesn't apply to attachments.** Offline, Send with an attachment
  stays available and fails definitely if it can't reach the server, or it is disabled with
  "Attachments can't wait for the connection" if engineering prefers that. Attachment drafts
  are never queued for an automatic upload (large bodies, expiring URI grants).
- Upload progress runs only while the app is in the foreground and the conversation is the
  owner. Leaving the app follows the existing genuine-background rules for in-flight writes.
  Navigation never cancels or replays a POST.

## In the transcript

Within the transcript spec's bubble, run and chit rules:

- **Previewable images** (`previewable: true`):
  - The image fills the bubble's content width (max 260dp, max height 320dp, `contain`
    scaling, 14dp inner corners) with the caption below it.
  - Before the image's real dimensions are known, it reserves a 4:3 box. After decoding, it
    keeps the decoded aspect ratio. The box size changes only once, from placeholder to
    decoded, to limit layout jumps. Read proof still measures the item's actual bounds.
  - **GIFs** show their first frame, with a small "GIF" badge. They animate only in the viewer
    (battery).
- **Other files:** a file card inside the bubble: type icon, filename (two lines max, ellipsized
  in the middle so the extension stays visible), "PDF · 2.4 MB", plus **Open** and **Save**
  (48dp each). The caption goes below.
- **Image states:**

  | State | Shows |
  |---|---|
  | Not loaded (policy) | A `raised` box with a picture icon, "Photo · 3.4 MB" and **Show** |
  | Loading | The same box with a small static progress ring. No shimmer (P-34) |
  | Loaded | The image |
  | Failed (network) | The box with "Couldn't load the photo" and **Try again**, an explicit GET retry |
  | Gone (410) | A dashed box, "This file was deleted" |
  | Not allowed (403 or 404) | The box, "You can't open this file anymore" |
  | Offline | The box, "Photo · 3.4 MB · available when you're back online", with Show disabled |

- **Deleted messages** keep the tombstone from the transcript spec. No file card or image.
- **Accessibility:** the image's description is "Photo from {sender}, {time}", followed by the
  caption if present. The API has no alt text, so don't invent descriptions. File cards read as
  "{name}, {type}, {size}, Open, Save".

## Image loading policy

The policy is local and read from existing system state, with no new requests:

- **Automatic** loading only for previewable images ≤ **2 MiB** (from `size_bytes`) while the
  network is **unmetered** (`ConnectivityManager` metered flag) and the message is on screen.
- Otherwise **tap to show** (Show). Once shown, the image stays shown for that message during
  the session.
- Requests use `inline=1`, the existing session transport and same-origin rules. No disk
  cache: decoded bitmaps go into a bounded **memory-only** cache, cleared on sign-out, account
  switch and generation change. Decode with downsampling to the displayed size.
- **Decision needed (technical lead):** whether to offer a setting ("Show photos
  automatically: on Wi-Fi / always / never") in this slice or later. The default above stands
  either way.

## Full-screen viewer

- Opened by tapping a loaded image, or a not-loaded one, which loads it first.
- Black ground, edge-to-edge. Pinch and double-tap zoom, pan. Swipe down or Back closes.
- The top bar is on a scrim: back, sender and time, **Save** and **Share**. The caption shows at
  the bottom on a scrim, expandable when long.
- One image at a time (no swiping between messages in this slice). GIFs animate here, and stop
  when the viewer closes or the app backgrounds.
- The viewer is a choice-sheet-class surface for contract purposes: it suppresses read and
  typing proof while open, as other modals do (modals spec).
- Accessibility: zoom gestures have button alternatives (zoom in, zoom out, reset). The image
  description is as above.

## Save (download to a chosen place)

- **Save** opens the system "create document" picker with the original filename and MIME type,
  then streams the download into the chosen document with a progress line in a level-1 notice
  ("Saving diagram.png… 40%").
- Success: "Saved to {app or folder name}" (a snackbar is acceptable, since the person can see
  the result).
- Failure mid-way: a level-2 notice, "Saving stopped. The file may be incomplete. Delete it
  in the place you chose if you don't need it." The app **never** deletes the person's chosen
  document silently (same rule as exports).
- Gone or forbidden: the same copy as the image states.

## Share out (to another app)

- **Share** (viewer, file card overflow, message actions) downloads the file into the app's
  private cache, then opens the system share sheet with a **temporary read grant** through a
  FileProvider URI. Authenticated URLs are never handed to other apps (feature-parity).
- The cached copy is deleted when the share completes or is cancelled, and in any case on the
  next app start. A small first-use note: "Sharing copies the file to the app you choose."
- For DMs, the share sheet doesn't preselect any target, and the not-end-to-end-encrypted
  disclosure stays where it is. No extra warning is invented.

## Share in (from another app)

- CCM registers for `ACTION_SEND` with text and with single files (images and the allowlisted
  types). `ACTION_SEND_MULTIPLE` is **not** registered, because the API takes one file per
  message.
- **Flow:** the share target opens a **"Send to…"** screen: search plus recent DMs and joined
  rooms, in two sections, using the shared list rows from the lists spec. Choosing a
  conversation opens it with the file attached as a pending card, and shared text placed in
  the field. **Nothing is sent until the person presses Send.**
- **Not signed in:** onboarding first. The shared item is dropped with a notice ("Share again
  once you're signed in"). It is never held across sign-in.
- Destinations where uploads aren't allowed (guest, reading-only, blocked) appear disabled with
  a reason.
- Direct-share shortcuts in the system share sheet are out of scope for this slice.

## Tests

- **Unit:**
  - the state machine from pending to uploading, finishing, confirmed, definite failure and
    unknown;
  - Cancel is allowed only before the body completes;
  - unknown never auto-retries, and Check history leads to the duplicate-risk dialog;
  - the auto-load policy (size, metered, previewable);
  - memory-cache clearing on account change.
- **Compose:**
  - the attach sheet and pending card (Remove, Replace confirmation);
  - progress semantics;
  - each image state;
  - file card actions;
  - viewer open and close suppressing read and typing proof;
  - the share-in "Send to…" flow never sends without Send.
- **Native:** the composer field and Send keep every existing input contract with a pending
  card shown. The card sits outside the scrollers with the composer.
- **Manual:** real providers (Photos, Files, a cloud provider) for pick, save and share-in; a
  metered network for the auto-load policy; one large file over the server limit.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-150 | Only explicit Send uploads. One file per message. Nothing renders before 201. | C |
| P-151 | Cancel only while the body is still sending. Unknown outcomes use Check history then the duplicate-risk dialog, never automatic retry. Attachments are never queued for Send when back online. | U, C |
| P-152 | Server refusals show their meaning, keep the file attached and never guess client-side limits. | C |
| P-153 | Images follow the loading policy, use memory-only caching cleared on account change, and show every state in the table. | U, C |
| P-154 | Save uses a chosen document and never silently deletes it. Share-out uses temporary FileProvider grants and never hands out authenticated URLs. | C, R |
| P-155 | Share-in never sends by itself, drops items across sign-in, and supports one file. | C |
| P-156 | The viewer suppresses read and typing proof and offers zoom alternatives. | C |
