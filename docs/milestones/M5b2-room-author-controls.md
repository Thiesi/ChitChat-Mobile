# M5b2: native room author Edit/Delete

On PR54 (`44440a8`):623 fresh JVM/23author/lint0/build passed4m1s (production/R8 reused). Native6/6 baseline70.102s,150%-portrait81.745s,landscape49.486s; six author cases also passed Full. Full159/163 remains FAILED688.397s: room-ignore second tap, DM search/thread entry, room-target FIRST tap before lookup, standalone cycle5. Maintainer explicitly extended development-only treatment to [#56](https://github.com/Thiesi/ChitChat-Mobile/issues/56)'s three exact recorded failures, in addition to #55; other/new failures still block. No fix/release waiver.

Candidate4aa4c81 passed unfiltered CI166/166 (6m56.65s),623 fresh JVM/23author/lint0/R8 executed and fresh exact-head review. Both isolated three-case cohorts passed: merged PR54 in51.564s, candidate53.81s. Neither erases Full or establishes cause. Approval documentation changes no compiled inputs; its current-head CI/reviews remain gates. DM/guests/live acceptance separate.

## Behavior and scope

Accessible own-message Edit/Delete uses [M5b1 APIs](M5b1-author-mutation-protocol.md), unchanged session/CSRF. Edit is literal PHP-trimmed1-4000-codepoint text, not a command; reject unchanged edits, preserve type/quote/file. Confirm deletion and disclose retained revisions/files/copies, not erasure/E2EE.

Fence original server/account/generation/version, room/entry and canonical instance/permissions. Members only; mute blocks edit, not own deletion. Rebind fresh metadata only for identical author/type/body/edit/creation revision; changed/deleted/missing rows fail closed.

Separate author jobs/POST markers from SEND. Serialize reads/invalidations; preserve drafts/replies/history anchors. Modals suppress read/typing. Apply ACK/visible-quote redaction before fallible GET; GET failure cannot undo confirmation or restore unavailable quotes.

Post-entry failure/cancellation may follow commit: retain ID-only per-room uncertainty (max100/no eviction), never replay. Only explicit GET clears its original marker, not passive/navigation/send reconciliation. Retain foreground proposal read-only through GET -> fresh Review -> separate Save; unchanged validation prevents resending committed text. Cancel/background drops proposal; account/process retirement clears memory. No saved-state/disk/log content or durable queue. Off-window absence is not deletion proof.

## Layout and evidence

Edit uses a full native Dialog with [standard insets](https://developer.android.com/develop/ui/compose/system/insets-ui), scrolling body and measured-once actions beside it in short/wide space, below otherwise. One editor node preserves multiline text through resize; Delete stays an alert. No focus/IME/timing workaround or dependency upgrade.

Historical pre-PR54 source (not new-base verification):

| Check | Actual result |
|---|---|
| Debug/release/test APK | Passed5m8s; production/R8 reused prior11m4s build |
| Fresh JVM / author cases / lint | 623 /23 (0.542s),0 failures/errors/skips; lint0 |
| Native baseline | 6/6 (143.812s) |
| 150%-portrait /landscape | 6/6 (228.02s) /6/6 (221.004s) |
| Full163 / hosted166 / reviews | Local157/163 failed (2394.161s); CI/reviews not started |

Six author cases also passed within historical Full. Its failures: login1, Reply4, first-tap control1; causes unproven. Require48dp input/native focus/IME within5s, own-window Save geometry and new canonical outcome. Local excludes three clipboard writers; CI none. New landscape cleanup stopped on immediate landscape read; later read-only verification found exact portrait/settings restored, before first Full. No device writes or layout-test replay; both records retained.

Archives retain earlier import/lint failures; Full161/162; font2/5; Save geometry0/1 (y469 below IME381); Details-route0/6; landscape5/6 (18px editor); focused5/6 before own-window fixture correction; separate pause interruption. [Prior report](https://github.com/Thiesi/ChitChat-Mobile/issues/31#issuecomment-6338927649); preserve issue31's maintainer closure. Sequential snapshots are not causal/atomic proof.

PR54 separately delivered controlled async auth/diagnostics: CI160/160, both reviews, physical100cycles passed; local156/157 retained under the approved [#55 development-only exception](https://github.com/Thiesi/ChitChat-Mobile/issues/55). Other failures still block; no input/IME repair or release waiver. Archives retain exact sources/APKs/reports. No new backend/integration-file/live/clipboard/phone access; API26/TalkBack/push/live acceptance unclaimed.
