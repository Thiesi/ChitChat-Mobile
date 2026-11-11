# Account and security (M6): presentation specification

Status: presentation spec for the M6 account and security screens, written ahead of the
engineering slices (technical lead, 2026-11-09). It's grounded in the ChitChat API docs at main
`1ddd59c` (`docs/api/account.md`, `mfa.md`, `authentication.md`, `password-recovery.md`,
`web-push.md`), and at tag `v3.7.0` for `signed-in-sessions.md`, `totp.md` and `session.md`. It designs only what those APIs offer, and marks anything missing as a
**Dependency**. Mockup: `PrivacySecurity` on the canvas.

Maintainer decisions on 2026-11-09 (issue #1, comment 6734871007): authenticator-app codes
(TOTP, ChitChat#204) are wanted as an additional method, and a signed-in sessions list
(ChitChat#205) is wanted. **Both shipped in ChitChat v3.7.0, and CCM's minimum server version is
3.7.0 since 2026-11-11** (technical lead). Native passkeys (ChitChat#145) and the browser sign-in
handoff (ChitChat#146) shipped in v3.6.0, so the minimum includes their server halves too. The
app half of passkeys (B1, Credential Manager) is still to be built. There are no per-feature
version checks or older-server fallbacks. Operator settings (`capabilities.totp`,
`capabilities.native_passkey_origins`, `capabilities.signed_in_sessions`) still decide what a
given server offers.

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
- **Two-step sign-in (MFA):** passkeys (WebAuthn), authenticator-app codes (TOTP, #204) and
  single-use recovery codes.
  - `GET account/mfa/status.php` returns `{available, enabled, required_by_admin_policy,
    recovery_codes_remaining, credentials: [{id, label, transports, algorithm, backup_eligible,
    backup_state, created_at, last_used_at}], usable_methods, totp: {enabled, available,
    created_at, last_used_at}}`. `available` still means passkey (WebAuthn) availability.
    Unknown method names and keys are ignored.
  - **Authenticator codes** (`docs/api/totp.md`): `capabilities.totp` is true when this server
    offers setup (missing means unknown, false means not offered). Setup is `account/mfa/totp/`
    `begin.php` (returns a one-time Base32 `secret`, an `otpauth_uri` and `expires_at`, five
    minutes), `confirm.php {enrollment_id, code}`, `cancel.php` and `remove.php`, all with
    step-up. Only the account's first two-step method returns recovery codes. Proofs are
    `mfa/login-totp.php`, `mfa/step-up-totp.php` and `password-reset/mfa-totp.php`. Errors:
    `403 invalid_totp`, `409 totp_enrollment_expired`, `409 last_mfa_method`,
    `404 totp_not_found`, `503 totp_unavailable` and 429. Each code works once, and enabling,
    replacing or removing a method ends step-up on every login.
  - With step-up: enroll (`register-options`/`register-finish`, whose first enrollment returns
    the recovery codes once), rename (label 1–80 characters), remove (never the last passkey),
    regenerate recovery codes (the new set is returned once), and disable (refused when admin
    policy requires MFA; rotates the session version).
- **Sign-in methods:** `GET account/identities/list.php` returns `{providers, identities,
  has_password}`. Unlink needs step-up, and `409 last_sign_in_method` protects the last way in.
  Linking is a browser round trip.
- **Native passkeys (ChitChat#145, in the minimum since v3.6.0; the app half is B1):**
  WebAuthn ceremonies also accept the app's `android:apk-key-hash` origin when the operator
  lists the app in `NATIVE_ANDROID_APPS`. `capabilities.native_passkey_origins === true` says
  this server is configured for it. That's an operator setting, not a version check: on a server
  that hasn't configured the app, or until B1 is built, passkey actions keep the "on
  {installation}'s website" lines.
- **Signed-in sessions** (ChitChat#205, `docs/api/signed-in-sessions.md`):
  `capabilities.signed_in_sessions: true` advertises it. `GET account/sessions/list.php` returns
  `{sessions: [{id, current, created_at, last_active_at, client_hint, device_label}],
  maximum_sessions}`, current first, then by last activity. `client_hint` is a server-derived
  label such as "Firefox on Windows", "ChitChat app on Android" or "Unknown client".
  `POST revoke.php {id}` signs one out (`404 session_not_found`, `409 current_session`), and
  `POST revoke-others.php {}` signs out all others (step-up for members, returns
  `revoked_count`). These are sign-ins, not proof a device is online. A revoked login's next
  request gets `401 session_revoked`, and its live stream closes with `forced_logout` reason
  `session_revoked`. A password change still ends all other sessions.
- **Step-up:** password accounts use `step-up.php`. MFA accounts use a passkey
  (`mfa/step-up-*`), an authenticator code (`mfa/step-up-totp.php`) or a recovery code
  (`mfa/step-up-recovery.php`). Step-up lasts 10 minutes by default and is bound to this session.

## Where it lives

**You → Privacy and security**, grouped as below (mockup `PrivacySecurity`). Each row is a
settings row (title, supporting text, chevron when it opens a screen).

1. **Sign-in**
   - **Password**: "Change password", or "Set a password" for a provider-only account.
   - **Two-step sign-in**: "On · Authenticator app · 2 passkeys · 7 recovery codes left" (each
     part only when present), "Off", or "Required by {installation}". Hidden when two-step
     sign-in is off and neither passkeys (`available`) nor authenticator setup
     (`capabilities.totp`) is offered.
   - **Recovery key** (recovery-key.md).
   - **Sign-in methods**: "Password and Google", for example. Shown only when the server
     offers providers or the account has a linked identity.
2. **Devices**
   - **Notification devices**: opens You → Notifications → Devices (push-presentation.md), where
     "This phone" is marked.
   - **Where you're signed in**: "This phone and {n} other places", or "Only this phone".
     Shown when `capabilities.signed_in_sessions` is true. See "Sessions".
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

- Lead: "Two-step sign-in asks for a second step after your password: a code from your
  authenticator app, or your passkey. If you can't use either, a recovery code gets you in
  once." When this server offers no authenticator setup and the account has none: "Two-step
  sign-in asks for your security key or passkey after your password. If you can't use it, a
  recovery code gets you in once."
- **Status card:** "On", "Off" or "Required by {installation}" (when
  `required_by_admin_policy`; then Turn off is absent and a line says "Your role on
  {installation} requires two-step sign-in.").
- **Passkeys** list: one row per credential, showing the label, then "Added {date} · last used
  {date or 'never'}", plus "Synced" when `backup_state` is true. Only the label and dates are
  shown; transports and algorithm stay hidden.
  - Row actions (overflow): **Rename** (form dialog, 1–80 characters) and **Remove** (decision
    dialog: "Remove {label}? You won't be able to use it to sign in."). Remove is absent on the
    last passkey unless a working authenticator app is set up (`totp.enabled` and
    `totp.available`). Otherwise the note reads "To remove your last passkey, set up an
    authenticator app or turn off two-step sign-in." `409 last_mfa_method` or `409 last_passkey`
    re-reads the status and shows that note.
  - **Add a passkey**: **Dependency B1** (native passkeys through Credential Manager, trusted
    app origins and each server's Digital Asset Links). The backend half (ChitChat#145) is in
    the 3.7.0 minimum. The row activates once B1 is built, on servers where
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
- **Turning it on** happens by setting up an authenticator app or adding the first passkey
  (B1). When the server offers neither in the app, the Off state shows the same "on
  {installation}'s website" line.

**Authenticator app** (ChitChat#204, in the 3.7.0 minimum). The row shows when
`capabilities.totp` is true or the account already has one (`totp.enabled`). A missing or false
capability hides setup only. It never hides or disables an authenticator that's already on.

- **Row** in Two-step sign-in, above Passkeys: "Authenticator app", with "Set up" or "On ·
  added {date} · last used {date or 'never'}".
  - When it's on but `totp.available` is false (the server can't use its key right now), add
    the muted line "Codes from your authenticator app aren't working on {installation} right
    now. Use your passkey or a recovery code." The row stays. Nothing is removed.
- **Set up** (form screen, opened through `withStepUp` and `begin.php`), titled "Set up an
  authenticator app", with `FLAG_SECURE` for the whole screen:
  - lead: "Scan this code with an authenticator app, then enter the 6-digit code it shows.";
  - the **QR code** of the returned `otpauth_uri`, drawn on the device at 200dp or larger
    on a white tile in both themes (scanners need the contrast), described for screen readers
    as "QR code for your authenticator app". It's never sent to another service or opened as a
    link;
  - **Can't scan it?** expands the **setup key** (`secret`) in the monospace face, grouped in
    fours, with **Copy key** (sensitive flag) and the account line "{username} on
    {installation}";
  - **Code** field (6 digits, numeric keyboard, one-time-code autofill hint; spaces and hyphens
    are accepted and the server strips them), then **Turn on** (`confirm.php`).
- **Outcomes** of Turn on:
  - `403 invalid_totp`: "That code didn't match. Check your phone's time is set automatically,
    then try the newest code." The field clears and nothing is retried for them.
  - `409 totp_enrollment_expired` (setup lasts five minutes): "This setup expired. Start again
    for a new code." with **Start again**, which runs `begin.php` once more and replaces the QR
    code and key. The old ones are discarded.
  - `503 totp_unavailable`: "Authenticator codes aren't available on {installation} right now.
    Try again later." Setup closes.
  - 429: the waiting-time copy (status-presentation "Waiting times").
  - **Unknown** (the response was lost): "Not sure your authenticator app was turned on." with
    **Check**, which re-reads `mfa/status.php`. The code is never resent (each code works once),
    and if it isn't on, setup starts again with new data.
  - Success: if the server returns recovery codes (the account's first two-step method), the
    **codes display** follows. Otherwise the level-1 confirmation "Authenticator app turned on."
- The secret and the URI are **memory only**: never in saved state, logs, screenshots or the
  clipboard beyond the explicit Copy. Leaving the screen before confirming discards them and
  sends one `cancel.php`, which isn't retried (an unconfirmed setup expires on its own). Setup
  starts over with new data. A lost `begin.php` response also starts over: the server never
  repeats a secret.
- **Set up on a new phone** (row overflow, when it's on): the same setup screen, with the lead
  "Scan this code with the authenticator app on your new phone. Your current one keeps working
  until you turn this on." Recovery codes aren't replaced.
- **Remove** (row overflow): a decision dialog, "Remove the authenticator app? Its codes will
  stop working." (**Cancel** / **Remove**, `error`), then `withStepUp` and `remove.php`. The
  server allows it only when a passkey is set up, so Remove is absent without one, with the
  note "To remove the authenticator app, add a passkey or turn off two-step sign-in."
  `409 last_mfa_method` re-reads and shows that note. `404 totp_not_found` just re-reads.
- Turning on, replacing or removing a method ends step-up on every sign-in. The next sensitive
  action asks again through the existing dialog. No extra copy is needed.

**Codes display** (after regenerate, and after the account's first authenticator or passkey
setup) reuses the
recovery-key display rules (recovery-key.md, "Handling the key"):

- memory only, `FLAG_SECURE`, Copy with the sensitive flag, an explicit Save to a file with the
  unencrypted warning, and a "Saved your recovery codes?" confirmation on Continue and Back;
- title "Save your recovery codes"; the codes in the monospace face, one per line, numbered;
  lead: "Each code works once. Use one when you can't use your passkey. These aren't your
  recovery key.";
- the saved file contains the host, username, the date and the codes, with the line "Each code
  works once."

**Step-up for MFA accounts** uses the existing dialog. A passkey needs B1. A recovery code
keeps the existing emergency warning and remaining count verbatim. When an account has only
passkeys and B1 isn't available, the dialog says so plainly: "Use a recovery code, or do this on
{installation}'s website."

**Authenticator codes in step-up and MFA sign-in** (ChitChat#204): when an authenticator is
usable, the first choice is "Enter the code from your authenticator app" (6 digits, numeric,
one-time-code autofill hint), with "Use your passkey" (B1) and "Use a recovery code" as
alternatives below.

- **Step-up** offers it when `totp.enabled` and `totp.available` are true
  (`mfa/step-up-totp.php`).
- **MFA sign-in and restoration** take their choices from the session's
  `mfa_pending.methods` (`totp`, `passkey`, `recovery_code`; unknown names are ignored), never
  from installation capabilities (`mfa/login-totp.php`). The **password-reset MFA step** offers
  it when its pending reset lists `totp` (`password-reset/mfa-totp.php`).
- `403 invalid_totp`: "That code didn't work. Wait for a new one and try again." The field
  clears.
- `503 totp_unavailable`: "Authenticator codes aren't working on {installation} right now.
  Use your passkey or a recovery code." The authenticator choice is hidden for the rest of
  that dialog.
- The 429 and lockdown copy stay as they are. Codes are never saved, and a submitted code is
  never resent, including after an Unknown result.

## Sign-in methods

- A list of `identities` (provider label, "Connected {date} · last used {date or 'never'}"),
  plus "Password" when `has_password`.
- **Disconnect {provider}** (decision dialog, then `withStepUp`). `409 last_sign_in_method`:
  "You can't disconnect your only way to sign in. Set a password first." with **Set a password**.
- **Connect {provider}**: **Dependency** (a browser OIDC round trip). Leave it out.

## Sessions

**Where you're signed in** (ChitChat#205, in the 3.7.0 minimum; shown when
`capabilities.signed_in_sessions` is true). The push device list's `current_session` is still
not a session list and isn't presented as one.

- **Screen** "Where you're signed in". Lead: "These are the places where your account is
  signed in. Signing one out means it has to sign in again." Below it, muted: "This shows
  sign-ins, not whether a device is online right now."
- **This phone** first (the row with `current: true`): its `client_hint` (for CCM, "ChitChat
  app on Android"), "Signed in {date}", and the tag "This phone". It has no sign-out action
  here (Sign out lives on the You tab).
- **Other places** below, in the server's order (most recently active first): the
  `client_hint` as the title, then "Active {coarse time} · signed in {date}" (status-presentation
  relative times; the server keeps activity to the minute and updates it every few minutes).
  - Labels are server text shown as plain, one-line text, ellipsized, never parsed or linked.
    "Unknown client" is shown as the server sends it.
  - `device_label` isn't shown. Only the server's classification appears, never raw addresses
    or locations (the server stores none).
  - Each row has **Sign out**: a decision dialog, "Sign out {label}? It will need to sign in
    again, and its notifications stop." with **Cancel** / **Sign out** (`revoke.php`).
- **Sign out everywhere else** (`error` text at the bottom, only when other places exist): a
  decision dialog, "Sign out everywhere else? Every other device will need to sign in again,
  and their notifications stop." (**Cancel** / **Sign out**), then `withStepUp` and
  `revoke-others.php`. Success shows the level-1 confirmation "Signed out of {n} other
  places." from `revoked_count`.
- **Outcomes:** after every action the list re-reads.
  - `404 session_not_found`: it's already gone. Re-read without an error.
  - `409 current_session`: re-read (this phone signs out from the You tab).
  - 429: the waiting-time copy.
  - **Unknown:** re-read and never resend.
- **Footnote,** muted: "{installation} keeps up to {maximum_sessions} sign-ins. Signing in
  somewhere new can sign out the one that was used least recently."
- **When this phone is signed out elsewhere:** a `401 session_revoked` on any request, or a
  `forced_logout` event with reason `session_revoked` on the live stream, ends the session
  through the existing session-ended screen (communication.md) with the copy **"You were
  signed out from another device. Sign in again."** (`session_revoked` in copy-deck.md).
  Local session data is cleared as for any sign-out, and a fresh anonymous bootstrap runs
  before sign-in. Other invalid-session reasons keep `session_expired`.
- **Engineering note** (settled, technical lead 2026-11-11): CCM sends a User-Agent containing
  `ChitChat-Mobile` on every request, for example `ChitChat-Mobile/{app version} (Android
  {release})`, so its sign-ins read "ChitChat app on Android". It never includes the device
  model or name.
- **Upgrading a server to 3.7.0 signs everyone out once** (migration 0051). CCM shows the
  ordinary `session_expired` screen for it, not the revoked copy.

## Account closure placement

Delete account stays where play-readiness.md §2 puts it: the last row of Privacy and security,
`error`-colored, with its own explanation screen, step-up and outcomes. No change.

## Tests

- **Unit:** row visibility (`available`, `required_by_admin_policy`, `has_password`, providers,
  `avatars_available`); the last passkey has no Remove; the recovery-code count line and its
  low-count threshold; Unknown outcomes check by GET and never resend; codes and keys never in
  saved state; the 409 last-method mappings; the sessions row only with
  `capabilities.signed_in_sessions`; authenticator setup only with `capabilities.totp`, an
  enabled authenticator shown regardless; the unavailable-authenticator line; MFA sign-in
  choices from `mfa_pending.methods`; passkey actions only with `native_passkey_origins`; the
  TOTP secret and URI held in memory only, discarded with one unretried `cancel.php` when setup
  is left; `totp_enrollment_expired` restarts setup; `session_not_found` re-reads silently;
  `401 session_revoked` and `forced_logout` `session_revoked` show the revoked copy; the
  User-Agent contains `ChitChat-Mobile` and no device model.
- **Compose:** the crop screen with button alternatives; dialogs' copy and `error` actions; the
  codes display rules (Copy sensitive, Save warning, Saved? confirmation); B1 placeholders never
  open a browser; the TOTP setup screen with `FLAG_SECURE`, the QR description and the setup
  key alternative.
- **Native:** password and code fields follow the native input contracts.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-142 | Privacy and security groups Sign-in, Devices, Privacy, Your data and Delete account as specified. Rows hide when the server doesn't offer them. "Where you're signed in" (ChitChat#205, `capabilities.signed_in_sessions`) shows this phone first and other sign-ins with the server's `client_hint` only, signs out one or all others after a confirmation (all others through step-up), re-reads after every outcome and never resends on Unknown. A revoked session shows "You were signed out from another device. Sign in again." CCM's User-Agent makes it read "ChitChat app on Android" without a device model. | U, C |
| P-143 | Profile pictures are cropped and re-encoded on the device (square, at most 1024 px, metadata dropped) before one explicit upload. Remove asks first. Unknown results re-read the profile, never resend. Provider import is left out until its dependency is built. | U, C |
| P-144 | Changing the password says it signs out other devices, keeps the password out of saved state, and never resends on Unknown. | U, C |
| P-145 | Two-step sign-in shows passkeys (label and dates only), protects the last passkey and admin-required MFA, and regenerates codes only after a confirmation and step-up. Adding passkeys and passkey step-up are B1 dependencies, need `native_passkey_origins`, and never open a browser. Authenticator setup (ChitChat#204) appears only with `capabilities.totp`, runs under step-up and `FLAG_SECURE` with a locally drawn QR code and a copyable setup key held in memory only, is confirmed by a first code, and restarts on expiry. An enabled authenticator is always shown, with a line when the server can't use it. Authenticator codes are offered in step-up, MFA sign-in (from `mfa_pending.methods`), restoration and reset without saving or resending them. | U, C |
| P-146 | Recovery codes are shown once under the recovery-key display rules (memory only, `FLAG_SECURE`, sensitive Copy, explicit unencrypted Save, a Saved? confirmation) and are distinguished from the recovery key. | U, C |
| P-147 | Sign-in methods list identities and the password. Disconnect needs step-up and maps `409 last_sign_in_method`. Connecting is left out until its dependency is built. | U, C |
