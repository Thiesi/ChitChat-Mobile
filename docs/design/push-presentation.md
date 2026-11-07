# Push notification presentation: specification for M6

Status: design specification for how background notifications look and behave once M6
implements UnifiedPush/Web Push (see [push-architecture.md](../push-architecture.md)). It
decides presentation only. Transport, registration, key handling, instance and generation
validation, and logout cleanup are M6 engineering under the existing architecture decision.
Mockups: `PushShade` and `NotificationSettings` on the canvas.

## What a push contains today

The server's dispatcher sends `{title, body, link, app_name}`:

- `title` and `body` are the same rendered, display-safe text the in-app notification list
  uses. **No raw message body is ever in a push.**
- `link` is a same-origin link that the native link parser (M4c4a) treats as a hint.
- `app_name` is the installation's **effective** name, which the public health endpoint
  doesn't expose.

Optional notification IDs and bindings are proposed in backend issue ChitChat#147.
Notification kinds (mention, ping, account, moderation) are **not** in the payload, and
ordinary unmentioned DMs create no notification at all (B6 is pending a product decision).
Quiet hours and the mention/ping mutes are applied by the server before sending.

## Principles

- **A push is a doorbell, not the message.** It says who wants your attention and where. The
  conversation itself is read in the app. That matches the payload, which carries no message
  bodies, and keeps lock-screen exposure low.
- **No re-engagement.** Only server-sent notifications are shown. CCM never posts local
  "come back" notifications (communication.md).
- **Nothing happens in the background.** Receiving a push posts a notification. It doesn't
  fetch history, mark anything read or send anything.

## Channels

Android channels are created **when the person first turns notifications on** (You →
Notifications), never at install. Their IDs never change once shipped, because Android keeps
user settings per channel ID.

| Channel ID | Name | Importance | Created | Receives |
|---|---|---|---|---|
| `mentions` | Mentions and pings | High (heads-up, sound) | When push is enabled | Mentions and pings. **Until the payload carries a kind, every push goes here** |
| `account` | Account and security | Default | When `kind` is available | Sign-ins, security and account notices |
| `moderation` | Moderation | Default | When `kind` is available and the account holds a moderation role | Reports, revision reviews and moderator notices |
| `direct` | Direct messages | High | Only if B6 is accepted | Ordinary DMs (not existing parity) |

**Dependency:** separating `account` and `moderation` from `mentions` needs an optional
`kind` in the payload, alongside the IDs proposed in ChitChat#147. Until then, CCM must not
guess a kind from the title text.

## The notification

Built with `NotificationCompat`:

- Small icon: the monochrome Lantern mark. Accent color: persimmon `#F06A3B`.
- Content title: payload `title`. Content text: payload `body`, using `BigTextStyle`
  so longer text expands.
- Sub-text: payload `app_name` (for example "Schmidt Family Chat"), so the person knows
  which community it came from. This becomes essential once more than one server or account
  is supported.
- Time: the moment of receipt (`setWhen`, `setShowWhen(true)`). The payload has no server
  time, and server dispatch adds roughly 30–60 seconds, so don't present it as the exact
  message time.
- No large icon or avatar. Fetching images on receipt is network and battery work that the
  payload doesn't authorize.
- **Not** `MessagingStyle`, conversation shortcuts or bubbles: the payload isn't a chat
  message, and presenting it as one would imply content and reply abilities CCM doesn't
  have.
- **No action buttons** in the first version. "Mark read" would be a background POST with
  uncertain outcomes, and "Reply" is impossible without message content. Both would need
  their own design.
- Tap: an immutable `PendingIntent` opens CCM with the `link`. The link goes through
  the existing native link parsing and the guarded notification-destination flow (M4c4d):
  fresh authorization and exact-target validation, nothing marked read. If the destination
  is unauthorized, unsupported or stale, CCM opens the Activity tab. It never opens a browser.
  If the person must sign in first, the destination is dropped, not replayed afterwards.

## Grouping and replacement

