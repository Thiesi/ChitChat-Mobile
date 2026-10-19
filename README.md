# ChitChat Mobile

![ChitChat Mobile — Self-hosted browser chat on your phone](docs/assets/chitchat-banner.png)

ChitChat is a small, self-hosted browser chat application. ChitChat Mobile is its native mobile client.

The Android application uses Kotlin and Jetpack Compose with ChitChat's existing PHP sessions, CSRF protection, HTTP APIs and foreground Server-Sent Events. Background push will use UnifiedPush with the existing Web Push backend: prefer an installed distributor, with an optional embedded FCM distributor fallback. No publisher-operated relay or Firebase project is required.

## Current status

**Native session authentication, registration and restoration are implemented.** M2a supplies HTTPS/password/recovery, encrypted session restoration and local/server logout. M2b1 adds native registration with cancellable proof-of-work and explicit closing-account restoration, including pending MFA. See [M2b1 checks and limits](docs/milestones/M2b-registration-restoration.md); historical auth evidence is in [M2a](docs/milestones/M2a-session-auth.md). Shared step-up remains M2b2, so M2 is not complete; chat and push remain unimplemented. The audited backend baseline is [ChitChat v3.2.1](https://github.com/Thiesi/ChitChat/releases/tag/v3.2.1).

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

Begin setup with the build guide. Public application identity/distribution and ordinary-DM alerts remain open decisions; the recommended namespace is used only for development until confirmed. The supplied v3.2.1 test server passed the opt-in password/session/logout HTTP test with a dedicated account in ignored, user-owned `.env.integration`. This is not live native UI or chat acceptance. A release signing identity has not been supplied. No Firebase identifiers or configuration are needed for the accepted push approach. [Delivery tracking](https://github.com/Thiesi/ChitChat-Mobile/issues/1) records milestone completion.
