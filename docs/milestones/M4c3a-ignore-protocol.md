# M4c3a: room-ignore protocol prerequisite

This historical buildable prerequisite adds the existing ignore mutation to shared HTTP/session policy. It deliberately did **not** expose incomplete native controls: the later [M4c3b consumer](M4c3b-native-ignore.md) supplies them. Room collapse/reveal, hidden pings and room-only typing filters consume confirmed policy. Direct-message blocking remains separate.

## Verified existing contract

Baseline: v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`. The backend checkout was inspected read-only, including the endpoint, service, API documentation, browser toggle/local handler and integration tests; the six evidence paths have no source diff from that baseline. Graph coverage matched their metadata at generation2026-10-17T18:56:58Z; the backend index was not refreshed. Android is unindexed, so relevant implementation was read directly.

- [`users/ignore.php`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/users/ignore.php): authenticated user-or-guest, CSRF, `POST {user_id:8,ignored:true}` with an actual Boolean. HTTP200 returns the complete authoritative `ignored_user_ids`, not a local toggle or profile. `session.php` also reports it.
- [`IgnoreService.php`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Account/IgnoreService.php): self-ignore400; ignoring a missing/closed account404; repeat ignore is idempotent; unignore deletes even if the target disappeared. No step-up or ignored-person notification.
- [`account.md`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/docs/api/account.md#ignoring-people-in-rooms), [`name-menu.js:230`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/assets/js/name-menu.js#L230) and [`app.js:169`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/assets/js/app.js#L169): private room-only presentation/attention. Collapse/reveal room messages, hide pings/room typing, suppress their room mentions/ping notifications and exclude their messages from room unread counts. DMs are unaffected.

No backend change or invented list/profile/event endpoint is needed. Native guest access remains its own unfinished slice; this prerequisite does not claim guest UI support.

## Shared-session behavior

`IgnoreOperations` binds a mutation to the caller's original server/account/generation/version. The same origin-bound cookie jar, CSRF, authorization, cooldown, persistence and invalidation logic are reused. Response lists must be present, non-null, positive and unique; publish the complete set while preserving mute/display policy. Unknown policy is not an empty list. Do not optimistically infer the requested state or log person IDs with content.

A runtime `policyChanging` gate now serializes both typing preference and ignore mutations against bootstrap, even if an unrelated request clears the presentation busy flag. There is no network wait under the session mutex. Runtime-fenced non-cancellable cleanup releases the gate; stale logout/replacement replies cannot restore policy. Failed/cancelled mutations preserve the **last confirmed** list, mark refresh required and require explicit ordinary session GET before another attempt. Cancellation also retires CSRF. No automatic POST replay is introduced, even though the server's set operation is idempotent.

## Native consumer follow-up (M4c3b)

The later [native consumer](M4c3b-native-ignore.md) implements the following requirements; its verification remains separate from this historical protocol-only slice.

Provide accessible room-author and collapsed-row actions with private/room-only disclosure and confirmed Ignore/Stop ignoring controls. Keep dialogs/callbacks scoped, expose unknown/loading/error/reconcile states, and suppress read/typing proof behind dialogs. Do not require a long-press or manual numeric-ID entry. Preserve names/state only in scoped VM memory, not saved state or logs.

Session policy publication alone is insufficient: room transcripts hold `context.policy` snapshots. Update canonical room contexts/reveals and private pings immediately; prevent a delayed history/context request restoring older ignore policy. Reconcile pings after unignore and refresh room unread counts without cancelling/replaying a message/membership write. Test unchanged DM/block semantics, stale account/dialog callbacks, actual landscape/font150% and real keyboard behavior. No native consumer is declared functional until these end-to-end client fixtures pass.

## Verification

Delivered in [PR35](https://github.com/Thiesi/ChitChat-Mobile/pull/35), squash `18e157ba2742e6754d6dfa38d880b4fd50657bf6`, reviewed head `9aecd3f7f6813950a48d315ef550496f423c22c2`; identical tree `3c1b903e21fa070fc7dd1596bfe3ea7f6900a0d6`. Actual clean reviews39459895342 and requested39460101411 delivered complete diff/comments with scoped runtime audits. Android39459895244 attempt1 failed SDK system-image ZIP installation before any emulator/test; its bounded failed-job-only attempt2 passed build/all87 native on the unchanged head. Separate merged-main39461842080 also passed build/all87 native. The original infrastructure failure was not counted as a test pass. Owned prerequisite worktree/branches were cleaned with build artifacts preserved; dependent UI work was retained and hash-verified across squash-base rebase.

Initial `:app:assembleDebug :app:testDebugUnitTest --tests '*SessionHttpTest' --tests '*SessionRepositoryTest'` passed1m41s. Added reverse-bootstrap/failure regressions then passed the full `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.expectSoftKeyboard=true` gate6m38s:380 JVM/87 API36 native, zero failures/errors/skips, actual debug/release-shrinker/testAPK and lint success. XML totals were checked independently and native reports preserved in ignored `.tooling/m4c3a-final-380-87-pass`. The dedicated emulator retained CI-aligned disabled system animations; no UI source changed or new native ignore journey is claimed. Actual current-head Claude review and CI remain delivery gates.

No live endpoint calls, `.env.integration` reads or server mutations. Live two-account ignore/unread/notification effects, native guest flows, API26/physical devices/TalkBack and push remain unperformed.
