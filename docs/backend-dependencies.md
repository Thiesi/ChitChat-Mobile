# Backend dependencies and filed issues

Source baseline v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`; checked 2026-10-19. Android delivery tracking: [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1). No backend source, configuration, documentation or Git history was modified. The permitted GitHub issues below were created after listing all existing open/closed issues and finding no matching native requirements. Existing closed WebAuthn hardening #81 is not an Android-origin contract.

**Proposed additions are not available in v3.2.1. Existing Web Push already supports UnifiedPush subscriptions.** The accepted [push decision](push-architecture.md) supersedes the original FCM-token registration/sender proposals in B3/B4. Both GitHub issues have been revised for shared Web Push hardening. New proposed route/field examples must not enter production requests until documented server support is established.

| ID / filed issue | User-facing dependency | Status and effect |
|---|---|---|
| B1 [ChitChat #145](https://github.com/Thiesi/ChitChat/issues/145) | Trusted Android WebAuthn origins, existing RP and DAL deployment | Open; blocks native passkey enrollment/login/step-up/restoration, not password/registration/guest/chat. Recovery codes remain emergency fallback |
| B2 [ChitChat #146](https://github.com/Thiesi/ChitChat/issues/146) | Secure browser identity handoff into the app's own PHP session | Open; blocks Google/Twitch end-to-end sign-in/new-account continuation/link/provider step-up/restore/picture import. No browser-cookie extraction |
| B3 [ChitChat #147](https://github.com/Thiesi/ChitChat/issues/147) | Revised: shared Web Push lifecycle, endpoint ownership/security and additive native routing metadata | Original FCM proposal superseded; no new native token API needed. Tracks production hardening, not a prerequisite for basic UnifiedPush HTTPS443 delivery |
| B4 [ChitChat #148](https://github.com/Thiesi/ChitChat/issues/148) | Revised: shared dispatch retries/atomic claims and custom-port VAPID-audience dependency defect | Original second-sender proposal superseded; shared worker already delivers compatible subscriptions. Custom-port fix affects strict endpoints off HTTPS443 |
| B5 [ChitChat #149](https://github.com/Thiesi/ChitChat/issues/149) | Effective public installation name, additive native capabilities and consistent revision-notification policy | Open; accurate branding/gating/disclosure dependency, not a blocker to basic v3.2.1 core chat. Host label and disabled unadvertised features are honest fallbacks |
| B6 [ChitChat #150](https://github.com/Thiesi/ChitChat/issues/150) | Explicit opt-in incoming-DM notification category | **Product proposal awaiting decision**; ordinary DM push is not existing notification behavior. Can be declined/deferred without disabling foreground DM |

Issue bodies are retained for review/offline use; B3/B4 are revised proposals, while the originals remain in Git history:

- [B1: Android passkey origins](backend-issues/B1-android-passkey-origins.md)
- [B2: Native browser handoff](backend-issues/B2-native-browser-handoff.md)
- [B3: Shared Web Push lifecycle/security](backend-issues/B3-fcm-device-lifecycle.md)
- [B4: Dispatch reliability/custom-port VAPID](backend-issues/B4-fcm-delivery.md)
- [B5: Capabilities and policy](backend-issues/B5-capabilities-and-policy.md)
- [B6: Incoming-DM proposal](backend-issues/B6-incoming-dm-notifications.md)

Each issue includes requirement, verified existing behavior/source, proposed API/delivery contract, authorization/privacy/compatibility considerations and acceptance criteria, and references Android #1/M6 or its affected milestone. Filing issues does not authorize modifying the backend checkout or prove that the server changes will be accepted.

## Improvements that are not fundamental dependencies

Efficient around-message history, snapshot/current-cursor and expired-cursor reset, server send idempotency, conversation-list pagination beyond 100 and bounded DM read-through ID are optional improvements. Current Android work can use older-page traversal, state invalidation/refetch, explicit unknown-send status, capped conversation lists and existing read semantics. No optional endpoint is invented or treated as shipped. See [known limitations](known-limitations.md).

## Native integration rules while waiting

Build normal password/session/rooms/DM/media/search/moderation vertical slices against existing contracts. Isolate native-auth interfaces with honest unsupported UI until B1/B2 ship. Push uses documented Web Push subscription fields from the UnifiedPush connector and existing `session.php.web_push` configuration; do not gate it on nonexistent FCM capability flags. Test fakes belong only in test source sets; no production fake success, hidden fallback authentication, background SSE service or Firestore.

Integration acceptance requires an accessible compatible server, configured optional features, real app identity/signing/DAL where applicable, and actual distributor/device evidence. No Firebase project is needed. Keep B1-B6 status and tested capabilities current in affected milestone PRs. Ordinary-DM alerts remain separately unapproved. Do not close a backend dependency from Android compilation alone.
