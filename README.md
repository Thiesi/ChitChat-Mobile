# ChitChat Mobile

![ChitChat Mobile — Self-hosted browser chat on your phone](docs/assets/chitchat-banner.png)

ChitChat is a small, self-hosted browser chat application. ChitChat Mobile is its native mobile client.

The Android application uses Kotlin and Jetpack Compose with ChitChat's existing PHP sessions, CSRF protection, HTTP APIs and foreground Server-Sent Events. Background push will use UnifiedPush with the existing Web Push backend: prefer an installed distributor, with an optional embedded FCM distributor fallback. No publisher-operated relay or Firebase project is required.

## Current status

**Native authentication, personal-data export and manually refreshed room conversations are implemented.** M2a supplies HTTPS/password/recovery, encrypted session restoration and local/server logout; M2b1 adds native registration/PoW and closing-account restoration. M2b2 adds shared password/emergency-recovery step-up and native JSON export through Android’s document picker. See [step-up/export checks and limits](docs/milestones/M2b-step-up-export.md), [registration/restoration](docs/milestones/M2b-registration-restoration.md), and [historical auth evidence](docs/milestones/M2a-session-auth.md). Live acceptance is still partial; realtime chat, DMs and push remain incomplete. The audited backend baseline is [ChitChat v3.2.1](https://github.com/Thiesi/ChitChat/releases/tag/v3.2.1).

M3a adds a [native room directory](docs/milestones/M3a-room-directory.md), known-ID lookup, membership and permitted room creation. M3b adds [room history, composition and visible read state](docs/milestones/M3b-room-conversation.md), including older pages, `/me`, private `/ping`, ignored-message collapse and safe ambiguous-send handling. Refresh is manual; SSE/presence remain M3c, advanced message tools/files M5, and live chat acceptance is pending. Core chat and UnifiedPush over HTTPS port443 have existing APIs. Native passkeys and browser identity handoff require the [backend dependencies](docs/backend-dependencies.md); shared Web Push hardening and capability discovery remain tracked separately. Ordinary incoming-DM push is a separate, unapproved product proposal. The ChitChat backend checkout remains read-only.

## Engineering documents

M3c1's shared-session protocol foundation is merged. M3c2 adds foreground session-event connection status, forced-logout handling and native server announcements. Room transcript/directory reconciliation and visible presence/reentry remain M3c3; chat still explicitly uses Refresh. See the runtime evidence below for actual gates and remaining acceptance.

- [Discovery report and consequential decisions](docs/discovery.md)
- [Feature-parity matrix](docs/feature-parity.md)
- [Proposed architecture](docs/architecture.md)
- [Verified API contracts and compatibility](docs/backend-compatibility.md)
- [Implementation milestones and acceptance gates](docs/implementation-plan.md)
- [Sensitive-action protocol foundation](docs/milestones/M2b2a-protocol-foundation.md)
- [SSE/presence protocol foundation and pending foreground integration](docs/milestones/M3c1-realtime-protocol.md)
- [Foreground session events, announcements and remaining room-consumer work](docs/milestones/M3c2-foreground-runtime.md)
- [Backend issues](docs/backend-dependencies.md)
- [Accepted decentralized push architecture](docs/push-architecture.md)
- [UnifiedPush and optional FCM fallback setup](docs/firebase-setup.md)
- [Build, test and release instructions](docs/build-test-release.md)
- [Connect the Claude PR reviewer](docs/claude-review-setup.md)
- [Current limitations](docs/known-limitations.md)

Begin setup with the build guide. Public application identity/distribution and ordinary-DM alerts remain open decisions; the recommended namespace is used only for development until confirmed. The supplied v3.2.1 test server passed opt-in password/session/logout and read-only room context/history/mutation/ping HTTP checks with a dedicated account in ignored, user-owned `.env.integration`. No live room send/read/presence/membership mutation was performed; this is not live native UI or end-to-end chat acceptance. A release signing identity has not been supplied. No Firebase identifiers or configuration are needed for the accepted push approach. [Delivery tracking](https://github.com/Thiesi/ChitChat-Mobile/issues/1) records milestone completion.
