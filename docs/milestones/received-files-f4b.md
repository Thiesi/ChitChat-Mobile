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
- **Zoom group (PL, J2):**
  - Three 48dp buttons, "Zoom in", "Zoom out" and "Reset zoom", on one **horizontal** overlay-scrim pill with a 1dp
    border at 24% overlay text, directly above the bottom block.
  - Zoom out and Reset are disabled at fit scale, and Zoom in at the maximum.
  - There's no group when nothing is shown.
  - The bottom column is capped to the space below the top bar, and the expanded caption is what gives way, so the pill
    never reaches the top bar.
  - **Wide windows (J8):** wider than tall, the pill sits beside the bottom block (end, bottom-aligned, 8dp in, inside
    the insets) and the column narrows by its width plus 8dp, so an expanded caption keeps at least two lines. TalkBack
    still reaches the zoom group before the bottom bar (`traversalIndex` in a traversal group).
  - **Shared row (J8):** in a wide window More/Less and the image action share one row (More/Less at the start, the action
    at the far end, two rows only when they don't fit), and state lines keep their own row above it; the block orders
    its own traversal: caption, More/Less, state line, action. The bar is measured from the window's top edge, its inset
    included, so the bottom stays below it.
- **Insets (J1):** the top bar takes only the top and side insets; the bottom block only the bottom and side insets.
- **Colour (J3):** every viewer text button (Load full image, Try again, More/Less) uses overlay text, at 38% when
  disabled; persimmon is too faint on black.
- **GIF badge (J5):** a GIF shown still (its preview, offline, a failed original, API 26–27) keeps the badge at the top
  start of the fitted image, 8dp in; it hides while zoomed or animating.
- **Bars:**
  - The top bar has Close plus the sender and the time. The sender is server text: never ellipsized, so at 200% it may
    take more than two lines and break mid-token.
  - The bottom bar has the caption (two lines with More/Less; expanded, at most 40% of the window, scrolling) and the
    full-image line:
    - "Load full image · {size}"; offline, it's disabled under "The full image is available when you're back online."
      (J4), and back online the viewer makes the opening decision once (a small original may load, anything else returns
      to the offer); during a 429 wait it's disabled under status row 7's line (the server's positive wait through the
      shared formatter, else "Try again in a little while."; P-106), and the wait ends on the offer only, with no burst.
      The end-of-wait timer belongs to the viewer attempt: close, departure and a session change cancel it, and a stale
      one never touches a later viewer (TL);
    - the loading ring, with "Loading the full image" after 2 s;
    - "Couldn't load the full image" / "Couldn't show the full image right now" with Try again;
    - the too-large lines, gone and not allowed;
    - on API 26–27, "Animation isn't available on this phone."
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
- **`ReceivedViewerLayoutTest`** (a real 200% font scale from the Activity's Configuration, which the viewer's Dialog
  window takes; a LocalDensity override in the host doesn't reach it):
  - portrait, caption expanded: the pill is below the top bar and above the bottom block (J2);
  - in both, the bottom block stays below the top bar, measured with its inset;
  - landscape, caption expanded: the pill overlaps neither the top bar nor the bottom block and sits at its end; the
    caption keeps at least the two lines it shows collapsed; Less and Load full image share one row at its two ends; the
    pill comes first in traversal, and the block reads caption, More/Less, action (J8);
  - landscape, a failed original with the caption expanded: the line sits above the shared row of Less and Try again,
    the caption keeps at least one full line, and the block reads caption, More/Less, line, Try again (J8).
- **`ReceivedFilesJourneyTest`:** the F3 frame cases now assert the F4 tap ("Open photo", long-press actions), including
  the too-large box.
- **Captures:** the viewer's presentation captures are added by a separate follow-up PR, not by this slice. The reconnect
  decision, the wait end and the shared size text came with the prerequisites PR (see the [F4a record](received-files-f4a.md)).
