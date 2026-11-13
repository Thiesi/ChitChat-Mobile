# M6d: receive and post pushes (push P3, part A)

A decrypted push becomes a notification, following
[push presentation](../design/push-presentation.md). Tapping a notification opens the app.
Routing the tap to its destination is part B.

## When a push is shown

A push is shown only when this phone's sealed `PushRecord` exists, its instance is the one the
message arrived for, and the payload's `binding` equals the record's binding exactly. The record is
destroyed under the session lock whenever its login session ends here, and only its keys can open
the payload, so it's the local proof even when a distributor wakes the app from scratch.

Receiving a push never publishes a user or session, never starts a bootstrap, reads nothing but the
record and makes no request; the text comes only from the payload.

Everything else is dropped silently, with nothing logged:
- not decrypted (the connector's plain-text fallback);
- a missing, unknown or unrecorded binding;
- another instance;
- a malformed payload;
- no readable record;
- a repeat of an event already seen.

### When the server ends the session

The released backend (v3.9.0, `8b790ca`) stops sending when a session ends on the server without this
phone knowing:
- **Delivery needs a live session.**
  [`PushDeliveryQueue.php` L107–113](https://github.com/Thiesi/ChitChat/blob/8b790caca5686038adc3b12f3e4e09a02ffd03aa/src/WebPush/PushDeliveryQueue.php#L107-L113)
  sends to a subscription only if its `session_version` is the user's current one and a
  `login_sessions` row with the same binding hash exists with `revoked_at IS NULL`.
- **Ending a session deletes its subscriptions.**
  [`LoginSessionRepository::retire`, L205–212](https://github.com/Thiesi/ChitChat/blob/8b790caca5686038adc3b12f3e4e09a02ffd03aa/src/Auth/LoginSessionRepository.php#L205-L212)
  sets `revoked_at` and deletes that binding's subscriptions. It runs for logout, revoking from another
  device, the sign-in session cap and a replaced binding.
- **A `session_version` change stops delivery at once.** Password and credential changes, MFA,
  account closure, lockdown and guest changes all bump it, through the eligibility query above. The rows
  are pruned at the next subscribe
  ([`PushSubscriptionService.php` L222](https://github.com/Thiesi/ChitChat/blob/8b790caca5686038adc3b12f3e4e09a02ffd03aa/src/WebPush/PushSubscriptionService.php#L222)).
- **Login sessions have no time-based expiry.** The only prune removes rows revoked more than seven days
  earlier, so expiry isn't a way a session ends.

## Parsing

`PushPayload.parse` is strict:
- the plaintext is at most 4,096 bytes of valid UTF-8 JSON;
- `title` and `body` are required strings;
- `link`, `app_name`, `kind`, `event_id` and `binding` are strings when present;
- `kind` is lowercase letters and `_`;
- `event_id` is 1–64 printable ASCII;
- `binding` is 64 lowercase hex;
- `notification_id` is a positive number, never a string.

Anything else drops the push; nothing is repaired. The server's own "…" trimming is shown as received.

## The notification

- **Channel**: only from the explicit `kind`. `mentioned` and `pinged` go to `mentions`,
  `direct_message_received` goes to `direct` (created on the first DM alert), and every other kind,
  known, unknown or missing, goes to `account`.
- **Content**: the payload title and text (`BigTextStyle`), `app_name` as sub-text, the receipt time,
  the Lantern mark and persimmon accent. There's no large icon, no actions and no MessagingStyle.
- **Lock screen**: every notification, the summary included, is `VISIBILITY_PRIVATE` with a public
  version. The public version shows the installation name as title and "New mention", "New ping",
  "New direct message" or "New notification" as text, never a sender, room or message text. Android
  ignores an app's lock-screen visibility on a channel, so this is set on each notification.
- **One per event**: the tag is the `event_id`, scoped by server and account. A bounded, memory-only
  set of recent event IDs (512) drops repeats, so a duplicate never stacks or comes back after
  dismissal. Sign-out, Turn off and a session change clear it with the record. After process death it
  starts empty, which is accepted.
- **Grouping**: one group per server and account. With two or more showing, a summary reads
  "{n} new in {app_name}", without names.
- **Foreground**: while the app is in the foreground, notifications post silently.
- **Permission**: nothing is posted while notifications are blocked or, on Android 13 and later, the
  permission is missing.

Sign-out and the other session-ending events already cancel this app's notifications (M6b).

## Verification

JVM tests:
- `PushAlertTest`: exact text, malformed payloads, the kind table (text never decides), the
  instance/binding rule and the recent-event bound.
- `PushRegistrarTest`:
  - a cold-process push posts from the record alone, with the session state unchanged and no request;
  - only decrypted, bound first deliveries post;
  - sign-out stops posting and clears the recent events.

Device test: `PushPosterDeviceTest` posts through the real NotificationManager and reads back:
- `VISIBILITY_PRIVATE` and the public version's title and text for all four kinds, with no sender,
  room or body in it;
- the `direct` channel created on a DM alert;
- the private counting summary;
- normal versus silent foreground alerting.

Real distributor delivery, Doze and lock-screen appearance are **pending device acceptance**.
