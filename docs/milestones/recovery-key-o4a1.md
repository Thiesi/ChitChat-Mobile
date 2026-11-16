# Recovery key O4a1: protocol and state

Protocol and state for the recovery key, with no UI. It follows [recovery-key.md](../design/recovery-key.md) (P-184,
P-188) and the released ChitChat v3.10.0 `docs/api/password-recovery.md`. O4a2 adds the key display, Privacy and
security, and the "didn't reach this phone" notice. O4b is password reset.

## Status

`user.recovery_key` on session, login and registration users becomes `SessionState.recoveryKey`:
- **Missing:** `Unknown`, so every surface stays hidden.
- **`null`:** `NotApplicable` (a provider-only account or a guest).
- **An object:** `Known(exists, createdAt)`, decoded strictly. `exists` must be a JSON boolean and `created_at` a date
  string or null. `exists: true` without a date is `Unknown`.

## The key

`RecoveryKey` holds one key in its canonical shape (`XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`). Its `toString` is redacted and
equality is identity. A malformed value is dropped and never logged.
- **Registration:** `register.php`'s top-level `recovery_key` becomes `SessionState.registrationRecoveryKey`. It lives
  in memory only until the key display drops it (`dropRegistrationRecoveryKey`). Any new session state (sign-out,
  another account) drops it too. Only registration hands over a key; a sign-in reply never does.
- **Generate or Replace:** `AccountAccess.createRecoveryKey()` makes one CSRF `POST account/recovery-key.php {}`.
  - The confirmed `created_at` updates the session status.
  - The key goes only to the caller.
  - A 200 without both fields may have committed, so it counts as ambiguous.
- **Outcomes:** `requestRecoveryKey(...)` wraps `withStepUp`, which retries exactly once, only on
  `403 step_up_required`. The results:
  - `Created`;
  - `PasswordAccountRequired` (409);
  - `RateLimited` (429, with the server's positive wait);
  - `Failed` (any other refusal);
  - `Unknown`, resolved by **one session read comparing `created_at`**: created elsewhere, unchanged, or unresolved
    when the read fails.

  Never resent.

## "Didn't reach this phone"

`SessionState.recoveryKeyUndelivered` is set in two cases:
- a registration reply carried no key (TL answer 4);
- a sign-in follows an ambiguous registration **of the same username**.

The runtime keeps the ambiguously registered username until the next authentication, because "Sign in instead"
switches the form and that clears `registrationMayHaveSucceeded`. A sign-in to another account is never marked. O4a2
shows the notice only while the status says `exists: true`.

## Verification

- **`RecoveryKeyTest` (6):**
  - status decoding (missing, null, strict object, malformed);
  - the canonical key shape and a redacted `toString`;
  - an incomplete 200 counts as ambiguous;
  - step-up retries exactly once;
  - typed 409 and 429 and other refusals, each with one POST;
  - ambiguous outcomes resolved by one read, never resent.
- **`SessionRepositoryTest` (+6):**
  - registration holds the key, with nothing in `toString`, and drops it on request;
  - a keyless registration is marked undelivered;
  - a sign-in to the ambiguously registered username is marked even after the form switch, and another username isn't;
  - a plain sign-in carries the status but no key or marker;
  - Generate posts once (path, `{}`, CSRF) and only the date reaches the session.
