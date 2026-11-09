# Account and security (M6): presentation specification

Status: presentation spec for the M6 account and security screens, written ahead of the
engineering slices (technical lead, 2026-11-09). It's grounded in the ChitChat API docs at main
`1ddd59c` (`docs/api/account.md`, `mfa.md`, `authentication.md`, `password-recovery.md`,
`web-push.md`). It designs only what those APIs offer, and marks anything missing as a
**Dependency**. Mockup: `PrivacySecurity` on the canvas.

Existing contracts stay in force: the generic step-up dialog and `withStepUp` (M2b2: only
`403 step_up_required`, one retry), emergency recovery-code consent and counts, export (M2b2),
restoration (M2b1), the recovery key (recovery-key.md), push devices (push-presentation.md) and
account deletion (play-readiness.md §2).

## What the server offers

- **Profile picture:** `POST account/avatar/upload.php`, multipart `avatar`, at most 5 MB, JPEG,
  PNG or WebP, at most 6000 × 6000 px. The server re-encodes it as a centre-cropped 256 × 256
  WebP and drops all metadata. `POST account/avatar/remove.php {}` removes your own.
  `503 avatars_unavailable` without GD/WebP, and profiles carry `avatars_available`. Importing
  a Google or Twitch picture is a browser round trip (`oidc/begin.php`, `purpose: picture`).
- **Password:** `POST password.php {current_password, new_password}`. Changing it **ends every
  other session**, rotates this one and clears step-up. A provider-only account sets its first
  password with step-up instead of a current password. The recovery key survives an ordinary
  change.
- **Two-step sign-in (MFA):** **passkeys (WebAuthn) plus single-use recovery codes. There is no
  authenticator-app (TOTP) option on the server**, so CCM doesn't design one.
  - `GET account/mfa/status.php` returns `{available, enabled, required_by_admin_policy,
    recovery_codes_remaining, credentials: [{id, label, transports, algorithm, backup_eligible,
    backup_state, created_at, last_used_at}]}`.
  - With step-up: enroll (`register-options`/`register-finish`, whose first enrollment returns
    the recovery codes once), rename (label 1–80 characters), remove (never the last passkey),
    regenerate recovery codes (the new set is returned once), and disable (refused when admin
    policy requires MFA; rotates the session version).
- **Sign-in methods:** `GET account/identities/list.php` returns `{providers, identities,
  has_password}`. Unlink needs step-up, and `409 last_sign_in_method` protects the last way in.
  Linking is a browser round trip.
