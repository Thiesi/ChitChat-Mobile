# M3b: native room history, composition and read state

The Rooms detail sheet now opens a native Compose conversation when the existing visibility/membership/global-role policy permits history. The server still enforces history, age, membership, guest and mute rules. M3b consumes the independently buildable [conversation protocol](M3b1-conversation-protocol.md). No new backend route, dependency, authentication mechanism or persistent message cache is introduced.

## Implemented behavior

- Ascending room history with older-ID pagination, stable message keys and first-visible ID/pixel anchor. The bounded500-message memory window never joins unrelated history ranges. Jump to latest explicitly reconciles the newest page; ordinary refresh preserves a surviving anchor or announces a reset if it no longer exists.
- Multiline native composer, explicit send, keyboard insets and server-equivalent code-point validation for text, `/me` and `/ping`. Only acknowledged message IDs enter the transcript. Private pings have a separate labeled, bounded history dialog, not fabricated public messages.
- Ambiguous sends preserve the draft, block replay and require an explicit GET reconciliation followed by duplicate-risk consent. Matching message text is never proof of delivery. Cancel/background/navigation retire requests; account/server/generation changes clear all private UI state.
- Current edit/delete metadata, unavailable replies, plain attachment labels, reaction counts and timestamps. Tombstones do not expose stale body/reply/file details and retain server reactions. Reply/edit/delete/react controls, rich formatting, attachment bytes/previews/download/share and emoji picker remain M5.
- Existing ignored senders collapse with explicit temporary reveal; ignored pings remain hidden. Fresh bootstrap policy is mandatory. Global/room mute reason/expiry, nonmembership and guest read-only restrictions have visible explanations. Account date locale/hour cycle are honored with device timezone; account preference editing remains M6.
- Server unread divider and foreground, visible-live-edge read acknowledgment only for members. A directory visit, background view, covered ping dialog or old history window cannot mark unseen content read. Failed read acknowledgment has an explicit retry and never loops automatically.
- Refresh/retry/cancel, empty/loading/stale/error/access-lost states and room switching. Small-screen/large-font controls scroll within bounded regions; landscape, configuration recreation and account transitions retain or clear state appropriately.

## Explicitly incomplete

This screen is labeled **manual refresh**. It has no SSE, periodic polling, typing, presence lease or inactivity management; those are M3c/M4, not simulated by a background service. Permission/mute/ignore changes are revalidated on entry/resume/refresh or rejected by the server, not instantly received. Guest-shaped read-only policy is handled defensively, but guest entry is not implemented until M7. Expanded two-pane navigation and manual TalkBack/API26/device acceptance remain later gates.

No live message was sent and no live room read cursor changed as part of this milestone. Live mutations require separate approval and a dedicated room. Fixture delivery is not end-to-end chat acceptance. Push remains unimplemented under the accepted decentralized UnifiedPush architecture. M3 and the full application remain incomplete.

## Verification record

Final corrected consumer source (`5d37fb0`, before documentation-only handoff edits) passed the following local gates. All reported tests had zero failures/errors/skips:

| Gate | Actual result |
|---|---|
| `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:liveSessionTest --console=plain --max-workers=2` |1m33s,130 tasks:186 fixture units, clean lint, debug/test APKs, shrunk unsigned release and one opt-in live auth/restoration/logout plus read-only directory/context/history/mutation/ping case |
| `:app:connectedDebugAndroidTest --console=plain --max-workers=2` |53 complete native journeys on the owned API36 emulator at320×640dp, font100%;4m19s. Includes actual app room navigation/logout clearing and nine conversation journeys |
| Same connected task with `-Pandroid.testInstrumentationRunnerArguments.class=io.github.thiesi.chitchat.feature.conversation.ConversationJourneyTest` |All nine conversation journeys passed again at640×320dp, font150%;49s. This short landscape-shaped viewport is an automated stress check, not manual rotation/TalkBack/device acceptance |
| Isolated protocol/presentation prerequisite |Separate full debug/unit/lint/release/test-APK command passed with186 units; see [exact evidence](M3b1-conversation-protocol.md). Its emulator suite is hosted separately, not inferred from consumer tests |

Claude's tall-message finding did not reproduce: the visibility predicate permits a negative top offset and checks the message bottom/live edge, not whole-message fit. Both initial30-line history and acknowledged30-line send reach the actual read cursor without a fabricated message or extra send. The new history regression passed in both viewport/font configurations. The prerequisite's real read-CSRF finding was fixed with two regressions: preserve authorized history/context/pings/draft, no automatic replay, explicit retry after session refresh, and continued clearing on genuine resource denial.

The first52-journey run passed51; the new app-navigation test waited for an off-screen home button. Waiting for authenticated state then scrolling to that button fixed the fixture interaction; its focused pass was followed by complete52 and53 passes, not substituted for them. An early short-viewport/font run passed7/9; two ping-dialog assertions now scroll to below-fold content in the existing scrollable dialog, preserving privacy assertions. The final corrected-head9/9 result above includes those interactions.

Use [PR #13](https://github.com/Thiesi/ChitChat-Mobile/pull/13) and [PR #14](https://github.com/Thiesi/ChitChat-Mobile/pull/14) for exact-head hosted CI, full-input Claude reviews, triage and fresh-review evidence; those remain independent mandatory merge gates. The prerequisite merged only after corrected-head build/journey checks and two actual clean reviews. Retargeting the dependent PR preserved its remote head and complete-diff SHA256; merged main matched the reviewed prerequisite tree. Local release APKs are unsigned; supplied native libraries were packaged unchanged when symbol stripping was unavailable. No signed/public release or live message-send acceptance is claimed.

An initial combined run stopped before compilation because an old generated resource directory denied deletion on Windows. Its generated `app/build` outputs were moved recoverably to ignored `.tooling/m3b-prior-build-preserved`, without ACL changes or source removal; that failed run is not a successful gate. Subsequent compiling runs found and fixed two Compose scope errors and observable-locale lint before the successful command above.

## Artifact QA and availability-copy follow-up

Post-merge main `8fbd5a7` passed [hosted build/journeys](https://github.com/Thiesi/ChitChat-Mobile/actions/runs/39364408436). Its downloaded development APK verified with Android Debug signing, package `io.github.thiesi.chitchat.debug`, version0.1.0-dev, min26/target36; it installed and cold-launched on the owned API36 emulator. The native first-launch screen was visually checked; screenshots/XML remain ignored test artifacts. This was not live-account/native-chat acceptance or production signing.

Final QA found the authenticated home still said room screens would arrive later. The copy-only follow-up now directs users to Rooms while keeping DMs explicitly unavailable; the existing room-navigation journey asserts that message before opening Rooms. Debug/unit/lint/unsigned-release/test-APK command passed in20s (129 tasks,31 executed,2 cached,96 up-to-date); the unchanged186-unit task reused its prior passing result. The affected native journey passed separately at320×640dp in25s. Complete hosted suites and fresh full-input Claude reviews remain independent merge gates. No API/session/authorization behavior or backend source changed; agent guidance now includes rechecking older in-app availability disclosures when a feature ships.
