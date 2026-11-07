# M5c3b: native direct-message reactions

Consumes delivered [M5c3a DM state](M5c3a-direct-reaction-state.md), PR71/main `2cba7cc`, and the unchanged [M5c1 protocol](M5c1-reaction-protocol.md). Authenticated members can open DM reactions from message actions or retained chips, choose among six named emoji, inspect complete people lists and recover unconfirmed writes explicitly. Guests and live/device/accessibility/release acceptance remain separate.

## Original scope, presentation and privacy

The existing DM actor remains the only write owner. Every UI callback retains its original rendered session, peer, canonical row or recovery marker and actual navigation-entry guard. No transport, parallel mutation job, toggle POST or server permission assumption is added. Blocking/unavailable sending and room ignores do not revoke reactions on readable history. Selected chips/choices derive from viewer IDs, not another actor's flags. Counts and names remain ephemeral, never logged/saved or treated as authorization.

The shared picker is presentation only. Room/DM actors supply current authorized groups, deleted/review/unknown status and original callbacks; the room wrapper retains its existing tags, labels and behavior. Unknown discovered DM privacy removes people rows and disables choices immediately. Chips open choices without changing a reaction. Explicit named Add/Remove buttons, chips, modal recovery and close controls have48dp targets. The complete people list and choices scroll within the modal.

Own reaction withdrawal on retained tombstones/while muted follows the already-delivered room decision and unchanged service authorization. New adds remain disabled there; deleted text, Copy/Reply and author actions are not restored. The picker discloses retained counts/names and promises no purge. Canonical ACK publication precedes fallible history refresh, preserving all other row metadata.

## Recovery and lifecycle

Unconfirmed POSTs retain only bounded actor-owned peer/message/emoji/desired markers. Check history is an explicit original-marker GET; a retained picker then requires NEW Review before a separate fresh desired choice. Closing never clears uncertainty, and dismissed pickers are not recreated by recovery. Details exposes recovery/capacity/status when the header is collapsed. Reaction reconciliation does not grant SEND duplicate-risk, whole-peer READ, BLOCK or AUTHOR consent, nor chat-read proof.

Root/pane/entry guards and actor reference checks reject old callbacks and busy target handoffs. Modals suppress read/typing; closing requires fresh actual viewport proof. Configuration recreation retains the ViewModel POST once; genuine navigation/background retires proposals and preserves unknown outcomes. Explicit modal opening retires composer focus just as existing message actions do. No focus/IME repair, deadline change, tap replay, saved content or retry queue is introduced.

## Verification and remaining acceptance

Seven new full-root synthetic native journeys cover viewer-correct chips/complete lists/Add/Remove while blocked; committed500 GET/Review/fresh choice; dismissed-picker recovery through Details; muted tombstone own removal; immediate people-list privacy withdrawal; one physical composer tap/strict IME and complete multiline send after closing; and one POST through configuration recreation. They wire the real root/inbox/notification/target consumers, use plain peer8 fixture55 and assert no target lookup. Fakes remain in androidTest. All201 previous native tests remain enabled and unchanged alongside these seven cases.

The PR must record actual full JVM/lint/debug/release/test-APK/standalone compilation, original APK signatures, complete unfiltered/untraced Linux/API36 native CI and both actual written exact-head Claude reviews/dispositions. Local compilation is not native execution. No local device/live run, diagnostic dispatch or inherited PR80 exception is part of this slice. Original failed evidence remains negative; later passes do not establish an input correction. API26/current Android, TalkBack/font/landscape, live accounts and distribution/signing remain separate gates.

## Local result

The full local command `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest -PfocusDiagnostic=true :focus-diagnostic:assembleDebug --rerun-tasks` passed with974 JVM tests (0 failures/errors/skips), lint0 and160 executed tasks. All21 Python diagnostic-script checks passed. Source verification preserves45 original native files byte-for-byte outside the extended androidTest fixture, with seven new cases and no CI/input-helper changes. Local APK compilation invokes no native test or device; own full208-test Linux/API36 CI and both written current-head reviews remain required and are recorded on the PR.
