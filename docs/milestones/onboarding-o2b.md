# Onboarding O2b: Find your people

Implements step 5 of [onboarding.md](../design/onboarding.md) and
[onboarding-restyle.md](../design/onboarding-restyle.md) §5 (P-54) on top of
[O2a](onboarding-o2a.md). The lantern glyph and identity hue per room are deferred to the Fable overhaul.

## When it shows

**New accounts only.**
- Submitting **Create** marks a new account as pending (saved instance state). The shell entry reads and clears that
  mark in one step, so the screen is shown once and never repeated. Any phase other than signed out-but-onboarding or
  signed in (a sign-out, an ended session) clears it too, so a later sign-in never shows it.
- Sign-in and restore never set it. A sign-up that ended "uncertain" and was followed by a sign-in doesn't either.
- Registration and sign-in share the session's `authenticated()` path, so the session can't tell a new account apart.
  The UI remembers that the submitted flow was Create.

**Ordering ahead:** after register the spec order is recovery key (O4a), then guidelines and rules (O3b), then Find
your people. O4a and O3b insert their screens *before* this step through `firstNewAccountStep()`, without reworking
the trigger.

## The screen

`find_people` is a pushed destination in the shell, under the Rooms tab, so there's no bar. It shows:
- the heading "Welcome, {name}. Pull up a chair.";
- the body naming the installation (application name, else the host with the display-only dot break);
- the line saying notifications won't be asked about now;
- the server's open (`public`) rooms, each with its name and info line.

**Start chatting** is the single exit. It's pinned below the list, so it's reachable without scrolling at any font
size, and it returns to the Rooms root.

**No icon Back:** this is a deliberate exception to P-76 for this one onboarding destination, approved by the
technical lead. The account already exists, so there's nothing to go back to. **System Back does exactly what Start
chatting does:** it goes to the Rooms root and discards this screen.

## Joining

Joining reuses M3a's `RoomsViewModel`, single-flight `start()` and session gating. No new protocol.
- **`joinListed(id)`:** one CSRF join POST for a listed, not-member, public room, then the ordinary list reconcile.
  "Joined" (in ink, weight 600, announced once through a polite live region) appears only from that confirmed
  membership. No pre-checked boxes, no "join all", no auto-join.
- **One join at a time:** every other row's Join is disabled while a join is in flight or uncertain.
- **Definite refusal** (a mapped 4xx): its outcome is known. The ordinary list GET reconciles at once (M3a), the
  existing room error shows inline on that row, and nothing is blocked. Join on that room stays a fresh explicit choice
  (a person's tap, never a resend), and other rooms stay joinable. There's no Check room.
- **Ambiguous failure** (network, timeout, 5xx): the row reads "We couldn't confirm whether you joined." and "Check
  this room before joining another." (textMuted, inside its live region), with **Check room**: the ordinary list GET,
  never a resend. Until it runs, `canMutate` refuses any other join. After it, the row shows Joined, or Join again as a
  fresh explicit choice.
- **If the reconciling GET itself fails,** that row shows the error with Check room (a GET only).
- **Navigation doesn't cancel a join:** the screen observes the Activity's lifecycle, not its back-stack entry's.
  Leaving for Rooms keeps the POST running, and its result reconciles through the Rooms list.
- **A genuine app background still cancels it** (M3a) and marks it uncertain. The foreground list GET on return settles
  it, and the join is never resent.
- **No open rooms:** "No open rooms yet. Invitations and new rooms will show up in Rooms."

## Verification

- `FindPeopleJourneyTest`:
  - a new account sees it with only open rooms, no icon Back and no bar;
  - a returning sign-in lands on Rooms;
  - Joined only after a gated POST completes, and a second Join can't fire meanwhile;
  - an ambiguous failure → Check room → a GET only, with the POST count still 1;
  - Start chatting and system Back → Rooms root;
  - a join in flight survives Start chatting;
  - a genuine background cancels and marks the join uncertain, and it's never resent;
  - the empty state.
- `OnboardingRecreationTest.findYourPeopleSurvivesRecreation`: on the production restore path, the screen survives
  recreation and isn't shown again once consumed.
- `FindPeoplePresentationTest`: default and 200%, light and dark. Start chatting is on screen without scrolling, every
  Join has a 48dp touch target, and there are 16 captures (`ccm-find-people-presentation`): the top, a confirmed row,
  a definite failure and an uncertain one with Check room.
