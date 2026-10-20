# Room-switch draft retention and explicit recovery

[Issue37](https://github.com/Thiesi/ChitChat-Mobile/issues/37) was found while investigating notification navigation. Replacing the single room state discarded both unsent text and an unconfirmed-send guard. Two independent regression assertions failed against unchanged production code before this fix. This client-only prerequisite adds no backend endpoint/event, persistent cache, send idempotency or notification feature.

## Native behavior

Rooms retain their own text and delivery-unknown flag in account/server/session-generation/version-scoped ViewModel memory. Returning through ordinary navigation or Saved room drafts restores the correct room's text, never another room's input. Restored focus/text does not publish typing. Acknowledged sends clear only their own slot; a cancelled, non-cooperative late send cannot clear the successor's text or forget the old room's uncertainty.

Automatic entry/resume GETs do not grant resend consent. Unknown delivery survives switching; explicitly refresh history, then explicitly accept duplicate risk before another Send. No POST is replayed. Discard removes local text/unknown state only, with an explanation that it cannot retract an already accepted message.

Saved room drafts is available from the directory and pinned Room details, even when a room disappears or access is denied. The scrollable native manager shows room/preview/unknown-delivery state and explicit Open/Discard actions. At100 slots a new room's composer is disabled with reachable recovery guidance; nothing is evicted. Discard frees capacity. Modal presentation suppresses timeline read proof and typing selection.

Entries and callbacks carry their original scope; discard additionally binds the original entry reference. Rendered callbacks freeze session values instead of reading a delegated Flow after replacement. UI independently rejects old entries before asynchronous clearing. Logout/account replacement clears everything. Nothing is written to saved-instance state, disk, intents or logs. Process death intentionally loses these memory-only drafts; guest visitation remains a separate slice.

## Verification record

Two original regressions failed independently (empty restored text and forgotten unknown guard); raw ignored reports are `.tooling/room-draft-red-text` and `.tooling/room-draft-red-guards`. Ten JVM cases now cover switching, unknown consent, late cancellation,100-slot no-eviction/discard, original account/room callbacks, acknowledgment, stable discard references and passive resume. The focused state/realtime/ignore gate passed49 cases. Full JVM XML totals:400 tests, zero failures/errors/skips; debug and test APK compilation passed.

Four root-navigation native fixtures exercise restoration through the real directory/room details/manager with one-tap native IME, explicit uncertain-send recovery, capacity/discard, and modal read/typing plus sign-out isolation. The initial run passed3/4; the capacity assertion matched both modal and underlying warning. The selector was scoped to the dialog without changing production behavior, timeout or assertion; original reports are `.tooling/room-draft-capacity-selector-failure`.

The first six-task gate passed all97 native tests but failed lint on three composition-time `StateFlow.value` reads; it is not a successful full/release gate. Reports are preserved in `.tooling/room-draft-lint-failed-400-97-pass`. Rendering now uses observed Compose state, while action callbacks still verify the actual runtime and original account/entry; no lint suppression or baseline was added. An earlier missing `Box` import also failed test-APK compilation and was corrected before the successful compile.

All six tasks passed9m51s: debug/testAPK,400 JVM, lint, release/R8 and97 native, zero failures/errors/skips with independently checked XML totals (`.tooling/room-draft-full-400-97-pass`). The successful-send native fixture was then tightened to persist an outgoing message from the signed-in user and assert its ID/body/ownership after reconciliation; the four affected portrait journeys plus lint passed1m40s. The room-directory copy was also corrected: foreground SSE and active presence are no longer described as unimplemented.

On that final source, debug/testAPK/JVM/lint/release and all four actual landscape/font150% journeys passed2m24s, with per-case configuration assertions (`.tooling/room-draft-final-landscape4-pass`). This invocation ran4 native cases, not97. A separate matching-APK capture run passed22.733s (`OK (2 tests)`); inspected `.tooling/room-draft-final-keyboard-landscape150.png` and `...manager-landscape150.png` for visible keyboard/Send and scrollable Open/Discard actions. Restored portrait/font100%. Exact-head full CI and completed Claude/re-review remain mandatory delivery gates, with results recorded on the delivery PR.

No live endpoints, account/content/read/typing writes or `.env.integration` reads were used. API26, physical devices, TalkBack, live two-account delivery, push and notification routing are unperformed. Issue31 remains open: CI-aligned emulator passes do not prove its intermittent IME cause or animated-device acceptance.
