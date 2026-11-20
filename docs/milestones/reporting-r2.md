# Reporting R2: the message report on screen

R2 puts [R1](reporting-r1.md)'s report state on screen for room and DM messages, following
[reporting.md](../design/reporting.md) at 5c88f70, the PL's rulings of 2026-11-20 (the follow-up sheet copy included) and
the TL's R2 plan. The person report is R3; the captures for the PL's J follow in a stacked R2c.

## Where it shows

- **Report…** in the message actions, after Copy and Save/Share. It shows only where the server allows a report
  (`canReportMessage`): never your own, deleted or system messages (a pending or unknown-delivery message is your own);
  in DMs only messages you received; an attachment-only message is reportable. It stays enabled while another report
  is sending: a tap then closes the sheet and shows "Your last report is still sending. Try again in a moment." in this
  conversation's stack, cleared when that report settles or the person leaves, never twice.
- **The form** (a form dialog, the StepUpDialog geometry): a scrolling `DialogScrollBody` above the keyboard
  (`imePadding`), the buttons below it.
  - The title, then the context card: the sender over a one-line excerpt, or "Photo", "GIF" or the file name
    (bidi-stripped, isolated, middle-elided so the extension stays).
  - The eight categories as 52dp radio rows, nothing preselected; the optional details field with the counter: "0 / 1000",
    "Too long: N / 1000" in `error` (Send disabled, never truncated). The counter brings itself into view, and TalkBack
    hears it once at the crossing, through a live node whose text changes only then.
  - Who sees it: the room or DM text, plus the attachment line.
  - Cancel and Send report (enabled once a category is chosen). While sending, the button shows its progress ("Sending
    report" for TalkBack), Cancel is disabled and Back does nothing (R1 refuses close()).
  - In-form notices: the report budget's wait (a composition-bound timer that only re-enables Send), and "Something went
    wrong. Nothing was sent."
  - Unknown: the read-only summary ("Reason: {label}", the details in `textMuted`), the level-2 notice "Not sure your
    report went through.", Send again (the same frozen report) and Close.
- **The conversation's stack** (next to the transfer notice), for this conversation's own report only: the outcome as a
  level-1 line (the 201 and already-reported confirmations, the refusals), "Sending your report…" after leaving
  mid-send, and "Not sure your report went through." with Review after a background. Review reopens the same dialog in its
  summary; Close there also dismisses the notice.
- **The follow-up sheet** (PL): only when a success lands while the dialog is up, never on return, and only when its
  action applies. A room offers "Stop seeing {name}'s messages?" with Ignore {name} (the existing M4c3b flow) and Done; a
  DM offers "Stop hearing from {name}?" with Block {name} (the existing confirmation) and Done. Nothing applies
  automatically. An already-ignored sender, a blocked peer, an unknown policy or a guest shows no sheet.

## How it works

- `ReportViewModel` (R1) is app-scoped in MainActivity and provided through `LocalReports` by `ReportsHost`, which also
  turns a genuine background (an ON_STOP that isn't a configuration change) into `backgrounded()`.
- R2 adds `presented` (the dialog is up), the outcome's `sheet` flag, `review()`, `dismissSheet()`, `request()` (open plus
  the BUSY line) and `leaveConversation(origin)`: leaving consumes this conversation's shown outcome and drops its open
  form, while a report in flight or a pending Unknown stays, with the dialog taken away. Another conversation's report is
  untouched.
- The room and DM screens render only the report whose origin is theirs, and count its dialog or sheet in
  `composerDialog` (no read proof, no typing). A configuration change keeps the dialog and every field; process death
  comes back with nothing, since nothing is saved.

## Tests

- **`ReportJourneyTest`** (the real room and DM screens): the gating in rooms and DMs (own, received, attachment-only);
  a category is required, the counter, too long and its single crossing announcement, nothing truncated; who sees it for
  a room, a DM and an attachment; a 201 closes the form, shows the line and offers Ignore without applying it; no sheet
  for an already-ignored sender or a blocked peer; Cancel and Back are no-ops while sending; Send again answered
  already-reported reads as sent, with the same frozen report; leaving mid-send and returning shows "Sending your
  report…" then the outcome, with no sheet; a background offers Review and its summary, and Close dismisses it; the
  BUSY line, cleared when the report settles.
- **`ReportFormLayoutTest`:** a real `recreate()` keeps the form and its fields, and a fresh model (process death) has
  nothing; at 200%, portrait and landscape, Send report and Cancel stay displayed after a native tap brings up the
  keyboard.
- **`ReportViewModelTest`** (JVM, 28): R1's cases plus the sheet only in place, never for a refusal; leaving its own
  conversation versus another's; Review; the BUSY line's clearing.
