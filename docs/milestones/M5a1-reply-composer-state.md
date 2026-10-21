# M5a1: reply protocol and scoped composer state

This independently buildable prerequisite prepares room and DM reply composition. It does **not** expose a Reply button, preview banner, Copy action or reply-draft manager UI. Native consumers are preserved separately and follow immediately after this slice's verification/review/merge. The combined implementation exceeded the review workflow's complete-input limit; splitting it keeps both reviews complete without dropping tests or increasing reviewer permissions.

## Existing backend contracts

The pinned v3.2.1 [room send](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/rooms/send.php) and [DM send](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/direct-messages/send.php) accept optional positive `reply_to_message_id`. The room service restricts references to the room; the DM service restricts them to the participant pair. Missing/wrong-scope references fail with `invalid_reply_target`; an unavailable/deleted quote is not permission to downgrade a send into an ordinary message. Existing session cookies, CSRF, authorization, rate limits and HTTP errors remain authoritative. No backend change or new issue is required.

Room operations now pass the existing field through the same session transport. Both ordinary room and DM acknowledgments must identify the requested quote; mismatched/missing echoes fail closed. Confirmed DM IDs still return before a fallible enrichment GET. Room `/ping` deliberately ignores the quote, matching the browser/server: reuse PHP-trim/exact-command classification rather than treating DM slash text as a command. No POST is automatically replayed and invalid references never silently fall back to a null quote.

## State and security

`ComposerReply` stores only a positive message ID. Its instance identity prevents a stale Cancel callback from clearing a newer selection of the same ID. It does not store quoted bodies, authors, Android views or saved-instance state. Room and DM draft memos retain this reference in existing server/account/generation-scoped VM memory; reply-only drafts count toward the 100-slot limit, never evict another draft, and clear on explicit discard, acknowledged send or account retirement. Process death still loses drafts.

Selection requires the original current canonical row reference, active owner/resource, fresh authorized pane and existing send eligibility. Room ignore/reveal rules apply only to rooms; blocked DM history remains usable for explicit Copy but cannot become a new reply. A full draft table disables new reply selection too. Pending sends freeze selection/removal. Cancellation removes only the chosen reference and its specific `invalid_reply_target` error, not text, uncertain-delivery state, staleness or reconciliation/duplicate-risk requirements. Target navigation preserves the reference without granting read or resend consent. New selection/removal performs no network request.

These are tested integration points, not usable native reply creation yet. Existing screens continue ordinary composition until the dependent consumer ships; in-app feature-availability copy remains honestly unchanged. No clipboard, notification, background service, transport, dependency, durable cache, sensitive log or additional server capability is introduced here.

## Verification and remaining gates

The preserved combined candidate passed debug, all 579 JVM tests (zero failures/errors/skips), lint and test-APK compilation. Those 579 include two consumer excerpt tests absent from this prerequisite. Compilation of new consumer tests was not a device run or live acceptance.

During incremental development a missing import caused a compile failure, then a new capacity test exposed DM reply selection bypassing `canEditDraft`; the guard and regression test were corrected before splitting. A later keep-index stash saved all source but failed its untracked-path cleanup. Work stopped; all 11 consumer source blobs and independent SHA256 backups were verified, and the prerequisite's one missing fake signature was restored explicitly. No source was discarded, no test predicate weakened and no failed result is counted as a pass.

On 2026-10-21 the isolated full gate passed in 30m35s: `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=2 --console=plain`. Independent XML checks record 577 JVM cases and zero failures/errors/skips; lint reports zero issues. Release R8 and packaging completed. Complete reports/results and independently hash-matched debug, unsigned release and test APKs are preserved outside the worktree. All ten credential-free Python diagnostic tests also passed.

The first unfiltered native attempt was aborted after 26/125 tests (six reported failures, incomplete instrumentation): five window-focus timeouts plus the interrupted final test. Passive window metadata and screenshots showed unrelated Digital Wellbeing and Android System UI ANR dialogs owning focus. Full original reports/logs/screenshots are preserved; this is a failed/aborted run, not a native pass. The dedicated emulator was cleanly restarted without wiping data, disabling packages, forcing application focus or changing test assertions/timeouts. The fresh full gate required actual healthy launcher focus/boot readiness, portrait/font1.0, CI-aligned disabled animations and strict real-keyboard assertions.

That fresh unfiltered `:app:connectedDebugAndroidTest` run passed in 10m19s with `expectSoftKeyboard=true`, `expectedOrientation=1` and `expectedFontScale=1.0`. Independent XML checks record all 125 native journeys and zero failures/errors/skips. Complete successful reports/results are preserved separately from the first failure; all three APK hashes still match the build archive. No individual test was retried or filtered. This environment recovery does not establish a production keyboard fix; issue31 remains open.

Exact-head CI, completed initial Claude review plus explicitly requested fresh review, worthwhile finding resolution and guarded merge remain mandatory.

Native reply/Copy/keyboard/landscape/accessibility acceptance belongs to the next slice. No `.env.integration` read or live call is made here; dedicated fixture preparation is not successful live chat acceptance. Push, remaining M5 tools/media/search, API26/physical-device/TalkBack and release acceptance remain separate.
