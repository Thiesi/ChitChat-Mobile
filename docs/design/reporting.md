# Reporting a message: presentation specification

Status: presentation spec for participant reporting (M5), written ahead of the engineering
slice (technical lead's plan, issue #1). Message reporting uses the existing report API.
Reporting a person uses ChitChat#159 (merged, below). The moderation queue for moderators is M7
and out of scope. Mockups: `ReportMessage` and `ReportPerson` on the canvas.

## What the server provides

`POST /api/v1/reports/message.php` with `message_kind` (`room` | `direct`), `message_id`,
`category`, and optional `details` (trimmed, ≤ 1,000 characters):

- **Categories (fixed):** `spam`, `harassment`, `hate`, `threats`, `sexual_content`,
  `privacy`, `impersonation`, `other`.
- **Who can report what:**
  - room messages: anyone who can read the room's history (members, and guests once guests
    exist);
  - DMs: only the **recipient** of the message.

  Never your own message, a deleted message, a system message or an empty one.
- **What is kept:** an immutable snapshot of that one message: current text or caption,
  timestamps, room name and type for rooms, and attachment name, type and size. **Not**
  surrounding history, earlier revisions, attachment bytes, IP addresses or session data.
- **Who sees it:** room reports go to that room's owners and moderators plus global moderation
  roles. DM reports go **only** to global roles (Super-Administrator, Administrator, Chat
  Admin, Global Moderator).
- **One report per account per message.** A repeat returns `message_already_reported`. Reports
  on the same message aggregate into one case.
- **Errors:** `message_not_found`, `message_not_reportable`, `report_recipient_required`,
  `message_already_reported`, `rate_limited` (10 per hour).
- There is **no** endpoint for a participant to list their own past reports or case outcomes.

## Principles

- **Calm and safe.** Reporting is something people do when upset. The flow is short, never
  scolding, and says exactly who will see what.
- **Reporting is not hiding.** A report doesn't remove the message for anyone. Separate
  existing actions (ignore in rooms, block in DMs) are offered afterwards, never applied
  automatically.
- **No false promises.** CCM doesn't know what happens next, so it doesn't promise outcomes or
  notifications.

## Entry point

- **Report…** in the message actions sheet (modals spec, social section, after Copy).
- **Shown only where the API allows it:** not on your own messages, not on deleted or system
  messages, and in DMs only on messages you received. When a report would be refused for
  another reason (for example rate-limited), the row still shows and the outcome explains it.
- The trailing ellipsis means a form follows.

## Report form (form dialog)

The report form contains a text field, so it is a **form dialog** under the modals spec: it
keeps the dialog window type and the native input and IME rules.

- **Title:** "Report this message".
- **Context line:** a one-line excerpt of the message (plain text) and its sender, so the person
  knows what they're reporting.
- **"What's wrong?":** a radio list of the eight categories, each row 52dp, in plain words:

  | Category | Label | Helper text (one line) |
  |---|---|---|
  | `spam` | Spam | Ads, scams or repeated junk |
  | `harassment` | Harassment or bullying | Targeting or insulting someone |
  | `hate` | Hate | Attacks on who someone is |
  | `threats` | Threats or violence | Threatening to hurt someone |
  | `sexual_content` | Sexual content | Unwanted or inappropriate sexual material |
  | `privacy` | Private information | Sharing someone's personal details |
  | `impersonation` | Pretending to be someone | Posing as another person |
  | `other` | Something else | Tell the moderators below |

  Nothing is preselected. The person must choose.
- **"Anything else moderators should know?" (optional):** a multi-line field with a live
  counter "0 / 1000", following the native input contracts (48dp, one tap to focus, IME). For
  `other`, the hint encourages details, but the server doesn't require them, so the field stays
  optional.
- **Who will see it**, plain text above the buttons:
  - room: "**{room}**'s owners and moderators, and the server's moderation team, will see
    this message's text, its time and your report. They won't see other messages.";
  - DM: "The server's moderation team will see this message and your report. They won't see
    the rest of your conversation."
  - "The server's moderation team" means the global roles that review reports
    (Super-Administrator, Administrator, Chat Admin, Global Moderator). Its exact meaning may be
    offered behind a small "Who is this?" link.
  - If the message has an attachment: "They'll see the file's name, not the file itself."
- **Buttons:** **Cancel** (text) and **Send report** (`primary` text button, enabled once a
  category is chosen). Not `error`-colored: reporting isn't destructive.
- The details text lives only in the dialog's state while it is open. It is never saved,
  logged or put in bundles (the same rule as other private text). Configuration changes keep
  it; genuine background and navigation retire the dialog per the existing modal rules.

## Outcomes

