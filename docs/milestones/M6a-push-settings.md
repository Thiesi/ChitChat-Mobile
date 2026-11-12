# M6a: notification settings (push P1)

The server-side half of push, on screen before this phone can receive anything. Account →
Notifications shows the account's push choices and devices through the existing released
Web Push API (ChitChat ≥3.5.0, unchanged in 3.7.0/3.8.0). Turning on this phone (distributor,
permission, channels, subscription) is P2; receiving and rendering is P3.

## On screen

- **This phone:** "Off", with a line saying turning it on comes in a later update.
- **What you get:** Mentions and Pings switches, and Direct messages only when the server returns
  `direct_message_push_enabled` as a Boolean (missing or null hides it, never "off"). The DM hint
  says the alert shows who it's from, never what was written, and "from now on" appears once it's
  on. A fixed line says account, security and moderation notices always arrive.
- **Quiet hours:** whole hours (From/Until) and an IANA time zone, defaulting to the device's zone
  when turned on (22:00–07:00). Off clears the window. The server-applied and "not sent later"
  lines and the honesty line follow push-presentation.md.
- **Devices:** newest first with "This phone" (`current_session`) on top, the stored
  `device_label` or a "Browser on OS" summary only when the user agent clearly names both (else
  the raw string, truncated), and "Added … · last used …". Remove (Turn off for this phone) asks
  first and revokes by `id`.

## Write rules

Every update sends the complete choice set. The server treats omitted quiet-hours fields as
null and **clears the window**, so the client always sends the current triplet explicitly. An
unoffered DM choice is never written. There's no optimistic flip: a definite refusal keeps the
previous choice with its copy. A timeout, network loss, 5xx, malformed reply or cancellation
leaves the change **unconfirmed**: further changes are blocked until an explicit Refresh (GET),
and the POST is never replayed. Facts are memory-only, scoped to server, account, generation
and session version, and cleared on genuine background or another account.

## Verification

JVM: `PushProtocolTest` (one GET without CSRF, missing/null versus Boolean DM, strict
rejection of malformed facts, the full-triplet body, revoke by id, device labels) and
`NotificationSettingsViewModelTest` (load once, no optimistic flip, definite versus
unconfirmed writes, GET-only reconciliation, revoke, identity reset). No device, distributor or
live-server evidence.
