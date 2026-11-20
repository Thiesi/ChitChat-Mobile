# Received files F4b: the full-screen viewer

F4b is the viewer's screen, on top of [F4a](received-files-f4a.md)'s session. Tapping a received photo now opens it full
screen. Save, Share and Open arrive in F5, and the disclosure says so. It follows files-media.md "Full-screen viewer" and
the TL's and PL's F4 rulings of 2026-11-19.

## The frame's tap

- A frame's tap opens the viewer ("Open photo") and takes precedence; long-press keeps the message actions. This changes
  F3's "no click on the frame" (recorded in F3 as the F4 step).
- Gone and not allowed have nothing to show, so their frame has no tap and taps reach the row's actions (TL condition 6).
- Too large and offline open the viewer with their line.
- GIF frames show the play glyph only where the phone animates GIFs. The rule follows the viewer's own capability (API
  28+), so tests can show the API 26–27 look without an API 26–27 emulator.

## The viewer

- **Surface:** a full-screen Dialog, edge to edge on black, the same in every theme.
  - Its window takes focus, so the screens' read-proof gate is closed.
  - The screens also count it in `composerDialog`, which gates read proof and typing.
  - Closing requests no focus and shows no keyboard (TL condition 3).
- **Rotation:** the viewer stays open from the view model's state, and the zoom resets.
- **Closing (TL, #313):**
  - **The entry's own departure** closes it: ON_STOP, not during a configuration change, while the entry is no longer
    the current one (navigation, or the entry was popped), checked by identity (`isEntryCurrent`, from ChitChatApp).
  - A genuine background (ON_STOP with the entry still current) keeps it, and so does a configuration change.
  - **The same check runs on dispose**, in case Navigation disposes the leaving entry before its ON_STOP arrives: whichever
    comes first closes it (`departed` is idempotent).
  - **A dispose while the entry is still current is never a departure:** the room screen re-keys on the session version, and the DM thread
    re-branches on readiness, while the entry stays current.
  - A server, account or generation change closes it through the view model's own fencing.
  - It closes when the message is gone, but only once the conversation is settled, never during a reload.
- **Showing the image:**
  - The transcript's held image first, then the original once it's loaded.
  - A GIF's animation starts when shown and stops on ON_STOP and when the viewer leaves.
  - The image's TalkBack description says what **the viewer** shows (TL condition 7, #313): the label alone while it
    shows an image (the original or the transcript's held one), otherwise the label plus the viewer's own line (too large,
    gone, not allowed, failed, can't show, loading). It never carries the frame's state over a visible photo.
- **Gestures:**
  - Pinch and double-tap zoom, and pan, clamped to the scaled image's bounds so it can't be dragged off screen (#313).
  - Swipe down closes at fit scale only; Back and an explicit Close always work.
- **Layout (PL J1–J8):** files-media.md "Full-screen viewer" holds the presentation detail: the horizontal zoom pill
  (48dp buttons, state-based enabling, none when nothing shows), insets, overlay-text buttons, the GIF badge, the caption
  with More/Less, the offline and wait lines, and, in a wide window, the pill beside the bottom block with More/Less and
  the image action sharing one row. Engineering points:
  - the top bar is measured from the window's top edge, its inset included, and the bottom is capped below it, so
    nothing reaches the bar; the expanded caption is what gives way;
  - in a wide window the bottom block orders its own traversal (caption, More/Less, state line, action), and the zoom
    group still comes before the bottom bar;
  - the sender is server text: never ellipsized, so at 200% it may take more than two lines;
  - the full-image states and the wait end come from the session (see the [F4a record](received-files-f4a.md)).
  - There's no Save or Share until F5.

## Disclosure

Both strings now read "Not in the app yet: saving, opening and sharing files and photos, profile pictures, and guest
invite links." (PL).

## Verification

- **Instrumented: `ReceivedViewerJourneyTest`:**
  - a tap opens the viewer (onClickLabel "Open photo"), a long-press keeps the message actions, and the 48dp Close closes;
  - gone and not-allowed frames offer no "Open photo";
  - too large opens the viewer with "Too large to show here." on the ground, no zoom group and nothing fetched;
  - "Load full image · 3.4 MB" loads the original, no Save or Share is shown, and the viewer image has the frame's
    description;
  - opened from a failed frame, a loaded original is described as the photo (the label alone), never the frame's
    failure;
  - the zoom buttons' labels, 48dp size and enabled states at fit, zoomed and reset;
  - Back closes the viewer and clears its session;
  - **no read POST and no typing publish while it's open, and none caused by closing it** (the composer isn't
    focused);
  - a session-version bump (the room screen re-keys) keeps the viewer open;
  - with a driven lifecycle, a background (ON_STOP, entry current) keeps the viewer, and ON_STOP after the entry stops
    being current closes it;
  - a dispose after the entry stops being current, with no ON_STOP, closes the viewer and releases its original's lease;
  - **`ReceivedViewerRecreationTest`:** a real `recreate()` (content set in onCreate, so `isChangingConfigurations` is
    set) keeps the viewer open; a test-side recomposition alone isn't a configuration change and would count as a departure;
  - a DM frame opens the viewer, with only the transcript's preview downloaded;
  - offline shows "The full image is available when you're back online." over a disabled button, and back online a large
    original returns to the offer with nothing loaded by itself (J4);
  - a 429 shows status row 7's line ("Try again in about 3 minutes.") over the disabled button;
  - at 200% with a long caption expanded, the zoom pill stays below the top bar (J2);
  - a GIF whose original failed keeps its badge on the still preview until zoomed (J5);
  - **Show and Try again stay their own accessibility actions beside "Open photo"** (#313 review): each is a separate
    clickable node in the merged tree, and invoking it as TalkBack does shows or retries without opening the viewer.
- **`ReceivedViewerLayoutTest`** (a real 200% font scale from the Activity's Configuration, which the Dialog window takes):
  portrait and landscape keep the pill and the bottom block clear of the top bar; landscape keeps two caption lines with
  Less and Load full image on one row, one line with a failed original's line above Less and Try again, and the J8
  reading order.
- **`ReceivedFilesJourneyTest`:** the F3 frame cases now assert the F4 tap ("Open photo", long-press actions), including
  the too-large box.
- **Captures:** the viewer's presentation captures came separately, in #315: `ReceivedViewerPresentationTest`, 135
  captures (every viewer state at default and 200%, the landscape set, the GIF frames and one dark viewer). The reconnect
  decision, the wait end and the shared size text came with the prerequisites PR (see the [F4a record](received-files-f4a.md)).
