# Native Android client: delivery milestones and backend dependencies

Implement the production native Kotlin/Compose ChitChat client in this repository. The backend repository is read-only except for explicitly requested GitHub issues. Begin with the discovery review and consequential product decisions, then execute buildable vertical slices with tests, commits and PRs.

Discovery baseline: ChitChat v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`. No Android application/build/FCM end-to-end functionality exists at issue creation.

- [ ] M0: discovery delivered; decentralized push accepted; ordinary-DM and public identity/distribution decisions remain open
- [ ] M1: buildable native scaffold, CI, tests and navigation (local debug/release, eight unit tests, lint and three API36 device tests pass; remote CI/review gates pending)
- [ ] M2: server selection, PHP cookie/CSRF sessions and authentication
- [ ] M3: rooms, history/composition, foreground SSE/presence/reconciliation
- [ ] M4: DMs, unread state, blocking/ignoring, notification center and navigation
- [ ] M5: interactions, attachments/sharing, search, profiles and reports
- [ ] M6: account/native authentication, UnifiedPush/Web Push lifecycle and notifications; optional embedded FCM fallback
- [ ] M7: guest, moderation, administration and remaining parity
- [ ] M8: real-installation/device acceptance, accessibility, docs and release

Reviewable specifications: [discovery](https://github.com/Thiesi/ChitChat-Mobile/blob/main/docs/discovery.md), [architecture](https://github.com/Thiesi/ChitChat-Mobile/blob/main/docs/architecture.md), [parity matrix](https://github.com/Thiesi/ChitChat-Mobile/blob/main/docs/feature-parity.md), [gates](https://github.com/Thiesi/ChitChat-Mobile/blob/main/docs/implementation-plan.md), [backend dependencies](https://github.com/Thiesi/ChitChat-Mobile/blob/main/docs/backend-dependencies.md).

Filed backend dependencies: [Android passkey origins #145](https://github.com/Thiesi/ChitChat/issues/145), [browser handoff #146](https://github.com/Thiesi/ChitChat/issues/146), [shared Web Push lifecycle/security #147](https://github.com/Thiesi/ChitChat/issues/147), [dispatch reliability/custom-port VAPID #148](https://github.com/Thiesi/ChitChat/issues/148), [capability/policy metadata #149](https://github.com/Thiesi/ChitChat/issues/149), and [opt-in incoming-DM proposal #150](https://github.com/Thiesi/ChitChat/issues/150). B3/B4 GitHub issues now supersede their FCM proposals with shared Web Push work. No issue is shipped or closed by documentation.

Accepted push topology: UnifiedPush/Web Push, installed distributor preferred, optional embedded FCM fallback, no mandatory publisher relay or Firebase project/config. Outstanding decisions: opt-in ordinary incoming-DM category and public application ID/distribution. Integration acceptance needs a compatible non-production HTTPS instance/test identities and real devices/distributors. Do not post production credentials or private keys here.

Completion requires successful real core-chat integration and honest backend dependency status. Mocks/compilation/Console push cannot stand in for full server/device acceptance. Each milestone must update the parity matrix and current limitations before commit/PR.
