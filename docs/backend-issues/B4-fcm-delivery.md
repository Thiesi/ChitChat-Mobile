# Deliver existing account notifications through FCM without changing privacy or Web Push behavior

Status: proposed backend integration. Depends on B3 device lifecycle and the publisher/operator push-topology decision. Android [M6](https://github.com/Thiesi/ChitChat-Mobile/blob/docs/android-discovery/docs/implementation-plan.md) depends on actual delivery; a Firebase test message alone is insufficient.

Tracks [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1).

## User-facing requirement and evidence

When the app is backgrounded, eligible existing account notifications should reach registered native devices through FCM, with account preferences, privacy and authorization matching ChitChat. Foreground messaging continues to use SSE.

At v3.2.1 [WebPushDispatcher::dispatchOne](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/WebPush/WebPushDispatcher.php#L127) sends VAPID Web Push after checking quiet hours and mention/ping preferences. It marks a single `account_notifications.push_dispatched_at` even when suppressed/failed. A second independent FCM sweep over that field could cause starvation/duplicate/racy delivery. [PrivacyNotificationService](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Account/PrivacyNotificationService.php#L214) renders text shared with browser push; later kinds can contain moderation reasons, so forwarding all rendered bodies is not a safe content-free Android default.

## Proposed delivery contract

Use a durable per-transport/device delivery ledger or one coordinated dispatcher with atomic claims and independent outcomes for Web Push and FCM. Define notification ID + registration binding as the dedupe key. Retain bounded retries/backoff for transient FCM errors, prune confirmed invalid/unregistered tokens, distinguish project/credential/configuration failures from expired tokens, and document best-effort delivery/TTL. Do not mark browser dispatch complete because native dispatch ran, or vice versa.

Proposed FCM **data-only** payload fields, all strings: `{schema_version:"1",registration_binding:"<opaque>",notification_id:"<id>",category:"<existing kind>"}`. Optional route IDs must be validated and contain no message body, caption, filename, search text, recovery/identity material, free-text reason, username or room name. The app shows a generic private notification and retrieves authorized details after entry. Prefer opaque binding over raw user/server identifiers so Google/relay sees minimal metadata. Scope route binding to the configured server/device; notification tap must reauthorize. No arbitrary URL navigation from payload.

Preserve current account-level mention/ping toggles and quiet-hour suppression semantics, including no deferred morning replay unless separately agreed. Security/policy categories stay non-optional at account preference level; Android OS/device permissions can still disable all alerts. Do not treat a push receipt as read acknowledgment. Ordinary DM push is separate B6 proposed product behavior. Recheck recipient/device eligibility at dispatch, including revocation, room access changes, closed/banned accounts, ignores and expired registrations; never use a raw client-provided user ID as delivery authorization.

## Sender ownership/security

For a matching operator build, server-side OAuth/FCM HTTP v1 credentials belong to that operator and match the app's Firebase project. For a universal publisher build, use an explicitly agreed authenticated relay with trusted instance enrollment, quotas, replay protection, per-device authorization and no raw content. Do not grant arbitrary hosts unrestricted publisher-project send permission, accept client-selected relay URLs, or copy sender keys into the APK. No relay deployment/hosting is authorized by this issue alone.

## Acceptance criteria

- Existing categories reach a real background Android device and route to reauthorized native state; denied permissions, no-GMS and force-stop/delayed delivery remain honestly reported limitations.
- Web Push and FCM coexist without duplicate ledger processing, starvation or changes to existing browser behavior.
- Tests cover registration expiry/revoke/account switch, duplicate/reordered messages, transient failures, invalid tokens, project mismatch, preference changes, quiet hours/timezones, ignored users and authorization loss.
- Lock-screen payload and transport logs contain no sensitive content; service credentials remain server-only.
- Document dispatch scheduling/health, relay or direct configuration, category semantics and end-to-end verification steps.
