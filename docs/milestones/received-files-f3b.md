# Received files F3b: transcript frames, file cards and the disclosure

F3b is the second half of F3 and the first received-files slice with UI. Received photos now show inline in room and DM
transcripts, and other files show as cards. Save, Share, Open and the full-screen viewer are still to come (F4, F5), and
the availability disclosures say so. It follows [files-media.md](../design/files-media.md) "In the transcript", "Image
states" and "Image loading policy", the TL's and PL's F3 rulings of 2026-11-19, and [F3a](received-files-f3a.md) for the
loader's owner and the panes.

## Wiring

- MainActivity creates the one `AttachmentImagesViewModel` (F3a) and passes it to every room and DM conversation screen.
- Each screen opens its `ImagePanes` pane on ON_START with its owner token and nav entry, and releases it on ON_STOP and on
  disposal (`rememberReceivedImages`).

## What the transcript shows

- **Visibility comes from the viewport.** The screen counts a row as on screen only when its offset range overlaps
  `viewportStartOffset..viewportEndOffset`; `visibleItemsInfo` can include rows outside it (content padding,
  beyond-bounds layout for focus search). Prefetched and off-screen rows never load.
- **Placement (TL/PL, option (b)):** the frame or card sits inside the message-actions target (the long-press and tap
  area for Reply, Copy and so on) as **its own TalkBack node with no click or long-click action**. Pointer taps on it reach
  the row's message actions, as taps on text do.
  - An attachment-only message keeps a separate, labelled, focusable "Message actions" node of at least 48dp.
  - Show and Try again are their own 48dp buttons and consume their taps; they never also open message actions.
  - The pressed ripple is clipped to the frame's 14dp corners, never a square over them.
  - The frame's label ("Photo from {sender}, {time}", then the caption) is read once, on the frame's own node.
  - **F4 changes the tap:** a tap on the frame will open the viewer and take precedence, with the click label "Open
    photo", while long-press keeps the message actions.
- **Frames:** sized from metadata before loading and never resized (`attachmentFrameSize`): the content width, at most
  280dp, never wider than the image at 1px per dp; height 96–320dp, the width shrinking to keep the ratio at 320dp but
  never below 120dp; 240×180 without dimensions. A clamped frame is contain-fitted on `raised`, 14dp corners, caption
  below.
- **States:** every row of the spec's table: Not loaded (Show), Loading (a static ring, then "Loading the photo" after
  about 2 seconds, P-34), Loaded, Failed and Can't show right now (Try again), Too large (static text, no Show, not
  clickable), Gone (dashed), Not allowed, Offline (Show disabled). A frame not yet evaluated shows its size with nothing
  to press. During a session 429 wait the box shows Show disabled; the status slot carries the wait.
- **GIFs:** the "GIF" badge only, on every API level. The play glyph and the API 26–27 rule arrive with the viewer tap in
  F4 (PL).
- **File cards:** a type icon, the name (up to two lines, the middle elided so the start and the extension stay), and
  "PDF · 2.4 MB". No buttons until F5 and no click semantics; the label is "{name}, {type}, {size}", APKs included.
- **No frames or loads** in reply quotes (a quoted photo stays text-only, as before), search results, notification rows
  or push.
- **Ignored authors (rooms only):** a collapsed row composes no frame and requests nothing until it's revealed.
- **Blocked DM threads (TL):** frames render and load like any other. Blocking governs sending, not reading history the
  server still serves; a 403 or 404 shows "You can't open this file anymore". There's no client-side gate from
  `blockedByMe` or `blockedMe`.
- **Read proof:** frames are fixed-size and the loader never touches read or typing proof, so the read-proof viewport
  logic is unchanged.

## Disclosure

Both `preview_limit` and `auth_preview_limit` now read "Not in the app yet: saving, opening and sharing files and
photos, viewing photos full screen, profile pictures, and guest invite links." (`preview_limit` keeps its
development-build prefix; PL wording, recorded in copy-deck.md with the F4 and F5 steps).

## Verification

- **JVM:**
  - `ReceivedFramesTest` (9): frame geometry at every clamp (normal, content width, small, panorama, tall, very tall,
    no dimensions), the middle elision, and the viewport rule (including rows beyond both ends). The panes and the
    small-budget case are F3a's.
- **Instrumented: `ReceivedFilesJourneyTest` (11)**, in the real room and DM screens with the real decoder:
  - attachment-only room and DM rows keep a separate "Message actions" node (≥ 48dp), and the frame has no click or
    long-click action; the same for a DM file card;
  - the frame label, with and without a caption;
  - one tap on Try again gives exactly one retry and no actions sheet;
  - a file card's label, its middle-elided long name keeping ".pdf", and no download;
  - a too-large box is static text and nothing is fetched;
  - a frame above the viewport loads only once scrolled into view;
  - a reply quoting a photo shows no frame in the quote;
  - an ignored author's photo loads only after Reveal;
  - a blocked DM thread loads normally and a refusal shows with no retry loop.
- **J captures: `ReceivedFilesPresentationTest`** writes `files-presentation` (82): every state, the geometry set, the
  GIF badge, short, long and APK cards, a reply quoting a photo, and incoming and outgoing DM bubbles with short and
  wrapping captions, light and dark at default and 200%, plus black for a loaded frame and the raised box. The
  disclosure screens are in the existing `a11y-presentation` sign-in and You captures.
