# Recovery key and password reset: presentation specification

Status: activates ChitChat#155 (merged as ChitChat#183, main `2281345`; contract in issue #1,
comment 6728405354; ChitChat `docs/api/password-recovery.md`). The web UI follows separately.
Mockups: `RecoveryKey` and `ResetPassword` on the canvas.

A recovery key lets a person with a password account set a new password when they've forgotten
the old one. It's the **only self-service way back in**: without it, only the people who run the
server can reset a password. The copy says this plainly and never suggests another way.

## What the server provides

- **One key per password account:** 24 characters in six groups of four
  (`XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`). The server keeps only a hash, so a key can never be
  shown again after its one disclosure.
- **Discovery:** private `user.recovery_key: {exists, created_at}` in session, login and
  registration responses. `exists: false` means a password account without a key. `null`
  means a provider-only account or a guest. A missing field means unknown (older server), so
  every surface below is hidden.
- **Shown once in three places:**
  - `register.php` returns a top-level `recovery_key` for password accounts;
  - a completed reset returns a replacement key;
  - `POST account/recovery-key.php` (signed in, CSRF, step-up) returns a new key and makes the
    old one stop working immediately.
- **Forgiving input:** case, spaces and hyphens are ignored, O counts as 0, and I and L count as 1.
- **Reset** (signed out): a fresh challenge per attempt (`GET password-reset/challenge.php`,
  the same proof of work as registration), then `POST password-reset.php`. That returns
  `200 complete` (signed in, new key, other sessions signed out) or `202 pending_mfa`.
- **The recovery key is not an MFA recovery code.** It replaces the password only. An account
  with MFA still needs its security key or one of its sign-in recovery codes. The two can't
  stand in for each other.

## Wording

- "**Recovery key**" always means this key. "**Recovery code**" keeps its existing meaning: the
  finite MFA codes (`mfa_recovery_explanation`, unchanged). The two terms are never used
  interchangeably, and wherever both can appear the copy names the difference once: "This
  isn't one of your sign-in recovery codes."
- Honest about the stakes: "If you forget your password, this key is the only way to reset it
  yourself. Without it, only the people who run {installation} can help."
- Never "backup", "secret code" or "PIN".

## Handling the key (all surfaces)

- The key lives **only in ViewModel memory** while its display is open. It's never written to
  disk, saved state, bundles, logs, analytics or crash reports, and it's never part of a
  debug string.
- It survives configuration changes and **genuine background**, because the person often has
  to switch to a password manager to save it. It's discarded when the person confirms "I've
  saved it", on sign-out, and on process death.
- The display window sets `FLAG_SECURE`, so the key stays out of screenshots, screen recording
  and the recents thumbnail. Copy and Save to a file are the supported ways to keep it.
- **Copy:** puts the key in the clipboard with `ClipDescription.EXTRA_IS_SENSITIVE` on API 33
  and later, which hides the clipboard preview. The confirmation is the level-1 line "Copied.
  Paste it into your password manager." The app doesn't clear the clipboard later, matching
  the existing Copy rule.
- **Save to a file:** an explicit choice that opens the system document picker
  (`ACTION_CREATE_DOCUMENT`, `text/plain`, suggested name
  `ChitChat recovery key - {host}.txt`).
  - A line above the button, always visible: "The file isn't encrypted. Keep it somewhere only
    you can open."
  - Contents: the server host, the username, the key, its creation date and the line "Anyone
    with this key and your username can reset your password."
  - The URI grant isn't kept and the file isn't deleted later, as with exports. A failed write
    says "The file wasn't saved." and the key stays on screen.
- After the display closes, the key is gone. The only path forward is generating a new one.

## The key display (mockup `RecoveryKey`)

Full screen, no bottom bar, Back handled as below.

- **Title:** "Save your recovery key" (after a reset: "Your new recovery key"; after Replace:
  "Your new recovery key").
- **Lead:** "If you forget your password, this key is the only way to reset it yourself.
  Without it, only the people who run {installation} can help. You'll only see it once."
- After a reset, a second line: "Your old key no longer works, and your other devices were
  signed out." After Replace: "Your old key no longer works."
