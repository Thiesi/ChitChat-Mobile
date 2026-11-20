# Received files F6: tap to hide the viewer's chrome

F6 lets a single tap on the full-screen viewer's image hide or show its chrome. It's the TL-approved fix for the 200%
landscape defect (the chrome covering most of a short window), following the PL's spec and the TL's conditions of
2026-11-20. It reverses the F4 "no tap-to-hide" ruling. The presentation detail lands in
[files-media.md](../design/files-media.md) ("Full-screen viewer") in the PL's docs PR; the captures follow in a stacked PR.

## What toggles

- **The tap:** a single tap on the image toggles only after the system double-tap timeout (`detectTapGestures` with
  `onDoubleTap`), so a double-tap zooms and never flickers the chrome. A drag past the touch slop is consumed by the
  transform gesture, so pan, pinch and swipe-down never toggle. A long-press doesn't toggle either: a no-op
  `onLongPress` consumes it, since without one a held, still press would end as a tap (PL).
- **The tap target (PL and TL rulings):** a tap anywhere that isn't a control toggles, both ways: the scrims, the sender
  and time, a collapsed caption's text, the state lines and empty block space are plain content. Controls keep their own
  taps, enabled or not (`keepsTaps`, since a disabled button's clickable may take no part in hit testing): Close, Save,
  Share, More/Less, Load full and Try again, the whole zoom pill, and the transfer notice's Cancel and Try again; the
  expanded caption's scroller keeps its taps and drags. An exclusion of the scrims would switch F6 off exactly where it's
  needed: at 200% landscape with a collapsed caption and a two-line sender the clear band is 0 (#321's capture).
- **The chrome:** the top bar (Close, sender and time, Save, Share), the bottom block (caption, More/Less, state lines,
  transfer notice, action), the zoom pill and the GIF badge.
- **Hidden means gone:** the pieces leave composition, so they're out of semantics and the focus order; their semantics
  clear as the fade-out starts. The image doesn't move or rescale: it was already fitted to the whole window. System
  bars stay as they are (no immersive mode).
- **Motion:** a 150 ms fade both ways, instant when the system animator scale is 0.

## When the chrome is always shown

The toggle is off with no image to look at (the too-large ground without a preview, gone, not allowed, the black ground
while nothing is decoded), **while a transfer runs** (Running or Ready), and while assistive input is on. Anything that
turns the toggle off shows the chrome for good, so assistive input turning on while hidden shows it at once.

- **Assistive input** (`AssistiveInput`): touch exploration, or any enabled accessibility service whose capabilities
  include touch exploration, gestures or key-event filtering (`INPUT_CAPABILITIES`, one constant with a JVM test per
  capability). Never by package name, so a password manager's window-reading service doesn't count. Any failure, or no
  manager, reads as assistive. It listens for accessibility-state and touch-exploration changes, on API 33+ for the
  enabled services too, and re-reads on every resume (below 33, a service enabled beside another changes no listened
  state; enabling one in Settings means coming back to the app).

## What brings it back

- A state line appearing or changing (offline, waiting, failed, no memory), compared by kind, so a line present when
  the viewer opens doesn't prevent hiding afterwards. Offline is decided at opening and at Load full (F4), both made
  with the chrome shown, so a line that appears while hidden comes from a load already running (failed, no memory,
  waiting).
- A transfer's result line (saved, or stopped with a line, by the notice's own `hasLine()` rule: every Save stop, and a
  Share or Open stop except cancelled or backgrounded); never a progress tick. The viewer's notice shows any transfer's
  line until it's dismissed, so this is the line on screen. A transfer starting shows the chrome by being a running
  transfer.
- Any key but Back and Esc (Tab, arrows, Enter, D-pad): it first shows the chrome and puts focus on Close, and the key is
  consumed. Focus on Close applies outside touch mode (keyboard, D-pad), which a real key event enters; in touch mode
  Compose, like Views, gives buttons no input focus, and an accessibility service moves its own focus. System keys (volume, mute, media, headset, call) and a modifier pressed alone pass through untouched and
  change nothing (TL). While hidden the image holds focus, so nothing ever leaves focus on a hidden control.
- **Esc and Back** always close the viewer, hidden or not; Esc closes in the viewer itself, so every API level does the
  same.
- **The image's accessibility actions** (TL): while hidden, its click action "Show controls" restores the chrome and
  moves focus to Close outside touch mode; in touch mode it restores the chrome and leaves accessibility focus with the
  service (PL), and no focus is left on the chrome or the image; while shown and hideable, a custom action "Hide controls". A voice or node-action service the
  detection misses can always bring the chrome back without a gesture.
- **Configuration change:** hidden survives a rotation within the viewer's opening unless a state line is present; each
  new opening starts shown. There's no hint and no first-use note.

## Tests

- `ReceivedViewerChromeTest` (the real room screen, a host that sets its content in onCreate, so `recreate()` is a
  genuine configuration change): hidden leaves no chrome node and the image's bounds equal the window's; a tap on More
  or zoom-in keeps the chrome while a tap on the sender line or the collapsed caption toggles; a tap on a disabled Save
  doesn't toggle; a double-tap zooms with no toggle, and pan, pinch and a long-press don't toggle; Show controls and
  Hide controls; assistive input keeps it shown, with no Hide controls, and turning it on while hidden shows it; a
  running transfer can't be hidden and its result keeps it shown; a load that fails while hidden shows it with its line; a key while
  hidden shows it with focus on Close (a real key in keyboard mode, touch mode restored by a real tap in finally); a system key (volume up, dispatched to the viewer's Compose view) isn't
  consumed and leaves it hidden; Esc and Back while hidden close; rotation keeps hidden with no line and shows
  with one; a new opening starts shown; and with the fade on a test clock, the semantics are gone from the first frame
  of the exit. CI runs with animations off, so every other case covers the instant path. Keys go straight to the
  viewer's Compose view, never through the instrumentation (a real key event takes the device out of touch mode for the
  rest of the run, which broke AuthJourneyTest), and every case asserts touch mode is unchanged. The 200% landscape
  caption layout's real tap on the sender line is in #321's capture.
- `AssistiveInputTest` (JVM): the capability set, each capability, a window-reading service alone, and failure.
