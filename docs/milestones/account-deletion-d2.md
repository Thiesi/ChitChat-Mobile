# Account deletion D2: the screens

D2 puts [D1](account-deletion-d1.md)'s protocol and state in front of people (play-readiness.md §2, P-172), with the
presentation lead's rulings and the TL's conditions. Labels match `docs/pages/delete-account.html`: "You → Privacy and
security → Delete account" and "Restore a closed account".

## Flow

- **Row:** "Delete account", the last row in Privacy and security: `error`-coloured text, 48dp, no icon, with 24dp extra
  space above. It's absent while the server-update state shows (nothing new starts).
- **Explanation screen** (a pushed shell destination, bar title "Delete account"):
  - the title "Delete your account on {installation}" (host dot-break fallback);
  - §2's copy verbatim, with "on {installation} only" and each block lead SemiBold;
  - *Restore a closed account* is italic, drawn from the onboarding button's own resource, so the two always match;
  - **Export my data first** pops back to Privacy and security and brings Export personal data into view; no export
    runs from here;
  - **Continue** (an `error` text button, no persimmon on this screen) and **Cancel**.
- **Decision dialog:** "Delete your account?", its body in `DialogScrollBody` (P-220), **Cancel** and **Delete account**
  (`error`). Only Delete account sends.
- **Step-up:** only on `403 step_up_required`, through `withStepUp` (one retry). The dialog is titled "Confirm it's you to
  delete your account" (the per-flow pattern from #297; `StepUpTitleContractTest` covers this fourth caller). It's a form
  dialog, so it keeps its geometry.
- **Live-session outcomes** on the explanation screen:
  - **definite refusals:** an inline line each (last Super-Administrator, account not active, too many attempts, or the
    shared account errors);
  - **Unknown:** a level-2 `WarningNotice` with the lead "We're not sure your request went through." and the body "Check
    with the server; nothing is sent again." Continue and Cancel give way to **Check**, the single pinned primary, and
    the notice has no inline action. Back still leaves, and the Unknown stays (memory only) until Check resolves it;
  - **Check, still signed in:** "Your account wasn't deleted." as a muted line under the title, announced once.
    Continue and Cancel return; starting again or leaving clears the line.
