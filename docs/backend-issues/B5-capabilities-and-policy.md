# Expose additive native-client capabilities and consistent public policy metadata

Status: proposed compatibility/read-contract improvement. Android [M2/M6](https://github.com/Thiesi/ChitChat-Mobile/blob/docs/android-discovery/docs/implementation-plan.md) needs accurate server branding and reliable gating for new native features. Core v3.2.1 chat can work without this addition.

Tracks [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1).

## User-facing requirement and current behavior

The native app should display the installation's actual configured name and explain which native authentication/push features the installation supports, without guessing routes or scraping HTML. Privacy disclosures must match what the server actually does.

At v3.2.1 [session.php](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/session.php#L41) exposes useful policies but no effective application name or native capability version. [health.php](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/health.php) intentionally uses the environment default, while the effective DB override is exposed through a [Super-Admin-only service](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Admin/ApplicationNameService.php#L67). Browser markup reads the effective name directly.

There is also a concrete policy discrepancy: `session.php` returns `privacy.message_revisions.participant_notification:false`, but [revision documentation](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/docs/architecture/message-revisions.md) and the [0017 audit notification trigger](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/migrations/0017_privacy_notifications.sql#L27) describe/create revision-review participant notifications. Resolve this from intended current behavior rather than having Android choose a contradictory promise.

## Proposed additive response contract

Extend the ordinary session bootstrap with stable public metadata, for example `application:{name,version}` and `capabilities:{schema_version:1,native_passkey_origins:false,native_auth_handoff:false}`. Field names and exact native-auth payloads need review alongside B1/B2; flags must reflect actual configured support, not only source version. UnifiedPush uses existing `web_push.enabled` and `web_push.vapid_public_key`; no FCM flag/project/client list is required or assumed. Optional future shared subscription-hardening capabilities can be additive after B3/B4 define them. Publish no private client keys or global device identifiers. Keep policy values consistent with current DB-trigger/service behavior and documents; use explicit unknown/unavailable values when a deployment cannot determine a policy.

Document which fields are public/anonymous versus authenticated, their defaults, additive evolution, unsupported/unknown behavior and version compatibility. On old servers Android uses host/default branding and disables unadvertised new features. Do not turn API v1 into a breaking mobile-specific response.

## Security and compatibility

No admin config secrets, credential metadata, raw signing credentials, device tokens or cross-account state in public discovery. Effective name is already public browser branding. Authenticate account policies as appropriate, retain PHP cookie/CSRF rules and all existing fields. Do not imply E2EE or weaken revision/inspection authorization. Feature discovery is UI guidance, never permission enforcement.

## Acceptance criteria

- Anonymous/ordinary users see the effective installation name without admin privilege or HTML scraping; changed name matches web branding.
- Native-auth metadata distinguishes absent/unconfigured/supported deployments and trusted client identities; legacy clients and existing Web Push discovery still work.
- Revision notification policy value agrees with integration tests for successful authorized revision review and affected participants.
- Public response tests prove no secrets/other-user data are exposed, and documents define backward-compatible defaults.
