# Backend dependencies and filed issues

Source baseline v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`; checked 2026-10-19. Android delivery tracking: [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1). No backend source, configuration, documentation or Git history was modified. The permitted GitHub issues below were created after listing all existing open/closed issues and finding no matching native requirements. Existing closed WebAuthn hardening #81 is not an Android-origin contract.

**None of these proposed contracts is currently available in v3.2.1.** New route/field examples in issue bodies must not enter production Android requests until server implementation, contract documentation, capability gating and integration tests establish support.

| ID / filed issue | User-facing dependency | Status and effect |
|---|---|---|
| B1 [ChitChat #145](https://github.com/Thiesi/ChitChat/issues/145) | Trusted Android WebAuthn origins, existing RP and DAL deployment | Open; blocks native passkey enrollment/login/step-up/restoration, not password/registration/guest/chat. Recovery codes remain emergency fallback |
| B2 [ChitChat #146](https://github.com/Thiesi/ChitChat/issues/146) | Secure browser identity handoff into the app's own PHP session | Open; blocks Google/Twitch end-to-end sign-in/new-account continuation/link/provider step-up/restore/picture import. No browser-cookie extraction |
| B3 [ChitChat #147](https://github.com/Thiesi/ChitChat/issues/147) | Authenticated FCM token registration, renewal, rotation, ownership and revocation | Open; blocks token association/device lifecycle. Includes offline logout and expired/stale account bindings |
| B4 [ChitChat #148](https://github.com/Thiesi/ChitChat/issues/148) | Authorized privacy-safe FCM delivery and independent transport ledger | Open; depends on B3 and chosen sender topology. Existing Web Push must coexist without starvation or altered preference semantics |
| B5 [ChitChat #149](https://github.com/Thiesi/ChitChat/issues/149) | Effective public installation name, additive native capabilities and consistent revision-notification policy | Open; accurate branding/gating/disclosure dependency, not a blocker to basic v3.2.1 core chat. Host label and disabled unadvertised features are honest fallbacks |
| B6 [ChitChat #150](https://github.com/Thiesi/ChitChat/issues/150) | Explicit opt-in incoming-DM notification category | **Product proposal awaiting decision**; ordinary DM push is not existing notification behavior. Can be declined/deferred without disabling foreground DM |

Complete submitted issue bodies are retained for review/offline use:

- [B1: Android passkey origins](backend-issues/B1-android-passkey-origins.md)
- [B2: Native browser handoff](backend-issues/B2-native-browser-handoff.md)
- [B3: FCM device lifecycle](backend-issues/B3-fcm-device-lifecycle.md)
- [B4: FCM delivery](backend-issues/B4-fcm-delivery.md)
- [B5: Capabilities and policy](backend-issues/B5-capabilities-and-policy.md)
- [B6: Incoming-DM proposal](backend-issues/B6-incoming-dm-notifications.md)

Each issue includes requirement, verified existing behavior/source, proposed API/delivery contract, authorization/privacy/compatibility considerations and acceptance criteria, and references Android #1/M6 or its affected milestone. Filing issues does not authorize modifying the backend checkout or prove that the server changes will be accepted.

## Improvements that are not fundamental dependencies

Efficient around-message history, snapshot/current-cursor and expired-cursor reset, server send idempotency, conversation-list pagination beyond 100 and bounded DM read-through ID are optional improvements. Current Android work can use older-page traversal, state invalidation/refetch, explicit unknown-send status, capped conversation lists and existing read semantics. No optional endpoint is invented or treated as shipped. See [known limitations](known-limitations.md).

## Native integration rules while waiting

Build normal password/session/rooms/DM/media/search/moderation vertical slices against existing contracts. Isolate native-auth and push registration/delivery interfaces, with explicit unsupported/unconfigured UI on old servers. Test fakes may exercise client state machines only in test source sets; no production fake success, hidden fallback authentication, background SSE service, Firestore or undocumented Web Push substitution.

Integration acceptance requires an accessible compatible server, configured optional features, real app identity/signing/DAL where applicable, and actual Firebase/device evidence. Keep B1-B6 status and tested server capabilities current in each affected milestone PR. Do not close a backend dependency from Android compilation alone.
