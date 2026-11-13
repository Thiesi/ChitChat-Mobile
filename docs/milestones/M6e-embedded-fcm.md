# M6e: Google delivery through the embedded FCM distributor (push P4)

Phones with Google Play services but no UnifiedPush delivery app can still get push alerts. ChitChat
uses UnifiedPush's embedded FCM distributor, `org.unifiedpush.android:embedded-fcm-distributor:3.1.0`.
It is an unmodified, separate AAR, with no Firebase SDK, no `com.google.*` artifact, no
`google-services.json`, no Firebase project and no sender credentials.

## When it's offered

The approved privacy policy allows Google only "if no UnifiedPush delivery app is installed":
- **A delivery app is installed:** Turn on lists only the installed delivery apps. Google is never
  listed next to them. `UnifiedPushDistributors.installed()` filters out this app's own package.
- **No delivery app, Play services present:** the "No delivery app found" dialog offers **Use Google**,
  with a link to choosing a delivery app. Nothing happens until the person taps Use Google.
- **Neither:** today's no-delivery-app dialog and "Needs setup: no delivery app found", unchanged.
- **Already on Google when a delivery app is installed:** nothing switches by itself. The card keeps
  "On, delivered by Google" and adds "A delivery app is installed now. Turn on again to use it instead
  of Google." with **Turn on again**. That takes the existing switch path: the Google registration is
  turned off first, then fresh keys are registered with the delivery app. The card checks on screen
  start and resume, with no polling and no package receiver.

UnifiedPush reports this app as the distributor, so the label is overridden to "Google". Any failure is
a registration failure and reads Needs setup, never On (M6b).

## How it works

Connector 3.3.5 lists this app's own package as a distributor only when the embedded library is present
and `com.google.android.gms` is installed. That was checked in the connector bytecode
(`getDistributors`, `hasEmbeddedFcmDistributor`, `isPlayServicesAvailable`).

Facts from the library's source at tag 3.1.0:
- **Registration:** `MessengerIpcClient.kt` binds `com.google.android.c2dm.intent.REGISTER` in
  `com.google.android.gms`. `C2mRequests.kt` registers with `scope=GCM`, `sender=subscription=` the
  installation's VAPID public key and `subtype=wp:<channel>`. There's no hardcoded sender ID.
- **Endpoint:** the server receives the direct `https://fcm.googleapis.com/fcm/send/<token>`
  (`Constants.kt`, `Utils.kt`). The unifiedpush.org rewrite gateway (`DefaultGateway.kt`) is used only
  by apps that subclass the receiver and opt in. Its own receiver has `gateway = null`, and ChitChat
  never opts in.
- **Backend fit:** the released backend (v3.9.0) has no endpoint-host allowlist and blocks only private
  addresses (`GuardedPushHttpClient`, `PushDestinationGuard`), so the public FCM endpoint is accepted.
  The server signs VAPID with the same key the token was registered for.
- **Registration IPC risk:** this is Play services' internal registration path, not a public contract,
  and Google gives no longevity guarantee. If it stops working, Turn on reads Needs setup and delivery
  apps still work.

Merged-manifest additions, all from the library:
- an exported `FirebaseReceiver` guarded by `com.google.android.c2dm.permission.SEND`, for the RECEIVE
  and REGISTRATION actions;
- a non-exported `UnifiedPushReceiver`;
- a `<queries>` entry for `com.google.android.gms`.

It adds no `uses-permission`.

## Privacy

Payloads stay RFC 8291 `aes128gcm` Web Push, encrypted by the ChitChat server to this phone's keys, so
Google can't read them. Google sees:
- that this app on this phone has an FCM registration;
- the authorized entity, which is the server's VAPID key;
- the sending server's address;
- each delivery's timing and size.

This doesn't make chat end-to-end encrypted. The privacy policy names Google as the carrier in this case.

## Licence

The 3.1.0 POM says Apache-2.0, but the LICENSE at tag 3.1.0 is LGPL-2.1. ChitChat complies with
LGPL-2.1 (owner decision, 2026-11-13); see [third-party licences](../third-party-licenses.md).

## Verification

- **JVM `PushPhoneViewModelTest`:**
  - Google is offered only with no delivery app, and only on Use Google;
  - with delivery apps it's never offered or listed;
  - with neither, Needs setup;
  - on Google, a later delivery app shows the hint and nothing switches;
  - Turn on again turns the Google registration off before starting with the delivery app.
- **Compose `NotificationSettingsScreenTest`:** the Use Google dialog and its guide link; Use Google
  starts once; the hint with Turn on again, and the switch through Turn off. Captures `google` and
  `google-instead` go to the presentation lead.
- **Device `PushDistributorsDeviceTest`:** on the real connector, this app is never a delivery app, and
  Google is listed exactly when Play services is installed.

CI's Google APIs emulator has a Play services package but no Play Store and no signed-in Google account,
so it proves the listing only.

**Google registration and delivery are UNVERIFIED** until the owner's device acceptance ("Play-signed FCM
acceptance"). Agents don't sign into Google accounts or send live pushes. Until then, a failure shows
Needs setup, never On.

## Owner acceptance checklist

Use a phone with Play services and a signed-in Google account, no UnifiedPush delivery app, and a real
ChitChat server at the minimum version:
1. **Turn on:** Account → Notifications → Turn on, then Continue. The "No delivery app found" dialog
   offers Use Google, and nothing starts before you tap it. After Use Google, the card reads "On,
   delivered by Google".
2. **Listing:** the phone appears under Devices.
3. **Receive:** from a second account, mention this account. One alert arrives with the server's text.
4. **Lock screen:** it shows only the installation name and "New mention".
5. **Tap:** the alert opens the mentioned message in the app, with nothing marked read.
6. **Turn off:** the card returns to Off, and the phone leaves Devices.
7. **Switch-over:** turn on with Google again, then install a UnifiedPush delivery app and return to
   Notifications.
   - The card shows "A delivery app is installed now. Turn on again to use it instead of Google." with
     Turn on again.
   - Turn on again switches to the delivery app, and the old Google registration is removed first.

**If registration fails** (Needs setup right after Use Google), first try adding
`<uses-permission android:name="com.google.android.c2dm.permission.RECEIVE" />` to the app manifest.
The library doesn't declare it, but the Firebase SDK normally does. Record the Play services version,
the Android version and the outcome.
