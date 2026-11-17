# Password reset O4b1: protocol and state

Protocol and state for resetting a forgotten password with the recovery key, with no UI. It follows
[recovery-key.md](../design/recovery-key.md) ("Forgot your password?", P-186) and the released ChitChat v3.10.0
`docs/api/password-recovery.md`. O4b2 adds the screens.

## Discovery

The released contract makes the challenge itself the discovery signal: "While signed out, a successful GET of
`/api/v1/password-reset/challenge.php` with `challenge.nonce` advertises recovery; older servers can return 404. Do not
infer support from absent capability flags or show a reset form that submits without a challenge." `session.md` only
links to that page. There's no session capability for reset.
- **`discoverPasswordReset()`:** one GET when step 4a opens (O4b2 calls it once per visit, never on recomposition). The
  challenge is rate-limited at 60 per 60 seconds by source IP. Success with a nonce makes `ResetSupport.SUPPORTED`; a
  404 or any failure is `UNSUPPORTED`, which isn't a session error. Only `SUPPORTED` lets `selectAuthFlow(RESET)`
  through.
- **The discovery challenge serves the first attempt** while it's under four minutes old (the server expires it after
  five), so opening the form costs no second GET. It's used at most once.

## An attempt

The reset lives in the ANONYMOUS phase (`authFlow = RESET`, a typed `ResetState`). There's no new `SessionPhase`, and a
pending reset is not signed in.
- **`prepareReset()`:** a fresh challenge (or the young discovery one), solved by the registration proof-of-work solver.
  It records the challenge response's HTTP `Date` header, the server's time just before the attempt.
- **`resetPassword(username, key, newPassword)`:** one `POST password-reset.php`. The key is sent as typed after trimming
  (the server normalizes case, spaces and hyphens). The proof is consumed even when the attempt is refused, so the next
  attempt prepares anew. Nothing is ever resent.
  - **`200 complete`:** signed in as after a sign-in. The replacement key goes into the one-shot display slot with
    origin `RESET` (title "Your new recovery key", note "Your old key no longer works, and your other devices were
    signed out."). A completion without a key marks it undelivered with origin `RESET`.
  - **`202 pending_mfa`:** `ResetState.pending` holds the listed methods and `expires_at`. Only `recovery_code` is
    offered here: passkey reset (`mfa-options`/`mfa-finish`) waits for native passkeys (B1). A pending reset that lists
    only a passkey can only be cancelled.
  - **`400 reset_failed`, `400 validation_error`, `429`, `503`:** typed in `SessionState.error` (429 and 503 set the
    wait), and the form stays.
  - **Ambiguous** (connection lost, timeout, a 5xx, or a malformed reply): `ResetOutcome.UNCERTAIN`. The password may
    have changed, and nothing is resent.
- **`completeResetRecovery(code)`:** `mfa-recovery.php` with the field `code` (sign-in uses `recovery_code`). A
  rejected code keeps the step open. `409 reset_not_pending` means the pending reset expired or became invalid, since
  nothing was retried: `ResetOutcome.EXPIRED`, back to the form. Ambiguous is `UNCERTAIN`.
- **`cancelReset()`:** `cancel.php`. Only an acknowledged `cancelled` is `CANCELLED` ("nothing changed"). A `409
  reset_not_pending`, a lost response or any failure is `CANCEL_UNCERTAIN`, never "nothing changed" and never retried.
  Both return to step 4a.

## The key that never arrived (TL and PL)

After an Unknown reset, or an uncertain MFA or cancel, the runtime keeps the attempt's username and its challenge's
server `Date`, in memory only. The next sign-in to that username (case-insensitive) decides it by **server time only**,
never the device clock:
- the account's `user.recovery_key.created_at` is at or after that `Date`: the reset committed and replaced the key, so
  `recoveryKeyUndelivered = RESET` and the Rooms notice shows its reset variant ("Your new recovery key didn't reach this
  phone. Your old one no longer works. …");
- it's earlier: the reset never committed (they signed in with the old password), so the marker clears silently;
- either time is missing or unreadable: nothing shows.

`Date` has one-second resolution and precedes the POST, so a key that POST created always counts. A key created before
the challenge within that same second would count too; that edge is accepted. Like the registration marker, it's cleared
by any completed authentication, by sign-out (the runtime is replaced) and by process death, and a malformed reply that
invalidates the runtime loses it. `recoveryKeyUndelivered` is now typed (`KeyOrigin` REGISTRATION or RESET, or null),
and the registration path is unchanged.

## Secrets

The key, new password and code travel only in the request. The request classes' string forms are redacted, the
replacement key is the redacted `RecoveryKey`, and the runtime's challenge, proof and marker classes have no generated
string form. `SessionState`'s string never contains a key, password or the marked username.

## Tests

`SessionRepositoryTest`:
- discovery and the 404;
- the discovery challenge serving the first attempt, and each later attempt fetching its own;
- the POST body;
- `reset_failed` consuming the challenge;
- a completion with and without a key;
- the three `created_at` cases, a missing `Date`, device-clock skew, and another username;
- a pending reset and its code;
- expiry, and a passkey-only pending reset sending nothing;
- acknowledged, refused and failed cancels.
