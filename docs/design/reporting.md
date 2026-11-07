# Reporting a message: presentation specification

Status: presentation spec for participant reporting (M5), written ahead of the engineering
slice (technical lead's plan, issue #1). It uses only the existing report API. The moderation
queue for moderators is M7 and out of scope. Mockup: `ReportMessage` on the canvas.

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
| `rate_limited` | "You've sent a lot of reports recently. Try again later." The form stays open with its content | — |
| Unknown (connection lost after sending, timeout) | The level-2 notice inside the dialog: "Not sure your report went through." with **Send again** | **Send again** is an explicit new submission. It's safe: if the first one arrived, the server answers `message_already_reported`, which shows as success. Never resent automatically |

The message itself is **unchanged** after a report: no badge, no hiding, no "reported" mark. The
API exposes no per-viewer report state to drive one, and a local mark would be lost on reload.

## Report a person (Dependency: ChitChat#159, not built yet)

Context: CCM is going on Google Play (maintainer, issue #1). Play's user-generated-content
policy expects in-app reporting of objectionable **users** as well as content. Message
reporting above works today. Reporting a person (an offensive username, profile picture, or a
pattern not tied to one message) needs the API proposed in ChitChat#159. **Leave it out of the
build until that API exists.** This section only fixes the presentation, so the slice is ready
when the API lands.

- **Entry:** the profile card sheet (modals spec) gains **Report {name}…** below Refresh. It is
  shown only when the API exists and the server allows it for that person: not for yourself,
  and guests reportable only if the API says so.
- **Form:** the same form dialog pattern as message reporting: title "Report {name}", context
  line with pebble and username, the same category list (with "Pretending to be someone" and
  "Something else" especially relevant), and optional details (same bound if the API keeps
  1,000 characters).
  - "Who will see it" copy depends on the API's routing (room context or global). Write it
    when the API is final, following the same rule: say exactly who sees what, never more.
  - A room context, when the report is opened from a room, is passed only if the API defines
    it.
- **Outcomes:** the same table pattern as messages. An "already reported" equivalent counts as
  success if the API provides it. Unknown results offer an explicit Send again only if the API
  makes repeats safe; otherwise the unknown flow needs its own decision when the API lands.
- **Follow-up:** the same sheet (ignore or block offered, never applied).
- Acceptance for this part is added when the API exists.

## Guests (M7)

Guests may report room messages. The same flow applies, with the room copy, once guest
sessions exist. Guests never see DM reporting.

## Moderator side (M7, not in scope)

The moderation queue (cases, claim, resolve) is M7. It will live in the steward section of You
("Reports waiting", admin mockup) and in a steward-colored queue screen. This spec adds nothing
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
- **Native:** the details field follows the native input contracts (one tap, focus, IME, 5 s).

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-160 | Report… appears only where the API allows (not own, deleted or system messages; DMs only as recipient). | U, C |
| P-161 | The form requires a category, keeps details optional (≤ 1,000), and states exactly who will see what for room, DM and attachment. | C |
| P-162 | Outcomes match the table. `message_already_reported` shows as success. Unknown offers an explicit Send again only, never automatic. | U, C |
| P-163 | Reporting never hides or marks the message. Ignore and block are offered afterwards, never applied automatically. | C |
| P-164 | Details text is never saved, logged or put in bundles. The form is a form dialog under the native input contracts. | C, R |