- All notifications for one account on one server share a group key, scoped by server and
  account. When two or more are showing, a summary notification reads "{n} new in
  {app_name}", without listing names.
- With notification IDs (ChitChat#147), use the ID as the notification tag so a re-delivered
  push replaces its earlier copy. Without IDs, each push is a separate notification. Never
  deduplicate by comparing text.
- Opening CCM does **not** automatically clear notifications. Viewing a destination may clear
  only that destination's own notification, once the tag or ID makes it identifiable.

## Lock screen

- Channel visibility is `VISIBILITY_PRIVATE`, with a public version whose title is the
  installation name and whose text is "New notification", or "New mention" once `kind` exists.
  Rendered notices can contain usernames and moderation reasons (M4c4a), so the full text
  shows only when unlocked or when the person's system settings allow it.
- The setting text says so plainly: "On the lock screen, ChitChat shows only that something
  arrived, unless your phone is set to show all notification content."

## Account and session safety

- Signing out, switching account or a session-generation change cancels that account's posted
  notifications immediately, and later pushes for it are dropped silently (M6 contract).
- A push for an account that isn't the signed-in one is never shown.

## When the app is open

- While CCM is in the foreground, incoming pushes post **silently** to the shade (no sound or
  heads-up), so the person isn't interrupted twice.
- Receiving a push in the foreground doesn't trigger a refresh by itself. The Activity count
  keeps its existing refresh rules (foreground, navigation, manual). A push-triggered summary
  refresh would be a new request trigger and needs a separate decision.

## Settings (You → Notifications)

The screen shows:

- **Status card** for this phone, one of:
  - "Off";
  - "On, delivered by {distributor}";
  - "Needs setup: no delivery app found";
  - "Blocked in Android settings" (with a button to the system settings).
- **Turn on** walks through:
  1. a one-paragraph explanation ("ChitChat doesn't run in the background. A delivery app you
     choose wakes it when something arrives, which saves battery.");
  2. the Android notification permission (API 33+) at this moment only;
  3. distributor selection when more than one is available. With none installed, a short
     explanation and a link to choosing one. The optional embedded fallback is used only if the
     build includes it, and is named honestly.

  Battery-optimization exemptions belong to the distributor, not CCM. CCM never asks for one
  for itself.
- **What you get:** toggles for mentions and for pings (the existing server preferences).
  Quiet hours as start and end times with the account timezone. Copy: "Quiet hours are applied
  by your server, on all your devices."
- **Devices:** the existing server device list, read-only names, each with "Remove", and
  copy that doesn't invent transport labels the API doesn't provide.
- **Honesty line:** "Notifications can arrive a minute or so after the message. Open the app to
  see everything."

## Tests (for the M6 slice)

- **Unit:**
  - payload to notification mapping: title, text, sub-text, channel `mentions` without a kind;
  - the public lock-screen version;
  - group key per account and server;
  - tag from the ID only when present;
  - a stale-generation payload is dropped.
- **Instrumented:**
  - channels appear only after Turn on;
  - tap routes through the guarded destination flow and falls back to Activity;
  - sign-out cancels posted notifications;
  - pushes post silently while foregrounded;
  - no notification permission prompt outside the Turn on flow.
- **Manual:** two distributors and one device without Play services (existing release gate in
  push-architecture.md); lock-screen appearance with the system set to hide sensitive content.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-120 | Channels are created only on Turn on with stable IDs. Every push uses `mentions` until a payload `kind` exists. The kind is never guessed from text. | U, C |
| P-121 | The notification shows the payload title and text, `app_name` as sub-text, the mark and persimmon accent, with no large icon, no action buttons and no MessagingStyle. | U, S |
| P-122 | Tap routes through native link parsing and the guarded destination flow (no read, no browser), falling back to Activity. | C |
| P-123 | Lock-screen visibility is private with a generic public version. | U, M |
| P-124 | Sign-out, account switch and generation change cancel that account's notifications and drop later pushes. | C |
| P-125 | The notification permission is requested only inside Turn on. The app requests no battery exemption for itself, and no local re-engagement notifications exist. | C, R |
