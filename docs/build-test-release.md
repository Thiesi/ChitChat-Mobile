# Build, test and release

Current status: **M1 foundation in progress**. No Android build/test command below has passed yet; these are M1 and later gates. Keep this status synchronized with the actual milestone evidence. See [milestones](implementation-plan.md).

## Toolchain setup in M1

Install/locate Android Studio or Android command-line tools, the JDK required by the selected stable Android Gradle Plugin, matching SDK platform/build tools and an emulator/device. Pin AGP/Kotlin/Compose/Gradle compatible versions after consulting their official release/compatibility documentation. Check in the Gradle wrapper including JAR/checksum and a version catalog. Keep machine `local.properties`, SDK paths, Firebase config and signing keys out of Git.

The inspected discovery shell had no `java`, `gradle` or `adb` on PATH, no Android/JAVA environment variables, and no SDK/Studio in the inspected standard locations. We have not attempted a build or claimed that a missing tool is installed. Android SDK license acceptance and actual downloads may require maintainer/environment support; report any specific inability at the M1 gate rather than accumulating unbuildable production code.

## Planned local checks

After M1 creates actual variants, the credential-free check set will be equivalent to:

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Keep this file synchronized with actual tasks as M1 establishes them. Emulator/device checks require a running supported device; skipped instrumented tests are never counted as passed. Neither the installed-distributor path nor optional embedded FCM fallback needs `google-services.json` or a Firebase project. The fallback is UnifiedPush's Web Push path, not Firebase SDK token registration. Release signing is separate from debug builds and awaits confirmed public identity/distribution.

## Automated coverage and CI

M1 adds reliable credential-free GitHub checks: wrapper validation, build, unit tests and Android lint; add an emulator job for important Compose flows once stable. Use least-privilege workflow permissions, pinned action revisions with documented update policy and Gradle dependency verification as appropriate. Public CI never authenticates to a real ChitChat account or embeds Firebase/service/signing secrets. Test-only API/SSE fakes belong to test source sets.

| Area | Meaningful cases |
|---|---|
| Session/auth | Cookie and CSRF rotations, 202 pending MFA, invalidated version, restored process, source-IP MFA failure, anonymous/guest expiry, 401 vs 403, logout clearing and late-response isolation |
| Network/DTO | Actual payload union/envelopes, unknown optional fields/events, missing security fields, invalid JSON, 429/503, relative-route/redirect origin boundaries, no secret logging |
| Chat/cache | Room vs DM ID namespaces, oldest-to-newest pages, stable prepend anchors, send+SSE convergence, edit replay not new unread, deletion metadata, retained/unauthorized cache purging |
| Realtime | Normal 25-second EOF, heartbeat frames, retry hints, duplicate/out-of-order/expired events, reconnect/background cancellation, both forced-logout shapes, stale snapshot races |
| Offline writes | Draft retention, delivery-unknown send/upload, no unsafe automatic retry, user-authorized resend and unchanged text not mistaken for receipt |
| Policy/authorization | Private/unlisted/age/invitation rules, owner invariants, generic DM blocking, ignore vs block, mute/guest limits, scoped moderation evidence and reason/step-up |
| Notifications | Endpoint/key rotation/register failure, stale connector/job generations, offline logout/key destruction, duplicate/unknown/wrong-instance payloads, immutable tap routing, denied permissions/channels, installed distributors without GMS and optional embedded fallback compatibility |
| Compose journeys | Server -> sign-in -> room/DM, IME/multiline input, loading/empty/offline/errors, list/detail/back/rotation, attachment selection/share, search target, permission loss, accessible contextual actions |

Run focused tests per change and all required milestone checks before merge. Capture exact command/result/count and environment. Mock contract tests are not a real server integration; compilation is not a UX review. Do not add trivial tests merely mirroring layout constants.

## Manual/device acceptance

Use two accounts and representative staff/guest roles on a compatible non-production server. Exercise real room/DM/history/reconnect/block/edit/files/search/report/account/admin flows, password and configured MFA/provider paths, privacy disclosures and forced invalidation. Device review covers portrait/landscape, compact/expanded windows, font scaling, TalkBack, contrast, keyboard/focus, slow/intermittent network and app process death. Push acceptance needs two distributors and the optional embedded fallback as defined in [setup](firebase-setup.md#end-to-end-manual-test-once-implemented).

## Release gate

Before calling a build production-ready: all applicable parity rows completed or genuine blockers explicitly disclosed, full required CI green, release/shrinker build and install passed, real core-chat acceptance recorded, backend-dependent gaps and notification limitations clear. Produce signed APK and optionally AAB with maintainer-controlled keys after public identity/distribution is confirmed; never invent an approved production signing identity. Document actual SDK requirements, backend compatibility, application ID and privacy/data safety disclosures. Public alpha cannot claim complete native MFA or push while their relevant acceptance is outstanding.

When publication is authorized, verify the remote commit/tag/release, asset hash/signature and a freshly downloaded installation artifact. A local build or merged PR is not published-release verification. No release or Play deployment has been performed in discovery.