- **The key:** a `raised` card with the six groups in the system monospace face
  (`FontFamily.Monospace`, 22sp, `text`; no new font), two rows of three groups, wrapping to more rows at large font scales, never
  truncated. TalkBack reads it group by group, spelling out each character ("Group 1 of 6: A,
  7, K, 2").
- **Actions:** **Copy** and **Save to a file** as outlined buttons, side by side, stacking when
  narrow. The unencrypted-file line sits under them.
- **Continue** (primary, persimmon-filled) opens the confirmation:
  - a decision dialog, "**Saved your recovery key?**", with the body "You won't see it again.
    This isn't one of your sign-in recovery codes.";
  - **Go back** (text) and **I've saved it** (`primary` text button).
  - I've saved it discards the key and continues. Nothing is recorded about whether the person
    saved it.
- **Back** opens the same dialog. It never leaves silently.

**Where it appears:**

1. **After native registration** (onboarding step 4b): straight after a successful
   `register.php` that returned `recovery_key`, and **before** the guidelines screen and step 5.
   The response is held only until this display is confirmed.
   - **Ambiguous registration** (`registration_unknown`, then Sign in instead): if the sign-in
     shows `recovery_key.exists: true` on an account created in this session, the first screen
     after sign-in shows the level-2 notice "Your recovery key didn't reach this phone. Create a
     new one in Privacy and security." with **Go there**. A key that never reached the phone
     can't be shown.
2. **After a completed reset** (below), before the app opens.
3. **After Generate or Replace** in Privacy and security (below).

## Forgot your password? (mockup `ResetPassword`)

Onboarding step 4a's static line "Forgot your password? Ask the person who runs the server."
becomes a **Forgot password?** text button when the server supports reset. Support is detected
by a successful `GET password-reset/challenge.php` with a `challenge.nonce`, fetched when step
4a opens. A 404 or failure keeps the static line. The app never shows a reset form that can't
submit a challenge.

### Reset form (signed out, ANONYMOUS phase)

- Server chip, display title "**Reset your password**".
- Lead: "Use the recovery key you saved when you created your account or last reset your
  password. No key? Only the people who run {installation} can reset it."
- **Username** (56dp, label above), prefilled from step 4a's field when present.
- **Recovery key:** a single-line field, label "Recovery key", supporting text "Upper or lower
  case, with or without dashes." Input is forgiving: the app sends what was typed after
  trimming and the server normalizes. The field doesn't reformat while typing (no live
  dashes), so the IME and paste behave normally. Keyboard type is visible-password with
  autocorrect off. `contentType` stays unset, so autofill doesn't offer saved passwords here.
- **New password** and **Confirm new password:** the same fields, policy text and visibility
  toggle as Create account (4b).
- **Anti-bot preparation:** the same calm row as 4b ("Making sure you're a person…"), using
  the reset challenge. Never a percentage.
- **Reset password** (full-width primary). Disabled until the challenge is ready, all fields
  are filled and the passwords match.
- Text button: **Back to sign in**.
- The recovery key, new password and confirmation follow the existing password rules: they're
  only in composition memory, cleared on `ON_STOP`, never in bundles. A person who leaves to
  copy the key from a password manager pastes it after returning.

**Every attempt uses a fresh challenge.** A submission uses up its challenge even when it's
refused, so after any outcome the app fetches a new one before Reset password is enabled
again. Nothing is ever resubmitted automatically.

### Outcomes

| Result | Shows | Then |
|---|---|---|
| `200 complete` | The new-key display ("Your new recovery key") | After I've saved it, the app opens as after sign-in (guidelines or re-acceptance first if due) |
| `202 pending_mfa` | The reset MFA step below | — |
| `400 reset_failed` | Level-1 line under the button: "That didn't work. Check your username and recovery key." It's the same for every cause and never says which field was wrong | The form stays. The key and password fields keep their text, and a new challenge is fetched |
| `400 validation_error` (password) | The password policy line, as in 4b | The form stays |
| `429` | "Too many attempts. Try again in {wait}." from `error.retry_after_seconds` (status-presentation "Waiting times"), or "… in a little while." when unknown | Reset password stays disabled until the wait ends. No automatic retry |
| `503 maintenance_lockdown` | The level-2 maintenance notice with the server's message, as on sign-in | The form stays |
| Unknown (connection lost after sending, timeout) | Level-2 notice: "**We're not sure your password changed.** Try signing in with your new password. If that doesn't work, use your old one." | **Sign in** returns to step 4a. Nothing is retried, and the key may have been used up |

### Reset MFA (pending reset, not signed in)

The layout of step 4c ("One more step"), with reset wording:

- Title "**One more step**". Lead: "Your account uses two-step sign-in. Confirm it's you to
  finish resetting your password." Then "Finish before {time}" from `expires_at` (device
  time format).
- Only the methods the server lists in `methods`:
  - `passkey`: **Use your security key** (the dedicated reset assertion via
    `mfa-options`/`mfa-finish`; it carries the existing passkey dependency B1, so it's omitted
    until native passkeys exist);
  - `recovery_code`: the field "Sign-in recovery code" with the existing finite-code warning
    copy verbatim, and **Use recovery code**. The line under the field reads "This is one of
    the codes you saved when you turned on two-step sign-in, not your recovery key."