| Result | Shows | Then |
|---|---|---|
| 201 | The dialog closes. The level-1 confirmation "Thanks. Your report was sent to {room}'s moderators and the server's moderation team." (DM: "Thanks. Your report was sent to the server's moderation team.") | A follow-up sheet offers existing actions: rooms: **Ignore {name}** (existing M4c3 ignore), DMs: **Block {name}** (existing block confirmation). Plus **Done**. Nothing applies automatically |
| `message_already_reported` | "You've already reported this message." Treated as success (the report exists) | Same follow-up sheet |
| `message_not_reportable` | "This message can't be reported anymore. It may have been deleted." | Close |
| `message_not_found` or `report_recipient_required` | "You can't report this message." | Close |
| `rate_limited` | "You've sent a lot of reports recently. Try again in {wait}." (ChitChat#157, phrased per status-presentation "Waiting times"), or "… Try again later." when the delay is unknown. The form stays open with its content, and nothing is resubmitted automatically | — |
| Unknown (connection lost after sending, timeout) | The level-2 notice inside the dialog: "Not sure your report went through." with **Send again** | **Send again** is an explicit new submission. It's safe: if the first one arrived, the server answers `message_already_reported`, which shows as success. Never resent automatically |

The message itself is **unchanged** after a report: no badge, no hiding, no "reported" mark. The
API exposes no per-viewer report state to drive one, and a local mark would be lost on reload.

## Report a person (ChitChat#159, merged)

Play's user-generated-content policy expects in-app reporting of objectionable **people** as
well as content. ChitChat#159 merged in ChitChat main `9c3e83e` (contract in issue #1, comment
6727662890; ChitChat `docs/api/moderation-reports.md`). The web UI follows separately.
Mockup: `ReportPerson` on the canvas.

### What the server provides

- `POST /api/v1/reports/user.php {user_id, category, details|null, context: {room_id}|null}`
  with CSRF returns `201 {case: {id, status, report_count}}`.
- **Scope:** with `room_id`, the report belongs to that room. The reporter must be able to
  read it, the person must be linked to it, and that room's owners and moderators can review
  the case alongside the global roles. Without it, the report is **global** and only the global
  roles review it. A refused room is never turned into a global report.
- **What moderators get:** the person's name and their already-sanitized profile picture as
  they were at the case's **first** report, plus the room context. No messages and no other
  profile details are copied.
- **One report per account per case** (person plus scope), in any case status.
- **Profile facts:** `users/profile.php?user_id&room_id` adds `can_report` and `reported_by_me`.
  Client check: `can_report` present on profile responses. Missing means unknown, so the
  entry is hidden.
- **Never gated by rules acceptance** (play-readiness.md §1). Reporting stays available while
  new rules wait for agreement.

### Entry

- The profile card sheet (modals-restyle.md) gains **Report {name}…** below Refresh, with the
  `report` icon, in `text`. Not `error`-colored: reporting isn't destructive.
