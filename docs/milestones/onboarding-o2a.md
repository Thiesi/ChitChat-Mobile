# Onboarding O2a: the flow skeleton

Implements [onboarding-restyle.md](../design/onboarding-restyle.md)'s behaviour, copy and test tags for steps 1–4.
The visual restyle (installation lantern and identity hue, the server chip, 56dp labels-above fields, the always-dark
Welcome) is deferred to the Fable overhaul. Find your people is O2b.

## Steps

1. **Welcome** (no server saved): the copy-table title and body, Get started (`onboarding_welcome_start`), and "What
   works in this preview" (`onboarding_preview`), a sheet with the existing disclosure (P-55). Back exits. No Scan
   button until invite codes ship.
2. **Where do you chat?** (`ServerEditor`): validation on press, Continue (`onboarding_continue`, IME Go, no
   autocorrect).
   - Connecting is shown in place: "Looking for {host}…", with the field read-only.
   - A failed bootstrap of a server saved during this onboarding returns here with the address kept and one notice
     from the copy table: not answering, certificate, not ChitChat or busy (P-51). Continue on the same address is a
     fresh bootstrap.
   - An incompatible host never reaches step 3.
   - "No connection" isn't distinguishable from "isn't answering" with today's failure kinds, so both read "isn't
     answering".
   - The kept address is the saved, normalized one.
3. **Server found** (after a successful anonymous bootstrap, P-52):
   - The card (`onboarding_server_card`) shows the name, with the host always visible, and a lock. The host breaks
     after its dots for display only (#259's helper); semantics keep the raw host.
   - O1's update notice replaces the choices for an outdated server (a successful bootstrap, never step 2).
   - Otherwise: "Found it. Are you new here?"; one notice (lockdown first, then providers unavailable); **Create my
     account** (`onboarding_create`, only with registration on and no lockdown, else the closed line); **I already have
     one, sign in** (`onboarding_sign_in`; the outlined secondary when Create is shown, otherwise the one filled
     primary); **Restore a closed account** (`onboarding_restore`); **Use a different
     server** (`onboarding_other_server`, step 2 with the field empty).
   - Below the choices: the privacy line, the preview disclosure, and the P-221 accessibility feedback footer.
   - No guest entry (M7). The privacy-policy link (O3a) and Forgot password (O4b) follow with their slices.
4. **The forms:** today's sign-in, registration and restore forms (P-53 behaviour unchanged). Flow selection moved to
   Server found. Back (system, or the visible `onboarding_back`) returns to the chooser. Sign in is titled "Welcome
   back"; Create account "Make yourself at home".

Other phases (pending MFA, guest, invalidated, signed out, a cold start's connecting) keep today's screens. The step
state (Welcome passed, saved in this onboarding, flow chosen) is saved instance state, so recreation and process death
return to the same step (P-50).

## Verification

- `OnboardingJourneyTest`:
  - Welcome and Back;
  - the disclosure and privacy line before sign-in;
  - the failure path, incompatible and timeout, with Continue as a fresh bootstrap;
  - an outdated server on Server found, never step 2;
  - Create gating;
  - Back to the chooser;
  - the P-221 footer.
- `OnboardingRecreationTest` (P-50): a debug host that sets its content in onCreate like MainActivity, recreated on
  step 2 and on the chosen form; both survive. Recreation goes through the platform's own restore path. (A test-side
  setContent after recreate() doesn't restore, which is a harness artefact; PR #260 records those failures.)
- `OnboardingPresentationTest`: each step scrolls to its primary action at default and 200%, light and dark, and every
  chooser action is reachable (A-04). 18 captures (`ccm-onboarding-presentation`): each step,
  Server found unscrolled and scrolled, and a step 2 failure notice at 200%.
- Journeys that sign in through the form tap the chooser first (navigation-only). Copy-table changes update their text
  assertions.
