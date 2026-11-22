# Reporting R3: reporting a person from their profile card

R3 puts [R1](reporting-r1.md)'s person report on screen, following [reporting.md](../design/reporting.md) ("Report a
person", P-165–P-167), the PL's rulings of 2026-11-20 and the TL's R3 plan and conditions of 2026-11-22. It reuses
[R2](reporting-r2.md)'s form, stack lines and follow-up sheet. The captures for the PL's J follow in a stacked R3c.

## Where it shows

- **The profile card** gains **Report {name}…**, a plain 48dp text row after the card's facts, never `error`-coloured.
  It shows only with the rendered profile's strict `can_report: true` and `reported_by_me: false`, never on your own
  card, and only for a card shown over a room conversation, the notification room route or a DM thread (the only three
  places a card opens). With `reported_by_me: true` the row is replaced by the caption "You've reported {name}.". Missing
  or malformed facts, a loading card and an error card show neither. The caption comes only from the server: after a
  report, the next fresh card GET shows it, and no extra re-read is added. The spec's `report` and `check` icons aren't in
  the app's icon set yet, so both are text only, like R2's Report….
- **The tap** freezes the target, the picture fact and the name from the profile the card rendered, and goes through the
  card's own guard (same selection, entry STARTED, same session owner). A Refresh that replaced the profile makes the tap
  do nothing (the guard fails closed). The card closes as the form opens, in the same frame, so the conversation's modal
  suppression of read proof and typing never lapses in between.
- **The room context** is the profile request's own: a room card's report carries that room's `room_id`, while a DM
  card's (and a room card's without a room context) has none and is global, with the global copy. The conversation's
  room is never filled in.
- **The form** is R2's, in the conversation the card was over:
  - the title "Report {name}";
  - the context: the person's pebble and username;
  - "Offensive username" and "Offensive profile picture" first (the picture one only with a current picture: avatars
    on and a picture on the profile), then the eight message categories;
  - the details field and counter;
  - who sees it: the room, global or no-picture wording.
- **Outcomes:** the line goes in that conversation's stack, where a message report's goes (TL: PL ruling 3's "else the
  stack").
  - 201: "Thanks. Your report was sent to {room}'s moderators and the server's moderation team.", or the global line.
  - Already reported: "You've already reported {name}.". Both successes offer the follow-up sheet in place: Ignore
    {name} from a room card (when the session's policy is known and the person isn't ignored yet), Block {name} from a
    DM card (the peer, not yet blocked). Nothing applies automatically.
  - `report_not_allowed` or `guest_not_allowed`: "You can't report {name} here."; `user_not_found`: "This account isn't
    available anymore.". The form closes, nothing is retried, and no sheet follows.
  - `report_snapshot_unavailable`: the level-2 "Nothing was sent." notice in the form. The form stays, and Send report
    is a new attempt.
  - A 400 on the picture category: "{name} no longer has a profile picture. Choose another reason." The category goes
    and the choice is cleared.
  - The report budget's 429: the wait line, as in R2.
  - Unknown: the read-only summary with **Check**, never Send again. Check is one profile GET with the frozen room. Found
    reads as the 201. Not found shows "Your report didn't arrive." and Send report is an explicit choice again. A failed
    read stays Unknown.

## Notices after Send (PL and TL, 2026-11-22)

Measured at 200% portrait: Send disables the details field, so its focus and the keyboard go before the outcome, and
the form switches to its pinned layout. The two level-2 notices, "Nothing was sent." (snapshot) and "Your report didn't
arrive.", ended out of view below the field, while InlineError and the wait line already brought themselves into view.
- **The fix:** both now bring themselves into view when they appear, as a scroll only. A notice taller than the body
  (the snapshot one at 200%) shows from its top.
- **Precedence:** the notice holds the view until the person's next interaction, usually a tap on the field. The
  keyboard then returns and the field's caret line is brought back.
- **No focus return:** the app never brings focus or the keyboard back after a refusal that keeps the form, for message
  and person reports alike. The person taps the field.
- **`ReportNoticeViewportTest`** asserts each line in view when it appears (snapshot, picture gone, the wait line,
  didn't arrive, R2's NotSent), no focus and no keyboard after the refusal, and, after a native tap on the field, the
  caret line above the keyboard.

## A report already held in that conversation (TL condition 1)

The entry stays enabled, and nothing held is replaced without the person seeing it:

- **A report sending or checking** (any conversation): the tap shows "Your last report is still sending. Try again in a
  moment." in the conversation's stack and opens nothing. The card closes so the line, and later that report's outcome,
  can be seen.
- **This conversation's pending Unknown for this person:** its summary again, with Check.
- **This conversation's pending Unknown for another target:** that summary shows instead (`ReportOpen.HELD`). Send
  again, Check or Close settles it before a new report can open. R2's message Report… still replaces it, as before.
- **This conversation's outcome that settled while the card was up** (counted by `ReportViewModel.settled`, recorded
  when the card opens): the card closes and nothing opens, so the line is seen first. An outcome from before the card
  opened was already on screen in the stack the card was opened from, and the form replaces it as in R2.

## Unchanged from R2

Departure, background and privacy rules, and `composerDialog`, are R2's. Category and details stay in memory only and
never reach saved state, a log or `toString` (nor does the frozen name). They are cleared on success, background,
navigation and sign-out. Reporting is never gated by rules acceptance (P-167).

## Tests

- **`PersonReportTest`** (JVM):
  - the target from the rendered profile, with a DM card or a room card without a room context global;
  - the picture category only with a current picture;
  - no Report origin outside a room or DM thread;
  - the BUSY line with nothing opened;
  - a held Unknown shown, never replaced;
  - the same person's Unknown with Check only, and Check never POSTs;
  - an outcome that settled under the card is seen first;
  - the frozen name carried to the outcome, never in a string;
  - a message report carries no name;
  - reporting while rules acceptance is pending.
- **`PersonReportJourneyTest`** (the real room and DM screens):
  - the room copy and Ignore for that person, with nothing applied;
  - the global, name-only copy without a room context or picture;
  - a DM card's global report and Block;
  - Unknown offers Check only, which never posts;
  - a snapshot refusal keeps the form, and a refusal closes with the named line and no sheet;
  - the picture category removed after its 400;
  - the card's entry, caption or neither.
- **`ProfileJourneyTest`** (the real root):
  - the room card's Report row closes the card and opens the form in that room, with the card's room, the frozen name
    and nothing sent;
  - a DM card's report is global;
  - the next fresh card GET shows only the caption;
  - missing facts show neither.
