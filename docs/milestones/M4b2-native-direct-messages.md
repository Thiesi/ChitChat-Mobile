# M4b2: native direct messages

Consumes M4a's existing-session protocol and M4b1's canonical state. Audited backend: ChitChat v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`. No backend writes, invented endpoint, alternate authentication, WebView or production fake.

## Behavior

Authenticated non-guests get native inbox/prefix search (also IME Search), canonical previews/metadata, account-formatted timestamps, server unread and separate local attention. Inbox/drafts cap at100; drafts require explicit discard, never silent eviction. No content/searches enter bundles, disk or routes.

Validate server/account/generation/version routes before composition/selection. Replacement returns Home; logout clears state; rotation retains VM memory. Details disclose fresh non-E2EE/retention/inspection/revision policy and configured reason/audit/participant notices. Missing policy disables DMs. Confirm own block/unblock; never infer another's block. Send restrictions preserve authorized history/read.

Entry, SSE hints, reconnect and one-minute STARTED refresh reconcile canonical HTTP, never raw-event presentation. Consume [M4b1 state guarantees](M4b1-direct-state.md): queued hints, no write replay, confirmed-ID/unknown-outcome separation, explicit resend consent, anchors/pagination/retention/new-arrival jumps and rate-expiry readiness without automatic posting.

Read needs RESUMED/focused/unobscured/live-edge/newest-bottom proof, including tall rows. Modal retry remains reachable but waits for restored proof. Refetch whole-peer reads and label non-durable local attention for unrendered arrivals, never an atomic server/cross-device count/cursor.

Reuse `MessageInput` (persistent label + EditableText), pinned Send and one field through IME resize. Declare resize before attachment (main/debug host), consume IME once above Scaffold and collapse the toolbar in short keyboard windows; system Back restores navigation. Short messaging views retain generation-filtered connection retry/announcements in badged details. Search/navigation retire focus; DMs never synthesize room presence.

## Verification

Final local six-task gate passed5m53s:338JVM/81native, zero failures/errors/skips, lint clean, debug/testAPK and R8 release. Strict software-IME readiness enabled; `.tooling/m4b-final-integrated-81-native-pass`. Final-head hosted CI and completed/fresh reviews remain separate gates.

Revised10-case landscape/font150% passed65s, every case asserting actual configuration; report: ignored `.tooling/m4b-final-asserted-landscape-10-pass`. Hosted final-head gates remain pending. Historical passes: initial338JVM/76native six-task gate, asserted9-case landscape and integrated338JVM/10-native portrait focus before expansion. A repeat passed75/76 with old issue24 room Send0. [PR29](https://github.com/Thiesi/ChitChat-Mobile/pull/29) records earlier-head evidence; none substitutes for final gates.

PR30 (`c3baa73`) passed336JVM/71native and two actual clean reviews. Integration preserved the consumer tree exactly; old PR29 head14fb4ca's CI/review cannot approve later revisions.

Landscape diagnostics separated10px/negative Send bounds from missing IME (`m4b-app-frame-proven-10px-action-failure`, `m4b-window-ready-no-composer-IME`). Keep five-second predicates/one click, actual IME + visible/enabled Send, [external draw synchronization](https://developer.android.com/develop/ui/compose/testing/synchronization) and [documented window setup](https://developer.android.com/develop/ui/compose/system/setup-e2e). Only ordinary login forms scroll; Send never does. Fixtures use actual recovery resources.

An expanded run disconnected after8 completed cases (`m4b-expanded-frame-device-disconnect`); offline capture/force-stop failed. Restarted only the verified owned emulator (>10GB resident), restoring display overrides first; no shared ADB reset, other-AVD change, root, obtained Java trace or claimed app cause. PR29 retains earlier failures. NOSENSOR-requested rotation alone is not coverage; restore overrides after asserted runs.

## Acceptance still outstanding

Fakes are `androidTest` only. No `.env.integration` values or live mutations were consumed. Screenshots are fixture evidence, not live-data/manual TalkBack acceptance. Live two-account send/read/block, API26, device/process-death and full manual accessibility/IME journeys remain unperformed. Final-head CI and completed/fresh Claude reviews are separate merge gates.

M4c supplies typing/preferences, ignore management and notification center. M5 supplies author actions, formatting, media/sharing/search/exact targets; read-side metadata is not that action UI. M6 supplies UnifiedPush. This slice does not establish complete application or push parity.
