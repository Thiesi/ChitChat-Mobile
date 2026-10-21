# ChitChat Mobile

![ChitChat Mobile — Self-hosted browser chat on your phone](docs/assets/chitchat-banner.png)

ChitChat is a small, self-hosted browser chat application. ChitChat Mobile is its native mobile client.

The Android application uses Kotlin and Jetpack Compose with ChitChat's existing PHP sessions, CSRF protection, HTTP APIs and foreground Server-Sent Events. Background push will use UnifiedPush with the existing Web Push backend: prefer an installed distributor, with an optional embedded FCM distributor fallback. No publisher-operated relay or Firebase project is required.

## Current status

**Native authentication, export and foreground room/direct conversations are implemented.** M4b2 adds the native DM inbox, recipient search, privacy-aware conversation/composer, blocking and read-race attention. M4c1/2 add typing privacy and foreground member typing; M4c3b adds private room-ignore controls and policy reconciliation. Room switching retains scoped memory-only drafts and delivery-unknown guards, with explicit recovery/discard controls. Native notification-list/read controls are implemented; destination routing, push and remaining parity are pending. Live chat/typing/ignore acceptance is not established. Audited baseline: [ChitChat v3.2.1](https://github.com/Thiesi/ChitChat/releases/tag/v3.2.1). Exact verification boundaries are linked from the milestone plan.

The [native notification center](docs/milestones/M4c4c2-native-notification-center.md) adds the account list, independent unread indicator and explicit individual/All read controls. Destination links and background push remain pending; notices say so explicitly. Local verification passed 531 JVM and 113 native tests; destination routing, push and live acceptance remain pending.

Room and DM reply previews can open the exact original as a marked historical window in the normal pane, keeping drafts and unknown-send guards. Missing/deleted/unauthorized targets are explicit; quote navigation does not mark newer messages read. Reply composition and general notification/search routing remain pending; see [native reply-target scope and verification](docs/milestones/M4c4b2-native-reply-targets.md).

## Engineering documents

M3c1/M3c2 supply the shared-session transport/runtime, forced logout and native announcements. M3c3a/b add bounded canonical history and native room/directory/presence consumers. An open event connection does not prove current history; stale/gap/retry states remain explicit. See actual checks and acceptance limits below.

- [Discovery report and consequential decisions](docs/discovery.md)
- [Feature-parity matrix](docs/feature-parity.md)
- [Proposed architecture](docs/architecture.md)
- [Verified API contracts and compatibility](docs/backend-compatibility.md)
- [Implementation milestones and acceptance gates](docs/implementation-plan.md)
- [Sensitive-action protocol foundation](docs/milestones/M2b2a-protocol-foundation.md)
- [SSE/presence protocol foundation and pending foreground integration](docs/milestones/M3c1-realtime-protocol.md)
- [Foreground session events, announcements and remaining room-consumer work](docs/milestones/M3c2-foreground-runtime.md)
- [Bounded canonical-history prerequisite for the realtime room consumer](docs/milestones/M3c3a-canonical-history.md)
- [Native realtime room/directory and presence consumer](docs/milestones/M3c3b-room-realtime.md)
- [Direct-message protocol prerequisite and remaining native UI work](docs/milestones/M4a-direct-protocol.md)
- [Bounded direct-message state prerequisite; native consumer follows](docs/milestones/M4b1-direct-state.md)
- [Pinned room composer and compact controls](docs/milestones/room-composer-send-target.md)
- [Native direct-message consumer and verification](docs/milestones/M4b2-native-direct-messages.md)
- [Account-wide typing privacy and remaining signal work](docs/milestones/M4c1-typing-privacy.md)
- [Foreground native typing and verification](docs/milestones/M4c2-foreground-typing.md)
- [Room-ignore protocol prerequisite](docs/milestones/M4c3a-ignore-protocol.md)
- [Native room-ignore controls and verification](docs/milestones/M4c3b-native-ignore.md)
- [Room-switch draft retention and explicit recovery](docs/milestones/room-draft-retention.md)
- [Notification HTTP/native-route prerequisite](docs/milestones/M4c4a-notification-contracts.md)
- [Exact-message lookup/state prerequisite](docs/milestones/M4c4b1-message-target-state.md)
- [Historical-boundary/scoped-callback corrections and target-adoption state](docs/milestones/M4c4b2a-target-adoption-state.md)
- [Native same-conversation reply-target navigation](docs/milestones/M4c4b2-native-reply-targets.md)
- [Scoped notification-center state prerequisite](docs/milestones/M4c4c1-notification-state.md)
- [Native notification list/read controls and remaining routing](docs/milestones/M4c4c2-native-notification-center.md)
- [Backend issues](docs/backend-dependencies.md)
- [Accepted decentralized push architecture](docs/push-architecture.md)
- [UnifiedPush and optional FCM fallback setup](docs/firebase-setup.md)
- [Build, test and release instructions](docs/build-test-release.md)
- [Connect the Claude PR reviewer](docs/claude-review-setup.md)
- [Current limitations](docs/known-limitations.md)

Begin setup with the build guide. Public application identity/distribution and ordinary-DM alerts remain open decisions; the recommended namespace is used only for development until confirmed. The supplied v3.2.1 test server passed opt-in password/session/logout and read-only room context/history/mutation/ping HTTP checks with a dedicated account in ignored, user-owned `.env.integration`. No live room send/read/presence/membership mutation was performed; this is not live native UI or end-to-end chat acceptance. A release signing identity has not been supplied. No Firebase identifiers or configuration are needed for the accepted push approach. [Delivery tracking](https://github.com/Thiesi/ChitChat-Mobile/issues/1) records milestone completion.
