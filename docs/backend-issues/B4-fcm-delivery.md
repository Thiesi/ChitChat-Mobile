# Improve shared Web Push dispatch reliability and custom-port VAPID support

Status: revised proposal following accepted UnifiedPush architecture, submitted to [ChitChat #148](https://github.com/Thiesi/ChitChat/issues/148). This supersedes the FCM sender/independent-ledger proposal; the filename is retained for tracking. Tracks [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1), M6/M8. No new native sender or publisher relay is required.

## Requirement and existing behavior

Eligible account notifications should reach browser and Android Web Push subscriptions reliably while preserving authorization, quiet hours and preferences. The existing [`WebPushDispatcher`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/WebPush/WebPushDispatcher.php#L127) already uses VAPID and `aes128gcm`, interoperating with compatible UnifiedPush subscriptions on HTTPS port 443. A single shared worker avoids the second-sender/global-marker race described in the original proposal.

The worker currently marks attempts complete even after transient failure; scheduling adds roughly 30–60 seconds before transport delivery. The pinned `minishlink/web-push` v11.0.0 also builds a VAPID audience without a non-default endpoint port. An investigation request-generation check reproduced that defect for `https://…:8443`; strict services may reject it. See [dependency source](https://github.com/web-push-libs/web-push-php/blob/v11.0.0/src/WebPush.php#L337) and [RFC 8292](https://www.rfc-editor.org/info/rfc8292/). Basic port-443 interoperability does not depend on these improvements.

## Proposed changes and compatibility

Fix/update the upstream dependency so VAPID `aud` includes the endpoint's scheme/host/non-default port as required. Prefer a maintained upstream release; retain signature, encryption and endpoint-validation checks. Until fixed, document a port-443 reverse proxy as the workaround for push services.

Use atomic work claims and bounded retry/backoff for transient transport failures with safe concurrent-worker behavior. Track per-subscription outcomes where necessary to avoid retrying successful recipients while losing failed ones; a shared Web Push delivery ledger may implement this without inventing another transport. Distinguish permanent expired subscriptions from configuration/network failures. Define TTL/urgency, retry deadline, operational scheduling/health and duplicate-delivery expectations. Never promise exactly-once delivery.

Keep existing `{title,body,link,app_name}` payload keys and API routes. Optional notification IDs/bindings can improve native/browser deduplication but cannot be assumed by old clients. Preserve current quiet-hour suppression (no deferred morning replay without a separate product decision), mention/ping preferences and security/policy semantics. Recheck recipient/subscription eligibility before dispatch/retry. Ordinary incoming-DM alerts remain separate unapproved B6 behavior.

## Security and privacy

Continue RFC 8291 encryption and installation-owned VAPID keys; keep private keys on the ChitChat server. Do not add Firebase server credentials or a mandatory publisher relay. Redact endpoints/keys/decrypted content from logs. Android shows generic lock-screen text, validates local account generation and reauthorizes taps; chat is not E2EE. Coordinate destination protections and subscription lifecycle with B3 without treating a raw endpoint as user authorization.

## Acceptance criteria

- Strict compatible push servers accept VAPID for HTTPS port 443 and a non-default HTTPS port; wrong audiences still fail.
- Existing browser delivery and at least two UnifiedPush distributors continue working with unchanged subscription fields/encryption.
- Tests cover concurrent claims, transient retry/expiry, permanent invalid subscriptions, partial multi-device failure, duplicates and configuration errors.
- Preferences/quiet hours, ignored users, revoked/closed/unauthorized recipients remain correct across retries.
- Operational delay and failure/retry behavior are documented; actual background device delivery is tested separately from request-generation/unit tests.