- **Outcomes after the session ended** take over the signed-out page as full, onboarding-style screens (no shell). Each
  is announced as a pane:
  - **Waiting** (the server's `forced_logout` came first): "Deleting your account…", with a muted line and an
    indeterminate progress indicator. There are no actions, and Back leaves the app. The result replaces it.
  - **Unknown:** the explanation title, the same notice, and Check pinned. A Check that doesn't get through keeps the
    notice and adds "The check didn't get through. Try again when you're connected.", which the next tap clears. Back goes
    to step 3 for this server.
  - **Scheduled** (only after a confirmed 200 with `finalizes_at`): "Account scheduled for deletion", "…until {date}",
    and "To restore it, sign in with *Restore a closed account*." The date is the server's instant as the localized medium
    date and short time in the device's time zone, kept on one line with no-break spaces. There's never a definite title
    without the server's deadline (TL). **Done**, pinned, goes to step 3.
  - **You've been signed out** (only after an actual anonymous or 401 session read): §2's hedged text, with "about
    {date}" as the device clock plus 14 days, date only. Done goes to step 3.
  - **A refusal after the session ended for another reason:** the ordinary "Session not available" page with a muted
    caption under the title, "Your account wasn't deleted." followed by the reason. Reconnect clears it.
- **Check** is D1's one session read (`AccountOperations.checkClosure`) and never a resend. **Done** clears the outcome
  (`dismissClosure`, TL C4).
- **An Unknown survives its Check (TL, #300; a D1 fix):**
  - On an ended session, Check's `connect()` replaces the session state, closure included. The repository now puts the
    outcome back in a non-cancellable `finally`: LikelyClosed only after a completed anonymous read, otherwise the same
    Unknown.
  - That holds even when the Check is cancelled (process scope) or connect fails.
  - The outcome goes back only into this server's state, never another account's, and never over an outcome set
    meanwhile. The old code wrote the Unknown into another server's state too.
  - The live branch never replaces the state, so a cancelled read keeps the Unknown.
  - The root's hold of the Unknown being checked (the ViewModel's `checking`) is now **only presentation**: it keeps the
    screen from flickering to the connecting page during the read. It's no longer load-bearing.
- **Sign-in to a pending account:** `account_closure_pending` now reads "This account is scheduled for deletion. To keep
  it, go back and choose Restore a closed account. That works for 14 days after it was deleted." The label is a format
  argument from the button's own resource. It stays plain, because the shared sign-in error mapping renders plain
  strings.

## State

**A sent request settles on its own (review, #300).** The server's `forced_logout` ends the session before it answers.
Neither the identity change nor the signed-out takeover (which disposes the explanation screen) cancels a request that's
on the wire; they retire only a pending step-up. D1 lands the result on its marker: Scheduled, EndedWithRefusal or
Unknown. A refusal is shown on the explanation screen only while the session that asked is still the live one. Only a
genuine background or navigation of the still-live session cancels a sent request, and D1 marks that Unknown (M2b2).
**After the forced_logout, a background doesn't cancel the in-flight request**: cancelling could only lose a result the
server has already decided. Process death meanwhile loses it, which is acceptable, since the app then lands on onboarding
(D1, TL C4).

`AccountDeletionViewModel` is app-scoped and memory only:
- it holds the attempt, step-up, refusal and Check state;
- it cancels on genuine background and navigation, not on configuration change (M2b2);
- D1's repository turns a cancel after sending into Unknown;
- an identity change retires the attempt but keeps a Check in flight, since the Check is what changes the session.

## Verification

- **JVM, the repository** (`SessionRepositoryTest`, D1 fix):
  - an ended Check cancelled mid-read keeps the same Unknown, with one read at most;
  - an ended Check whose read fails stays Unknown;
  - another server chosen during an ended Check gets nothing restored;
  - a live Check cancelled mid-read keeps the Unknown.
  - The first and third fail on the previous code.
- **JVM:** `AccountDeletionViewModelTest` (15). The two forced-logout cases fail on the previous ViewModel:
  - the session ends mid-request and the screen is disposed, yet the request isn't cancelled and no stray refusal is left;
  - a confirmed request still completes after the forced logout.
  - **The request:** one POST per explicit delete; step-up only on 403, where Cancel sends nothing more and Verify retries
    once; definite refusals shown, Unknown and Closed not.
  - **Guards and outcomes:** "wasn't deleted" cleared before starting again and on leaving; no POST while an outcome is
    pending; leaving cancels; Check is one read, with the failed-Check caption cleared by the next tap; Done dismisses.
  - **Formatting:** the deadline in the device zone on one line; the hedged date; the italic label.
- **Journeys through the real root:** `AccountDeletionJourneyTest`.
  - **The happy path:** the row and explanation, with only the dialog sending; the confirmation with the server's date,
    and Done to step 3.
  - **The server's forced_logout before the 200:** Waiting, then the confirmation. The request is never cancelled.
  - **Step-up and refusals:** step-up's own title, and Cancel sends nothing; the last Super-Administrator's refusal.
  - **Unknown:** a live Unknown with Check pinned, then "wasn't deleted"; an ended Unknown with a failed Check, then the
    hedged signed-out screen and Done.
  - **Ended-session screens and the rest:** Waiting replaced by the result; the ended refusal caption; Export my data
    first; the row's 48dp target and quietness.
- **J:** `AccountDeletionPresentationTest`, 49 captures in `deletion-presentation`.
  - Each state at default and 200%, light and dark.
  - One 200% confirmation in a long-date locale (pt-BR).
- **Existing guards still apply:** `StepUpTitleContractTest` covers the new caller.

D1's JVM tests still cover the protocol and the `forced_logout` race. Live acceptance against a disposable account needs
its own approval (AGENTS: live mutations), and the public deletion page (§3) is the owner's.