- Shown only when the card's profile response has `can_report: true` **and** `reported_by_me:
  false`.
- When `reported_by_me` is true, the row is replaced by a non-interactive caption with the
  `check` icon: "**You've reported {name}.**" No second report is offered.
- `can_report` false or missing: nothing is shown. Never your own card.
- **Room context:** a card opened from a room (room author, presence list) loads the profile
  with that room's `room_id`, and the report sends **the same `room_id`**. A card opened from a
  DM loads without `room_id`, and the report is global. The report uses the context of the
  profile response the card rendered, frozen with the card's request reference (M5f2), never a
  different room.
- Ignoring or blocking someone doesn't hide this entry: it's a safety action.

### Form (form dialog)

The same form dialog as message reporting, with the native input and IME rules:

- **Title:** "Report {name}".
- **Context line:** the person's pebble and username, so it's clear who is being reported.
- **"What's wrong?":** a radio list. The two person-only categories come first:

  | Category | Label | Helper text (one line) |
  |---|---|---|
  | `offensive_username` | Offensive username | The name itself is offensive |
  | `offensive_profile_picture` | Offensive profile picture | The picture itself is offensive |

  These are followed by the eight message categories with their labels from the table above.
  "Offensive profile picture" appears only when the profile shows a current picture (the
  card's avatar availability fact). Nothing is preselected.
- **Details:** the same optional field, "Anything else moderators should know?", up to 1,000
  characters with the live counter.
- **Who will see it**, plain text above the buttons:
  - room context: "**{room}**'s owners and moderators, and the server's moderation team, will
    see your report and a copy of {name}'s name and profile picture. They won't see
    {name}'s messages.";
  - global: "The server's moderation team will see your report and a copy of {name}'s name and
    profile picture. They won't see {name}'s messages.";
  - without a picture, "name and profile picture" reads "name".
- **Buttons:** **Cancel** and **Send report** (`primary` text button, enabled once a category is
  chosen).
- The details text follows the same never-saved rule as message reporting.

### Outcomes

| Result | Shows | Then |
|---|---|---|
| 201 | The dialog closes. The level-1 confirmation "Thanks. Your report was sent to {room}'s moderators and the server's moderation team." (global: "Thanks. Your report was sent to the server's moderation team.") | The follow-up sheet: from a room, **Ignore {name}**; from a DM, **Block {name}**; plus **Done**. Nothing applies automatically. The card then shows "You've reported {name}." |
| `409 already_reported` | "You've already reported {name}." Treated as success (the report exists) | The same follow-up sheet |
| `409 report_snapshot_unavailable` | Inside the dialog, a level-2 notice: "**Nothing was sent.** The server couldn't keep a copy of {name}'s profile picture just now. Try again later." | The form stays open with its content. Send report stays available as an explicit new attempt, never automatic |
| `400 validation_error` on the picture category | "{name} no longer has a profile picture. Choose another reason." | The form stays open, the picture category is removed and the choice is cleared |
| Other `400 validation_error` | "Something went wrong. Nothing was sent." | The form stays open |
| `403 report_not_allowed` | "You can't report {name} here." | Close |
| `404 user_not_found` | "This account isn't available anymore." | Close |
| `429` | "You've sent a lot of reports recently. Try again in {wait}." from `error.retry_after_seconds` (status-presentation "Waiting times"), or "… Try again later." when unknown | The form stays open. Nothing is resubmitted automatically |
| Unknown (connection lost after sending, timeout) | Inside the dialog, a level-2 notice: "Not sure your report went through." with **Check** | **Check** re-reads the profile with the same `room_id`. If `reported_by_me` is true, show the 201 confirmation and the follow-up sheet. If it's false, the notice reads "Your report didn't arrive." and Send report is available again as an explicit choice. A new report is never sent before that re-read, and never automatically |

The person's card, name and messages are otherwise **unchanged** after a report. The only
change is the "You've reported {name}." caption, which comes from the server's
`reported_by_me`, not from local state.

## Guests (M7)

Guests may report room messages. The same flow applies, with the room copy, once guest
sessions exist. Guests never see DM reporting. Guests may report people only with a room
context (the server requires it), so a guest's card shows Report only when opened from a room
and the profile says `can_report`.

## Moderator side (M7, not in scope)

The moderation queue (cases, claim, resolve) is M7, specified in [moderation.md](moderation.md):
the steward section of You and a steward-colored queue screen. This spec adds nothing
for it.

## Tests

- **Unit:** entry visibility (own, deleted, system, DM sender versus recipient); the mapping of
  each error to its outcome; `message_already_reported` treated as success; unknown offers
  Send again with no automatic resend.
- **Compose:**
  - the form requires a category;
  - the counter and 1,000-character limit;
  - who-sees-it copy for room, DM and attachment;
  - the follow-up sheet offers ignore or block without applying either;
  - details are absent from saved state after recreation.
- **Person (unit):** entry visibility from `can_report` and `reported_by_me`, with missing
  fields hidden; the report sends the card's own `room_id` (or none from a DM); each error maps
  to its row; `already_reported` counts as success; unknown requires the profile re-read
  before a new report, with no automatic resend; the picture category appears only with a
  current picture.
- **Person (Compose):** who-sees-it copy for room, global and no-picture cases; the follow-up
  sheet offers ignore (room) or block (DM) without applying either.
- **Native:** the details field follows the native input contracts (one tap, focus, IME, 5 s).

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-160 | Report… appears only where the API allows (not own, deleted or system messages; DMs only as recipient). | U, C |
| P-161 | The form requires a category, keeps details optional (≤ 1,000), and states exactly who will see what for room, DM and attachment. | C |
| P-162 | Outcomes match the table. `message_already_reported` shows as success. Unknown offers an explicit Send again only, never automatic. | U, C |
| P-163 | Reporting never hides or marks the message. Ignore and block are offered afterwards, never applied automatically. | C |
| P-164 | Details text is never saved, logged or put in bundles. The form is a form dialog under the native input contracts. | C, R |
| P-165 | Report {name}… appears on a profile card only with `can_report: true` and `reported_by_me: false`. With `reported_by_me` it shows "You've reported {name}." Missing fields hide both. The report sends the same `room_id` as the card's profile request, or none from a DM. | U, C |
| P-166 | Person reports offer the two person-only categories first (the picture one only with a current picture), state exactly who sees what for room and global scope, and map every error per the table. `already_reported` is success. `report_snapshot_unavailable` says nothing was sent. 429 shows the wait. | U, C |
| P-167 | An unknown person-report result re-reads the profile (`reported_by_me`) before any new report and never resends automatically. Reporting is never gated by rules acceptance. | U |
