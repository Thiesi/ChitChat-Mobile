# M6b: push registration core (push P2, part A)

The parts of turning push on for this phone that have no screen of their own: Web Push
discovery, the subscribe protocol, sealed keys, the connector receiver, registration and the
sign-out cleanup. The Turn on flow in Account → Notifications (explanation, notification
permission, distributor choice, channels, status card) is part B. Until it lands, nothing calls
[`PushRegistrar.start`](../../app/src/main/java/io/github/thiesi/chitchat/core/push/PushRegistrar.kt),
so this part changes nothing a person can see. Receiving and rendering is P3.

## Discovery and subscribe

- Bootstrap `web_push` goes into `SessionState.webPush`, like the attachment policy: memory only,
  reset on revalidation. Missing is Unknown. An explicit off, a missing key, a quoted Boolean or
  a malformed key is Unavailable, which disables only turning push on. Anonymous and guest
  sessions are Unavailable. The VAPID key must be what the connector accepts: 87-character
  unpadded base64url of a 65-byte `0x04` P-256 point.
- `subscribe.php` gets `{endpoint, p256dh, auth, replaces_id?, device_label}`. The label is the
  fixed "ChitChat for Android", never a device model. Before anything is sent, the endpoint must
  be ASCII HTTPS of at most 2048 bytes with a host and no credentials or fragment. The keys must
  be a 65-byte point and a 16-byte secret, and both must equal this record's own.
- The reply needs a Boolean `subscribed: true`, an `id > 0` and an optional binding of 1–64
  printable ASCII characters. Named refusals map to CONFLICT, LIMIT, PROVIDER_UNAVAILABLE,
  DESTINATION (400 validation) and RATE_LIMITED (any 429). Network errors, timeouts, other 5xx
  and malformed replies leave the outcome **unconfirmed**. Neither case is retried.

## Keys and storage

One `PushRecord` per phone holds:
- the server origin and account that turned push on;
- a random instance UUID (the connector's instance name);
- the P-256 key pair and auth secret;
- once known, the server's registration id and binding.

It is sealed by `KeystorePushVault`, which follows `KeystoreSessionVault`: AES-GCM with its own
non-exportable Keystore key, the `noBackupFilesDir` file `push.v1.enc` and fresh nonces. Clearing
deletes the key first, so the file can't be opened even if deleting it fails.

The record is scoped to origin and account, not to the local generation counter. That counter
lives in memory and changes on every launch and restore. Instead, every event that ends a session
generation destroys the record:
- sign-out;
- a session the server ended;
- a different account signing in.

`VaultKeyManager` replaces the connector's `DefaultKeyManager`, which would keep keys in the
connector's SQLite `keys` table. It knows only the record's own instance, never mints keys for an
unknown one, and decrypts with the connector's bundled `fixed_webpush` RFC 8291 implementation.

## Receiver

The manifest removes the connector's `MessagingReceiverImpl` and registers `ChitChatPushReceiver`
for the same five actions. It is exported, because distributors must reach it, and the base class
authenticates each broadcast by its connector token before any callback runs. The receiver runs on
its own single thread through `goAsync`. The connector's `PushService` path isn't used, so there's
no service to declare.

| Callback | What happens |
| --- | --- |
| New endpoint | Subscribe only if the record, instance, account and authenticated session all match; send `replaces_id` when an earlier registration exists; save the answer only into the record that asked |
| Message | Ignored unless the connector decrypted it with our keys. The connector falls back to plain text when decryption fails, so plain text is never trusted. Rendering is P3 |
| Unregistered | Forget the record locally, then best-effort revoke for the same signed-in account |
| Registration failed | Forget the record and report network, action-required or other |

## Sign-out and ended sessions

`SessionRepository` calls `PushTeardown.retire` inside its lock, before clearing the saved session:
- cancel this app's notifications;
- wipe the record and its keys;
- unregister with the distributor, fire-and-forget (unknown instance: remove every registration and
  the saved distributor).

On sign-out, the revoke request (`revoke-device.php {"id":N}`) is built from the still-valid cookies
and CSRF before they're retired. It runs **concurrently** with the existing bounded logout, so
sign-out waits no longer than before. Logout also removes this session's registrations on the
server; the explicit revoke covers a failed logout.

A session the server ended gets local cleanup only, with no request. A failing cleanup never blocks
sign-out. Copy never claims the server removed the device.

## Privacy

Endpoints, keys, bindings and decrypted payloads are never logged, put in bundles or given a
string form. The connector's own SQLite store (`unifiedpush-connector`) keeps its registration
token, instance name, VAPID key and the distributor choice until unregistration. It doesn't keep
the endpoint and, with our key manager, no keys. RFC 8291 notification encryption doesn't make
chat messages end-to-end encrypted.

## Dependencies and manifest

- `org.unifiedpush.android:connector:3.3.5`, with its bundled `fixed_webpush` classes;
- `com.google.crypto.tink:tink:1.23.0`: the connector's own Tink version, pinned explicitly because
  the app calls the bundled decryption directly. It brings `protobuf-java` 4.33.6, `gson` and
  `jsr305`. DataStore's protobuf is the shaded `datastore-preferences-external-protobuf`, so there
  are no duplicate classes;
- no Firebase SDK, `google-services.json` or sender credentials.

The connector adds these merged-manifest entries:
- `WAKE_LOCK`, held briefly while it handles a distributor message;
- `RaiseToForegroundService`: exported but bind-only, so a distributor can raise the app's
  priority while it delivers; it never calls `startForeground`, so it needs no foreground-service
  type;
- `LinkActivity`: translucent, used for linking to a distributor;
- `<queries>` for the distributor `LINK`, `REGISTER` and `UNREGISTER` actions.

The connector's own receiver is removed, as described above. The PR records the dependency
report and the release APK size change.

## Verification

JVM tests:
- `PushProtocolTest`: discovery, keys, subscribe body and reply, refusals;
- `PushLocalTest`:
  - a real Web Push encrypt/decrypt round trip through `VaultKeyManager`;
  - only its own instance;
  - teardown wipes keys, unregisters and cancels notifications;
  - a revoke id only for the matching account;
  - unreadable records drop every registration;
  - stale answers can't revive a record;
- `PushRegistrarTest`: subscribe and adopt, `replaces_id`, named refusal, stale-instance and
  foreign-key fencing, cross-account wipe, start gated on discovery;
- `SessionRepositoryTest`:
  - the revoke carries the retiring cookies and CSRF;
  - cleanup runs before the saved session clears;
  - offline sign-out completes;
  - a failing cleanup doesn't block sign-out;
  - an ended session cleans up with no request.

Device test: `PushVaultDeviceTest` (real Keystore, the real connector with no distributor
installed):
- sealed storage;
- decryption;
- retirement removes the file and key alias;
- the connector's `keys` table stays empty.

There's no distributor, delivery or live-server evidence yet.
