# Visual vocabulary

Values here are the reference for the Compose theme. Contrast ratios are WCAG 2.x
ratios computed for the exact pairs listed. Implement every value as a named token in
one theme module (see [acceptance.md](acceptance.md), P-01). Feature screens use tokens only.

## Color

### Core tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `ground` | Paper `#FBF7F1` | Night ink `#14111C` | Screen background |
| `surface` | `#FFFFFF` | `#1C1826` | Cards, fields, sheets, bottom bar |
| `raised` | `#F3ECE2` | `#272233` | Incoming bubbles, quiet chips, offline strip |
| `line` | `#E6DCCF` | `#3A3347` | Decorative dividers only (not a component boundary) |
| `fieldLine` | `#8C8299` | `#7D7490` | Input and outlined-button borders (3.64:1 on white, 3.95:1 on dark surface) |
| `text` | `#14111C` | `#F2EDF7` | Primary text (17.5:1 / 16.2:1 on ground) |
| `textMuted` | `#5E566B` | `#A79FB4` | Secondary text (6.5:1 / 7.3:1 on ground; 5.9:1 / 6.1:1 on raised) |
| `signature` | Persimmon `#F06A3B` | Persimmon `#F06A3B` | Fills: Send, primary buttons, badges, chit, active tab tint |
| `onSignature` | `#14111C` | `#14111C` | Text and icons on persimmon (6.1:1). **Never white** (3.1:1 fails) |
| `signatureText` | `#B5401A` | `#F06A3B` | Persimmon used *as text* (5.3:1 on paper, 5.7:1 on white, 6.1:1 on night ink) |
| `outgoing` | Apricot `#FFD9C4` | Ember `#8A3A1E` | Your bubbles |
| `onOutgoing` | `#14111C` | `#F2EDF7` | Text on your bubbles (14.2:1 / 6.7:1) |
| `presence` | Honey `#FFB547` | Honey `#FFB547` | Presence glow and dot, typing dots, mention badge, "not sure" outline |
| `presenceWash` | `#FFF0D2` | `#4A3A1C` | Mention highlight background, warning strip |
| `steward` | Dusk `#3D52C4` | Dusk `#A9B6FF` | Admin and moderation, links (6.1:1 / 7.9:1) |
| `error` | Rose `#C4314B` | Rose `#FF8A9B` | Errors and destructive confirmation only (5.1:1 / 7.8:1) |

Rules:

- Persimmon is the only action color. If two things on one screen compete for persimmon,
  one of them is not the primary action.
- Persimmon filled on paper is 2.9:1, so a persimmon fill must never be the *only* thing
  identifying a control or a state on the light theme. Buttons carry ink labels; badges carry
  numbers; the active tab also has bold label weight.
- No green for success. Confirmed states use ink and wording ("Sent"). Green belongs to
  another app's identity and is a poor signal for people with red-green color blindness.
- Honey means "people" or "attention, not danger". Rose means "something failed or will be
  destroyed". Never swap them.
- Dark is not an inversion. Dark mode keeps warm ink grounds (violet-black), never neutral
  gray. Pure black exists only in the separate AMOLED black theme below. The web's
  high-contrast theme can be offered as an accessibility palette (D6).

### AMOLED black theme (required before going live, D6)

A third appearance choice next to Light and Dark, for OLED screens. It changes only these
dark tokens; every other dark token stays as listed above.

| Token | AMOLED black | Check |
|---|---|---|
| `ground` | `#000000` | `text` 18.2:1, `textMuted` 8.3:1, persimmon 6.8:1, honey 12.0:1 |
| `surface` | `#110E17` | `fieldLine` 4.3:1 |
| `raised` | `#1F1B29` | `text` 14.6:1, `textMuted` 6.6:1 |
| `line` | `#2E2938` | Decorative only |

- Large areas (ground, transcript, lists) are true black. Surfaces stay a hair above black
  so sheets, fields and the bottom bar remain distinguishable without borders everywhere.
- The glow keeps its ground-colored gap, which is now black, so it still reads as a ring.
- The setting is local like the existing System/Light/Dark preference: *System* follows the
  device dark mode and uses regular Dark unless the person picked AMOLED black.

### Identity hues (rooms and people)

Eight hues, always with `#14111C` text (all at least 8.4:1):

`apricot #F7A072`, `honey #F5C451`, `lime #A8D672`, `sea #7FD1C1`, `sky #8DB8F5`,
`lilac #B7A3F5`, `rose #F29BC4`, `clay #D9B08C`.

The hue is chosen by a stable hash of the server-provided immutable ID (room ID, account
ID), so it never changes when someone renames a room or person. When an avatar image exists
(future M5 media), the hue stays as a fallback and loading color.

