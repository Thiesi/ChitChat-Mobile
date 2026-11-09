# Push notification presentation: specification for M6

Status: design specification for how background notifications look and behave once M6
implements UnifiedPush/Web Push (see [push-architecture.md](../push-architecture.md)). It
decides presentation only. Transport, registration, key handling, instance and generation
validation, and logout cleanup are M6 engineering under the existing architecture decision.
Mockups: `PushShade` and `NotificationSettings` on the canvas. `NotificationSettings` shows the
batch 3 target ("This phone", the quiet-hours line and the new honesty line), which depends on
#147 and #148.

## What a push contains today

The server's dispatcher sends `{title, body, link, app_name}`:

- `title` and `body` are the same rendered, display-safe text the in-app notification list
  uses. **No raw message body is ever in a push.**
- `link` is a same-origin link that the native link parser (M4c4a) treats as a hint.
- `app_name` is the installation's **effective** name, the same value the session now
  returns as `application.name` (ChitChat#149). The public health endpoint still doesn't
  expose it.

Notification kinds are **not** in today's payload. Ordinary unmentioned DMs create no
notification today. Quiet hours and the mention/ping mutes are applied by the server before
sending.

### Backend batch 3 (Dependency: ChitChat#148, #147, #150)

The batch 3 design is approved on both sides (ChitChat#163, design comment 6729256820, final
client-facing decisions 6729292941; CCM issue #1, comment 6729288173). **Nothing has merged
yet.** Each part below names the issue it depends on. Leave it out of the build until that
issue merges on ChitChat main, and keep today's behavior meanwhile. Merge order: #148, then
#147, then #150.

- **Delivery lifetime (#148):** mentions, pings and DM alerts live **30 minutes** from
  notification creation, with urgency `high`. Every other kind lives **6 hours**, with urgency
  `normal`. `TTL` is the remaining time, with at most five retries inside it. A push that
  can't arrive in time expires on the server, so CCM never receives a stale doorbell and
  needs no "late" wording.
- **At-least-once delivery (#148):** a retry after an ambiguous provider acceptance can deliver
  the same event twice. The client deduplicates (see "Grouping and replacement").
- **Read, forbidden, preference-off and quiet-hours events are dropped, never replayed
  later (#148).** There's no morning catch-up: the Activity tab still has everything.
- **Payload additions (#147),** alongside `title`, `body`, `link` and `app_name`, which keep
  their meaning:
  - `kind`: the exact timeline kind (string);
  - `notification_id`: the selected timeline row (integer);
  - `event_id`: opaque ASCII, at most 64 characters, stable across retries and the mention or
    DM choice;
  - `binding`: opaque ASCII, at most 64 characters, compared exactly. It's present on every
    push from a bound subscription.
- **Subscription lifecycle (#147):** subscribe returns `{subscribed: true, id, binding}`.
  Revoking by `id` works for the owning session even after the endpoint rotated. Logout
  removes that login session's registrations on the server. The device list adds an
  optional `device_label` and a current-session flag.
- **Opt-in DM alerts (#150):** see "Direct message alerts".
- **Wake latency (#148):** with the optional listening worker, a push leaves the server within
  a few hundred milliseconds. With the timer only, it leaves within about 15 seconds. Which
  mode an installation runs is its operator's choice.

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
| `mentions` | Mentions and pings | High (heads-up, sound) | When push is enabled | `mentioned` and `pinged`. **Without a payload `kind` (before #147, or an older server), every push goes here** |
| `account` | Account and server notices | Default | When push is enabled on a server whose payloads carry `kind` | Every other pushed kind: security, account, moderation of your own content and server notices, plus **any kind CCM doesn't know** |
| `moderation` | Moderation | Default | Only once the server pushes a moderator-facing kind (M7). None exists today, so the channel isn't created | Moderator queue notices |
| `direct` | Direct messages | High | When the person turns on Direct messages, or on the first `direct_message_received` push if the preference was turned on from another device | `direct_message_received` (ChitChat#150) |

**Choosing the channel (Dependency: ChitChat#147).** The channel comes **only from the explicit
payload `kind`**, through a fixed table in code:

- `mentioned` and `pinged` go to `mentions`;
- `direct_message_received` goes to `direct`;
- every other kind, known or unknown, goes to `account`. An unknown kind never gets a
  high-importance channel;
- no `kind` (older server) goes to `mentions`, as today.

CCM never guesses a kind from the title, body or link text. A timeline-only kind
(`privacy_policy_updated`) is never pushed, so it needs no mapping.

## The notification

Built with `NotificationCompat`:

- Small icon: the monochrome Lantern mark. Accent color: persimmon `#F06A3B`.
- Content title: payload `title`. Content text: payload `body`, using `BigTextStyle`
  so longer text expands.
- Sub-text: payload `app_name` (for example "Schmidt Family Chat"), so the person knows
  which community it came from. This becomes essential once more than one server or account
  is supported.
- Time: the moment of receipt (`setWhen`, `setShowWhen(true)`). The payload has no server
  time. Dispatch takes up to about 15 seconds (less with the listening worker), and retries
  stay inside the 30-minute or 6-hour lifetime, so don't present it as the exact message time.
  The Activity tab shows the real time. CCM adds no "late" wording, because the server
  expires anything older than its lifetime.
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

## Direct message alerts

**Dependency: ChitChat#150** (accepted, backend batch 3 with #147/#148). Leave all of this out
until it merges on ChitChat main. Server semantics, which presentation never changes:

- kind `direct_message_received`; account preference `direct_message_push_enabled`,
  **default false**;
- generic text only, never message content, captions or file names;
- no alerts for messages that existed before opting in;
- the timeline entry is marked read when the conversation is read;
- a DM that also mentions the recipient produces **one** alert per event (see "Mention plus
  DM");
- edits, reactions and messages from before opting in produce none;
- the alert names the current sender in the server's rendered text, or "Someone" for a closed
  or deleted account, and never carries content;
- eligibility (membership, blocks, closure, guests, revoked devices) is rechecked at dispatch.

Presentation:

- **The alert** is built exactly like every other push (see "The notification"): payload
  title and text, `app_name` as sub-text, no large icon or avatar, no MessagingStyle, no
  Reply or Mark read, and tap goes through the guarded destination flow (nothing marked read).
  A DM alert is still a doorbell. CCM never shows or fetches the message for it.
- **Channel:** `direct` when the payload carries `kind = direct_message_received`. Without a
  `kind` (an older server, or #147 not yet shipped), it posts in `mentions` like every other
  push. CCM never guesses from the text that a push is a DM.
- **Lock screen:** the public version reads "{installation name}" / "New direct message" when
  the kind is known, otherwise "New notification".
- **Mention plus DM (final #150 semantics):** both timeline rows are kept, and the server
  pushes **one** alert for the event, choosing at dispatch:
  - **mention preferred:** with mention alerts on, the alert is `mentioned`, shown in
    `mentions` with the "New mention" public text;
  - with mention alerts off and Direct messages on, it's `direct_message_received`, shown in
    `direct` with the "New direct message" public text;
  - with both off, there's no alert, and both rows stay in Activity.

  The choice is frozen once any device accepted it, and both kinds share the same
  `event_id`. CCM shows whatever arrives in the channel of its `kind`. It never posts a second
  notification for the same event, and it never merges or compares text on the client.
- **Activity:** a DM that mentions the person shows **two** entries (the mention and the DM
  alert), because the server keeps both rows. That's expected, and each opens the same DM.
- **Clearing:** opening that conversation may clear its own posted alert once the tag or ID
  identifies it (the existing rule). Reading it in the app marks the Activity entry read on the
  server; CCM never marks anything read from a push.
- **Activity list:** `direct_message_received` entries show with the person's pebble and the
  server's rendered text. Tapping one opens the DM through the same guarded flow.

## Grouping and replacement

- All notifications for one account on one server share a group key, scoped by server and
  account. When two or more are showing, a summary notification reads "{n} new in
  {app_name}", without listing names.
- **One notification per event (Dependency: ChitChat#147).** The notification **tag is the
  payload `event_id`** (scoped by server and account), so a duplicate delivery **replaces**
  the earlier copy and never stacks. A small, bounded set of recently seen `event_id`s per
  account (sized to the 6-hour lifetime) also stops a duplicate from reappearing after the
  person dismissed the first copy. That set holds only the opaque IDs, is kept **in memory
  only** (never on disk; technical lead decision, 2026-11-09), and is cleared with the
  account's other local push state on sign-out or account removal. After process death the
  set is empty, so a late duplicate of a dismissed alert can appear once. That's accepted:
  it's rare, and it avoids storing new data.
- Without `event_id` (before #147, or an older server), each push is a separate notification.
  Never deduplicate by comparing text.
- Opening CCM does **not** automatically clear notifications. Viewing a destination may clear
  only that destination's own notification, once `notification_id` or the tag makes it
  identifiable.

## Lock screen

- Channel visibility is `VISIBILITY_PRIVATE`, with a public version whose title is the
  installation name and whose text is "New notification", or by `kind`, once it exists: "New
  mention", "New ping", "New direct message", and "New notification" for everything else.
  Rendered notices can contain usernames and moderation reasons (M4c4a), so the full text
  shows only when unlocked or when the person's system settings allow it.
- The setting text says so plainly: "On the lock screen, ChitChat shows only that something
  arrived, unless your phone is set to show all notification content."

## Account and session safety

- Signing out, removing the account, switching account or a session-generation change
  **cancels that account's visible notifications locally, at once**, with no network
  needed. Later pushes for it are dropped silently (M6 contract).
- **Sign-out unregisters this device itself, immediately.** While the session is still valid,
  it revokes this phone's subscription (by its `id` once #147 provides it, else
  `POST push/unsubscribe.php` with its endpoint), then unregisters the distributor connection
  and discards the keys and binding. **With #147, the server also removes this login session's
  registrations at logout.** CCM doesn't rely on that: an offline sign-out can't confirm
  anything, and the local drop rule keeps the account's pushes hidden either way. Sign-out
  copy never claims the device was removed from the server.
- **Binding check (Dependency: ChitChat#147).** CCM keeps the `binding` returned at subscribe
  with the account and session generation it belongs to. A push is shown only when its
  `binding` matches the binding of a currently signed-in account and generation exactly. A
  mismatched or unknown binding, or a missing one once this device holds a binding, is
  **dropped silently**: nothing is posted, nothing is logged with content, and no error is
  shown. A stale account's push just doesn't appear.
- A push for an account that isn't signed in is never shown.

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
- **What you get:** toggles for **mentions** and **pings** (`mentioned_push_enabled`,
  `pinged_push_enabled`) and, once ChitChat#150 merges, **Direct messages**
  (`direct_message_push_enabled`). Under them, a fixed line: "Account, security and moderation
  notices always arrive while notifications are on." Those kinds are not optional on the server.
  - **Direct messages** toggle: **off by default**, label "Direct messages", hint "When someone
    writes to you directly. The alert never shows what they wrote." When switched on, one
    line below it: "You'll only get alerts for new messages from now on."
  - The toggle is shown **only when the preferences response actually contains**
    `direct_message_push_enabled` as a Boolean. Missing or null means the server doesn't
    offer it: hide the row, never show it as Off.
  - It's an account preference, so the copy doesn't promise per-phone behavior. It changes
    alerts on all this account's devices, like quiet hours.
  - Writes follow the existing preference rules: no optimistic flip that sticks after a
    failure, and an uncertain write needs an explicit GET before a new choice (never a
    replayed POST).
- **Quiet hours:**
  - **whole hours** (0–23) for start and end, plus a time zone. The time zone defaults to the
    device's IANA zone and can be changed;
  - overnight windows are allowed ("22:00 – 07:00");
  - **Off** clears the window (all three fields `null`);
  - the three values are always sent together (server rule);
  - copy: "Quiet hours are applied by your server, on all your devices."
  - **with #148 (Dependency)**, a second line: "Alerts during quiet hours aren't sent later. You'll find them in
    Activity." The server drops them for good (no morning replay), so the copy doesn't
    promise a catch-up.
- **Devices** (from `GET push/preferences.php`): one row per subscription, newest first.
  - Label: the stored `user_agent` string, shortened for display. A simple browser/OS summary
    such as "Firefox on Linux" may be used only when the string clearly contains both;
    otherwise show the raw string truncated.
  - Secondary line: "Added {date} · last used {date}".
  - **Remove** (decision dialog) calls `POST push/revoke-device.php` with the row's ID.
  - **Before #147, the API can't identify which row is this phone** (no endpoint in the
    list), so no row is labelled "This phone". Removing this phone's own subscription is done
    with **Turn off**, which unsubscribes by endpoint.
  - **With #147:** the row whose current-session flag is true is labelled "**This phone**" and
    sorted first. Its Remove action becomes **Turn off**. The label comes from `device_label`
    when present, else the shortened user agent. CCM sends the fixed `device_label` "ChitChat
    for Android", never the device model or name, so no new device detail leaves the phone.
  - Engineering note: sending a recognizable User-Agent from the push registration request
    (for example including "ChitChat Android") makes the rows readable. That is an
    engineering choice, not a presentation requirement.
  - With #147's cap of 20 devices, a subscribe refused with `409 push_subscription_conflict` is
    a definite refusal: "This delivery address is already used by another account. Choose a
    different delivery app or remove it there first." It's never retried automatically.
- **Honesty line:** today, "Notifications can arrive a minute or so after the message. Open
  the app to see everything." **With #148 (Dependency):** "Notifications usually arrive within
  seconds, but can come later if your phone was offline, and some never arrive. Open the app
  to see everything." Mentions, pings and DM alerts give up after 30 minutes and other
  notices after 6 hours, so "later" stays bounded without the copy promising a number.

## Tests (for the M6 slice)

- **Unit:**
  - payload to notification mapping: title, text, sub-text, channel `mentions` without a kind;
  - with #150: `direct_message_received` maps to `direct` with the "New direct message" public
    version, and without a kind it maps to `mentions`;
  - the Direct messages toggle is hidden when the preference field is missing or null;
  - the public lock-screen version;
  - group key per account and server;
  - tag from `event_id` only when present; a duplicate `event_id` replaces and a dismissed one
    doesn't reappear; the recent-event set is bounded and cleared on sign-out;
  - channel from the explicit `kind` table (`mentioned`/`pinged` to `mentions`,
    `direct_message_received` to `direct`, any other or unknown kind to `account`, no kind to
    `mentions`), never from text;
  - a push with a mismatched, unknown or missing binding (when this device holds one) is
    dropped without posting;
  - a stale-generation payload is dropped;
  - a mention-plus-DM event posts exactly one notification, in the channel of the kind that
    arrived.
- **Instrumented:**
  - channels appear only after Turn on;
  - tap routes through the guarded destination flow and falls back to Activity;
  - sign-out and account removal cancel that account's posted notifications, also offline;
  - pushes post silently while foregrounded;
  - no notification permission prompt outside the Turn on flow.
- **Manual:** two distributors and one device without Play services (existing release gate in
  push-architecture.md); lock-screen appearance with the system set to hide sensitive content.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-120 | Channels are created only on Turn on (or `direct` on the first DM alert) with stable IDs. The channel comes only from the explicit payload `kind` through the fixed table: unknown kinds go to `account`, and without a kind every push uses `mentions`. The kind is never guessed from text. | U, C |
| P-121 | The notification shows the payload title and text, `app_name` as sub-text, the mark and persimmon accent, with no large icon, no action buttons and no MessagingStyle. | U, S |
| P-122 | Tap routes through native link parsing and the guarded destination flow (no read, no browser), falling back to Activity. | C |
| P-123 | Lock-screen visibility is private with a generic public version. | U, M |
| P-124 | Sign-out, account removal, account switch and generation change cancel that account's visible notifications locally at once and drop later pushes. A push whose `binding` doesn't match a signed-in account and generation is dropped silently (with #147). | U, C |
| P-125 | The notification permission is requested only inside Turn on. The app requests no battery exemption for itself, and no local re-engagement notifications exist. | C, R |
| P-127 | Direct message alerts (after ChitChat#150 merges): an opt-in toggle, off by default and shown only when the server returns the Boolean preference, with copy that says the alert never shows what was written. Alerts use `direct` only with an explicit kind, never MessagingStyle or content. A mention-plus-DM event is one notification: `mentioned` in `mentions` when mention alerts are on, else `direct_message_received` in `direct`. | U, C |
| P-128 | With #147, one notification per event: the tag is `event_id`, so a duplicate replaces and never stacks, and a bounded, memory-only recent-event set keeps a dismissed duplicate from reappearing within the process lifetime. | U |
| P-129 | With #148 (Dependency): no per-notification "late" badge or label. The settings honesty line says alerts can come later or not arrive, and the quiet-hours line says they aren't sent after quiet hours. Until #148 merges, today's honesty line stays and the quiet-hours line is absent. | C, R |
| P-126 | Settings offer only the mention and ping toggles (plus Direct messages per P-127) and whole-hour quiet hours with a time zone. Before #147, no device row is labelled as this phone; with #147, only the current-session row is, and its action is Turn off. Remove revokes by ID. Sign-out unsubscribes this device before the session ends. | C, R |
