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
  28+), so the API 26–27 look can be captured on CI.

## The viewer

- **Surface:** a full-screen Dialog, edge to edge on black, the same in every theme.
  - Its window takes focus, so the screens' read-proof gate is closed.
  - The screens also count it in `composerDialog`, which gates read proof and typing.
  - Closing requests no focus and shows no keyboard (TL condition 3).
- **Rotation:** the viewer stays open from the view model's state, and the zoom resets.
- **Closing:**
  - A departure from the nav entry (not a configuration change) closes it through `ImagePanes.departed`.
  - A message that disappears closes it.
- **Showing the image:**
  - The transcript's held image first, then the original once it's loaded.
  - A GIF's animation starts when shown and stops on ON_STOP and when the viewer leaves.
  - The image's TalkBack description is the frame's (label plus state), from one shared `imageText` (TL condition 7).
- **Gestures:**
  - Pinch and double-tap zoom, and pan.
  - Swipe down closes at fit scale only; Back and an explicit Close always work.
- **Zoom group (PL):**
  - Three 48dp buttons, "Zoom in", "Zoom out" and "Reset zoom", on one overlay-scrim pill above the bottom scrim.
  - Zoom out and Reset are disabled at fit scale, and Zoom in at the maximum.
  - There's no group when nothing is shown.
- **Bars:**
  - The top bar has Close plus the sender (wrapping, never ellipsized) and the time.
  - The bottom bar has the caption (two lines with More/Less; expanded, at most 40% of the window, scrolling) and the
    full-image line:
    - "Load full image · {size}", disabled offline and during a session wait;
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
  - the zoom buttons' labels, 48dp size and enabled states at fit, zoomed and reset;
  - Back closes the viewer and clears its session;
  - **no read POST and no typing publish while it's open, and none caused by closing it** (the composer isn't
    focused);
  - a recreation keeps the viewer open;
  - a DM frame opens the viewer, with only the transcript's preview downloaded.
- **`ReceivedFilesJourneyTest`:** the F3 frame cases now assert the F4 tap ("Open photo", long-press actions), including
  the too-large box.
