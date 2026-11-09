# Guest visits (M7): presentation specification

Status: presentation spec for guest visits, written ahead of the M7 engineering slice
(technical lead, 2026-11-09). It's grounded in ChitChat `docs/api/guest-access.md` at main
`1ddd59c`, plus `policies.md` (rules acceptance) and `moderation-reports.md` (guest reports).
Mockup: `GuestVisit` on the canvas. Moderator actions on guests (End visit, Block this
connection) belong to the moderation spec.

## What the server offers

- **Availability:** `session.guest_access: true` while the server may offer a visit. A
  Super-Administrator switches it on; it's off by default.
- **Start:** `GET guest/challenge.php` (a proof of work like registration, or `challenge: null`),
  then `POST guest/start.php {challenge_nonce, challenge_solution, accepted_rules_version?}`
  returns `201` with a new CSRF token and the guest `user`.
  - Errors: `403 guest_access_disabled`, `403 guest_access_paused` (maintenance lockdown),
    `403 guest_access_blocked` (a moderator blocked this connection for a while),
    `429 guest_limit_reached` (three guests from this connection), `409 already_signed_in`.
- **Who a guest is:** a real account of its own kind, named "Guest NNNN" (never reused, never
  collides with a username, can't be @-mentioned or pinged). `user.guest: true` and
  `user.guest_expires_at` give the latest end.
- **How long:** one visit. It ends after **two hours without a request**, after **24 hours at
  most**, when the guest leaves (`logout.php`), when a moderator ends it, or when guest access is
  switched off. An ended guest becomes a closed account that keeps its name, so its messages stay
  readable.
- **What guests may do:** see and join rooms whose `guest_access` is `read` or `write`, read
  history, see presence; in `write` rooms, post, edit and delete their own messages, react and
  show typing (with an extra limit of 6 messages per minute). Open profile cards, ignore people,
  report room messages and people (person reports need the room context), read notifications.
- **What guests can't do:** everything else answers `403 guest_not_allowed`: direct messages,
  pings, uploads, search, room management, notifications on the phone (push), and every account
  and administration endpoint. Guests are never mentioned and can't be invited or hold a role.
- **Welcome:** the guest gets a `guest_welcome` notification that links to registration.
- **No conversion:** there's **no endpoint that turns a guest into a member.** Registering
  creates a new account. The guest's messages stay as "Guest NNNN".

## Principles

- **Honest about the limits, up front.** A guest learns what a visit can and can't do before
  starting, in plain words, not by bumping into errors.
- **Nothing pretends to be permanent.** The visit's end time is visible, and nothing suggests
  the guest's identity or messages carry over to an account.
- **Missing features are absent, not broken.** Controls guests can't use are left out of the UI
  rather than shown and refused.

## Starting a visit

- **Entry:** onboarding step 3's **Look around as a guest** (onboarding-restyle.md), shown only
  when the anonymous session reports `guest_access: true`. Not shown during a maintenance
  lockdown notice.
- **Explainer screen** "Look around as a guest":
  - "You'll get a temporary name, like Guest 4821. Your visit ends after two hours without
    activity, or after a day at most."
  - "As a guest you can read open rooms, and write where a room allows guests. You can't send
    direct messages, pings or files, search, or get notifications on your phone."
  - "Messages you write stay visible as your guest name after you leave, even if you create an
    account later."
  - Primary **Start visit**, text button **Back**.
- **Rules:** the app guidelines screen follows (play-readiness.md §1), with the server's rules
  section when `policies.rules` exists. **I agree** then starts the visit with
  `accepted_rules_version`. The app-guidelines acceptance is stored locally per server for
  guests on this device, so a later visit on the same server doesn't ask again unless the
  version changed.
- **Proof of work:** the same calm "Making sure you're a person…" row as Create account, under
  Start visit. Never a percentage. A fresh challenge per attempt.
- **Outcomes:**

  | Result | Shows |
  |---|---|
  | `201` | The guest shell, with the level-1 line "Welcome, Guest 4821. Your visit lasts until {time} at the latest." |
  | `guest_access_disabled` | "{installation} isn't offering guest visits right now." Back to step 3, without the guest button |
  | `guest_access_paused` | The maintenance notice, as on sign-in |
  | `guest_access_blocked` | "Guest visits from your connection are paused for a while. You can still sign in or create an account." |
  | `guest_limit_reached` | "There are already three guests from your connection. Try again later, or create an account." |
  | Unknown (lost response) | "Not sure your visit started." with **Check**, which re-reads the session. Never a second start POST |

## The guest shell

The navigation shell, reduced to what guests can use:

- **Bottom bar:** **Rooms**, **Activity** and **You**. The Direct tab is absent.
- **Rooms:** only rooms with guest access appear (the server filters them). A `read` room shows
  the composer replaced by a calm line: "Guests can read this room. **Create an account** to
  write." A `write` room shows the normal composer **without** the paperclip and private-ping
  entry. A `429` on the guest limit shows the waiting-time copy (status-presentation.md).
- **Search:** absent. **Room creation and management:** absent.
- **Profile cards:** open as usual. Reporting a person shows only when the card was opened in a
  room (reporting.md, Guests).
- **Activity:** readable, including the `guest_welcome` entry. Its link goes to **Create an
  account** (below), not a browser.
- **You:** a guest card instead of the account header:
  - the dashed guest pebble (visual-vocabulary.md), "Guest 4821";
  - "Visiting until {time}" from `user.guest_expires_at` (device time format; the date too when
    it's tomorrow), plus "Ends sooner after two hours without activity.";
  - **Create an account** (primary) and **Leave visit** (text).
  - Below: Appearance (per-device) and About and privacy. **No** account, security,
    notifications or export rows.
- **Others see guests** with the dashed pebble and a "Guest" pill (room-presence.md,
  lists-restyle.md). @-completion never offers guests (the server excludes them).

## Ending

- **Ten minutes before `guest_expires_at`:** one level-1 line in the shell, "Your visit ends at
  {time}." No countdown and no timers when backgrounded. The two-hour idle limit can't be
  predicted, so it isn't counted down.
- **Leave visit:** a decision dialog, "Leave your visit? You can't come back as Guest 4821.
  Messages you wrote stay visible under that name." (**Cancel** / **Leave**). It sends
  `logout.php` and shows step 3. An offline or unknown logout still discards the visit locally,
  with the existing sign-out disclosure.
- **Visit ended** (forced logout, an expired or invalidated session on revalidation): a
  full-screen state, "**Your visit has ended**", with "Guest visits end after two hours without
  activity, after a day, or when {installation}'s moderators end them." Actions: **Start a new
  visit** (only while `guest_access` is still true), **Create an account**, **Use a different
  server**. It never says which reason applied, because the client can't know.
- Drafts in memory are discarded with the visit. Nothing about the visit is kept on the device.

## Becoming a member

- **Create an account** (from You, a read-only room's line, the welcome entry or the ended
  state) first explains, in a decision dialog: "Creating an account ends your visit. Your new
  account starts fresh: messages you wrote as Guest 4821 stay under that name." (**Cancel** /
  **Continue**).
- **Continue** sends `logout.php` for the guest, then opens onboarding step 4b (Create account)
  on the same server. Registration then follows its own spec, including the recovery key and
  rules.
- Only when registration is enabled. Otherwise the action reads "Sign in" and the dialog says
  "Sign in with an existing account? Your visit will end."
- CCM never promises to carry over the guest's history, rooms or name. There's no API for it.

## Tests

- **Unit:** the guest button only with `guest_access`; each start error and Unknown-by-GET; no
  second start POST; the shell hides Direct, search, paperclip, ping and account rows; the
  ten-minute line from `guest_expires_at` only; leave and create-account both log out first.
- **Compose:** explainer and ended-state copy; read-only room line; guest card; the decision
  dialogs.
- **Native:** the composer in `write` rooms keeps every input contract without the paperclip.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-195 | Guest visits start only from step 3 when `guest_access` is true, after the explainer, the rules screen and a fresh challenge. Each start error has its copy, and Unknown re-reads the session instead of starting again. | U, C |
| P-196 | The guest shell shows only what guests can use: Rooms, Activity and You, with no Direct tab, search, paperclip, pings or account rows. Read-only rooms explain how to write. | U, C |
| P-197 | The visit's latest end is visible, one warning appears ten minutes before it, and the ended state never claims a specific reason. | U, C |
| P-198 | Leaving and creating an account both end the visit with a confirmation that says guest messages stay under the guest name. Nothing promises to carry the visit over. | C |
