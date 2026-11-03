# M5c1: scoped reaction protocol

This independently buildable prerequisite adds typed room/DM reaction writes to the existing session transport. It does **not** add a native picker, reaction chips, mutation actor, or guest consumer. Native reactions remain unavailable; remaining M5 tools and live acceptance stay separate. Start from merged-main `c84eed1` (PR59), preserving the independent-main native failure recorded there and the separate unmerged diagnostic PRs. The maintainer subsequently merged PR66 and approved the [development-only Linux CI gate policy](../build-test-release.md#development-merge-gates); historical failures remain failed.

## Existing backend contract

Verified against ChitChat v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`, using 13 SHA-verified source copies obtained read-only from GitHub. The sibling backend checkout was not modified or indexed. No new endpoint, event, authentication flow, dependency, or backend patch is required.

| Choice | Room endpoint | DM endpoint |
|---|---|---|
| Explicit add | `POST /api/v1/rooms/react.php` | `POST /api/v1/direct-messages/react.php` |
| Explicit remove | `POST /api/v1/rooms/unreact.php` | `POST /api/v1/direct-messages/unreact.php` |

All four use the original PHP cookie/CSRF session and `{message_id, emoji}`, returning HTTP200 `{reactions:[...]}`. The response has no target ID, namespace, revision, or separate count. The fixed vocabulary is exactly 👍 ❤️ 😂 😮 😢 🎉; the heart includes U+FE0F. Every group contains `emoji`, `users:[{id,username}]`, and an actual Boolean `reacted_by_me`. Counts come from the user list. Preserve all returned reactor identities and the complete summary; do not synthesize the requested choice if another client already changed it.

Room history/minimum-age authorization and DM participation stay server-owned. Adding rejects deleted messages and enforces the applicable mute policy; guest room adds also require guest posting permission. Removing only deletes the actor's own matching row and does not reuse add's deleted/mute restriction. Reactions are not author-only. Existing DM history remains reactable after a block; do not reuse new-message availability, author edit hints, or room-ignore policy as a DM reaction gate.

Sources: [vocabulary](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Reactions/ReactionVocabulary.php), [hydrator](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Reactions/ReactionHydrator.php), [room service](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Reactions/RoomReactionService.php), [DM service](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Reactions/DirectMessageReactionService.php), [ADR0005](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/docs/architecture/0005-reactions.md).

## Android boundary

`SessionMessageReactionRepository` takes the original rendered `SessionState` and original canonical row, obtains existing `SessionAccess`, and uses `authorized` for generation/identity/token checks before and after the request. Direct writes require the existing discovered privacy policy. No bootstrap, relationship GET, extra cookie jar, send/read acknowledgment, or implicit retry is added. Positive room/message identity and actual DM participation/direction are checked locally; current resource authorization remains server-owned. Known deleted additions fail locally, while an explicit removal may use a retained tombstone.

The HTTP reply is bounded to the ordinary1MiB/25-second limits. Groups must use the fixed vocabulary, distinct emoji and positive/distinct reactor IDs with names. Empty overall summaries are valid; empty groups and malformed/quoted Boolean fields fail closed. The shared reaction DTO now uses the existing strict-Boolean serializer, including on history/event paths; actual backend Booleans remain unchanged. Selection is derived from the viewer's presence in `users`, including for shared actor-perspective room event data; do not trust another actor's `reacted_by_me` flag. History/SSE invalidation and canonical GET algorithms are unchanged.

Database uniqueness makes repeated row operations idempotent, but both services mutate **before** fallible summary/event publication. Any exception/cancellation can follow commit. The protocol never replays or silently GETs after a POST. A future consumer must serialize with its feature's existing writes, retain only scoped ID/emoji/desired-choice uncertainty, and require an explicit GET/current-state review before a fresh user choice. Automatic entry/event refresh is not consent. Bind callbacks, response application, and cancellation cleanup to the original account/generation/room-or-peer/navigation-entry/request/canonical row; do not apply a late list to a replacement row with the same numeric ID.

Reactions are independent of SEND/READ/BLOCK and author-change consent. No message body, saved-state/disk content, new background queue, or replay timer is introduced. No proposal or uncertainty UI is claimed by these transport methods.

## Native consumer and acceptance

The separately reviewable native slice must add accessible fixed choices, counts/reactor labels, current-viewer selection, canonical response application, explicit unknown-outcome recovery, lifecycle/entry fences, and strict native layout/input journeys. Preserve existing deleted-row reaction data. ADR0005's older hide-on-deletion wording and the later browser room/DM hydration behavior differ; this transport's ability to remove on tombstones does not settle the native deleted-row presentation. Resolve against actual canonical/mutation behavior before choosing that UI.

This slice requires focused TLS/session protocol tests, full JVM tests, debug/release/test-APK builds, lint, unfiltered CI, and completed initial/fresh exact-head Claude reviews. Native UI, device/TalkBack/API26, guest and live reaction acceptance remain separate. The PR59 independent-main failure and all diagnostic negatives remain failed; the approved development policy changes only their platform-only blocking effect, with no keyboard correction or release claim.

## Verification

The corrected focused command `:app:testDebugUnitTest --tests '*MessageReactionProtocolTest'` passed all 24 cases. Full `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --no-build-cache --no-daemon --max-workers=2 --console=plain` passed in 11m09s: 672 executed JVM tests, zero failures/errors/skips, lint0, and executed release R8. Debug/release/test APKs were hashed and the app/test signing certificates match, using the original Android user home and keystore. The unchanged diagnostic-wrapper suite passed 10 tests. These are fixture/build results, not live reaction acceptance.

The first focused run passed23/24 and exposed quoted-Boolean acceptance in the existing shared reaction decoder. Its failed report/source/log are retained; the correction uses the existing strict serializer without weakening the test. No native input/test helper was changed.

The one explicitly maintainer-authorized unfiltered Windows/API36 run completed: **70 passed, 102 failed, zero skipped, all172 distinct cases** (1612.32s, exit1). It recorded83 missing-root-focus exceptions,18 Compose timeouts and one typing-status assertion failure. Its reports, original APKs and failed outcome remain retained in the SHA-verified evidence archive; no replay occurred. The PR66 standalone comparison passed once per declared condition and did not establish a cause or correction. Following explicit maintainer approval, these unexplained Windows discrepancies no longer alone block development delivery; new current-head Linux CI, builds and exact-head initial/fresh reviews remain required after rebasing onto PR66. Native controls and live/device/release acceptance are still pending.
