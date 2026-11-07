# Acceptance: accessibility floors and presentation requirements

Two kinds of standards live here:

- **Floors** (A-*, P-*): numbered requirements an implementation must meet. Each names how
  to verify it. A slice that touches the relevant screen fails review if it breaks one.
- **Judgment standards** (J-*): reviewed by a person or the presentation lead against the
  mockups and [vision.md](vision.md). They are not reducible to a test, and they are not
  optional.

Behavior contracts in `AGENTS.md` and `docs/milestones/` take precedence over everything
here.

Verification codes: **U** JVM unit test, **C** Compose/instrumented UI test, **S** screenshot
review on the canvas sizes, **M** manual check on a device or emulator, **R** code review.

## Accessibility and readability floors

| ID | Requirement | Verify |
|---|---|---|
| A-01 | Text contrast at least 4.5:1 against its actual background in both themes, including muted text, captions and text on bubbles, badges and strips. Large text (at least 24sp, or 19sp bold) at least 3:1. | U (token pair test), S |
| A-02 | Component boundaries and meaningful icons at least 3:1 against adjacent colors (fields, outlined buttons, selected chips, the unknown-delivery outline). Persimmon fills on paper (2.9:1) must not be the only cue (see visual-vocabulary). | U, S |
| A-03 | Every interactive element has a touch target of at least 48×48dp, even when drawn smaller. Existing 48dp contracts stay. | C (`assertTouchHeightIsEqualTo`/width at least 48dp), R |
| A-04 | At 200% system font scale, on a 360×640dp window: no text is clipped or overlaps, the message input and Send stay visible together above the keyboard, and the primary action of every screen stays reachable by scrolling. | C with font-scale configuration, M |
| A-05 | Never color alone. Unread, mention, presence, delivery state, role and errors each have text, a number, a shape or an icon besides color. | R, S (grayscale check) |
| A-06 | TalkBack: every icon-only button has a content description naming the action; badges are merged into their row ("Kitchen, 12 unread, 1 mention"); bubbles read sender, text, time and delivery state in that order; decorative glow, chit and illustration are hidden from accessibility. | C (semantics assertions), M |
| A-07 | Level 2 notices are polite live regions; level 3 decisions move accessibility focus to their title. | C |
| A-08 | System "Remove animations" (animator duration scale 0) produces instant transitions with no stuck intermediate state. | C, M |
| A-09 | Layouts mirror correctly in RTL locales, including the chit side and bubble alignment. | S (RTL pseudo-locale), C |
| A-10 | Light, dark, AMOLED black and high-contrast palettes all pass A-01 and A-02. | U |
| A-11 | Message text uses the system font and honors the device font family and size. The display face never renders message bodies. | R |
| A-12 | Landscape and large-screen windows keep a readable transcript width (bubbles never wider than about 600dp) and keep the existing landscape gates. | S, existing landscape tests |

## Presentation requirements

### Theme and tokens

| ID | Requirement | Verify |
|---|---|---|
| P-01 | One theme module defines every color, shape, type and spacing token from [visual-vocabulary.md](visual-vocabulary.md). Feature code under `feature/` and `core/ui/` contains no literal `Color(0x…)`, `RoundedCornerShape(<literal>)` values for identity shapes, or font families. | U (source scan test), R |
| P-02 | A JVM test computes the contrast of every token pair listed in visual-vocabulary.md for every palette and fails below its threshold. | U |
| P-03 | Dynamic color is off (D4). | R |
| P-04 | The theme respects the saved System/Light/Dark preference (existing M1 behavior) and the system bars match the ground color. | C, M |
| P-05–P-08 | AMOLED black (required before the first public release): the Pure black switch, exact tokens, edges instead of shadows, and no light flash. Defined in [amoled-black.md](amoled-black.md). | See that document |

### Signatures

