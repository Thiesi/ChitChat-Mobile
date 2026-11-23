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

## #323b (next)

Sends, ping, typing, author edit and delete, reactions and search get their budgets with the PL's copy and placement.
After it, only proxy, non-JSON, bootstrap and revalidation 429s reach row 7.
