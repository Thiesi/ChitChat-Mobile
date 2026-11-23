# Rate budgets (#323)

ChitChat has no global API limiter. Every limit is a named per-action, fixed-window policy
(`docs/operations/rate-limiting.md` at the minimum backend v3.10.0, b2791a0). Every limiter refusal comes from
`src/Http/RateLimiter.php:108–114`: HTTP 429, code exactly `rate_limited`, `retry_after_seconds` >= 1 and `Retry-After`.
`session.php` and the stream have no policy, so a bootstrap or revalidation 429 can only come from a proxy.

Before #323 every 429 went through `failLocked`, which set the session-wide wait. One policy's refusal (an eleventh
upload in an hour, for example) then blocked reading, sending and everything else until the window ended.

## #323a: the core (TL plan and conditions, 2026-11-23)

- **`RateBudget`:** one value per server policy, each citing where its endpoint consumes it (file:line at b2791a0).
  None is broader than its policy: a missed local gate costs one more server 429, while over-gating would refuse what
  the server allows.
- **Opt-in:** a call site passes `authorized(access, budget = RateBudget.X)`. Its policy's own `429 rate_limited` then:
  - sets no session-wide wait or error (no row 7, no P-25 gate);
  - records that budget's deadline: the transport's wait (Retry-After first, capped at 86,400 s) on a monotonic clock
    (`SystemClock.elapsedRealtime`, which counts deep sleep, so the gate never outlasts the server's window);
  - while that deadline runs, refuses the same budget locally: a typed `ApiFailure(429, "rate_limited")` with the
    remaining wait and `localGate = true`, and no request. It's read only at press time, with no timer.
  - An unknown wait (no positive `retry_after_seconds`) sets no gate; the next press goes to the server.
- **Unchanged, session-wide:** a 429 without `rate_limited` JSON (a proxy's), any 429 on a call site without a budget,
  503, 401, `csrf_failed`, network failures and timeouts.
- **Fencing:** budgets live in the session runtime, stamped with generation and account. Sign-out, server forget and
  any runtime replacement drop them; a different generation or account ignores and removes them. Memory only.
- **Definite refusals:** each mapped endpoint consumes its policy before any write work, so a write's `rate_limited` 429
  is definite. Its typed result (already a definite RateLimited outcome on every #323a path) is unchanged.

| Budget | Server policy | Consumed at (b2791a0) | Client call sites |
| --- | --- | --- | --- |
| `MESSAGE_REPORT` | `message_report` | `reports/message.php:24` | room and DM message reports |
| `USER_REPORT` | `user_report` | `reports/user.php:24` | person reports |
| `PUSH_SUBSCRIPTION_MANAGEMENT` | `push_subscription_management` | `push/update-preferences.php:23–24`, `push/revoke-device.php:23–24` | preferences, device revoke, Turn off's revoke, the unregistered revoke |
| `PERSONAL_DATA_EXPORT` | `personal_data_export` | `account/export.php:24–25` | the export |
| `ATTACHMENT_UPLOAD` | `attachment_upload` | `attachments/upload.php:25–26` | room uploads |
| `DIRECT_MESSAGE_ATTACHMENT_UPLOAD` | `direct_message_attachment_upload` | `direct-messages/attachments/upload.php:25–26` | DM uploads |
| `PRIVILEGED_STEP_UP` | `privileged_step_up` | `PrivilegedStepUpService.php:37` (via `step-up.php:24`) | the password step-up |
| `MFA_RECOVERY` | `mfa_recovery` | `MfaService.php:263`, before :269 (via `mfa/step-up-recovery.php:22`) | the recovery-code step-up |
| `RECOVERY_KEY_MANAGEMENT` | `recovery_key_management` | `RecoveryKeyService.php:27`, before :28 (via `account/recovery-key.php:22`) | Create or Replace key |

**Left unmapped on purpose (session-wide as before):**
- `push/subscribe.php`: it can refuse under `push_subscription_refresh` (`subscribe.php:23–24`) or
  `push_subscription_management` (`PushSubscriptionService.php:48/79`, the latter after a classification commit). It
  doesn't match exactly one policy.
- The app has no client for `sessions_management` or MFA management.

**Visible change:** none in the typed-429 UIs themselves (uploads, recovery key, step-up, export, push, reports). What
goes away is the session-wide reaction to these refusals: row 7, the P-25 gate, AccountScreen's session line
(`AccountScreen.kt`, `now < session.retryAtMillis`), and every other screen's wait.

## #323b1: sends, ping and typing (TL split and PL ruling, 2026-11-23)

| Budget | Server policy | Consumed at (b2791a0), before any work | Client call site |
| --- | --- | --- | --- |
| `ROOM_SEND` | `room_send` | `rooms/send.php:31–32`, when `PingCommand::parse` (:29) finds no ping | room send |
| `ROOM_PING` | `room_ping` | the same consume, for a ping | room `/ping` |
| `DIRECT_MESSAGE_SEND` | `direct_message_send` | `direct-messages/send.php:23–24` | DM send |
| `TYPING` | `typing` | `typing.php:30` | room and DM typing |

- **Which policy a room send hits:** `isPrivatePingCommand` (phpTrim, then `/ping` or `/ping `) mirrors
  `src/Realtime/PingCommand.php:14–15` exactly. A bare `/ping` is that parse's 400, before any consume, unchanged.
- **Guests:** a guest also consumes `guest_room_send` (`rooms/send.php:37`) for the same action. Guests are M7 and the app
  has none, so there's no budget for it.
- **Display:** `SessionState.rateWaits` mirrors each known deadline (written under the repository lock, memory only, gone
  with the state on any runtime replacement). `rateWaitMillis(budget, now)` also fences it to the generation and
  account. Screens read the same monotonic clock as the gate through `LocalMonotonicClock`, never wall time.
- **The composer line** (`RateWaitLine`, PL copy): "You've sent a lot of messages recently. Try again in {wait}. Your
  message is kept." and "You've sent a lot of pings recently. Try again in {wait}." A level-1 muted line, never error
  colour; Send announces it (below).
  - **Placement (PL amendment after the J on 3f0b798):** directly above the composer block (above the reply chip when
    there is one), outside the room's bounded header and the DM's top block, in full width. In the header it was
    invisible at 1.0 in the room, clipped in the DM, and not displayed at 200% or in landscape with the keyboard up.
  - **Composer first: full, short or hidden** (PL refinements after the re-Js on b2c34c4 and 12c3a36; TL-approved, the
    TL's condition 3 amended for the hidden state). With the timeline's height measured with the line absent (its height
    plus the drawn line's own slot, both from the same layout pass; the timeline is the column's only weighted block and
    the composer is unweighted):
    - **Full** only where the timeline keeps one 48dp row beside the full line.
    - **Short**, "Try again in {wait}." / "Try again later.", where the short form still leaves the composer its natural
      height (field at least 48dp, never shorter than without the line).
    - **Hidden** otherwise (compact with the keyboard up; on 12c3a36 the short line squeezed the 200% landscape field to
      about 30 dp): no line above the composer, the Details control shows its compact attention dot.
    - Short or hidden puts the full line first in Details (silent text, no tag); it's left out while the full line shows.
    - A drawn line with the timeline at 0 may be hiding a squeeze, so that measurement reads hidden.
  - **Stable:** `RateWaitSlot` decides once per notice and window: width, the height above the keyboard, font scale, and
    whether the reply chip and the attachment card or notices are present (PL: layout, not draft content). Never from the
    draft, so it can't flip while typing; the keyboard hiding or a reply started re-decides.
  - **No frame wait (TL condition):** timeline measurements are tagged with the window (`onGloballyPositioned`), so a
    notice arriving in a laid-out window decides at once. While a measurement for a new window is pending the previous
    form holds; with none yet, the short form shows. Measuring inside the column's own layout pass would have meant a
    custom layout in both screens' composer columns.
  - **A refused Send while hidden (PL ruling, TL-approved):** besides the announcement below, it hides the soft keyboard,
    a plain hide answering the tap: focus, draft, caret and reply stay. The window re-decides, the line shows by the rule
    and the dot clears. Tapping the field again brings the keyboard back and the line hides again behind the dot, silently.
    Only a repeat refusal does this (`sendLimitRepeats`), never the notice's first appearance.
  - **Send carries every announcement (PL and TL, 2026-11-23).** The line and the Details copy are silent text; their
    description is the full line, in both forms (static text, so visible and spoken text may differ, as under P-25).
    - **Why Send:** Compose UI 1.11.4's accessibility delegate reads `LiveRegion` only to fill in node info and diffs
      semantics snapshots every 100 ms (`SendRecurringAccessibilityEventsIntervalMillis`). A node that appears (the line,
      when a notice arrives or when the keyboard hides) looks new with its final text, so it isn't reliably announced.
      Only a content change on a node from the previous snapshot is. Send exists in every state.
    - **Label and state:** the label stays "Send"; while a notice is current its state description is the full line, with
      a polite live region (dropped while sending, so "Sending" isn't spoken). TalkBack says roughly "Send, You've sent a
      lot of messages recently. Try again in about 5 minutes. Your message is kept."
    - **Once on appearance, once per refused tap:** the state description appearing is one change. Each refused tap (the
      local gate or a repeated server 429) flips one trailing space, present or absent, on that state description only,
      never on a timer and never on the visible line or Details, so each refusal is a real snapshot difference.
    - **Silent otherwise:** clearing (the deadline one-shot, an unknown notice dropped on the next edit, a successful
      send) removes the state and the live region in the same change; typing, keyboard, rotation and form swaps don't
      touch Send.
    - **Tested** with real accessibility events (`LiveAnnouncements`: UiAutomation, content changes from a source that
      still has its live region, flushed through a test-only sentinel, no sleeps). On the owner's TalkBack list: two
      refused taps give two announcements with nothing extra spoken; if TalkBack drops the parity, the fallback is another
      inaudible variant under the same limits, never a timed change. Also for that check: emptying the draft during a
      known wait disables Send, a state change that its live region may voice.
  - **Unknown wait:** the send line keeps "Your message is kept." ("Try again later." replaces only "Try again in
    {wait}."); the ping line has neither.
  - **{wait}:** computed from the stored deadline at render and frozen until the notice changes; minutes and hours never
    tick; under a minute reads "less than a minute".
  - **At the deadline:** one composition-bound one-shot clears the line. It never sends; there's no other timer.
  - **An unknown wait** sets no gate and shows "…Try again later." until the next draft edit.
- **Definite:** the draft, reply and attachment stay; nothing becomes unknown; nothing resends. Nothing changed on the
  server, so history isn't marked stale and Send stays enabled. A tap during the wait is refused locally with no
  request and keeps the line.
- **Typing:** silent. During its wait the signal is refused locally and the coordinator shows nothing; only actual input
  attempts, at the existing cadence.
- **A proxy's 429** on a send keeps today's handling: the error line, the session-wide wait and row 7.

## #323b2 and #323b3 (next)

Author edit, delete and reactions (a contract change to M5b1, like O3b3b), then message and people search. After #323b3,
only proxy, non-JSON, bootstrap and revalidation 429s reach row 7.
