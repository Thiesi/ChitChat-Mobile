# Firebase setup and manual coordination

Status: planning guide only. No Firebase project, app registration, SDK integration or device delivery has been configured. B3/B4 in [backend dependencies](backend-dependencies.md) must ship for ChitChat push. B6 is a separate proposed DM category. The architecture keeps chat on existing HTTP/SSE and authentication on PHP sessions.

## Choose project and sender ownership first

For an operator-owned build, the operator owns a Firebase project, registers the exact Android application ID, supplies the build's Firebase config and configures the matching backend sender. One build cannot simply send through arbitrary operators' unrelated projects. For a publisher build usable across installations, agree the relay/instance trust design before creating production sender credentials. A relay is new operated infrastructure with metadata/privacy implications, not an automatic property of Firebase. Never grant unrestricted publisher-project send credentials to arbitrary ChitChat servers.

The application ID recommendation is `io.github.thiesi.chitchat`, **unconfirmed**. Debug should use a separate suffix/project registration. No server URL, project ID, sender ID, app ID, API key, signing fingerprint or private key in this guide is a real configured value.

## Maintainer steps in Firebase Console

1. In [Firebase Console](https://console.firebase.google.com/), create/select a project you control for the agreed build/sender model. Analytics is unnecessary; leave it disabled unless separately chosen. Use a separate development project for tests and keep production access restricted.
2. Register an **Android app** with the confirmed application ID, exactly matching the Gradle application ID. Register the debug ID separately if used. Firebase display nickname is not the application ID.
3. Download that app's `google-services.json`. Once M1/M6 establishes the source sets, place it in `app/src/fcm/google-services.json` for the planned FCM variant; distinct environment/build-type files need corresponding exact app registrations. These paths are a proposed layout, not files already consumed by a build. The Google Services Gradle plugin must process the chosen variant's real file.
4. Keep local config out of version control as this repository's default policy; it contains project/client identifiers rather than sender private keys, but publishing an unreviewed environment binding is unnecessary. CI's credential-free variant must not require this file or fabricate one. Supply real FCM configuration only to approved test/release jobs; fail the FCM-enabled build clearly when it is absent. Add ignores and variant checks in M1 before placing credentials/config here.
5. Enable/verify the Firebase Cloud Messaging HTTP v1 API in the associated Google Cloud project and verify sender IAM for the selected server/relay. Use least-privilege service identity and short-lived OAuth credentials or workload identity where available. If an operator must use a service-account JSON key, install it only in protected backend/relay secret storage outside source control and rotate it normally. **Never** put this file in `app/`, APK assets, Gradle properties, issues or chat.
6. Supply the backend operator with project/sender compatibility and the agreed registration/delivery contract version. Wait for actual B3/B4 support; existing VAPID settings and Web Push `endpoint/p256dh/auth` are unrelated to FCM Android registration.
7. For passkeys, separately configure DAL on every RP domain and explicitly trusted Android signing origins per B1. Fingerprints must be the certificate that signs the installed app; Play app signing and upload certificates differ. Firebase registration does not establish WebAuthn trust or authenticate a ChitChat session. B2 browser-handoff return verification is also separate.

Do not enable Firebase Authentication, Firestore or Realtime Database for this application. FCM service credentials never belong to the Android client. Avoid copying Firebase sample token logging into production code.

## Client behavior to implement in M6

Acquire the current token and observe `onNewToken`; bind it through the approved authenticated CSRF-protected registration API after explicit user push consent. Persist pending rotation securely and retry finite registration work with account-generation checks. Refresh association on valid foreground authentication; show unconfigured/unsupported instead of registered when the server cannot accept tokens. Revoke on disable/logout/account switch where possible, clear local binding and notifications immediately even offline, and delete/rotate this installation's token to prevent reuse. Backend expiry/invalidation remains necessary.

Create participation and security/policy notification channels before posting; request `POST_NOTIFICATIONS` on Android 13+ at the contextual enable-push action. Permission denial or disabled channels do not prevent foreground chat. Default public lock-screen content is generic. Validate opaque registration binding and payload schema, deduplicate durable notification identifiers, and use immutable explicit PendingIntents to native screens after session/server revalidation. No arbitrary payload URL or message-body preview by default. Suppress redundant foreground alerts for the visible conversation. No image/network-heavy work inside `onMessageReceived`; bounded WorkManager work handles necessary follow-up. Non-GMS devices expose an honest push-unavailable state and retain foreground chat.

The proposed sender uses data messages so the app can validate local account binding before displaying. FCM notification/composite messages can be displayed automatically by Android in the background, bypassing that check; do not switch payload modes without re-evaluating logout/privacy behavior. FCM is best effort: delayed, dropped, force-stopped and revoked-permission cases must reconcile on next foreground entry.

## End-to-end manual test, once implemented

Record build commit/variant, backend release and native capabilities, Android/API/device, Firebase project label and sender mode without exposing tokens or keys. On a real Play-services device or suitable emulator:

1. Sign in, opt in, grant permission and confirm acknowledged server device registration. Verify other accounts cannot list/revoke it.
2. Background the app; trigger an eligible existing mention/ping/security notification from another account. Confirm generic lock-screen display, native tap routing, authorization, one alert and coherent unread state.
3. Repeat foregrounded in the destination and elsewhere; check duplicate suppression and notification-center state.
4. Test denied/revoked permission, quiet hours/timezone, disabled channel and account preference. Confirm ordinary DMs follow the explicit B6 decision rather than an assumed category.
5. Force token refresh/reinstall, sign out online/offline, switch accounts/server, remotely revoke, invalidate the session and close the account. Old notifications, late token workers and pending intents must never expose a prior account.
6. Exercise no Play services, network loss, background duration/Doze and app force-stop. No delivery guarantee while force-stopped; a fresh foreground reconciliation must still restore authoritative state.

A Firebase Console message tests transport only; it does not prove ChitChat device authorization, dispatch, preferences or routing. Do not mark M6/M8 complete from it.

Official references: [Android FCM setup/token/permission](https://firebase.google.com/docs/cloud-messaging/android/get-started), [receive behavior/background limits](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), [token lifecycle](https://firebase.google.com/docs/cloud-messaging/manage-tokens), [HTTP v1 server authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api). These instructions were reviewed during discovery; verify Console labels/current stable SDK tooling when implementing.