| ID | Requirement | Verify |
|---|---|---|
| P-10 | Rooms render as squircles and people as circles everywhere they appear (lists, headers, notifications, profile cards, mention suggestions). Guests render as dashed circles once guests exist. | S, R |
| P-11 | Identity hue = stable function of the immutable server ID (not name), from the eight-hue set, with ink text. A rename does not change the hue. | U |
| P-12 | Bubbles have no tail. Exactly the last bubble of each run carries the chit at its speaker corner, mirrored for your own messages and for RTL. Runs break on sender change, day separator, or the chosen time gap. | U (run grouping), S |
| P-13 | Persimmon is the only action color. On any screen there is at most one persimmon-filled primary button, not counting Send and badges. | S, R |
| P-14 | The liveliness glow appears only where presence is actually known, always with a text equivalent, never animated. | R, S |
| P-15 | Admin and moderation actions use the steward color and appear in a labeled section after social actions ("As admin"). Destructive actions end in an ellipsis and open a confirmation. | S, R |

### Communication

| ID | Requirement | Verify |
|---|---|---|
| P-20 | Connectivity notices follow the table in [communication.md](communication.md): state names, copy intent, precedence, and the about-2-second "Back online" confirmation. One notice at a time. | C (fake connectivity source), S |
| P-21 | Offline: Send is disabled with an accessible reason, the draft remains, loaded content stays visible with the "Loaded at" marker, and nothing is sent automatically on reconnect. | C |
| P-22 | Delivery states render exactly as specified: Sending, Sent, Not sure (honey outline plus Check), Failed (text restored). The duplicate-risk dialog appears only after an explicit Check that did not find the message. | C, existing unknown-send tests |
| P-23 | No snackbar or toast carries information needed later. Errors name what happened, what was affected and what the person can do. | R |
| P-24 | Required disclosures in communication.md stay visible without scrolling past the first screen of their surface. | S |
| P-25 | Status presentation adds no polling, wake locks or background work. The short UI timers the spec calls for (about 2 s "Back online" dismissal, about 5 s reconnect escalation, about 2 s placeholder animation limit) are bound to the composition or lifecycle that shows them and cancelled when it leaves or the app goes to the background. Animations run only while visible in the foreground and none loops indefinitely. | R, U where practical |

### Structure and onboarding

| ID | Requirement | Verify |
|---|---|---|
| P-30 | Navigation is the bottom bar Rooms · Direct · Activity · You (D3). Each destination keeps its own scroll position and back stack across tab switches and configuration changes. | C |
| P-31 | Every list and transcript has a designed empty state with a next step (see mockups `RoomEmpty`, `FirstRooms`). No bare "No items". | S |
| P-32 | Onboarding follows [onboarding.md](onboarding.md): server first, server identity confirmed before credentials, no permission request before it is needed, input preserved across every failure state. | C, M |
| P-33 | Suggestion chips insert text into the composer; they never send. | C |
| P-34 | Loading placeholders stop animating within about 2 seconds and then say what is happening. | C |

## Judgment standards

| ID | Standard |
|---|---|
| J-01 | **Recognizable.** The screen passes the squint, color, two-of-three and not-a-skin tests in [vision.md](vision.md). |
| J-02 | **Belongs on the canvas.** Placed next to the mockups, a new screen looks like the same product: the same density, rhythm, warmth and restraint. |
| J-03 | **Inviting.** A person opening the screen can tell within a second who is there or what to do next. Empty and quiet states invite rather than report. |
| J-04 | **Honest.** Nothing on the screen implies certainty the app does not have (delivery, presence, read state, encryption). |
| J-05 | **Calm.** Warm color marks people and attention only. If everything is highlighted, nothing is. |

## Review process

- Each presentation slice attaches before and after screenshots, light and dark, at the
  default and 200% font scale, to its PR. The usual development merge gates in `AGENTS.md`
  still apply.
- The presentation lead reviews J-* standards and may request changes. A disagreement about
  a J-* standard that affects product direction goes to the maintainer.
- Screenshot tests (for example Roborazzi or Compose Preview screenshot testing) are
  recommended but are a dependency decision for the coordinator. They do not replace
  device checks of A-04 and A-06.
