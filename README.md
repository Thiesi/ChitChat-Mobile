# ChitChat Mobile

![ChitChat Mobile — Self-hosted browser chat on your phone](docs/assets/chitchat-banner.png)

ChitChat is a small, self-hosted browser chat application. ChitChat Mobile is its native mobile client.

The planned Android application uses Kotlin and Jetpack Compose with ChitChat's existing PHP sessions, CSRF protection, HTTP APIs and foreground Server-Sent Events. Firebase Cloud Messaging will provide background push once the required server integration exists.

## Current status

**Discovery complete; implementation has not started.** This repository does not yet contain a buildable Android project or APK. No Android build, emulator run, live chat or FCM end-to-end integration has passed. The audited backend baseline is [ChitChat v3.2.1](https://github.com/Thiesi/ChitChat/releases/tag/v3.2.1).

Core chat has existing APIs. Native passkeys, browser identity handoff, FCM device registration/delivery and reliable native capability discovery require the [backend dependencies](docs/backend-dependencies.md). Ordinary incoming-DM push is a separate product proposal. The ChitChat backend checkout remains read-only.

## Engineering documents

- [Discovery report and consequential decisions](docs/discovery.md)
- [Feature-parity matrix](docs/feature-parity.md)
- [Proposed architecture](docs/architecture.md)
- [Verified API contracts and compatibility](docs/backend-compatibility.md)
- [Implementation milestones and acceptance gates](docs/implementation-plan.md)
- [Backend issues](docs/backend-dependencies.md)
- [Firebase configuration and manual steps](docs/firebase-setup.md)
- [Build, test and release instructions](docs/build-test-release.md)
- [Current limitations](docs/known-limitations.md)

Begin setup with the build guide after the discovery decisions are resolved. Firebase identifiers, signing identity and integration server have not been supplied; no placeholder configuration is production-ready. [Delivery tracking](https://github.com/Thiesi/ChitChat-Mobile/issues/1) records milestone completion.
