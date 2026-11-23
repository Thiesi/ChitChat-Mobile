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
  colour, announced once politely.
  - **Placement (PL amendment after the J on 3f0b798):** directly above the composer block (above the reply chip when
    there is one), outside the room's bounded header and the DM's top block, in full width. It shows its full text,
    wrapping and never clipped, in every layout including compact with the keyboard up; the timeline gives up the
    space. In the header it was invisible at 1.0 in the room, clipped in the DM, and not displayed at 200% or in
    landscape with the keyboard up.
  - **Composer first (PL refinement after the re-J on b2c34c4, PL- and TL-approved rule):** the full line shows only where
    the timeline keeps one 48dp row beside it, measured with the line absent (the timeline's height plus the line's own
    slot) against the full line's measured height at its real width. The timeline is the column's only weighted block and
    the composer is unweighted, so the composer's arrangement and Send never change because of the line. Otherwise the
    short form, "Try again in {wait}." / "Try again later.", shows in the same place, and the full line comes first in
    Details (left out of Details while the full line is visible, so it isn't shown twice).
    - **Stable:** `RateWaitSlot` decides once per notice and window: width, the height above the keyboard, font scale,
      and whether the reply chip and the attachment card or notices are present (PL: layout, not draft content; a reply
      started during the wait re-decides). Never from the draft, so it can't flip while typing; a tall draft at decision
      time only makes it more conservative.
    - **No frame wait (TL condition):** measurements are tagged with the notice and window they were taken in
      (`onGloballyPositioned`), and the slot decides as soon as a layout pass supplies them for the current key. While
      they're pending the previous form holds; with none yet, the short form shows. The line is never absent while a
      notice exists, apart from its deadline one-shot. Measuring inside the column's own layout pass would have meant a
      custom layout in both screens' composer columns; the tagged measurement needs no restructuring and no timer.
  - **Spoken text (PL ruling, 2026-11-23):** always the full line, in both forms. The line's only semantics are the full
    line as its description, a polite live region and one test tag, identical in both forms, so it's announced once when
    the notice appears and a full/short swap (the keyboard, a rotation, a reply chip) is silent. TalkBack users always
    hear "Your message is kept." It's static text, not a control, so visible and spoken text may differ, as with the
    status slot under P-25.
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
