# M6c: Turn on for this phone (push P2, part B)

The visible half of turning push on, in Account → Notifications, on top of the
[M6b registration core](M6b-push-registration.md). It follows
[push presentation](../design/push-presentation.md#settings-you--notifications). Receiving and
showing pushes is still P3, so a phone that reads "On" posts nothing yet.

## Status card

`PushPhoneCard` shows this phone's status from `PushRegistrar.status`. The copy never claims more
than that status:

| Status | Copy |
| --- | --- |
| Off | "Off", or "Needs setup: no delivery app found" after a Turn on found none |
| Waiting | "Turning on: waiting for your delivery app" |
| On | "On, delivered by {distributor}" (the distributor's app label, read once when the screen opens) |
| Needs setup | "Needs setup: notifications for this phone may have stopped." |
| Refused | The fixed refusal copy for conflict, the 20-device limit, provider unavailable, destination (naming the installation) and 429 |
| Unconfirmed | "Needs setup: not sure this phone is registered. Your server's answer didn't arrive." |
| Distributor failed | Network, action required (open the delivery app) or other |

"Blocked in Android settings", with a button to the app's notification settings, shows whenever this
phone isn't Off but Android blocks the app's notifications. It also shows after the person declined
the permission inside Turn on. The value is re-read on every resume. The lock-screen line from the
design spec is always shown under the card.

Turn on shows only for a signed-in member on a server that advertises a valid Web Push key. Otherwise
the card says the server doesn't offer notifications for this phone. "Turn on again" replaces Turn on
for every failure state. Nothing is retried automatically.

## Turn on

1. **Explanation**: the one-paragraph dialog from the spec.
2. **Permission**: on Android 13 and later, `POST_NOTIFICATIONS` is requested only from the
   explanation's Continue, and only when it isn't granted. It's declared in the manifest, but the system
   never prompts at install or start. Declining stops here; the card then shows Blocked.
3. **Distributor**: `UnifiedPush.getDistributors`. With one installed, it's used directly. With several,
   a radio list preselects the current one. With none, a dialog explains why and links to the
   UnifiedPush project's [distributor list](https://unifiedpush.org/users/distributors/), opened in the
   browser only on that tap. Once the optional embedded FCM distributor (P4) ships, only builds
   without it can reach that case.
4. **Channels**: `mentions` (high) and `account` (default) are created now, never at install, both with
   private lock-screen visibility. `direct` (high) is created only when the account's DM alert
   preference is on while this phone isn't Off. P3 also creates it on the first DM alert. `moderation`
   waits for M7.
5. **Registration**: the distributor is saved and `PushRegistrar.start` runs. If it refuses (session or
   discovery changed), the card says so.

Choosing a **different** delivery app than the current one turns the old registration off first, so
the new app starts with fresh keys and the old one can't keep a live registration.

## Turn off

- **The card's Turn off** (any state with a record): `PushRegistrar.turnOff` wipes the keys,
  unregisters from the distributor and cancels this app's notifications **locally first**. It then sends
  one revoke for the record's server registration, only for the same signed-in account and generation.
  If the server doesn't confirm, the card reads: "Turned off on this phone. Your server didn't confirm
  the removal, so this phone may still be listed under Devices. You can remove it there." The next turn-on uses
  fresh keys without `replaces_id`, so it never replaces that row. The signed-in session continues, so
  Turn on works again at once.
- **The "This phone" device row's Turn off** revokes through the existing settings model (its
  unconfirmed-write rules unchanged) and wipes locally, with no second revoke.

## Leaving the screen

Leaving Notifications or a genuine background closes an open walk-through step. An operation already
running finishes. This phone's status isn't private data and stays.

## Verification

JVM tests:
- `PushPhoneViewModelTest`:
  - explanation first, then one distributor turns on with channels created before registration;
  - several distributors ask for a choice with the current one preselected, and a row that wasn't
    offered is rejected;
  - no distributor explains and starts nothing;
  - Turn on needs a member session and the server's key;
  - a declined permission stops before any distributor work;
  - switching delivery apps turns the old registration off first, while turning on again with the
    same app keeps its keys;
  - start and turn-off failures are shown, never retried;
  - the direct channel appears only while this phone receives;
  - the row's turn-off wipes locally with no second revoke.
- `PushRegistrarTest`:
  - turn off wipes, unregisters and revokes once, and the session can turn on again with fresh keys;
  - turn off is local first, reports an unconfirmed server removal, and sends nothing when there was
    no server registration.

Instrumented tests:
- `NotificationSettingsScreenTest`:
  - Turn on explains first, then guides when no delivery app is installed (no channels, no start);
  - a refusal shows its copy and offers Turn on again and Turn off;
  - light and dark card captures (Off, Waiting, On, Needs setup, Refused, the explanation dialog),
    collected by `native_presentation.py` into `push-presentation`. Each scene also asserts its copy;
    captures are for the presentation lead's review, not a visual pass.
- `PushChannelsDeviceTest`: the real NotificationManager creates `mentions` and `account` with their
  importance and private lock-screen visibility, `direct` only when asked, and no `moderation`.

Tests grant `POST_NOTIFICATIONS` up front, so CI never shows the system prompt. The prompt itself,
two real distributors, the no-distributor link and the Blocked path are **pending device acceptance**.
There's no delivery or live-server evidence yet.