- **Sessions:** **there's no API to list or end your login sessions.** The only device list is
  push subscriptions, with `current_session` (#147). A password change ends all other sessions.
- **Step-up:** password accounts use `step-up.php`. MFA accounts use a passkey
  (`mfa/step-up-*`) or a recovery code (`mfa/step-up-recovery.php`). Step-up lasts 10 minutes by
  default and is bound to this session.

## Where it lives

**You → Privacy and security**, grouped as below (mockup `PrivacySecurity`). Each row is a
settings row (title, supporting text, chevron when it opens a screen).

1. **Sign-in**
   - **Password**: "Change password", or "Set a password" for a provider-only account.
   - **Two-step sign-in**: "On · 2 passkeys · 7 recovery codes left", "Off", or "Required by
     {installation}". Hidden when `available` is false.
   - **Recovery key** (recovery-key.md).
   - **Sign-in methods**: "Password and Google", for example. Shown only when the server
     offers providers or the account has a linked identity.
2. **Devices**
   - **Notification devices**: opens You → Notifications → Devices (push-presentation.md), where
     "This phone" is marked.
   - **Signed-in sessions**: **Dependency (no backend API)**. Leave it out. See "Sessions".
3. **Privacy** (existing): typing indicator, ignored people.
4. **Your data** (existing): **Export my data**.
5. **Delete account** (play-readiness.md §2), an `error`-colored text row at the bottom.

The **profile picture** is edited from the You header: tapping the pebble opens its sheet. The
same sheet is reachable from a "Profile picture" row at the top of Privacy and security, for
people who don't expect the header to be tappable.

## Profile picture

- **Sheet** (choice sheet): the current pebble or picture at 96dp, then:
  - **Choose a photo**: the system Photo Picker (images only, no storage permission);
  - **Remove picture**: only when one is set, `error` text. It opens a decision dialog,
    "Remove your profile picture?" ("People will see your initials again."), with **Cancel**
    and **Remove**.
  - **Use your Google/Twitch picture**: **Dependency** (a browser OIDC round trip and the
    one-time `imported.php` handoff, B-series provider work). Leave it out.
- **Crop:** a full-screen square crop with a **circular preview mask** (people are circles in
  Lantern), pinch to zoom and drag to place, then **Cancel** and **Use photo**. The crop is
  done on the device, and the result is encoded locally as a square JPEG or WebP, at most 1024 px
  and well under 5 MB. Re-encoding drops location and camera metadata on the phone, before
  upload. The server crops and re-encodes again anyway.
  - A picked format the device can't decode: "This photo can't be used. Choose another."
- **Upload** shows progress in place on the pebble (a static ring, no shimmer). It's one
  explicit POST, **never retried automatically**.
  - Success: the pebble updates everywhere from the returned `avatar_version`.
  - `503 avatars_unavailable` (or `avatars_available: false`): the Choose row is replaced by
    the muted line "{installation} doesn't support profile pictures right now."
  - Too large or a refused type (the local encode makes this unlikely): "That photo couldn't be
    used. Try another."
  - **Unknown** (connection lost after sending): "Not sure your picture was saved." with
    **Check**, which re-reads the profile (`has_avatar`, `avatar_version`). Never a resend.
- **Accessibility:** the pebble button reads "Profile picture, change". The crop screen offers
  buttons as well as gestures (zoom in, zoom out, reset).

## Password

- **Change password** (form screen): current password, new password, confirm. Same fields,
  visibility toggles and policy text as Create account. The password rules from `AGENTS.md`
  apply: composition memory only, cleared on `ON_STOP`, never in bundles.
- A line above the button: "**Changing your password signs you out on all your other
  devices.**" Your recovery key keeps working.
- **Change password** (primary). On success, the level-1 confirmation "Password changed. Other
  devices were signed out." Step-up isn't needed: the current password is the proof.
- Errors: a wrong current password ("That's not your current password."), the password policy
  text, the 429 waiting-time copy. **Unknown:** "Not sure your password changed. Try signing in
  on another device to check." Nothing is resent.
- **Set a password** (provider-only account): a new password and confirmation only, through
  `withStepUp`. The lead line reads "Add a password so you can sign in without {provider}."
  Afterwards the Recovery key row appears ("Not set up").

## Two-step sign-in

**Screen** "Two-step sign-in":

- Lead: "Two-step sign-in asks for your security key or passkey after your password. If you
  can't use it, a recovery code gets you in once."
- **Status card:** "On", "Off" or "Required by {installation}" (when
  `required_by_admin_policy`; then Turn off is absent and a line says "Your role on
  {installation} requires two-step sign-in.").
- **Passkeys** list: one row per credential, showing the label, then "Added {date} · last used
  {date or 'never'}", plus "Synced" when `backup_state` is true. Only the label and dates are
  shown; transports and algorithm stay hidden.
  - Row actions (overflow): **Rename** (form dialog, 1–80 characters) and **Remove** (decision
    dialog: "Remove {label}? You won't be able to use it to sign in."). Remove is absent on the
    last passkey, with the note "To remove your last passkey, turn off two-step sign-in."
  - **Add a passkey**: **Dependency B1** (native passkeys through Credential Manager, trusted
    app origins and each server's Digital Asset Links). Until B1, the row reads "Adding passkeys
    in the app is coming. You can add one on {installation}'s website." It never opens a
    browser by itself.
- **Recovery codes:** "{n} of your recovery codes are left." When it's 3 or fewer, add "Make new
  codes soon." in honey.
  - **Make new recovery codes**: a decision dialog first, "Replace your recovery codes? Your
    current codes stop working right away." (**Cancel** / **Replace**, `error`), then
    `withStepUp`, then the **codes display** below.
- **Turn off two-step sign-in**: `error` text at the bottom, only when it's on and not required.
  A decision dialog says "Turn off two-step sign-in? Your passkeys and recovery codes will be
  removed, and you'll be signed out on your other devices." (**Cancel** / **Turn off**), then
  `withStepUp`. Afterwards normal revalidation runs. If this session ended too, sign-in follows.
- **Turning it on** happens by adding the first passkey, so it waits for **B1**. Until then, the
  Off state shows the same "on {installation}'s website" line.

**Codes display** (after regenerate, and after a first enrollment once B1 exists) reuses the
recovery-key display rules (recovery-key.md, "Handling the key"):

- memory only, `FLAG_SECURE`, Copy with the sensitive flag, an explicit Save to a file with the
  unencrypted warning, and a "Saved your recovery codes?" confirmation on Continue and Back;
- title "Save your recovery codes"; the codes in the monospace face, one per line, numbered;
  lead: "Each code works once. Use one when you can't use your passkey. These aren't your
  recovery key.";
- the saved file contains the host, username, the date and the codes, with the line "Each code
  works once."

**Step-up for MFA accounts** uses the existing dialog. A passkey needs B1. Until then, a
recovery code is the only option, with the existing emergency warning and remaining count
verbatim. When an account has passkeys but B1 isn't built, the dialog says so plainly: "Use a
recovery code, or do this on {installation}'s website."

## Sign-in methods

- A list of `identities` (provider label, "Connected {date} · last used {date or 'never'}"),
  plus "Password" when `has_password`.
- **Disconnect {provider}** (decision dialog, then `withStepUp`). `409 last_sign_in_method`:
  "You can't disconnect your only way to sign in. Set a password first." with **Set a password**.
- **Connect {provider}**: **Dependency** (a browser OIDC round trip). Leave it out.

## Sessions

- **There's no backend API to list or end login sessions.** CCM doesn't show a sessions list
  and never implies one exists.
- Honest guidance in the Devices group, under Notification devices, as a muted line: "To sign
  out everywhere else, change your password."
- **Dependency:** a "Signed-in sessions" screen (device, last active, sign out one or all)
  needs a backend issue. The technical lead decides whether to file it. The push device list's
  `current_session` is not a session list and isn't presented as one.

## Account closure placement

Delete account stays where play-readiness.md §2 puts it: the last row of Privacy and security,
`error`-colored, with its own explanation screen, step-up and outcomes. No change.

## Tests

- **Unit:** row visibility (`available`, `required_by_admin_policy`, `has_password`, providers,
  `avatars_available`); the last passkey has no Remove; the recovery-code count line and its
  low-count threshold; Unknown outcomes check by GET and never resend; codes and keys never in
  saved state; the 409 last-method mapping; no sessions row.
- **Compose:** the crop screen with button alternatives; dialogs' copy and `error` actions; the
  codes display rules (Copy sensitive, Save warning, Saved? confirmation); B1 placeholders never
  open a browser.
- **Native:** password fields follow the native input contracts.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-142 | Privacy and security groups Sign-in, Devices, Privacy, Your data and Delete account as specified. Rows hide when the server doesn't offer them. No sessions list exists until a backend API does. | C |
| P-143 | Profile pictures are cropped and re-encoded on the device (square, at most 1024 px, metadata dropped) before one explicit upload. Remove asks first. Unknown results re-read the profile, never resend. Provider import is left out until its dependency is built. | U, C |
| P-144 | Changing the password says it signs out other devices, keeps the password out of saved state, and never resends on Unknown. | U, C |
| P-145 | Two-step sign-in shows passkeys (label and dates only), protects the last passkey and admin-required MFA, regenerates codes only after a confirmation and step-up, and shows no TOTP option. Adding passkeys and passkey step-up are B1 dependencies and never open a browser. | U, C |
| P-146 | Recovery codes are shown once under the recovery-key display rules (memory only, `FLAG_SECURE`, sensitive Copy, explicit unencrypted Save, a Saved? confirmation) and are distinguished from the recovery key. | U, C |
| P-147 | Sign-in methods list identities and the password. Disconnect needs step-up and maps `409 last_sign_in_method`. Connecting is left out until its dependency is built. | U, C |
