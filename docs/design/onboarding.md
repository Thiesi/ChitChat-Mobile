# Onboarding: first launch to first message

Goal: a new person goes from installing the app to their first message in a real room in
under two minutes, without ever seeing a technical term they have to understand.

ChitChat is self-hosted, so the server address is unavoidable. The flow makes it feel like
"which café are you going to" rather than "configure an endpoint".

Mockups: boards 1–4 on the canvas (`Main`, `OnboardServer`, `OnboardFound`, `FirstRooms`),
then `Home` and `RoomEmpty`.

## Flow

1. **Welcome** (dark, the only screen that is always dark). The mark with its glow, the
   headline "Come in. The lights are on.", one sentence explaining that a community runs
   the server, **Get started**, and **Scan an invite code** (D5). No carousel, no feature
   tour, no account wall before the person knows where they are going.
2. **Where do you chat?** One field, "Server address", with an example-free hint ("Whoever
   invited you knows it. It looks like a website address."). Keyboard: URL type, no
   autocorrect. Accept a bare host (`chat.example.org`), a full HTTPS URL, or an IDN.
   Normalization and the HTTPS-only rule follow M2a. **Continue** stays enabled; validation
   happens on press, not while typing.
3. **Server found.** A card shows the installation's lantern (initial and identity hue from
   the host), its name and the host with a lock icon. If the public name is unavailable, show
   the host as the name (existing rule: the effective admin override is not public JSON). Then
   "Found it. Are you new here?" with **Create my account**, **I already have one, sign in**,
   and, when guest visits exist (M7) and the server allows them, **Look around as a guest**.
4. **Create account or sign in.** The existing M2a/M2b forms, restyled: one column, 56dp
   fields, labels above fields, password visibility toggle, optional birth date clearly
   marked "optional". The anti-bot proof shows as a calm determinate or indeterminate bar
   with "Making sure you're a person. This takes a few seconds." and a Cancel. Never a fake
   percentage.
5. **Find your people** (new accounts only). "Welcome, Mara. Pull up a chair." Public rooms
   with lanterns and info lines, each with its own **Join** button. Each Join is a separate
   confirmed action, shown as "Joined" only after the server confirms. No pre-checked boxes,
   no "join all". **Start chatting** goes to Rooms.
6. **First room.** If the room is empty, the empty state invites the first message, and
   suggestion chips put text into the composer without sending it.

Returning users who sign in skip step 5 and land on Rooms.

## Permission requests

- Notifications: ask only when the person first turns on a notification preference or
  opens the notification settings, with one sentence on what they will get. Never at
  launch or during onboarding. Step 5 says so explicitly.
- Camera: only when the person taps Scan (D5). Show the typed-address path if they decline.
- No other runtime permissions during onboarding.

## Failure states (all level 2 notices on the same screen, input preserved)

| Situation | Copy |
|---|---|
| Not a valid address | "That doesn't look like a web address. Check for typos." |
| Device offline | "No connection. Connect to the internet and try again." |
| Host does not answer | "chat.example.org isn't answering. Check the address, or try again later." |
| Host answers but is not ChitChat | "chat.example.org doesn't look like a ChitChat server. Ask the person who invited you for the exact address." |
| Registration closed | "Sign-up is closed on this server. Ask the person who runs it for an invite." |
| Wrong credentials | Existing M2a copy, never revealing whether the username exists |

## Invite codes (approved, D5)

An invite code is the installation's plain HTTPS origin as a QR code or link. Scanning it
fills step 2 and goes straight to step 3, which is always shown. It carries no credentials,
tokens, usernames or room IDs, and it needs no backend change. Anyone signed in can share one
from You → "Invite someone". Full specification: [invite-codes.md](invite-codes.md). Room
invitations stay a server feature (M7) and are separate.

## What onboarding must not do

- Collect anything not required by the server.
- Show marketing, ratings prompts or "rate us" dialogs.
- Auto-join rooms on the person's behalf or post a "Mara joined" message the person did
  not choose.
- Autofill or store passwords outside the system password manager. Synthetic test fixtures
  follow the autofill rules in `AGENTS.md`.
- Open a browser or another app with the contents of a scanned code, or connect to a
  scanned server before step 3 is shown (see [invite-codes.md](invite-codes.md)).