- **Cancel reset** (text button) sends `cancel.php`:
  - `200 cancelled`: "Nothing changed. Your password is the same as before." Back to 4a.
  - `409 reset_not_pending`, a lost response or any failure: "**We're not sure whether your
    password changed.** Try signing in." Back to 4a. Never "nothing changed", never a retry.
- **Success** (`200 complete` from either method): the new-key display, then the app.
- **Errors:** the existing MFA error copy and rate-limit wording. A rejected code keeps the
  step open. If the pending reset has expired or become invalid, the reset form returns with
  the level-1 line "That took too long. Start again with your recovery key." and a fresh
  challenge.
- Back on this step means Cancel reset, after the same decision dialog as the other cancels:
  "Stop resetting your password?" with **Keep going** and **Stop**.

## Privacy and security → Recovery key

You → Privacy and security gets a **Recovery key** row in its sign-in group (navigation-shell.md):

- `exists: true`: title "Recovery key", supporting text "Created {date}". Tapping it opens a
  short explanation screen with **Replace key**.
- `exists: false`: supporting text "**Not set up.** Without one, only the people who run
  {installation} can reset a forgotten password." in `textMuted`, with the `warning` icon in
  honey. The action is **Create recovery key**.
- `null` (provider-only account) or a missing field: no row.

The explanation screen repeats the lead from "Wording" and states what Replace does.

**Replace key** opens a decision dialog first:

- Title "**Replace your recovery key?**"; body "Your current key stops working right away, so
  save the new one before you leave the next screen."
- **Cancel** and **Replace** (`error` text button, because the old key is destroyed).
- **Create recovery key** (no key yet) skips this dialog.

Then the request goes through the existing `withStepUp` wrapper: the step-up form dialog appears
only on `403 step_up_required`, followed by its one retry (play-readiness.md §2 uses the same
rule).

| Result | Shows | Then |
|---|---|---|
| 200 | The key display ("Your new recovery key" / "Save your recovery key") | After I've saved it, back to Privacy and security, which shows the new date |
| `409 password_account_required` | "This account signs in through another service, so it has no password to recover." | The row hides after the next session refresh |
| `429` | "You've changed your recovery key a lot recently. Try again in {wait}." (or "… later.") | — |
| Unknown | A session GET compares `created_at` with the value before the request. If it changed: "**A new key was created, but it didn't reach this phone.** Your old key no longer works. Create another one now." with **Create again**. If unchanged: "Nothing changed. Your current key still works." If the GET fails, it says "We're not sure a new key was created. If it was, your old key no longer works." with **Create again** | **Create again** is an explicit new request, never automatic |

## Tests

- **Unit:** discovery (`exists`, `null`, missing); key never in saved state or `toString`;
  discarded on confirm, sign-out and owner retirement but kept on configuration change and
  background; a fresh challenge before every reset attempt; each reset outcome per the table;
  `reset_not_pending` and lost cancel responses map to the uncertain copy; no automatic
  resubmission anywhere; the Replace unknown result compares `created_at`.
- **Compose:** key display grouping and font-scale wrapping; the Saved it? dialog on Continue
  and Back; Copy sets the sensitive extra on API 33+; Save to a file shows the unencrypted line
  before the picker; reset form enablement; the two MFA methods appear only when listed; the
  recovery key and recovery code wording stays distinct.
- **Native:** the reset fields follow the native input contracts (one tap, focus, IME, 5 s).
  `FLAG_SECURE` is set while the display is shown.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-184 | A key returned by registration, reset or Replace is shown once, in memory only, and survives configuration change and background. It's discarded on I've saved it, sign-out or process death, and never reaches disk, saved state, logs or debug strings. The display uses `FLAG_SECURE`. | U, C, R |
| P-185 | Continue and Back both ask "Saved your recovery key?". Copy marks the clip sensitive (API 33+). Save to a file is an explicit document-picker choice with the unencrypted warning shown first. | C |
| P-186 | Forgot password? appears only after a successful reset challenge. Every attempt fetches a fresh challenge. `reset_failed` stays generic. Unknown results and `reset_not_pending` send the person to sign in with uncertain wording. Nothing is resubmitted automatically. | U, C |
| P-187 | Reset MFA offers only the listed methods, keeps "recovery key" and "recovery code" distinct, and only an acknowledged cancel says "Nothing changed". | U, C |
| P-188 | Privacy and security shows the key's state for password accounts only. Replace warns that the old key stops working, goes through `withStepUp`, and resolves an unknown result by re-reading `created_at`, never by retrying. | U, C |
