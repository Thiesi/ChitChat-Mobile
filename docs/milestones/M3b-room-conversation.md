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

No live message has been sent and no live room read cursor changed as part of this milestone without separate approval. Fixture delivery is not end-to-end chat acceptance. Push remains unimplemented under the accepted decentralized UnifiedPush architecture. M3 and the full application remain incomplete.

## Verification record

`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:liveSessionTest --console=plain --max-workers=2` passed in23s:184 fixture units and one opt-in live case, all zero failures/errors/skips, clean lint and debug/test APKs. The live case exercised auth/restoration/logout and only directory/context/history/mutation/ping GETs; no room writes. The first full API36 emulator run completed52 journeys with51 passes; the new navigation test waited for an off-screen home button at320dp. It now waits for authenticated state then uses the existing scroll-to interaction; its focused rerun passed in25s. All eight conversation journeys, including the30-line acknowledged-send/live-edge check, passed in that first run. A complete rerun and shrunk unsigned release/isolated foundation gates are still pending, not inferred from those focused results. Exact-head hosted/Claude evidence belongs on the PRs before merge.

An initial combined run stopped before compilation because an old generated resource directory denied deletion on Windows. Its generated `app/build` outputs were moved recoverably to ignored `.tooling/m3b-prior-build-preserved`, without ACL changes or source removal; that failed run is not a successful gate. Subsequent compiling runs found and fixed two Compose scope errors and observable-locale lint before the successful command above.
