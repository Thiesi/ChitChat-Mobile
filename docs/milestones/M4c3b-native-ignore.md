# M4c3b: native room-ignore management

Native member controls consume the [M4c3a shared-session contract](M4c3a-ignore-protocol.md); no backend endpoint, event, identity directory or DM behavior is added. This is a client implementation with the verification below, not a live-server acceptance claim.

## User experience and confirmed state

Tap a room message's author to open a scrollable native dialog explaining the private, account-wide, **room-only** choice. The author action remains above collapsed messages, so Stop ignoring does not require revealing their content. The button has a conventional touch target and an accessibility label including author/time, not only a long-press gesture. System Back can dismiss the IME before navigating timeline authors; returning to composition retains the scoped draft. Self/unknown/deleted authors do not invent a target account.

The dialog reads confirmed session IDs: unknown is not empty and offers refresh, not a guessed toggle. An explicit desired Boolean prevents an old Ignore button reversing a choice already applied elsewhere. Progress, cooldown, unavailable account, last-confirmed state, unconfirmed outcomes and explicit session GET are distinct. Cancel warns that the server may already have applied the write. No automatic POST retry follows error, resume, focus, restored draft or reconciliation.

`IgnorePersonViewModel` scopes targets, callbacks and pending work to server/account/generation/version/room/person; target-reference fences also prevent old dialog closure retiring a successor. Names/intent stay in VM memory, never saved-state/disk/logs. Configuration recreation retains VM work; actual navigation/background retires it. Rendered dialogs independently verify current scope, rather than waiting for an asynchronous collector to clear stale state. Native guest access remains separate.

## A successful POST is not the complete workflow

Canonical room state adopts confirmed policy immediately, clears temporary reveals and filters private pings. Delayed history/window GETs overlay the **current scoped** policy after their last await; they cannot restore the policy captured before a change. Queue canonical invalidation behind ongoing operations without cancelling/replaying message writes; unignore re-fetches previously filtered pings while preserving draft and anchor. The existing room-directory foreground GET refreshes unread counts when returning to that screen. No fake local unread count or notification category is invented.

Room-author dialogs suppress viewport read proof and retire ephemeral typing selection. DMs retain their own blocking/availability rules and remain unaffected by room ignores. Existing mandatory privacy/retention disclosures are unchanged.

## Actual verification so far

- Initial dialog-state compile and7 focused JVM cases passed1m16s. Wired debug/testAPK +10 focused presentation/canonical-policy cases passed39s.
- Full390 JVM tests passed. The first6 native cases passed5/6: timeout reconciliation matched both modal and underlying Refresh session semantics, not a failed mutation. Original report: ignored `.tooling/m4c3b-refresh-modal-fixture-failure`. Matching the actual dialog-scoped action, all6 passed41s with unchanged production behavior/assertions/timeouts.
- Cases exercise ignore/unignore from collapsed authors, unknown IDs, timeout plus explicit GET/no replay, stale account callbacks, cancellation, real keyboard/draft round trip and read/typing suppression behind the dialog. Fixtures are `androidTest` only, not production fallbacks or a live server.

All6 actual landscape/font150% cases passed44s, with per-case configuration assertions (`.tooling/m4c3b-landscape6-pass`). An additional capture invocation passed7.085s (`OK (1 test)`): inspected the real-keyboard/visible Send and scrollable native person-dialog captures. After restoring portrait/font100%, all six final tasks passed6m28s:390 JVM/93 native, zero failures/errors/skips, debug/lint/release-shrinker/testAPK (`.tooling/m4c3b-final-390-93-pass`). XML totals were independently checked. Current-head Claude/CI remain delivery gates. Live two-account ignore/unread/notification effects, API26/physical devices/TalkBack, native guests, notification center, push and remaining parity are unperformed. No live calls or `.env.integration` reads. Issue31 remains open; CI-aligned regression passes do not prove the intermittent IME cause or general animated-device acceptance.
