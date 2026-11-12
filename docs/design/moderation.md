# Moderation (M7): presentation specification

Status: presentation spec for moderating on the phone, written ahead of the M7 engineering
slices (technical lead, 2026-11-09). It's grounded in the ChitChat API at main `c56a2b1`:
`moderation-reports.md` (queue, person cases from #159, subject filter from #191), `admin.md`
("Moderating in place", mutes, bans), `rooms.md` (ranks), `guest-access.md` (guest actions),
`message-revision-review.md` and `direct-messages.md` (inspection). Vocabulary is being aligned
with the ChitChat web admin rework (#191, web S6 #172) through the CC visual lead: both clients
use the same status, action, resolution, snapshot, scope and restriction words. The web calls
its queue section "Moderation queue" where CCM's screen title is "Reports", a platform-context
difference, not a vocabulary one. Mockups: `AdminHome` (the steward section) and
`ModerationCase` on the canvas.

**Web-only split confirmed** by the maintainer on 2026-11-09 (issue #1, comment 6735056763):
whole-conversation DM inspection and edit-history (revision) review stay on the web, with the
other web-only tasks in administration.md. Where one would be looked for, the app shows "Do
this on {installation}'s website." with an explicit **Open website** (administration.md).

**Minimum server version.** The subject filter and `counts` (#191) are guaranteed by the
minimum server version (3.7.0), as in administration.md. There's no fallback for
older servers and no per-field check.

All moderation surfaces use the **steward** role color (Dusk) and the `steward` shield icon.
Persimmon stays the person's own voice, and a moderator acting is not a personal action.

## Principles

- **Offer only what would succeed.** The server says what this viewer may do
  (`profile.moderation`, case scope). Nothing is shown greyed out "for admins".
- **Evidence is not a window into private life.** A DM report shows that one message and
  nothing around it. Reading more always goes through its own audited, disclosed flow.
- **Say who will learn what.** Before any action that tells someone something (a mute reason,
  a room announcement, a revision-review notice), the screen says who sees it.
- **Recording an outcome isn't doing it.** Resolving a case records what happened. The actual
  action (delete, mute, ban, remove picture) is a separate, explicit step.
- **No retries on writes.** Every moderation POST is explicit. An unknown result re-reads the
  case or profile, never resends.

## Entry: Looking after {installation}

- The You tab shows a **Looking after {installation}** card (navigation-shell.md) for accounts
  that can review cases or moderate. The server decides: the card appears when a
  `moderation/cases.php` request (`status=open`, `limit=1`) succeeds, or the profile reports
  moderation powers.
- Tapping it opens the staff area, titled **Stewarding** (the web's name for the same place),
  with the subline "{installation} · {name}, {role}".
- Rows: **Reports** with "{n} open" (counted from the open queue, shown as "50+" past one page),
  and for administrators **People** and **Rooms** (administration spec).
- The You tab gets a steward-colored dot while open cases exist, refreshed only on existing
  triggers (entry, foreground, manual refresh). No polling and no push of its own.

## Reports queue

- **Screen** "Reports", steward accent in the top bar.
- **Filters** (chips): **Open** (default), **In review**, **Resolved**, **Dismissed**, **All**,
  plus a kind chip, **Everything / Messages / People**. Each chip maps to one `cases.php`
  request, in server order (newest case first), with **More** from `next_before_id`.
- **Rows:**
  - leading: the subject's pebble (identity hue), or the `report` icon for a message case;
  - primary: "Report about {subject}'s message" or "Report about {subject}";
  - secondary, the scope: "In {room}", "Direct message", or "Everywhere" (a global person
    case). A purged room shows "{room} (deleted)" in `textMuted`, never as a link;
  - trailing: "{n} reports" and the last report time;
  - a status chip: Open (steward outline), In review with "Claimed by {name}", Resolved or
    Dismissed (muted).
- **Room moderators** see only cases in rooms they own or moderate. The empty state says "No
  open reports in the rooms you look after." Global staff see "No open reports."
- Nothing in the list shows evidence, reporter names or details. Those are on the case.

## A case (mockup `ModerationCase`)

**Header:** the summary row, a small reference line "Case #{id}" (staff quote it to each other),
the status chip and **Claim** (when open), or **Release** (when it's
yours, or you're global staff). `moderation_case_assigned` says "{name} already claimed this
report." and refreshes. Closed cases can't be claimed.

**Message case evidence:**

- The **snapshot** exactly as kept: the message text as **plain literal text** (no links, no
  mention styling, no formatting), the sender, its time, the room, and for attachments "File:
  {name}, {type}, {size}" (no download right).
- **DM case:** a steward-tinted line above the snapshot: "You're seeing only the reported
  message. The rest of their conversation stays private."
- If the canonical message changed since (deleted or edited), only the snapshot is shown, with
  the caption "As it was when it was reported."

**Person case evidence:**

- "**Name then**": `snapshot.username`, and "**Name now**": the current name, as two lines (only
  the first when they're the same).
- "**Picture at first report**": loaded from `snapshot.avatar_url` only when the case opens,
  through the session transport, held in memory only (the server sends `no-store`). Without
  one: "No picture at first report."
- When `avatar_changed_since_report` is true: "**Their picture has changed since the first
  report.**" The live picture is never fetched for comparison here.
- The scope: "In {room}" or "Everywhere".

**Reports:** one row per report: the reporter's name, the category label (reporting.md), the
optional details as plain text, and the time. Details are participant-written and shown
literally.

**History:** a row "Earlier reports about {name}: {open} open, {resolved} resolved,
{dismissed} dismissed.", from the subject filter's `counts`. It opens the queue filtered to that
person. The filter and `counts` are guaranteed by the minimum server version (above). A
response missing them is a server problem shown with the existing status ladder: the row is
left out, and an unfiltered list is never presented as someone's history.

**Take action** (button, steward outline) opens a sheet with only the actions this viewer can
do, from `profile.moderation` for the subject (with the case's `room_id` for room cases), plus
message deletion by rank:

- **Delete this message** (room message case, when the rank allows): the existing delete
  confirmation.
- **Mute…**, **Remove from {room}**, **Ban…**, **Sign out everywhere**: see "Acting on a
  person".
- **Remove profile picture** (`can_remove_avatar`): a decision dialog, "Remove {name}'s profile
  picture? They'll be told it was removed." The account gets an `avatar_removed`
  notification.
- **End guest visit** / **Block guests from this connection** (guest subjects): see "Guests".
- **Review edit history**: web-only (confirmed). On a message case, a muted line "Reviewing
  edit history: do this on {installation}'s website." with **Open website**.

After an action, the case stays open and suggests resolving it, but never auto-resolves.

**Resolve** opens a form sheet:

- **Outcome:** Resolved or Dismissed.
- **What happened** (radio, by outcome):
  - Dismissed: **No rule broken** (`no_violation`, the only dismissal code);
  - Resolved: **Content removed**, **Person warned** (helper: "Record that you warned them,
    for example in a direct message. The app doesn't send a warning."), **Account restricted**,
    **Profile picture removed** (person cases only), **Something else** (note required).
- **Note** (optional, required for Something else): "Only moderators see this note."
- A fixed line: "The people who reported this aren't told the outcome."
- **Resolve** / **Dismiss** (steward), **Cancel**. Unknown re-reads the case.

## Acting on a person

From a case, or from the **Moderation** section of the profile card (modals-restyle.md), which
appears only when `profile.moderation` is not null:

- **In this room** (with `room`): **Make moderator** / **Remove moderator** (`can_set_moderator`),
  **Remove from {room}** (`can_remove`), **Mute in this room** (`can_mute`), or **Lift mute** when
  `mute` is set ("Muted until {time}").
- **Everywhere** (with `everywhere`): **Mute everywhere** (`can_mute`), **Sign out everywhere**
  (`can_kick`), **Ban** (`can_ban`), or **Lift ban** / **Lift mute** when active ("Banned until
  {time}: {reason}").
- **Mute form:** where (this room / everywhere), for how long (1 hour, 1 day, 1 week, until
  lifted), a reason, and for room mutes a switch "**Tell the room**" (off by default).
  - Disclosure lines: "{name} will see this reason in their notifications." and, when Tell the
    room is on, "The room sees '{name} was muted in this room for 1 day.' without the reason or
    your name."
  - Everywhere: "Muting everywhere also stops their direct messages."
- **Ban form** (administrators): duration (1 day, 1 week, 30 days, permanent), a reason, and
  with a room context, "Tell {room}". Disclosure: the announcement text is shown verbatim.
  Bans and kicks sign the person out.
- **Sign out everywhere:** a decision dialog, "Sign {name} out everywhere? They can sign in
  again." Kick, ban and unban need no step-up (server rule).
- Every form is a form dialog under the native input contracts. Reasons are never saved or
  logged. Unknown results re-read the profile card, never resend.
- **Ranks:** only actions the server lists appear. A `403` caused by a rank change between
  loading and acting says "You can't do that anymore. Someone's role may have changed." and
  refreshes.

## Guests

On a guest's profile card, when `moderation.guest` allows it:

- **End visit**: "End Guest 4821's visit now? They'll be signed out and leave every room."
- **Block guests from this connection**: duration (1 hour, 1 day, 1 week) and a reason. "Every
  guest from their connection ends now, and new guest visits from it are blocked. You won't see
  the address. Members on the same connection aren't affected."
- Kick and ban aren't offered for guests. Muting is.

## Web only: DM inspection and edit-history review

Inspecting a whole conversation between two people (`admin/direct-messages/inspect.php`) and
reviewing a message's edit history (`message-revision-review.md`) stay **on {installation}'s
website**, not in the app (maintainer, 2026-11-09). Paging through someone's private
conversation on a phone is hard to do carefully (shoulder-surfing, notifications, screenshots),
and both are audited reason flows that the web console already carries with full disclosure.
On the phone, a DM case shows only the reported message, plus "Do this on {installation}'s
website." with **Open website** for anything further.

## Tests

- **Unit:** the card and queue appear only from server answers; filters map to one request each;
  room-moderator empty copy; the history row from the subject filter's `counts`, left out when
  a response lacks them; Take action lists only offered actions; resolve codes per outcome (`no_violation` only for dismissed,
  `profile_picture_removed` only for people, a note for other); unknown re-reads; the case
  avatar held in memory only.
- **Compose:** steward styling; DM-case privacy line; disclosure lines in mute, ban and guest
  forms; the web-only lines with an explicit Open website; plain-text evidence (links inert).
- **Native:** reason and note fields follow the native input contracts.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-200 | The steward section and Reports queue appear only when the server grants queue access. Rows show scope and counts but no evidence. Room moderators see only their rooms' cases. | U, C |
| P-201 | Case evidence is shown as kept: plain literal text, a DM case limited to the reported message with its privacy line, person snapshots (name and picture at first report, memory only) and the changed-picture note. | U, C |
| P-202 | Take action offers only server-allowed actions. Resolving records an outcome with the allowed codes, says reporters aren't told, and never performs or implies the action. | U, C |
| P-203 | Mute, ban, kick, room role and guest actions disclose who learns what (reason to the person, the neutral room line), need a confirmation, and never resend on Unknown. | C |
| P-204 | Whole-conversation DM inspection and edit-history review aren't offered in the app (web-only, confirmed). Where they'd be looked for, the app shows "Do this on {installation}'s website." with an explicit Open website, never an automatic browser. | U, C |

## Appendix (parked): in-app edit-history review

**Parked, not part of the active spec.** The maintainer confirmed edit-history review as
web-only on 2026-11-09. This design is kept only for reference, in case that decision is ever
revisited. Nothing here is built.

Revision review is an opt-in, audited capability with disclosure duties. In the app it would be
offered **only from a message case**, for that exact message, when `privacy.message_revisions`
is enabled and the viewer holds the configured role.

- **Before:** a form screen "Review edit history" with:
  - "This shows earlier versions of this message that were edited or deleted."
  - "**{Author}** (for a DM: **both people in the conversation**) will be told that a review
    happened. They won't see your reason."
  - "Your reason is recorded with your name." and a **Reason** field (10–500 characters,
    required, counter).
  - **Review** (steward), through `withStepUp`.
- **Result:** the revisions in order, each with its action (Edited / Deleted), who did it, when,
  and the text before (and after, for edits) as plain literal text. `revision_history_not_found`:
  "This message has no earlier versions."
- Nothing is cached. Leaving the screen discards the content. Each new review asks for a reason
  again, because each is audited.
