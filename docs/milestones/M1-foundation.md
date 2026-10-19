# M1 native foundation evidence

Date: 2026-10-19. Implementation and all local automated gates pass; remote CI and Claude-review gates are not yet complete. M2 has not started. Backend source remains untouched.

## Implemented vertical slice

- Native Compose welcome/server form, change-server route, settings, Back navigation and forget confirmation. All screens clearly describe the local-only early preview; saving an address never claims a successful connection.
- Strict HTTPS root-origin validation/canonicalization, credentials/path/query/fragment rejection, default/custom ports and IPv6; local non-secret DataStore persistence; typed validation and storage error/retry state.
- Persisted System/Light/Dark appearance, edge-to-edge system bars, scrollable/IME-aware forms, saved input, minimum theme touch targets and accessible labels/error announcements.
- One module, small application container, ViewModel/Flow state and explicit repository interface. No production fakes, WebView, networking, session secrets or premature chat UI.
- Checked-in checksum-pinned Gradle wrapper, version catalog, debug identity suffix, shrunk unsigned release variant, cleartext prohibition and full backup/transfer exclusions.
- Credential-free Android CI definitions plus independent, credential-gated Claude review setup in [PR #3](https://github.com/Thiesi/ChitChat-Mobile/pull/3).

## Environment and actual checks

Windows PowerShell, JDK 17.0.20.1+1, Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21, Compose BOM 2026.06.01, Android platform 36 revision 2. Toolchain downloads are isolated under ignored `.tooling/`; no global environment or Windows feature changes. Emulator 37.2.12, API36 Google APIs x86_64 revision7, Pixel7 profile, WHPX acceleration verified.

This actual combined run passed with `BUILD SUCCESSFUL in 2m 39s` (130 actionable tasks):

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:connectedDebugAndroidTest --console=plain --max-workers=2
```

Eight unit tests, zero failures/skips; lint reports no issues; three connected tests, zero failures/skips; debug APK and R8-shrunk unsigned release APK produced. Debug APK installation and real launcher Activity startup also succeeded. The instrumentation runner removes test-installed packages at cleanup, so manual launch required installing the debug APK again.

Unit coverage: canonical HTTPS and IPv6/ports, invalid addresses and credential/path rejection, validation-before-write, ViewModel recreation with the same repository, storage-write error and read retry. The three passing device tests cover cleartext rejection/correction, save/theme/forget navigation, and actual DataStore persistence across repository instances. Device persistence tests restore prior debug settings in `finally`. Tests use Compose's current v2 test API; no claim of process-death, authentication or chat coverage.

Initial lint failed on newer-version notices and missing explicit Android12+ data-extraction rules. Explicit full exclusions now cover cloud and device transfer; only the three dependency-version suggestion checks are non-gating. All remaining lint warnings stay fatal. Initial test-APK compilation caught a missing test extension import, which was fixed and verified by the final successful run.

`actionlint` 1.7.12 passed on both workflow definitions. The official binary archive checksum was verified. The setup PR's actual hosted workflow reached and failed the missing-credential preflight as expected. No reviewer credential is configured; no completed Claude review or remote Android CI success has been claimed.

Manual screenshots/accessibility bounds were inspected for portrait, landscape at 150% font size and scrolling to the lower form controls: no overlapping form text or inaccessible lower controls observed. Initial inspection was obstructed by the emulator's **System UI** non-response dialog; it was dismissed and inspection repeated after stopping the build daemon. This is not a performance/device-reliability acceptance claim. TalkBack, baseline API26 and physical-device testing remain unperformed. Screenshots/logs stay in ignored `.tooling/ui-m1/`, not public artifacts containing real user data.

The additional software-keyboard screenshot command later returned exit1 without an image; that optional visual check is not counted as passed. Text entry/save/error correction are covered by the passing Compose journeys, not by a claimed manual IME acceptance. Emulator rotation/font/IME preferences were restored after inspection.

## Remaining gates

Hosted CI follow-up: the first Linux build failed only `OldTargetApi` because its SDK installation is newer than the local one. A scoped exception now records the deliberate M1 target36 policy and required later SDK/AGP/permission reassessment. The first hosted compact-emulator journey also exposed a real shared-scroll defect: the editor offset could carry into the saved-server page. Scroll state is now per destination, with the home page keyed by saved server, and Scaffold insets are consumed before IME insets. Both UI journeys now run in an explicit320x480dp viewport and scroll controls into view. The combined debug/release/unit/lint/three-device-test local follow-up passed (`BUILD SUCCESSFUL in 7m20s`); remote rerun remains required. The only subsequent Kotlin edit was indentation cleanup.

- Complete any further keyboard/accessibility/device checks appropriate to later feature screens; baseline API26/TalkBack acceptance is still outstanding.
- Push/open the M1 foundation PR and obtain real remote build/journey results.
- Maintainer connects one Claude credential securely; complete the review/fix/re-review loop before merge.
- Live server/password/chat and all push tests belong to later milestones and are unperformed. API26 device coverage, TalkBack and production signing/release acceptance remain future gates. No full application or end-to-end completion claim.
