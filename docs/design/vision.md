# Vision: Lantern

## The idea in one sentence

**ChitChat is the lit window on your street: warm light, a few familiar voices, and an open
door.**

ChitChat is not a global messenger. Each installation is a small, self-hosted community,
such as a family, a club, a group of friends or a team. People come to it because specific
people they know are there. The app's job is to make that presence felt, to make starting
a conversation effortless, and to make coming back feel like walking into a warm room
rather than clearing an inbox.

## Experiential principles

1. **People before plumbing.** The first thing on every screen is who is there and who
   said what. Server mechanics (sessions, sync, cursors) surface only when they change what
   a person can do, and then in plain words.
2. **Warmth means presence.** Warm light (honey, persimmon) is reserved for signs of
   people: someone is here, someone mentioned you, someone is typing, your own words. It is
   never decoration. A screen with nobody on it is calm, not dead: it invites you to be the
   first.
3. **The door is always open.** Every empty state offers a next step a real person would
   take: say hi, find a room, invite someone. No empty state merely says "Nothing here".
4. **Honest about uncertainty.** Mobile networks drop. When CCM does not know whether
   something happened, it says so, keeps your words safe, and lets *you* decide. It never
   pretends, never quietly retries a send, and never makes you retype.
5. **Return is a reward, not a duty.** CCM invites people back by making return
   worthwhile: who mentioned you, what you missed in rooms you care about, a draft you left.
   It never uses streaks, guilt, fake urgency, or notifications that exist only to pull you
   in. Bring people back for people, never for the app's metrics.
6. **Social is warm, stewardship is cool.** Everything social uses the warm palette.
   Moderation and administration use cool Dusk blue. An admin can always tell "I am talking"
   from "I am acting on someone".
7. **Quiet on the battery.** Liveliness is shown with static light, never with continuous
   animation. CCM looks alive because of the people in it, not because it moves.

## What "easily recognizable" means here

WhatsApp is recognizable from a blurred thumbnail because one color, one bubble shape and
one wallpaper repeat on every screen. CCM gets the same effect from three **signature
elements**, which must all be present in the product and repeat consistently:

| Signature | What it is | Where it appears |
|---|---|---|
| **Persimmon on Night ink / Paper** | A warm red-orange used as the single action and attention color, on a warm-dark or warm-light ground. Text on persimmon is always dark ink, never white. | Send, primary buttons, unread badges, the active tab, the chit |
| **The chit** | Bubbles have no pointed tail. The last bubble in a speaker's run gets a small detached dot at its speaker corner, the "chit" of ChitChat. The same big-bubble-plus-dot forms the app mark. | Every transcript, the launcher icon, empty states, onboarding |
| **Lanterns and pebbles** | Rooms are squircles ("lanterns"); people are circles ("pebbles"); guests are dashed circles. When people are present, the lantern gets a static honey glow, always paired with words. | Room and DM lists, headers, profiles, notifications |

### Recognizability tests (used in design review)

These are judgment tests for reviewers, not pixel rules:

- **Squint test.** Blur a screenshot of any primary screen (rooms, DM inbox, room, DM
  thread) until no text is legible, and convert it to grayscale. A reviewer who knows the
  apps should still separate it from WhatsApp, Telegram, Signal, Discord and Google
  Messages, by shape alone.
- **Color test.** At thumbnail size in color, the screen reads as "warm orange on dark" or
  "warm orange on paper", never as green, blue or purple.
- **Two-of-three rule.** Every primary screen shows at least two of the three signatures.
  The transcript shows all three.
- **Not a skin test.** Replacing CCM's colors with Material defaults should make the
  screen look clearly worse *and* less like CCM. If only the color changes the feel, the
  shapes are not doing their job.

## Where we allow freedom

These documents fix the identity, the information rules and the floors. They do not fix
every spacing value, every illustration or every animation curve. Implementers and later
designers may make choices that serve the principles above. When in doubt, compare
against the mockups: a new screen should look like it belongs on the same canvas.

## What this is not

- Not a restyle of the web client. The web app keeps its own six palettes, stored per device
  in the browser; there is no account-level palette to sync (D6). CCM may later offer extra
  local palettes, but its shapes and voice remain CCM's.
- Not permission to change behavior. See the [README](README.md) for precedence.
- Not final. The layout is explicitly open, and the maintainer approves consequential
  decisions.
