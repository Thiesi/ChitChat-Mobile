# Received files F4a: the viewer's session

F4 is the full-screen viewer, split like F3. F4a adds no UI: it gives the [F3a](received-files-f3a.md) owner a viewer session
that F4b's screen will show. The viewer stays unavailable in the app until F4b, so the disclosures stay accurate. It follows
files-media.md "Full-screen viewer" and the TL's and PL's F4 rulings of 2026-11-19.

## What it adds

- **`AttachmentViewer`**, one session at a time in `AttachmentImagesViewModel`, **opened only through a pane** (TL).
  - A retired screen's late open, close or Load full image is a no-op.
  - A rotation's new pane, for the same nav entry, takes the viewer over.
  - `ImagePanes.departed(entry)`, a navigation departure and not a configuration change, closes only the viewer opened
    from that entry.
  - Any change of server, account, generation or phase closes it.
- **Preview first:** the viewer shows the transcript's held image at once, with no second download.
  - That frame is **pinned** in the loader while the viewer is open, so it counts as visible and is never evicted
    underneath (TL).
  - The original loads by itself only under the loader's rule: at most 2 MiB, unmetered, online, no 429 wait. Otherwise
    the bar offers "Load full image", and only that tap fetches it.
  - Without a preview, opening loads the original once, by the viewer only.
  - **Opening a GIF is the explicit request** for its original, even on a metered network.
- **The original goes through F1 unchanged:** the caps, integrity, the server MIME and the session wait.
  - Too large is decided from metadata and never fetched.
  - A failure or an integrity mismatch keeps the preview, and the mismatched bytes are never shown.
  - A 429 leaves it waiting (`WAITING`). When the wait ends it returns to the offer only, never the automatic rule, so
    nothing loads by itself and there's no burst (F2's no-catch-up rule; TL, #313). Before that fix WAITING never cleared.
  - The end-of-wait timer belongs to one viewer attempt: close, a departure and a session change cancel it, and a stale
    one can't change a later viewer for the same attachment or another (TL, #313).
  - `ViewerState.waitUntilMillis` is set only from the server's positive wait (this response's `retry_after_seconds`, or
    the session's under the status slot's known-wait rule), otherwise null (P-106: never an invented number), for F4b's
    wait line.
  - **Offline, then back online:** the viewer makes the opening decision once. It fetches when there's no preview, for a
    GIF where it animates, or within the automatic rule; anything else returns to the offer. Never a retry loop, and
    offline only (TL, #313).
  - The states are `FullImage` (offer, loading, failed, can't show right now, too large, gone, not allowed, offline,
    waiting).
- **Main-confined state:** the decode runs off Main and only returns an outcome, which is applied on Main after the
  stale check, so a decode for a viewer that has since closed or moved on never touches its successor.
- **Memory:** the original decodes downsampled to the screen size, within the 16 Mpx guard, on the shared ledger with the
  lease before every allocation (F2). It's held only while the viewer is open: close, a departure, a session change or a
  cancelled load releases it. Nothing is cached.
- **GIFs (`AnimatedDecoding`):**
  - **API 28+:** `ImageDecoder` → `AnimatedImageDrawable`, with `setTargetSize` at the screen size. The 16 Mpx guard
    comes from the header before anything is allocated. The lease covers the source bytes the drawable keeps for its whole
    life, plus two frame buffers; it's released only when the viewer drops the animation, stopping it first.
  - **API 26–27:** no animated decoding, so a GIF is a still under the ordinary rule, flagged `animationUnavailable` for
    the PL's line.
  - **Background:** F4b's screen starts the animation, and stops it on background.
- **Shared size text (PL J6, #313):** `attach_size_*` puts a no-break space between number and unit, so a size never
  splits across lines wherever it shows; ReceivedFilesJourneyTest's size assertions expect it.
- **Shared caps check:** `originalBeyondDisplayCaps()` is now one function for the transcript's frames and the viewer.

## Verification

- **JVM: `AttachmentViewerTest` (16):**
  - it opens on the held preview with no second download, and offers the original beyond the rule;
  - a small original loads by itself only unmetered and online (metered offers it, offline says so);
  - a failure or a mismatch keeps the preview, and nothing reloads by itself;
  - beyond the caps nothing is fetched;
  - without a preview, one original download by the viewer only;
  - **closing during an in-flight original releases its one lease** (only the transcript's preview stays charged);
  - **a stale decode never writes into the next viewer:** a decode finishing after close and reopen leaves the new
    photo's state alone (#312 review);
  - close releases a held original, and a session change closes the viewer and frees everything;
  - the preview under an open viewer is never evicted, and is evictable again after close;
  - a GIF's original is fetched on opening (even metered), with the source bytes plus two frame buffers leased and the
    animation stopped on close;
  - below API 28 a GIF is a still under the ordinary rule;
  - a session wait starts nothing, nothing starts after it, and the wait ends on the offer;
  - a wait carries only the server's end, and an unknown wait stays unknown (P-106);
  - **a closed viewer's pending wait end never touches a later viewer**, and a session change during a wait closes the
    viewer with its timer (TL, #313);
  - back online, a small original loads once, a large one offers, and a reconnect on the offer loads nothing;
  - only the current pane drives the viewer (a retired pane's late close or replace is a no-op), and a departure closes
    only its own entry's viewer.
- **JVM:** `ImagePanesTest` (4) and `AttachmentImagesTest` (24) are unchanged and pass.
- **Instrumented: `AndroidAnimatedDecodingTest` (3, API 28+):**
  - a hand-built two-frame GIF decodes to an `AnimatedImageDrawable` within the target, leased once before decoding (bytes
    plus two frame buffers);
  - a 5000×4000 header is TooLarge with nothing leased;
  - a refused lease is NoMemory, and garbage is Unreadable.
