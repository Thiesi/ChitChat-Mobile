# Build, test and release

Current status: M1/M2a/M2b1 merged; M2b2 implements shared step-up and native personal-data export. [Current evidence](milestones/M2b-step-up-export.md) records actual gates and remaining acceptance; historical authentication evidence is in [M2a](milestones/M2a-session-auth.md). Historical M1 results remain in [PR #4](https://github.com/Thiesi/ChitChat-Mobile/pull/4). Compilation is not live-server or review acceptance. See [milestones](implementation-plan.md).

## Toolchain setup

Install Android Studio or Android command-line tools, **JDK17**, Android SDK platform **36** and an API26+ device/emulator. Accept the Android SDK licenses. AGP uses build-tools35.0.0 by default and can install it when licenses allow. Set `JAVA_HOME` and `ANDROID_HOME` to your own installation paths, or use Android Studio's SDK location/local `local.properties`. The wrapper downloads checksum-verified **Gradle8.13**. AGP8.13.2, Kotlin2.3.21 and Compose BOM2026.06.01 are pinned in `gradle/libs.versions.toml`; compile/target SDK36, minimum26. These are a deliberately compatible stable set, not a claim of newest-available versions. Review upgrades together and rerun all gates. Keep `local.properties`, SDK paths and signing keys out of Git.

For the agent validation environment, the JDK, SDK, Gradle cache and emulator were downloaded under ignored `.tooling/` and configured only per shell. They are not checked in and do not exist in a fresh clone. No Firebase project or `google-services.json` is needed. Windows restricted-profile execution can prevent adb finding its user directory; local connected tests used the approved unsandboxed runner, not weakened application security.

## Local checks

From the repository root with JDK/SDK configured:

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest
```

On Linux/macOS use `./gradlew` instead. Connected tests require an online supported device (`adb devices`); skipped tests never count as passed. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, development identity `io.github.thiesi.chitchat.debug`; install with `adb install -r` and launch normally. M2a contacts the chosen server only after its address is saved, using verified HTTPS. Unit/lint reports are under `app/build/reports/`; connected reports under `app/build/reports/androidTests/connected/debug/`. Release is shrunk but unsigned, not a distributable artifact until separately signed. Public identity/distribution/signing remain unconfirmed.

## Opt-in live authentication test

Ordinary `testDebugUnitTest` and CI entirely exclude `LiveSessionTest`. There is no hidden external-server test or automatic credential-file read during normal builds. To exercise the existing real password/session contracts explicitly:

1. Provision a dedicated **password-only, non-admin, non-production** test account. This test must not consume a person's MFA recovery codes. Never put production credentials in this repository or chat.
2. Create user-owned `.env.integration` at the repository root with `CHITCHAT_TEST_SERVER` (the test HTTPS origin), `CHITCHAT_TEST_USERNAME`, and `CHITCHAT_TEST_PASSWORD`, one `KEY=value` per line. Matching surrounding single/double quotes are supported; there is no shell expansion/evaluation. All three values must be nonempty. Keep values/whitespace inside a password as intended. Verify `git check-ignore .env.integration`; never stage it.
3. Run `.\gradlew.bat :app:liveSessionTest --console=plain --max-workers=2` (or `./gradlew`). Do not enable HTTP body logging, Gradle debug logging, or print the configuration. The test loads values directly; secrets are not Gradle task inputs or command-line arguments. The task is deliberately non-cacheable and reruns every invocation.
4. Inspect `app/build/reports/tests/liveSessionTest/`. The test signs in, refreshes, recreates the repository, signs out and attempts reuse of the now-logged-out cookie. It creates no account and changes no password; the server will record its normal authentication audit entries. Cleanup attempts server logout even after failure.

This is an actual JVM HTTP/server integration check using transient in-memory credential storage, not an Android Keystore/process-death/UI acceptance claim. Android encryption and forms have separate instrumented tests. Live results must be recorded separately from fixture counts. The supplied test URL and credentials are not baked into the application or public CI.

## Automated coverage and CI

`.github/workflows/android.yml` defines credential-free JDK17 build/unit/lint/release checks and an API36 Google APIs emulator journey job. Gradle setup validates wrapper JARs. Reports and the development APK are uploaded as artifacts. Workflows have read-only repository access and pinned action revisions; review upstream releases/security changes deliberately before updating pins and rerun gates. Public CI never authenticates to a real ChitChat account or embeds Firebase/service/signing secrets. Test-only fakes belong to test source sets. Separate [Claude review setup](claude-review-setup.md) needs a maintainer-supplied Actions secret and is not required to compile the app.

Lint warnings are fatal except `AndroidGradlePluginVersion`, `GradleDependency` and `NewerVersionAvailable`: these are newer-version suggestions, not code/security findings. They are excluded explicitly in `app/lint.xml` so unrelated online version changes cannot break a pinned build. This is not a general lint baseline or security-warning suppression.

The intentional M1 `targetSdk=36` declaration also has a narrowly scoped `OldTargetApi` exception in `app/lint.xml`: runners with newer SDKs otherwise disagree with the tested local SDK36 installation. API36 meets the [current 2026 Play submission floor](https://developer.android.com/google/play/requirements/target-sdk); [AGP8.13 supports at most36.1](https://developer.android.com/build/releases/agp-8-13-0-release-notes). This is not a production release waiver. Review the complete SDK/AGP/Kotlin set, current target requirements and Android17 local-network/privacy permissions before network/release acceptance. No other code/security lint rule is disabled.

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
