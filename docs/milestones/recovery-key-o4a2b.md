# Recovery key O4a2b: Privacy and security and the notice

Generate or Replace from Privacy and security, and the "didn't reach this phone" notice. It follows
[recovery-key.md](../design/recovery-key.md) (P-184, P-188), the presentation lead's O4a2 decisions and the technical
lead's O4a2b design. It builds on [O4a1](recovery-key-o4a1.md) and [O4a2a](recovery-key-o4a2a.md).

## The row and the screen

- **The row** sits at the top of Privacy and security (its sign-in group), only while `SessionState.recoveryKey` is
  `Known`. With a key: "Created {date}" (medium date). Without one: "**Not set up.** Without one, only the people who run
  {installation} can reset a forgotten password." with the honey `warning` glyph. `NotApplicable` (provider-only) and
  `Unknown` show nothing, and so does the update state.
- **The screen** is the pushed `recovery-key` destination (icon Back, bar title "Recovery key"):
  - with a key: the date as a muted caption, the lead, what Replace does, and a full-width outlined **Replace key**
    behind the "Replace your recovery key?" dialog (Cancel, or Replace in `error`);
  - without one: the Not set up line, the lead and **Create recovery key** as the persimmon primary, with no dialog.

## The request

`RecoveryKeyViewModel` is app-scoped and memory only. **Replace** and **Create** each make one
`account/recovery-key.php` POST through `withStepUp` (`requestRecoveryKey`, O4a1):
- **200:** the key display (O4a2a) as an overlay over the composed shell, in the same activity window, so FLAG_SECURE
  applies and the navigation stack stays. The shell's subtree is out of TalkBack's reach while it shows, and the
  display's Back handler is composed last. Its title is "Your new recovery key" with "Your old key no longer works."
  after Replace, or "Save your recovery key" after Create. I've saved it returns to the screen with the new date.
- **`409 password_account_required`, `429`:** their lines from the spec's table. The 429 wait comes from
  `retry_after_seconds` when it's positive.
- **An ambiguous result:** one fenced session refresh (`AccountAccess.recoveryKeyAfterRefresh`, the ordinary refresh,
  generation-fenced) compares `created_at`. It reads as created elsewhere (with **Create again**), unchanged, or, when the
  refresh was skipped or failed, unresolved (with **Create again**).
- **Never resent.** Create again is an explicit new request.

**Lifetime (M2b2).** The POST cancels on genuine background, navigation away from the screen, or logout, not on
configuration change. A cancel after the POST was sent takes the Unknown path: the next time the screen starts, the one
`created_at` check runs; nothing is read in the background. Leaving while that check runs (after an ambiguous
response, or the check on return itself) keeps it pending the same way, so a committed Replace is never left without an
outcome. Cancelling while the step-up dialog waits is not uncertain,
because the 403 committed nothing. The shown key survives configuration change and background, and is dropped on I've
saved it or any identity change (sign-out, another account or generation), which also drops a pending check.

## The notice

"**Your recovery key didn't reach this phone.** Create a new one in Privacy and security." appears on the Rooms root,
directly under the status slot and outside `resolveStatus`, while `recoveryKeyUndelivered` is set (O4a1) and the status
is `exists: true`. Like the rules strip, it's hidden while the slot shows a level-2 notice. It sits on `presenceWash`
with the honey `warning` glyph and is announced politely once. **Go there** opens the Recovery key screen and **Not now**
ends it; both clear the runtime flag, so it lasts for this process only. A confirmed Create or Replace also clears it.

## Tests

- `RecoveryKeyViewModelTest` (JVM):
  - Replace shows the key once, background keeps it, and I've saved it drops it;
  - Create is not Replace;
  - background during the POST cancels it, then one `created_at` check on return, never resent, with Create again as
    an explicit request;
  - an unreadable status is unresolved;
  - leaving during step-up isn't uncertain;
  - step-up retries once;
  - an ambiguous response is checked at once;
  - 409 and 429 are typed;
  - an identity change drops the key and a pending check.
- `RecoveryKeySettingsJourneyTest` (the real root):
  - Replace asks first, and its Cancel sends nothing;
  - the key shows over the shell in a secure window, with the shell out of reach;
  - Back asks first, then the screen returns with the new date and FLAG_SECURE cleared;
  - Not set up creates without a dialog;
  - a provider-only account has no row;
  - the notice's Go there and Not now.
- `RecoveryKeySettingsPresentationTest` captures both screen states (default and 200%) and the notice (200%), light
  and dark, into `recovery-presentation`.
