# UnifiedPush setup and optional FCM fallback

The filename is retained for existing links; the former Firebase-project/relay setup is superseded by the [accepted decentralized push decision](push-architecture.md). This is an implementation/setup guide, not a claim that push is implemented or device-tested.

## Maintainer and operator steps

1. Enable ChitChat's existing Web Push configuration with the operator's VAPID keypair and configure its dispatch worker according to the backend's [Web Push documentation](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/docs/api/web-push.md). Keep the private VAPID key on that server. Android reads only the public key from `session.php`.
2. Choose a compatible [UnifiedPush distributor](https://unifiedpush.org/users/distributors/) on the phone, and its push server. Users may use a public provider or self-host it. Follow distributor-specific background/battery instructions. The ChitChat server must reach and trust the delivery endpoint; additional private provider-login headers are not supplied by the existing worker.
3. Use HTTPS port 443 for the push endpoint until the pinned Web Push PHP dependency's custom-port VAPID-audience defect is fixed. This concerns the push endpoint's port, not an assumption about all ChitChat installation ports.
4. In the future M6 UI, sign in and explicitly enable push, select the installed distributor, grant Android notification permission where required, and confirm server acknowledgement. The client uses the existing PHP session/CSRF-protected `{endpoint,p256dh,auth}` subscription contract. No native FCM-token route is needed.
5. Confirm the public application identity/signing/distribution before release. The recommended `io.github.thiesi.chitchat` namespace may be used for development; it has not been accepted as the final release ID. Passkey DAL/signing trust and browser-auth handoff remain separate B1/B2 requirements.

No Firebase Console project, Android Firebase registration, `google-services.json`, service account, server sender credential or publisher-operated relay is required for either accepted push path. Do not place such files into this repository or invent identifiers. Firebase Authentication, Firestore and Realtime Database are not used.

## Optional embedded FCM distributor

When no external distributor is usable, the app may offer UnifiedPush's embedded FCM distributor on devices with compatible Google Play Services. The user can choose the external-distributor path instead. The embedded path obtains Web Push endpoints using the ChitChat installation's public VAPID key; ChitChat sends encrypted Web Push directly to those endpoints.

The investigated version is 3.1.0; review a maintained compatible version during implementation. Its Play Services registration IPC is not the supported Firebase Messaging SDK contract, so continued compatibility is a maintenance risk requiring real-device testing. No Firebase project configuration file is consumed. See [official guide](https://unifiedpush.org/kdoc/embedded_fcm_distributor/) and the [accepted decision](push-architecture.md).

Without Google services, an installed UnifiedPush distributor can still provide background notifications. If no usable distributor exists, show setup guidance and preserve foreground chat; do not substitute permanent background SSE/polling.

## Client privacy and lifecycle requirements

Scope connector instance, encryption material, registration jobs and callbacks to server/account/local generation. Submit rotations through existing subscribe/unsubscribe operations and reject stale callbacks. On disable/logout, unsubscribe while authenticated where possible, unregister locally, destroy keys/bindings and cancel notifications immediately even offline. Current server logout alone does not revoke subscriptions; B3 tracks shared hardening. Local rejection/key destruction must protect the prior account without waiting for it.

Guests must not be offered push registration: the existing `push/subscribe.php` calls `SessionManager::requireUser()`, which refuses a guest with HTTP403 `guest_not_allowed` (after valid CSRF). Reading a guest's in-app notifications does not grant push access. M6 tests must verify both the hidden opt-in and the real guest endpoint rejection. See [endpoint](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/push/subscribe.php#L24) and [guard](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Auth/SessionManager.php#L208).

Offline logout cannot immediately delete a server row. M6 acceptance records the next dispatch outcome after local unregistration: whether the distributor returns404/410 and the shared worker prunes the subscription. If it still accepts opaque encrypted deliveries, disclose the stale row/provider-visible delivery metadata and verify no old payload is displayed locally; do not claim guaranteed immediate remote revocation. B3/B4 track remote lifecycle/reliability hardening. Never retain a signed-out session cookie merely to retry cleanup.

Create native participation and security/policy channels. Request `POST_NOTIFICATIONS` contextually on Android 13+. Permission denial/channel blocking never prevents foreground chat. Validate decrypted bounded payloads and local bindings before display; current payloads lack guaranteed notification IDs, so do not assume proposed B3 metadata. Default lock-screen content is generic. Use immutable explicit PendingIntents and allowlisted same-server native routes; taps revalidate session and authorization. Reconcile authoritative notification state rather than treating receipt as read acknowledgement.

Encryption protects push payloads through the distributor/provider; it is not chat E2EE. Existing categories, account preferences and quiet-hour suppression remain unchanged. B6 ordinary-DM alerts are still awaiting a separate product decision.

## End-to-end manual test, once implemented

Record app/backend commits, distributor/client/server versions, Android device/API, scheduler and background settings without tokens, endpoints or keys. Test at least two independent compatible distributors, one without Play Services, and the optional embedded FCM fallback:

1. Sign in, opt in and verify acknowledged subscription; verify ownership of listing/removal.
2. Background the app and trigger eligible mention/ping/security events from another account. Check generic lock-screen display, one alert, authorized native routing and consistent unread state.
3. Repeat in the foreground, in the visible conversation and elsewhere, checking suppression/reconciliation.
4. Test denied/revoked permission, quiet hours, device channels, distributor unavailable and account preferences. Ordinary DM behavior must match the separate B6 decision.
5. Rotate endpoints/reinstall, sign out online/offline, switch accounts/servers, revoke remotely, invalidate a session and close an account. Old notifications, keys, callbacks and PendingIntents must not expose the previous account.
6. Exercise prolonged background/Doze, process death, network loss, distributor restart and force-stop. Do not guarantee delivery while force-stopped. Foreground entry must restore authoritative state.
7. Recheck browser push, encryption interoperability, dispatcher failures/retries, port-443 behavior and any claimed custom-port fix.

Mock connector callbacks and generated HTTP requests validate only local behavior. A direct test push does not establish ChitChat authorization/preferences/end-to-end delivery. No such device tests have passed yet.
