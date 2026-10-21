# ChitChat Mobile

![ChitChat Mobile — Self-hosted browser chat on your phone](docs/assets/chitchat-banner.png)

ChitChat is a small, self-hosted browser chat application. ChitChat Mobile is its native mobile client.

The Android application uses Kotlin and Jetpack Compose with ChitChat's existing PHP sessions, CSRF protection, HTTP APIs and foreground Server-Sent Events. Background push will use UnifiedPush with the existing Web Push backend: prefer an installed distributor, with an optional embedded FCM distributor fallback. No publisher-operated relay or Firebase project is required.

## Current status

[Additional input-handoff observations](docs/milestones/native-ime-handoff-observations.md) investigate open issue31 after PR49's native CI passed136/137. All12 new Reply/Copy journeys passed, but that PR remains unmerged. This test-only work does not fix the keyboard or add parity; existing failures remain recorded.

**Native authentication, export and foreground room/DM conversations are implemented**, including typing privacy, room-ignore controls, scoped draft/unknown-delivery recovery and notification list/read/destination controls. Push, remaining parity and live acceptance are pending. Audited baseline: [ChitChat v3.2.1](https://github.com/Thiesi/ChitChat/releases/tag/v3.2.1). See milestone records for gates.

The [native notification center](docs/milestones/M4c4c2-native-notification-center.md) adds the account list, independent unread indicator and explicit individual/All read controls. [Native destinations](docs/milestones/M4c4d-native-notification-destinations.md) add room/DM historical-message and private-ping handoffs, normal room/peer links and Account (552 JVM/125 native tests locally). View and Mark read remain separate. Unsupported/foreign/guest-registration links stay inert; push and live acceptance remain pending.

The independent merged-main native run then failed 112/113 on the known focused-but-hidden keyboard condition. [IME diagnostics](docs/milestones/native-ime-diagnostics.md) records that failure and the added test-only window/filtered-CI evidence (local 531 JVM/115 native tests passed); issue31 remains open. A later green run is not a production keyboard fix.

Room and DM reply previews open the exact original as a marked historical window, keeping drafts and unknown-send guards. Missing/deleted/unauthorized targets are explicit; quote navigation does not mark newer messages read. Reply composition and search remain pending. See [reply-target scope](docs/milestones/M4c4b2-native-reply-targets.md).

[M5a1 reply protocol/state](docs/milestones/M5a1-reply-composer-state.md) prepares scoped reply drafts and existing send contracts. Native Reply/Copy controls follow separately; this prerequisite does not make reply creation usable yet.

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