## Shape

| Element | Shape | Size |
|---|---|---|
| Room ("lantern") | Squircle (rounded rectangle, corner about 1/3 of the side) | 48dp in lists, 36dp in headers, 64dp on cards |
| Person ("pebble") | Circle | 44dp in lists, 32dp beside messages, 24dp in stacks |
| Guest | Circle with a dashed `fieldLine` border on `raised`, no identity hue | Same as a person |
| Bubble | 20dp corner radius on all corners; no tail | Max width about 75% of the transcript |
| Chit | Circle, 9dp, same fill as its bubble, offset about 11dp outward from the bottom speaker corner | Only on the last bubble of a run |
| Buttons | Full pill (height/2 radius); primary 52dp tall, small 40dp visual inside a 48dp target | |
| Fields | 16dp corners for forms; full pill for the message input | 56dp forms, 48dp minimum composer |
| Sheets and dialogs | 28dp top corners (sheets), 28dp (dialogs) | |

A "run" is consecutive messages by the same sender with no day separator and no more than
a few minutes between them (exact window: the implementation picks a constant, then keeps it
consistent everywhere). The chit sits on the incoming bubble's bottom-left and on your own
bubble's bottom-right, mirrored for RTL.

### Liveliness glow

- States: *nobody known to be here* (no glow) and *people here* (glow: a 2dp ground-colored
  gap, a 2dp honey ring and a soft honey halo).
- Always paired with text, such as "4 here now" in the row or header. The glow is never the
  only signal.
- Static. It never pulses, breathes or animates, because of the battery and because
  motion attracts attention that people did not ask for.
- Only shown where presence is actually known. Today that is the current room's header,
  after entry. Lists show it once the server provides a presence summary (D7).

## Typography

| Role | Face | Weight | Size (sp) | Use |
|---|---|---|---|---|
| Display L | Bricolage Grotesque (D2) | 800 | 34–44 | Onboarding headlines |
| Display M | Bricolage Grotesque | 700–800 | 26 | Top-level screen titles, empty-state headlines |
| Title | Bricolage Grotesque | 700 | 19 | Room/person name in a conversation header |
| Body | System sans | 400 | 15–16 | Messages, list primary text (names at 600) |
| Label | System sans | 600 | 13 | Section labels (uppercase, +0.02em), badges at 700/12 |
| Caption | System sans | 400 | 11–13 | Times, hints, states |

- Message text is never in the display face. It must follow the user's device font and size.
- All sizes are `sp` and scale with the system font size. See the floors in
  [acceptance.md](acceptance.md).
- Bricolage Grotesque is bundled (D2), subset to Latin plus Latin Extended. Scripts it does
  not cover fall back to the system sans at the same weights.

## Iconography

- Outlined stroke icons, 24dp, about 1.8dp stroke, round caps and joins. Material Symbols
  Rounded (outlined, weight 300–400) is an acceptable source.
- The Rooms tab icon is the app mark simplified (bubble plus chit). The Direct tab icon is a
  person. Do not use a generic chat-bubble icon for both.
- Emoji appear only as user content (messages, the six reactions). They are never used as
  UI icons.

## Motion

- Motion only explains a change of state: a message arriving, a sheet opening, a send
  completing. 150–250 ms, standard easing. Nothing loops.
- Typing dots: static honey dots. If animated at all, the animation runs only while the
  indicator is visible *and* the app is in the foreground, and stops with the existing
  6.5-second typing expiry. Typing is never a reason to keep a frame clock running
  in the background.
- Respect the system "Remove animations" setting: then every transition is an instant cut.
- No shimmer skeletons that run indefinitely. A loading placeholder stops animating after
  about 2 seconds and shows plain text ("Still loading…") instead.

## Illustration

- Built from the identity shapes only: lantern squircles, bubbles, chits, pebbles. Flat
  fills, at most one honey glow. No people illustrations, no stock-style scenes.
- Empty-room drawing: a dashed outline bubble with one persimmon chit ("someone could
  speak here").
- Vector drawables only, drawn once. No Lottie, video or animated backgrounds.

## The app mark

A persimmon squircle bubble with two ink "eyes", and a small persimmon chit at its
lower right, optionally inside a honey glow. Launcher icon: the bubble and chit on Night
ink, adaptive-icon safe zone respected, monochrome themed-icon variant provided. The
existing banner (`docs/assets/chitchat-banner.png`, blue glowing bubbles) belongs to the
web project and is not the CCM mark. Final public identity remains a maintainer decision
(see `AGENTS.md`).
