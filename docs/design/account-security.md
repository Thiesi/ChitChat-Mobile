# Account and security (M6): presentation specification

Status: presentation spec for the M6 account and security screens, written ahead of the
engineering slices (technical lead, 2026-11-09). It's grounded in the ChitChat API docs at main
`1ddd59c` (`docs/api/account.md`, `mfa.md`, `authentication.md`, `password-recovery.md`,
`web-push.md`). It designs only what those APIs offer, and marks anything missing as a
**Dependency**. Mockup: `PrivacySecurity` on the canvas.

Maintainer decisions on 2026-11-09 (issue #1, comment 6734871007): authenticator-app codes
(TOTP) are wanted as an additional method (**Dependency ChitChat#204**), and a signed-in
sessions list is wanted (**Dependency ChitChat#205**). Native passkeys (B1, ChitChat#145) are
merged on the backend but **not in v3.5.0**. They and the browser sign-in handoff (ChitChat#146)
come in the next release. CCM's minimum is 3.5.0 since 2026-11-09 (issue #1, comment
6737288486). Like every feature from a later release, these ship only once the technical lead
raises the minimum server version again. There are no per-feature version checks or
older-server fallbacks.

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
- **Two-step sign-in (MFA):** passkeys (WebAuthn) plus single-use recovery codes today.
  **Authenticator-app codes (TOTP) are a Dependency on ChitChat#204** (requested by the
  maintainer). The TOTP design below follows the issue's proposed shape and is adjusted to the
  merged contract before it's built.
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
- **Native passkeys (B1, ChitChat#145, merged, next release after 3.5.0):** WebAuthn
  ceremonies also accept the app's `android:apk-key-hash` origin when the operator lists the app
  in `NATIVE_ANDROID_APPS`.
  `capabilities.native_passkey_origins === true` says this server is configured for it. That's
  an operator setting, not a version check: on a server at a minimum that includes #145
  but hasn't configured the app, passkey actions keep the "on {installation}'s website" lines.
- **Sessions:** **there's no API yet to list or end your login sessions** (**Dependency
  ChitChat#205**). The only device list today is push subscriptions, with `current_session`
  (#147). A password change ends all other sessions.
- **Step-up:** password accounts use `step-up.php`. MFA accounts use a passkey
  (`mfa/step-up-*`) or a recovery code (`mfa/step-up-recovery.php`), and an authenticator code
  once #204 exists. Step-up lasts 10 minutes by default and is bound to this session.

## Where it lives

**You → Privacy and security**, grouped as below (mockup `PrivacySecurity`). Each row is a
settings row (title, supporting text, chevron when it opens a screen).

1. **Sign-in**
   - **Password**: "Change password", or "Set a password" for a provider-only account.
   - **Two-step sign-in**: "On · 2 passkeys · 7 recovery codes left", "Off", or "Required by
     {installation}". With #204: "On · Authenticator app · 2 passkeys · 7 recovery codes
     left". Hidden when `available` is false.
   - **Recovery key** (recovery-key.md).
   - **Sign-in methods**: "Password and Google", for example. Shown only when the server
     offers providers or the account has a linked identity.
2. **Devices**
   - **Notification devices**: opens You → Notifications → Devices (push-presentation.md), where
     "This phone" is marked.
   - **Signed-in sessions**: **Dependency ChitChat#205**. Leave it out until then. See
     "Sessions".
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
  can't use it, a recovery code gets you in once." With #204: "Two-step sign-in asks for a
  second step after your password: a code from your authenticator app, or your passkey. If you
  can't use either, a recovery code gets you in once."
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
    app origins and each server's Digital Asset Links). The backend half (ChitChat#145) is
    merged for the release after 3.5.0. It activates once the minimum includes it, on servers where
    `native_passkey_origins` is true. Until then, the row reads "Adding passkeys in the app is
    coming. You can add one on {installation}'s website." It never opens a browser by itself.
- **Recovery codes:** "{n} of your recovery codes are left." When it's 3 or fewer, add "Make new
  codes soon." in honey.
  - **Make new recovery codes**: a decision dialog first, "Replace your recovery codes? Your
    current codes stop working right away." (**Cancel** / **Replace**, `error`), then
    `withStepUp`, then the **codes display** below.
- **Turn off two-step sign-in**: `error` text at the bottom, only when it's on and not required.
  A decision dialog says "Turn off two-step sign-in? Your passkeys and recovery codes will be
  removed, and you'll be signed out on your other devices." (**Cancel** / **Turn off**), then
  `withStepUp`. Afterwards normal revalidation runs. If this session ended too, sign-in follows.
- **Turning it on** happens by adding the first passkey (B1) or, with #204, by setting up an
  authenticator app. Until either is available, the Off state shows the same "on
  {installation}'s website" line.

**Authenticator app** (**Dependency ChitChat#204**; leave it out until the API exists):

- **Row** in Two-step sign-in, above Passkeys: "Authenticator app", with "Set up" or "On ·
  added {date}".
- **Set up** (form screen, through `withStepUp`), titled "Set up an authenticator app", with
  `FLAG_SECURE` for the whole screen:
  - lead: "Scan this code with an authenticator app, then enter the 6-digit code it shows.";
  - the **QR code** of the server's `otpauth://` data, drawn on the device at 200dp or larger
    on a white tile in both themes (scanners need the contrast), described for screen readers
    as "QR code for your authenticator app";
  - **Can't scan it?** expands the **setup key** in the monospace face, grouped in fours, with
    **Copy key** (sensitive flag) and the account line "{username} on {installation}";
  - **Code** field (6 digits, numeric keyboard, one-time-code autofill hint), then **Turn on**.
  - A wrong code: "That code didn't match. Check your phone's time is set automatically, then
    try the newest code." Nothing is retried for them.
- The secret and the URI are **memory only**: never in saved state, logs, screenshots or the
  clipboard beyond the explicit Copy. Leaving the screen before confirming discards them, and
  setup starts over with new data.
- If this enrollment turns two-step sign-in on and the server returns recovery codes, the
  **codes display** follows.
- **Remove** (row overflow): a decision dialog, "Remove the authenticator app? Its codes will
  stop working." (**Cancel** / **Remove**, `error`), then `withStepUp`. Absent when it's the
  last method and admin policy requires two-step sign-in, with the same note as the last
  passkey.

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
verbatim. When an account has passkeys but B1 isn't available, the dialog says so plainly: "Use
a recovery code, or do this on {installation}'s website."

**Authenticator codes in step-up and MFA sign-in** (**Dependency ChitChat#204**): when the
account has an authenticator app, the first choice is "Enter the code from your authenticator
app" (6 digits, numeric, one-time-code autofill hint), with "Use your passkey" (B1) and "Use a
recovery code" as alternatives below. The same field appears in the MFA sign-in step,
restoration and the password-reset MFA step. A wrong or reused code: "That code didn't work.
Wait for a new one and try again." The 429 and lockdown copy stay as they are. Codes are never
saved, and a submitted code is never resent.

## Sign-in methods

- A list of `identities` (provider label, "Connected {date} · last used {date or 'never'}"),
  plus "Password" when `has_password`.
- **Disconnect {provider}** (decision dialog, then `withStepUp`). `409 last_sign_in_method`:
  "You can't disconnect your only way to sign in. Set a password first." with **Set a password**.
- **Connect {provider}**: **Dependency** (a browser OIDC round trip). Leave it out.

## Sessions

- **There's no backend API yet to list or end login sessions.** CCM doesn't show a sessions
  list and never implies one exists.
- Until then, honest guidance in the Devices group, under Notification devices, as a muted
  line: "To sign out everywhere else, change your password."
- The push device list's `current_session` is not a session list and isn't presented as one.

**Signed-in sessions** (**Dependency ChitChat#205**; leave it out until the API exists, then
adjust to the merged contract):

- **Screen** "Where you're signed in". Lead: "These are the places your account is signed in
  right now."
- **This device** first: the server's client label (for example "ChitChat for Android"),
  "Signed in {date}", and the tag "This phone". It has no sign-out action here (Sign out lives
  on the You tab).
- **Other sessions** below, newest activity first: the label, "Last active {coarse time}" and
  "Signed in {date}". Only server-derived labels are shown, never raw addresses. Each row has
  **Sign out** (decision dialog: "Sign out {label}? It will need to sign in again." with
  **Cancel** / **Sign out**).
- **Sign out all other sessions** (`error` text at the bottom, only when others exist): a
  decision dialog, "Sign out everywhere else? Every other device will need to sign in again,
  and their notifications stop." Then `withStepUp` if the server requires it.
- After either action the list re-reads. Unknown results re-read and never resend. The muted
  password line is removed once this screen exists.

## Account closure placement

Delete account stays where play-readiness.md §2 puts it: the last row of Privacy and security,
`error`-colored, with its own explanation screen, step-up and outcomes. No change.

## Tests

- **Unit:** row visibility (`available`, `required_by_admin_policy`, `has_password`, providers,
  `avatars_available`); the last passkey has no Remove; the recovery-code count line and its
  low-count threshold; Unknown outcomes check by GET and never resend; codes and keys never in
  saved state; the 409 last-method mapping; no sessions row until #205; no authenticator rows
  until #204; passkey actions only with `native_passkey_origins`; the TOTP secret and URI held
  in memory only and discarded when setup is left.
- **Compose:** the crop screen with button alternatives; dialogs' copy and `error` actions; the
  codes display rules (Copy sensitive, Save warning, Saved? confirmation); B1 placeholders never
  open a browser; the TOTP setup screen with `FLAG_SECURE`, the QR description and the setup
  key alternative.
- **Native:** password and code fields follow the native input contracts.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-142 | Privacy and security groups Sign-in, Devices, Privacy, Your data and Delete account as specified. Rows hide when the server doesn't offer them. No sessions list exists until ChitChat#205 is available. Then "Where you're signed in" shows this device and other sessions with server labels only, and signs out one or all others after a confirmation, never resending on Unknown. | U, C |
| P-143 | Profile pictures are cropped and re-encoded on the device (square, at most 1024 px, metadata dropped) before one explicit upload. Remove asks first. Unknown results re-read the profile, never resend. Provider import is left out until its dependency is built. | U, C |
| P-144 | Changing the password says it signs out other devices, keeps the password out of saved state, and never resends on Unknown. | U, C |
| P-145 | Two-step sign-in shows passkeys (label and dates only), protects the last passkey and admin-required MFA, and regenerates codes only after a confirmation and step-up. Adding passkeys and passkey step-up are B1 dependencies, need `native_passkey_origins`, and never open a browser. No authenticator option appears until ChitChat#204 is available. Then setup runs under step-up and `FLAG_SECURE` with a QR code and a copyable setup key held in memory only, is confirmed by a first code, and authenticator codes are offered in step-up, MFA sign-in, restoration and reset without saving or resending them. | U, C |
| P-146 | Recovery codes are shown once under the recovery-key display rules (memory only, `FLAG_SECURE`, sensitive Copy, explicit unencrypted Save, a Saved? confirmation) and are distinguished from the recovery key. | U, C |
| P-147 | Sign-in methods list identities and the password. Disconnect needs step-up and maps `409 last_sign_in_method`. Connecting is left out until its dependency is built. | U, C |
