# Accepted decision: decentralized Android push

Accepted by the maintainer on 2026-10-19 following the UnifiedPush investigation. This supersedes the discovery recommendation for a publisher-operated Firebase relay. It authorizes the push architecture, not ordinary incoming-DM notifications or a final application ID/distribution identity.

## Decision

Use UnifiedPush/Web Push as the common push interface. Prefer a user-selected installed distributor, with an optional embedded FCM distributor fallback for supported Google Play Services devices. A publisher-operated relay, Firebase project, `google-services.json`, Firebase Messaging SDK and publisher/server FCM credentials are not required. One Android package can connect to independently operated ChitChat installations. Google services remain optional.

Foreground messaging continues through authenticated HTTP/SSE. Push is a best-effort hint for existing durable account notifications, never message storage, session authentication, read acknowledgement or a replacement for foreground reconciliation.

## Verified interoperability

The investigation audited ChitChat v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`, UnifiedPush documentation/implementation, and a local request-generation check. It did not deliver a notification to an Android device.

1. Bootstrap the native PHP session and read `web_push.enabled` and `web_push.vapid_public_key` from existing [`session.php`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/session.php).
2. Register a distinct UnifiedPush connector instance for the server/account/local session generation using that installation's VAPID public key. The connector supplies a Web Push endpoint and RFC 8291 P-256/auth material, then decrypts incoming messages. See [UnifiedPush protocol](https://unifiedpush.org/developers/intro/), [endpoint model](https://unifiedpush.org/kdoc/connector/org.unifiedpush.android.connector.data/-push-endpoint/), and [message service](https://unifiedpush.org/kdoc/connector/org.unifiedpush.android.connector/-push-service/).
3. Submit `{endpoint,p256dh,auth}` to existing authenticated, CSRF-protected `/api/v1/push/subscribe.php`. [Subscription validation/storage](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/WebPush/PushSubscriptionService.php) has no browser/provider-only restriction.
4. The existing [dispatcher](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/WebPush/WebPushDispatcher.php#L127) uses VAPID and `aes128gcm`, sharing storage, preferences, quiet hours and dispatch work with browser subscriptions. Android parses the current `{title,body,link,app_name}` payload and displays native notifications.

For reachable, trusted HTTPS endpoints on port 443 this needs no new server endpoint, FCM-token registration, sender, database schema or second dispatch ledger. Existing device listing/removal and unsubscribe contracts remain usable. A raw Firebase SDK token is still not a valid subscription. Push endpoints requiring additional provider-login headers are not automatically supported.

## Compatibility defects and hardening

The pinned `minishlink/web-push` v11.0.0 builds a VAPID audience without a custom endpoint port. The investigation reproduced this for `https://…:8443`; strict servers can reject it. Supporting custom HTTPS push ports needs an upstream fix/dependency update or a port-443 reverse proxy. See [library source](https://github.com/web-push-libs/web-push-php/blob/v11.0.0/src/WebPush.php#L337) and [RFC 8292](https://www.rfc-editor.org/info/rfc8292/).

Shared improvements remain worthwhile: validated keys, proof of endpoint ownership, SSRF/address controls with explicit private self-hosted exceptions, bounded registration lifecycle, additive notification IDs/bindings, and atomic dispatch claims/bounded transient retries. Current logout does not remove subscriptions; the worker marks attempts even after transient failure and its schedule adds roughly 30–60 seconds before delivery. B3/B4 are recast as these backwards-compatible Web Push improvements, not prerequisites for basic port-443 interoperability. Keep existing routes, fields, payload keys and quiet-hour suppression semantics; add optional metadata rather than a parallel native-only transport. See [backend tracking](backend-dependencies.md) and [UnifiedPush security guidance](https://unifiedpush.org/developers/intro/#server-security).

Ordinary unmentioned DMs currently create no durable notification category. B6 remains a separate product proposal awaiting a decision; acceptance of UnifiedPush does not accept that addition.

## Tradeoffs and optional FCM fallback

An external distributor often requires one additional app and background-permission/battery setup. It shares its connection between participating apps and lets the user/operator choose or self-host infrastructure. There is still a push provider; payload encryption prevents that provider reading notification contents, while addresses/timing remain observable. Actual reliability depends on the selected provider, network, distributor and Android/OEM restrictions. Battery superiority is not established without measurements. See [distributors](https://unifiedpush.org/users/distributors/) and [ntfy setup](https://unifiedpush.org/users/distributors/ntfy/).

A publisher FCM relay makes initial phone setup simpler but adds publisher operations, metadata exposure, authorization/queueing complexity and another availability dependency. It is not the selected architecture. Installed distributors work with a single Play Store application; each self-hosted ChitChat operator retains their own VAPID keys.

UnifiedPush's optional [`embedded_fcm_distributor`](https://unifiedpush.org/kdoc/embedded_fcm_distributor/) 3.1.0 uses Play Services registration IPC to obtain direct FCM Web Push endpoints with the installation's public VAPID key. Its [registration implementation](https://codeberg.org/UnifiedPush/android-embedded_fcm_distributor/src/tag/3.1.0/embedded_fcm_distributor/src/main/java/org/unifiedpush/android/embedded_fcm_distributor/c2m/C2mRequests.kt) does not require a publisher Firebase project/config or sender credentials. This is not the supported Firebase Messaging SDK registration contract; no Google longevity guarantee was established. Pin/review a maintained compatible release at implementation, test actual device compatibility, keep it optional, and retain the installed-distributor path if it stops working.

RFC 8291 notification encryption does not make ChitChat chat messages end-to-end encrypted. Preserve existing DM retention/inspection disclosures. Do not log endpoints, keys, decrypted payloads or account secrets; default lock-screen text is generic.

## Implementation and release gates

M6 implements connector registration/rotation, consent and permission UX, channels, instance/generation validation, native routing and logout/key destruction. M1 adds no fake working push flow. No device delivery or push implementation has passed yet.

Before release, test at least two independent compatible distributors and the optional embedded fallback, including one device without Play Services. Cover background/Doze/network changes, endpoint rotation, offline logout/account switches, stale callbacks, denied permission/channels, quiet hours, duplicate payloads, foreground suppression and safe tap reauthorization. Record actual delivery delay, battery/background settings and backend scheduler configuration. Keep browser push regression coverage. Never equate local protocol checks or mock responses with real delivery.
