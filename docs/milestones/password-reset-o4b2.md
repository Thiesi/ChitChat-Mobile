# Password reset O4b2: the screens

The screens for resetting a forgotten password with the recovery key, on top of [O4b1](password-reset-o4b1.md). They
follow [recovery-key.md](../design/recovery-key.md) ("Forgot your password?", P-186) and the presentation lead's O4b2
decisions.

## Step 4a

The discovery row sits in the scroller after the password field, before Back and the P-221 footer; the pinned Sign in
block is unchanged.
- **Unknown or checking:** an empty 48dp row with no semantics, so nothing jumps and nothing is read.
- **Supported:** a **Forgot password?** text button. It opens the reset form with the username field prefilled from
  step 4a's.
- **Unsupported** (a 404 or any failure): the static line "Forgot your password? Ask the person who runs the server."
- The change is quiet (no live region), and the static line never shows first.
- Discovery runs once per visit: a per-visit flag survives recomposition and configuration change.
- **Cancel outcomes**, after a cancelled reset:
  - "Nothing changed. Your password is the same as before." is a muted caption under the title, announced once;
  - an uncertain cancel is the level-2 block "**We're not sure whether your password changed.** Try signing in.".
  - Both end on the next sign-in attempt or on leaving step 4a.

## The reset form

- Server chip, "Reset your password", the lead, then the fields:
  - Username (56dp);
  - Recovery key: visible-password keyboard, autocorrect off, no `contentType`, no reformatting;
  - New password and Confirm new password, with the policy line.
- The calm anti-bot row, and Back to sign in in the scroller.
- **Secrets:** the key, new password and confirmation live in composition memory only. They're cleared on ON_STOP and
  gone when the form leaves, never in bundles or the ViewModel. After a refusal they keep their text, as the spec says.
- **Every attempt uses a fresh challenge:** whenever none is prepared and nothing is pending, one is fetched. A
  submission consumes it even when refused, so Reset password stays disabled until the next one is ready. Nothing is
  ever resent.
- **The action block follows Create (#275, PL):**
  - pinned while the keyboard is hidden and absent while the IME is up; the Confirm field's Done hides the keyboard;
  - the `reset_failed` line ("That didn't work. Check your username and recovery key.", the same for every cause) and
    the 429 wait line sit directly above Reset password, announced politely.
  - A tap after the prepared challenge went stale (about four minutes) sends nothing. The fresh check starts, and the
    block says "That took a while, so the check is starting again. Nothing was sent. Tap Reset password when it's
    ready." until the next tap (review on #294, PL wording).
  - The screen's general error line doesn't repeat these. On the pending MFA step, which has no lines of its own,
    refusals and waits stay on the general error line (review on #294).
- **Outcomes:**
  - `validation_error` shows the policy line;
  - an Unknown reset shows the level-2 notice "**We're not sure your password changed.** Sign in with your new password
    first. If that doesn't work, use your old one. Your recovery key may no longer work." with **Sign in** back to
    step 4a;
  - an expired pending reset returns here with "That took too long. Start again with your recovery key."

Each reset screen (the form and the MFA step) brings its title into view when it opens. The shared scroller otherwise
keeps its offset from step 4a, where typing a username raises the keyboard, and the screen opened past its title.

## One more step (pending reset)

- The title, the lead, and "Finish before {time}" in the device's time format.
- **The recovery code:** the "Sign-in recovery code" field with the "not your recovery key" line, the finite-code
  warning, and Use recovery code.
- **A reset listing only a passkey** says "This account needs a security key to finish, and this app can't use security
  keys yet." and offers only Cancel reset (passkey reset waits for B1).
- **Cancel reset and Back** both ask "Stop resetting your password?" (Keep going or Stop). The dialog has no body text,
  so there's no text slot to scroll (P-220); its actions are asserted displayed at default and 200%.

## After a completed reset

The root shows the key display before the guidelines and the shell, exactly as after registration, with "Your new
recovery key" and "Your old key no longer works, and your other devices were signed out." (origin RESET from O4b1).

## Also in this slice

The shared `core/ui` `WarningNotice` (and `WarningLine`, `WarningGlyph`) draws the level-2 blocks. Its fixed 20dp honey
glyph is centred on the first line at any font scale. The Rooms "didn't reach this phone" notice and the Not set up rows
use it too (the presentation lead's follow-up from #289).

## Tests

- `PasswordResetJourneyTest` (the real root, the guidelines gate on):
  - the empty row while checking, then the button, with one discovery GET;
  - the static line;
  - a completed reset: prefill, pinned action, one POST, the new-key display with its title and note in a secure
    window, before the guidelines and the shell;
  - a refusal keeping the fields with the line in the action block and preparing again;
  - a stale challenge's line in the action block;
  - an error on the pending step shown there;
  - Unknown back to step 4a;
  - the pending code step completing;
  - passkey-only;
  - stop asking first, and an acknowledged cancel's caption;
  - an uncertain cancel's block;
  - an expired pending reset.
- `ResetPresentationTest` captures them for J (24 captures) into `reset-presentation`. `RecoveryKeyPresentationTest`
  adds the key display's reset variant, and `RecoveryKeySettingsPresentationTest` the notice at both scales.
- `RegistrationValidationTest` covers the reset form's local hints.
