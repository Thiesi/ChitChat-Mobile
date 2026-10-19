# Add authenticated FCM device registration, rotation and revocation

Status: proposed backend contract; missing in v3.2.1. Android [M6](https://github.com/Thiesi/ChitChat-Mobile/blob/docs/android-discovery/docs/implementation-plan.md) needs this before native token association is functional. Delivery is separate B4 work.

Tracks [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1).

## User-facing requirement and existing behavior

An authenticated user must explicitly enable/disable native background push, rotate a device token, list/revoke devices and safely sign out/switch accounts. Existing [push/subscribe.php](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/push/subscribe.php) accepts Web Push endpoint/P-256/auth material; it is not an FCM registration API. [PushSubscriptionService](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/WebPush/PushSubscriptionService.php) and browser device IDs must retain their existing meaning.

## Proposed API contract

Keep PHP sessions and CSRF. Suggested additive routes, subject to backend review:

- `POST /api/v1/push/fcm/register.php`: `{installation_id,token,client_id,device_label}`. Project/sender identity comes from server-approved `client_id`, not arbitrary client credentials or sender URLs. Validate bounds/format; atomically upsert the current authenticated account's installation/token and return `{device:{id,transport:"fcm",label},registration_binding,expires_at}`. `registration_binding` is an opaque per-registration routing value, not an account secret or login token. Re-registration/token rotation is idempotent and invalidates the old binding/token association. A fresh registration must not silently steal another account's binding: specify a secure installation-ownership proof or require prior unbinding.
- `GET /api/v1/push/fcm/devices.php`: own display-safe device metadata, state and expiry only, never full token/registration secret. Browser and native device listings remain distinguishable.
- `POST /api/v1/push/fcm/revoke.php`: `{device_id}` for an authenticated owner; missing/foreign IDs are nondisclosing no-ops. Support current-device logout revocation and remote device removal.
- Define bounded authenticated renewal/registration expiry (proposed at most seven days without renewal), token rotation races and an installation-scoped revocation capability if needed for offline logout. A revocation capability must allow only revoking its binding, never registering, reading or sending.

Advertise configured FCM support, approved client/project compatibility and lifecycle contract version through additive capability metadata. An unconfigured server must return a stable unsupported error, never accept-and-ignore tokens.

## Security, privacy, compatibility

Tokens are sensitive routing identifiers, not proof of ChitChat login. Scope by installation/server/account/client and enforce authenticated ownership. Rate limit registration/revocation, cap devices, redact tokens/secrets from logs/audit/listing/export, use HTTPS and server-side secure storage. No topic subscription that lets clients target other users. No Firebase service account or sender credential in APK or registration response.

Explicit logout/closure/device removal must revoke the matching association; account invalidation must suspend participation delivery. Define generic security-event treatment separately so account-reset notices do not disclose private content through an invalid session. Offline logout must clear local routing/notifications immediately, reject stale payloads, and have a bounded server expiry/invalidation story rather than assuming a request succeeded. Guests cannot register FCM. Separate registrations per self-hosted server; late workers cannot bind an old account's token under a new login.

No change to existing Web Push endpoints/subscriptions, account quiet hours or durable notification read state.

## Acceptance criteria

- Authenticated token registration/renewal/rotation is idempotent; old tokens/bindings stop receiving after rotation/revoke.
- Foreign account IDs, token/binding takeover, stale session/generation, expired registration, guest/anonymous requests and project mismatch are rejected or nondisclosing as appropriate.
- Online/offline logout, remote revoke, account switch, kick/ban/reset/closure and uninstall/stale-token cases have documented tested outcomes.
- Device listing never returns raw routing secrets; no credentials enter logs, APK or public issues.
- Real Android configuration can register and revoke against a deployed compatible backend; delivery remains separately dependent on B4.
