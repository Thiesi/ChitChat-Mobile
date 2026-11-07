# Onboarding restyle: implementation specification

Status: implementation-ready presentation spec for the onboarding slice. The target
experience is in [onboarding.md](onboarding.md), and this document maps it onto today's code
and contracts. The coordinator schedules the slice. Mockups: boards 1–4 (`Main`,
`OnboardServer`, `OnboardFound`, `FirstRooms`) plus `SignIn`, `CreateAccount` and
`ServerProblem` on the canvas.

Behavior is unchanged unless this document says otherwise. M2a, M2b1 and M2b2 contracts,
the secret-handling rules in `AGENTS.md` and the native input contracts all stay in force.

## Prerequisite

The **theme foundation** must land first: Lantern tokens, the bundled display font (D2),
shapes, dynamic color off (D4) and the System/Light/Dark + Pure black settings (see
[amoled-black.md](amoled-black.md)), without layout changes (P-01 to P-08). The technical
lead's sequence (issue #1; push moved up by maintainer decision, 2026-11-07) is: theme
foundation → files/media → push (M6) → onboarding restyle → navigation shell → reporting →
transcript, lists, status and modals → Send when back online.

## Today's flow (as of 2026-11-07)

- One `home` route renders everything. With no saved server it shows `ServerEditor`, which
  has the welcome title and body, the address field, **Save server**, the feature-preview
  disclosure (`preview_limit`) and the privacy card. With a saved server it shows "Saved
  server", the host and `AuthScreen`, whose content depends on `SessionPhase`, plus hub
  buttons once authenticated.
- `AuthScreen` (ANONYMOUS phase) shows the sign-in form by default, with text buttons for
  Restore account and Create account (only when registration is enabled and there is no
  lockdown). The anti-bot preparation, birth-date picker and ambiguous-registration warning
  live in `RegistrationForm`.
- Saving the address stores it locally, then session bootstrap validates the server.
  Failures appear as `InlineError` inside `AuthScreen`.

## Target screens and their mapping

### 1 Welcome (new)

- Shown when no server is saved and the app is not in a storage-failure state. Always dark
  (Night ink, or black when Pure black is on). Mark, headline, one sentence, **Get started**,
  **Scan an invite code** (once the invite feature ships; until then omit the button, not
  disable it).
- **Feature-preview disclosure:** the `preview_limit` text must stay reachable before
  sign-in (`AGENTS.md` requires keeping availability disclosures). Show a text button
  "What works in this preview" under the buttons, opening a bottom sheet with the existing,
  updated disclosure text. Remove this when a release replaces the disclosure.
- System Back exits the app. The welcome screen is a route of its own (for example
  `welcome`), so restoring after process death returns to it, not to an empty editor.

### 2 Where do you chat? (restyled `ServerEditor`)

- Display title "Where do you chat?", body copy from onboarding.md, a 56dp form field
  labelled "Server address" (URI keyboard, no autocorrect, IME action Go), and a full-width
  **Continue** at the bottom of the screen.
- Validation stays as it is (M2a `ServerAddress.parse`), shown on press, not while
  typing. The three existing field errors keep their meaning, with new copy (see the copy
  table).
- **Connecting** is shown in place. Continue becomes a busy button "Looking for
  chat.example.org…" and the field is read-only. There is no separate spinner screen.
  `SessionPhase.CONNECTING` and `NO_SERVER` with a pending save both render this way.
- **Failure returns here with the address kept.** When bootstrap fails for a server saved
  during this onboarding (TLS, network, timeout, incompatible, redirect, 503), the person
  sees this screen with a level-2 notice above the field (copy table) and the typed address
  intact, ready to edit and press Continue again. The `ServerProblem` mockup shows this.
  Whether the address is stored before validation remains an implementation choice. The
  person must experience one step with one outcome, and an incompatible host must never
  appear as "Found it".
- The privacy card moves to step 3 (below). Nothing else on this screen.

### 3 Server found (new chooser; ANONYMOUS phase)

