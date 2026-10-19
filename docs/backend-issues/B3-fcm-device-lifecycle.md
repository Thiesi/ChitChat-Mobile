# Harden the shared Web Push subscription lifecycle and endpoint security

Status: revised proposal following accepted UnifiedPush architecture, submitted to [ChitChat #147](https://github.com/Thiesi/ChitChat/issues/147). This supersedes the original FCM-token lifecycle proposal; the filename is retained for tracking. No `/push/fcm/*` route is required or implemented. Tracks [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1), M6.

## Requirement and current behavior

Browser and native users need safe device registration, rotation, removal and logout. Existing authenticated, CSRF-protected [`push/subscribe.php`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/push/subscribe.php) accepts `{endpoint,p256dh,auth}`. Modern UnifiedPush connectors provide those exact values; no native token API or Firebase credentials are necessary. Existing subscription listing/removal works across compatible providers. [`PushSubscriptionService`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/WebPush/PushSubscriptionService.php) validates HTTPS but needs stronger endpoint/key ownership/security treatment. Current [`logout.php`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/logout.php) does not remove subscriptions.

## Proposed backwards-compatible contract

Retain existing routes/required fields and browser behavior. Strengthen P-256/auth decoding and size validation, authenticated ownership/rate limits/device caps, and optional registration confirmation proving the registrant can receive/decrypt at the chosen endpoint. Design any challenge flow additively and document migration/legacy behavior before enforcing it. Treat subscription endpoints as routing secrets, never account authentication.

Add optional installation/registration binding and display-safe device labels if useful; old clients need not send them. Specify atomic endpoint/key replacement, revocation, optional bounded authenticated renewal/expiry and account-invalidated/closed behavior. List metadata without raw endpoint/key secrets where compatibility permits. A revocation capability, if introduced, must be narrowly scoped and cannot read, register or send.

Guard server-side delivery against SSRF: validate destinations and redirects, DNS resolution/rebinding and unintended private/internal addresses at connection time. Preserve arbitrary public compatible push providers. Support deliberately private self-hosted providers through explicit operator policy/exceptions rather than a fixed vendor allowlist. Define validation errors and safe rollout without silently breaking existing browser subscriptions. See [UnifiedPush security guidance](https://unifiedpush.org/developers/intro/#server-security).

Optional notification IDs/opaque bindings may be added to the existing `{title,body,link,app_name}` payload; retain current keys. Native clients must still scope connector instances locally and reject stale account generations. Offline logout must immediately unregister locally, discard keys/bindings and cancel notifications even when server cleanup fails; server expiry is additional protection, not proof that an offline revoke succeeded.

## Security and compatibility

Keep PHP sessions/CSRF, current guest restrictions, account authorization, preferences and quiet hours. Redact endpoints/keys/payloads from logs and public issue data. No Firebase Authentication, FCM sender credentials, publisher enrollment, mandatory relay or native-only notification semantics. Encryption through a push provider does not make chat E2EE. Basic UnifiedPush HTTPS-443 delivery is already feasible; these are shared production-hardening improvements.

## Acceptance criteria

- Existing browsers and at least two compatible UnifiedPush distributors register/rotate/remove using the shared APIs.
- Foreign-account removal, endpoint takeover, invalid key material, rate-limit bypass, SSRF/redirect/rebinding and unintended internal destinations fail safely; explicit private-provider configuration is tested.
- Online/offline logout, account switch, remote revoke, session invalidation and closure have documented outcomes; stale local callbacks cannot expose prior accounts.
- Optional metadata/challenge/expiry changes have versioned additive contracts and migration coverage; no raw routing secrets leak through logs/listing.
- Real server/device evidence is recorded separately from Android mock tests. No invented FCM-token route is required.
