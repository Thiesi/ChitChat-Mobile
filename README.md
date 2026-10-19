# ChitChat Mobile

![ChitChat Mobile — Self-hosted browser chat on your phone](docs/assets/chitchat-banner.png)

ChitChat is a small, self-hosted browser chat application. ChitChat Mobile is its native mobile client.

The Android application uses Kotlin and Jetpack Compose with ChitChat's existing PHP sessions, CSRF protection, HTTP APIs and foreground Server-Sent Events. Background push will use UnifiedPush with the existing Web Push backend: prefer an installed distributor, with an optional embedded FCM distributor fallback. No publisher-operated relay or Firebase project is required.

## Current status

**M1 native foundation implemented and locally verified; remote CI/review pending.** Debug and shrunk unsigned release APKs compile; eight unit tests, Android lint and three API36 emulator tests pass. Native server-address storage and appearance settings work locally; authentication, chat and push are not implemented yet. The audited backend baseline is [ChitChat v3.2.1](https://github.com/Thiesi/ChitChat/releases/tag/v3.2.1). See [M1 evidence](docs/milestones/M1-foundation.md).

Core chat and UnifiedPush over HTTPS port 443 have existing APIs. Native passkeys and browser identity handoff require the [backend dependencies](docs/backend-dependencies.md); shared Web Push hardening and capability discovery remain tracked separately. Ordinary incoming-DM push is a separate, unapproved product proposal. The ChitChat backend checkout remains read-only.

## Engineering documents

- [Discovery report and consequential decisions](docs/discovery.md)
- [Feature-parity matrix](docs/feature-parity.md)
- [Proposed architecture](docs/architecture.md)
- [Verified API contracts and compatibility](docs/backend-compatibility.md)
- [Implementation milestones and acceptance gates](docs/implementation-plan.md)
- [Backend issues](docs/backend-dependencies.md)
- [Accepted decentralized push architecture](docs/push-architecture.md)
- [UnifiedPush and optional FCM fallback setup](docs/firebase-setup.md)
- [Build, test and release instructions](docs/build-test-release.md)
- [Connect the Claude PR reviewer](docs/claude-review-setup.md)
- [Current limitations](docs/known-limitations.md)

Begin setup with the build guide. Public application identity/distribution and ordinary-DM alerts remain open decisions; the recommended namespace is used only for development until confirmed. A signing identity and integration server have not been supplied. No Firebase identifiers or configuration are needed for the accepted push approach. [Delivery tracking](https://github.com/Thiesi/ChitChat-Mobile/issues/1) records milestone completion.