- Shown when the anonymous bootstrap succeeds. A card with the installation lantern (initial
  and identity hue from the host), the host as the name, and a lock icon. Use the installation
  name only once the server exposes the effective name (backend issue #149). The configured
  default name from health must not be shown as if it were the installation's name.
- Headline "Found it. Are you new here?" and the buttons:
  - **Create my account**, only if `public.registrationEnabled == true` and lockdown is
    off. Otherwise replace it with the line "Sign-up is closed on this server. Ask the person
    who runs it for an invite."
  - **I already have one, sign in**.
  - **Look around as a guest**, only once guest visits exist (M7) and the server allows
    them. Omit it until then.
  - A quiet text link **Restore a closed account** (existing RESTORE flow).
- Level-2 notices on this screen as needed, at most one at a time, in this precedence:
  maintenance lockdown (with the server's message if any), then providers-unavailable.
- The privacy line from the old card, restyled as a muted line with an info icon: "Your
  server hosts your conversations. Messages are not end-to-end encrypted."
- **Use a different server** (text button) is available while not authenticated and returns
  to step 2 with the field empty.
- `AuthFlow` selection now happens here instead of through text buttons under the sign-in
  form. Back from a form returns to this chooser, not to the address.

### 4a Sign in (restyled `PasswordForm`)

- Server chip at the top (lantern plus host, not interactive), display title "Welcome back",
  username and password fields (56dp, labels above, password visibility toggle with the label
  "Show password"), full-width **Sign in**, and a text button "Forgot your password? Ask the
  person who runs the server." (static text: today only administrators can reset a forgotten password). When the server offers self-service reset ([ChitChat#155](https://github.com/Thiesi/ChitChat/issues/155)), replace it with a **Forgot password?** button leading to that flow.
- Existing rules stay: the password lives only in composition memory, is cleared on
  `ON_STOP` and is never saved in bundles. `contentType` hints for autofill remain.
  Rate-limit countdown (`retryAtMillis`) shows as a level-1 line under the button:
  "Too many attempts. Try again in 12 s."

### 4b Create account (restyled `RegistrationForm`)

- Server chip, display title "Make yourself at home", the policy line ("Your account is
  created on chat.example.org, not with the app maker. The server decides what you can do."),
  username with its policy as supporting text, password, confirm password, then the optional
  birth date as an outlined button "Add birth date (optional)" with its explanation as
  supporting text.
- **Anti-bot preparation** shows as a calm row above the submit button:
  - preparing: an indeterminate linear bar and "Making sure you're a person. This takes a
    few seconds; you can keep filling in the form.";
  - ready: a check icon and "Ready";
  - failed or idle: "That check didn't finish." with **Try again**.
  
  Never a percentage. **Create my account** stays disabled until ready, exactly as today.
- The ambiguous-result warning (`registration_unknown`) becomes a level-2 notice at the top
  of the form with a **Sign in instead** action. The request is never repeated automatically
  (existing rule).
- After success, go to step 5. "Registered in this session" is VM memory only, never
  persisted, and is cleared on sign-out.

### 4c One more step (restyled PENDING_MFA)

- Display title "One more step", the recovery-code explanation (existing copy, kept
  verbatim where it carries the finite-code warning), the code field, **Use recovery code**,
  and **Cancel sign-in**. Remaining-count and restoration copy are unchanged in meaning.

### 5 Find your people (new; new accounts only)

- As specified in onboarding.md, using the existing room directory and join operations
  (M3a). Each **Join** is its own confirmed action, showing "Joined" only after the server
  confirms. Failures show inline on that row with explicit reconciliation, never a replay.
- **Start chatting** continues to the main screen. That is today's hub until the navigation
  slice lands, and the Rooms tab afterwards.
- Returning sign-ins skip this step.

### Other phases

`INVALIDATED`, `SIGNED_OUT`, `GUEST` and storage-warning states keep their behavior and copy
intent. They adopt the new tokens, type and button styles, and their primary action becomes
the full-width primary button. They are not part of the first-run flow.

## Copy changes

New and changed strings. Keys are suggestions; keep existing keys where meaning is unchanged.

| Where | Old | New |
|---|---|---|
| Welcome title | Your conversations. Your server. | Come in. The lights are on. |
| Welcome body | A native home for your ChitChat community. Start by saving the address of your server. | ChitChat lives on a server run by your community. Bring its address and we'll take you there. |
| Step 2 button | Save server / Saving… | Continue / Looking for %1$s… |
| Field hint | Use your server's HTTPS address. | Whoever invited you knows it. It looks like a website address. |
| `invalid_server` | Enter a valid server address. | That doesn't look like a web address. Check for typos. |
| `https_required` | Your server must use HTTPS. | ChitChat only connects securely. Use an address that starts with https, or just the name. |
| `server_origin_only` | Use only the server address, without a page path, query, password or fragment. | Use just the server's address, like chat.example.org, without anything after it. |
| Network or timeout on step 2 | The request could not be confirmed… | %1$s isn't answering. Check the address and your connection, then try again. |
| Device offline on step 2 | (none) | No connection. Connect to the internet and try again. |
| `tls_error` on step 2 | The server certificate could not be verified… | We couldn't verify %1$s's security certificate. Check the address. This can't be bypassed. |
| `incompatible_server` on step 2 | The server returned an incompatible response… | %1$s doesn't look like a ChitChat server. Ask the person who invited you for the exact address. |
| `server_unavailable` | The server is temporarily unavailable. Please try again later. | %1$s is busy or down right now. Try again in a few minutes. |
| `connected_to_server` | Connected to your server | Found it. Are you new here? |
| `create_account_title` | Join this server | Make yourself at home |
| `preparing_registration` | Preparing the server's anti-bot check. You can fill in the form meanwhile. | Making sure you're a person. This takes a few seconds; you can keep filling in the form. |
| `registration_disabled` | Registration is currently disabled on this server. | Sign-up is closed on this server. Ask the person who runs it for an invite. |

Messages about sessions after sign-in (`network_error` with "refresh the session",
`session_retention`, recovery counts) keep their current meaning. Only their presentation
changes in this slice.

## Tests

- `FoundationJourneyTest` finds controls by visible text ("Save server"). The slice updates
  those tests deliberately. Add stable test tags so future copy edits don't break journeys:
  `onboarding_welcome_start`, `onboarding_server_input`, `onboarding_continue`,
  `onboarding_create`, `onboarding_sign_in`, `onboarding_restore`, `onboarding_other_server`.
- Keep every native input contract. The server field and the credential fields are
  keyboard surfaces: one physical tap must acquire focus and IME, with 48dp targets, 5 s
  deadlines and no retries (see `AGENTS.md` and `docs/milestones/system-native-taps.md`).
- Add Compose tests for these:
  - the step-2 failure path keeps the typed address;
  - the chooser hides Create when registration is disabled or locked down;
  - Back from a form returns to the chooser;
  - Welcome survives recreation;
  - the preview disclosure is reachable before sign-in;
  - passwords are absent from saved state after recreation.
- A-04 at 200% font scale on 360×640dp: every onboarding screen scrolls to its primary
  action, and the IME never hides Continue, Sign in or Create my account.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-50 | First launch with no saved server shows Welcome. Get started leads to step 2, Back exits, and recreation and process death return to the same step. | C |
| P-51 | Step 2 validates on press with the new copy, shows connecting in place, and on any bootstrap failure returns to step 2 with the address kept and one level-2 notice. An incompatible server never reaches step 3. | C, U |
| P-52 | Step 3 appears only after a successful anonymous bootstrap and shows the host (not the health default name). Create appears only when registration is enabled and there is no lockdown. Guest stays hidden until M7. | C |
| P-53 | Sign-in, registration and recovery behavior, secret handling and error mapping are unchanged apart from the copy table. Existing auth tests pass unchanged in meaning. | C, U, existing tests |
| P-54 | New accounts see Find your people. Returning sign-ins don't. Each Join is confirmed individually and never replayed. | C |
| P-55 | The feature-preview disclosure and the not-end-to-end-encrypted line are reachable before sign-in. | C |
| P-56 | No permission is requested anywhere in this flow. Camera permission arrives only with the invite slice, on Scan. | R |
