# Reporting R1: protocol and state

R1 is the protocol and state for participant reporting ([reporting.md](../design/reporting.md), P-160–P-167), with no
UI. The message report (R2) and the person report (R3) follow, each with its captures for the PL's J. The plan and its
conditions are the TL's of 2026-11-20; the copy rulings are the PL's of the same day. The backend contract is ChitChat
v3.10.0 (`docs/api/moderation-reports.md`, `reports/message.php`, `reports/user.php`), read-only.

## What the server takes and refuses

- **`reports/message.php`:** a CSRF POST of `{message_kind: room|direct, message_id, category, details}`. Success is
  exactly 201 `{case:{id,status,report_count}}`.
  - **Refusals:** 400 `validation_error`; 403 `report_recipient_required` (a DM you sent), and 403 `guest_not_allowed`
    (a guest reporting a DM, M7); 404 `message_not_found`; 409 `message_not_reportable` (deleted, system, own or
    empty); 409 `message_already_reported`; 429 `rate_limited` (`message_report`, 10 per 3,600 s).
  - The limiter consumes before validation, so every attempt counts.
- **`reports/user.php`:** a CSRF POST of `{user_id, category, details, context: {room_id} | null}`.
  - **Refusals:** 400 `validation_error` (also the picture category without a live picture); 403 `report_not_allowed`;
    404 `user_not_found`; 409 `already_reported`; 409 `report_snapshot_unavailable` (nothing written); 429
    (`user_report`). A refused room is never turned into a global report.
- **Categories:** spam, harassment, hate, threats, sexual_content, privacy, impersonation, other; a person adds
  `offensive_username` and `offensive_profile_picture` first.
- **Details:** the server applies PHP `trim` (ASCII whitespace only) and counts `mb_strlen` code points, at most 1,000.
- **No gates:** neither POST is gated by rules acceptance or step-up.
- **One report per account per case,** including after the case closes, so an explicit resend can never duplicate one.

## What R1 adds

- **`ReportOperations` / `SessionReportRepository`:** each report is one POST through the original rendered session,
  never resent.
  - **Outcomes:** `Sent` (a 201, even with an unreadable body), `AlreadyReported`, `Refused` (NOT_FOUND, NOT_ALLOWED,
    NOT_REPORTABLE), `RateLimited` (the server's positive wait, or unknown), `PictureGone`, `Invalid`,
    `SnapshotUnavailable`, `Unknown` (lost connection, timeout, 5xx or an unreadable reply to a POST: it may have
    arrived) and `Failed` (refused before committing: session ended, CSRF, an unexpected 4xx).
  - **Guards:** a person-only category is never sent for a message.
- **The report budget's 429 stays local (TL ruling):** `authorized()` takes a `localFailure` predicate, and only a 429
  whose JSON code is exactly `rate_limited`, from a report POST, skips the session-wide wait.
  - A 429 without that JSON (a proxy, say) still takes the session-wide path, as does every other endpoint's 429.
  - Any 429 is still a definite refusal and shows as the wait. #323 tracks per-policy handling everywhere else.
- **Profile facts:** `can_report` and `reported_by_me` decode strictly into `ProfileSnapshot.report`. A missing,
  quoted, null or non-Boolean field is unknown (null), which hides the entry. A profile never decides the global
  update state.
- **Rules as pure functions:**
  - `canReportMessage`: never your own, deleted or system messages. A room message is never refused for emptiness; a DM
    only with neither text nor attachment, and only for its recipient. An attachment-only message is reportable (TL).
  - `offersPersonReport` / `showsReportedCaption`: a strict `can_report: true` and `reported_by_me: false` (never your
    own card), and the caption on `reported_by_me`.
  - `ReportDetails`: PHP trim and code points as the server counts. Over 1,000 blocks Send locally and nothing is ever
    truncated; details go as typed, or as null when empty after the server's trim.
- **`ReportViewModel`:** app-scoped and memory only, one report at a time.
  - **Memory only:** the category and details live only here, never in bundles, logs or `toString`, and are gone once
    a report finishes.
  - **Unknown:** freezes them for the read-only summary (PL). Send again (message) re-sends the same frozen report, only
    on a tap, never on foreground or reconnect. A Send again answered already-reported means the first attempt arrived,
    so it reads as sent (the `afterUnknown` flag; PL); a first attempt's already-reported keeps its own line.
  - **Person Check:** one profile GET with the frozen room context. `reported_by_me` true is sent; false says it didn't
    arrive and makes the form editable again; a failed read stays Unknown.
  - **Kept-form notices:** a wait, picture gone (the category is removed and the choice cleared), not sent, and
    snapshot unavailable each keep the form with a notice.
  - **Closing:** definite refusals close it with their reason. A configuration change keeps it.
  - **A report in flight is never silently dropped (TL, #324):**
    - `close()` (Cancel, Close, Done) is refused while sending or checking; the form then offers no dismiss, and the wait
      is bounded by the request timeout.
    - `departed()` (navigation away) lets the attempt finish and keeps its outcome; the UI (R2) hosts the report so that
      leaving a conversation doesn't dispose it. It drops only an open form: a pending Unknown and a finished outcome
      stay for their origin conversation, as after a background (#324 review).
    - `backgrounded()` (a genuine background) cancels the call and lands in Unknown with the same frozen category and
      details, kept: Send again (message) or Check (person) follows, never automatically. An open form is dropped
      (memory only, no draft retention); an Unknown or a finished outcome stays.
    - Only a session change drops an attempt in flight (the identity fence).
  - **Where an outcome shows (PL, TL):** the state records its origin (`ReportOrigin.Room` or `.Direct`), so R2 shows
    the outcome, or an Unknown's Review notice, in that conversation; `consume()` drops a finished outcome once shown.
    `open()` is blocked only by a report that's sending or checking (`BUSY`, for the PL's "still sending" line). A
    finished outcome waiting to be shown never blocks it, and a pending Unknown for another target is dropped with
    nothing sent (the server's one-report-per-case rule covers one that arrived); the same target keeps its Unknown.

## Tests

- **`ReportsTest`:** the categories; every refusal's mapping; any 429 as the wait, with only the JSON
  `rate_limited` local; Unknown versus Failed; the counter (ASCII-only trim, emoji once, the limit) and the wire
  details; the message and person gating; strict profile facts.
- **`SessionReportRepositoryTest`** (MockWebServer and a real `SessionRepository`):
  - one CSRF POST per report with the exact body; a 201 with an unreadable body is sent;
  - a person's room context, or none; a person-only category never sent for a message; typed refusals with no resend;
  - the report budget's 429 leaves `retryAtMillis` untouched and a later read goes through, while a non-JSON 429 on
    the same path takes the session-wide lock and refuses the next report locally;
  - an upload's 429 is unchanged; profile facts read strictly with the frozen room context.
- **`ReportViewModelTest`:** a category is required; too-long details block Send without truncation; already-reported
  first versus after a Send again; the frozen Unknown summary and its exact resend; nothing automatic; the budget
  after Unknown versus on a first attempt; refusals; picture gone; the person Check's three outcomes; close refused
  while sending; navigation away finishing with the outcome kept; a background while sending landing in Unknown with
  the frozen report (Send again for a message, Check for a person); a background while editing dropping the form; a
  session change while sending dropping everything; navigation away keeping a pending Unknown and a finished
  outcome; the origin kept and a shown outcome consumed once; a waiting
  outcome never blocking `open()`; a pending Unknown dropped by a report elsewhere with no POST; only sending or
  checking blocking `open()`; nothing private in `toString`.
