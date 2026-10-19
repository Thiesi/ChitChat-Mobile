# Add an explicit opt-in privacy-safe notification category for incoming direct messages

Status: **product proposal awaiting maintainer decision**, not existing ChitChat behavior. Android [M6](https://github.com/Thiesi/ChitChat-Mobile/blob/docs/android-discovery/docs/implementation-plan.md) background DM alerts depend on approval and backend implementation; ordinary native DM/SSE remains supported without it.

Tracks [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1).

## User-facing requirement and current behavior

A person may expect a backgrounded native messenger to alert them about an ordinary incoming DM. ChitChat currently has targeted `direct_message` SSE events and conversation unread counts, but a plain DM does not create an `account_notifications` row. A mention within a DM can create `mentioned`. [DM transaction](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/DirectMessage/DirectMessageService.php), [existing notification vocabulary](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/docs/api/privacy-notifications.md). FCM transport alone cannot deliver an event the server does not produce.

## Proposed product/API contract

If accepted, introduce a durable `direct_message_received` kind and a per-account explicit boolean such as `direct_message_push_enabled`, default **false until opted in**. Add it to the existing account preference read/update API without requiring old clients to send the new field. Define whether it is in the visible notification timeline (recommended, so push has a durable source of truth), its safe presentation, and its read-state relationship to existing conversation reads. Do not retroactively notify for old messages on enablement.

Generate eligibility transactionally with an incoming text/file insert for the recipient only, not sender, edits, reactions or historical backfill. Keep message bodies/captions/filenames out of notification context/push. Specify deduplication when the same message also mentions its recipient: one device alert while preserving any necessary durable semantic records. Apply account quiet hours and device/channel permission consistently to Web Push and FCM rather than inventing a native-only notification meaning. Keep existing `mentioned`/`pinged` settings and all older category defaults unchanged.

## Security, authorization, compatibility

Respect two-party membership, either-direction blocking, mute/send restrictions, account closure/bans and revoked devices. Recheck eligibility at dispatch; a queued alert must not expose content or a private conversation to a now-unrelated account/session. Guests cannot send/receive/register these notifications. Default lock-screen content is generic; no E2EE claim. Existing clients must tolerate an added notification kind without crashing or showing raw internal fields.

If the maintainer elects to preserve existing notification semantics instead, close/defer this proposal and document that ordinary DMs appear on foreground synchronization but cannot produce background alerts. Android must not simulate this with perpetual background SSE/polling.

## Acceptance criteria

- Maintainer explicitly accepts or declines this product addition and category default/timeline/overlap behavior.
- Opted-in eligible recipients receive one privacy-safe background alert for a new text or attachment DM; unmentioned ordinary DMs are covered.
- Sender, opted-out/blocked/closed/guest accounts, edits/replays/backfill, revoked devices and duplicate mention overlap do not get inappropriate alerts.
- Tests cover transactional durability, preferences/quiet hours, read-state behavior, transport coexistence and legacy clients.
- Native background device evidence uses a compatible server, not a mocked category or direct Firebase Console message.
