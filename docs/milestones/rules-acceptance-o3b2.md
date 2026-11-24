# Rules acceptance O3b2: the guidelines screen and the Create-form consent line

The first screens for community-rules acceptance, from [play-readiness.md](../design/play-readiness.md) §1 (P-170,
P-171; mockups `Guidelines` and `GuidelinesServer`). They build on [O3b1](rules-acceptance-o3b1.md)'s protocol and
local store. Not in this slice:
- O3b3: re-acceptance with Not now for an account whose server rules changed, and the per-path
  `403 rules_acceptance_required` handling on gated writes.
- Guest acceptance: M7.

## "Before you chat"

`GuidelinesScreen` sits between sign-in and the shell. It shows for any signed-in member account with no local
guidelines acceptance on this device (per server, per account, the current `APP_GUIDELINES_VERSION`). For a new account
it comes before Find your people.
- **Layout:** full screen with no bar, like the server-update screen. The server-update screen takes precedence. While
  the local record is still being read, a plain surface shows, never a flash of either screen.
- **Nothing of the shell is composed until I agree,** so no screen can write before acceptance.
- **Back** leaves the app. It doesn't skip the screen; there's no handler. The one exception: Back closes a server
  document opened from the screen.
- **Copy:** the approved guidelines text, verbatim. The operator line reads "may have additional rules" when the server
  publishes no rules, and "set the rules above" when it does.
- **With `policies.rules`:**
  - a "{installation}'s rules" label, then a `raised` card with "Read {installation}'s rules" ("Updated {date}" in the
    device's medium date format) and, when published, "{installation}'s privacy policy";
  - each row is at least 48dp, with decorative icons, and opens the O3a document screen in place.
- **The consent line** ("By tapping I agree, you accept these guidelines and {installation}'s rules.") shows only while
  `user.rules.acceptance_required` is true.
- **I agree** (the single primary action, busy "Saving…") and **Sign out** (the ordinary sign-out).

## I agree

`GuidelinesViewModel.agree(session)`:
- **No server rules, acceptance not required, or status unknown:** records the local acceptance only, with no request.
- **Acceptance required:** one `acceptRules` POST of the shown `rules.version`. The local record is written only
  after the server confirms. A second tap while saving sends nothing.
  - **Outdated (409):** a session GET, then "{installation} updated its rules just now. Please look again." The screen
    shows the new version. Never accepted automatically.
  - **Unknown:** a session GET. If the same session no longer requires acceptance, record and move on. Otherwise the
    screen stays with the status line and I agree is enabled again. Never resent.
  - **Failed:** the existing status ladder; the screen stays.
- **Scoping:** the state is memory only, scoped to the account and generation it was made for.

## Create form (onboarding step 4b)

When the session publishes `policies.rules`, a line sits above Create my account: "By creating an account, you agree
to {installation}'s rules." With `policies.privacy`, it continues "and acknowledge its privacy policy". The linked
words open the document as a full-screen dialog over the form (`PolicyDocumentDialog`: the O3a bar with icon Back and
"Rules" or "Privacy policy", announced by that title as its pane title, on the shell background). The form stays
composed, so it keeps the typed password and birth date without saving them anywhere (PR #273 review). A document
opened from the guidelines screen uses the same bar. Registration sends the rendered version (O3b1).
- **`409 rules_version_outdated`:** "{installation} updated its rules while you were signing up. Look at them, then
  create your account again." The notice now survives O3b1's automatic session re-read; the repository restores it
  unless something newer replaced it.
- **`400 rules_acceptance_required`** (a client bug): "Something went wrong creating your account. Your details are
  kept; try again." (PL copy.)
- **The form keeps its fields** under the existing rules, and nothing resends.

## Open L4 item (manual, not a CI claim)

TalkBack focus return after closing the document dialog isn't proven by CI. CI runs without TalkBack, and Compose
can't place accessibility focus on a single link inside a Text. The platform normally restores focus when a dialog
window closes. Making the whole consent line focusable was rejected by the PL: it would hide the individual links from
TalkBack's link navigation. The check sits in [accessibility-verification.md](../design/accessibility-verification.md)
L4 walkthrough script 1 (First run): Create form, consent line, open the rules link, the pane announces "Rules", Back,
focus is back on the consent line. Record the result in the PR that performs the L4 walkthrough.

## The app's own privacy policy (2026-11-24)

The link that was left out here now exists (TL, PL): "The app's privacy policy", to the fixed
https://ChitChat.NetWorkXXIII.de/app/privacy (a constant, never a server value), opened only through
`ExternalLinkChoice` naming that host.
- **About and privacy:** a row with the open-external icon, after "Open-source licences" and before the server's
  documents.
- **"Before you chat":** a separate text link after the operator line, shown with or without the server's documents.
- **Welcome:** "Privacy policy" at its foot (no accessibility-feedback footer there).
- The label is never "ChitChat's": an installation can be named ChitChat.
- **Not merged until the page is live:** the link ships only once ops confirms the page (P-174 publishes it after its
  own approval). Play needs it before distribution.

## Verification

- **`GuidelinesViewModelTest` (5):**
  - with no POST needed, I agree records locally;
  - a required acceptance posts the shown version once, records only after the server confirms, and ignores a second
    tap;
  - Outdated re-reads and doesn't record;
  - Unknown moves on only when the re-read no longer requires acceptance;
  - a refusal stays with its failure.
- **`SessionRepositoryTest`:** the registration 409 notice survives the re-read.
- **`GuidelinesJourneyTest`** (real app root):
  - nothing of the shell shows before I agree, then the shell opens;
  - an accepted account goes straight to the shell;
  - required rules send one POST with the consent line;
  - the rules row opens the document and Back returns;
  - Sign out records nothing.
- **`GuidelinesPresentationTest`:**
  - captures without rules, with rules (required), and after a 409, each at its top and scrolled to the actions;
  - the Create form's consent line;
  - default and 200% (real Configuration font scale), light and dark;
  - every action reachable by scrolling, with 48dp targets;
  - collected by `native_presentation.collect_guidelines` (29 files: the 28 screen captures plus the document dialog over the Create form, dark at 200%).
