# Administration (M7): presentation specification

Status: presentation spec for server administration on the phone, written ahead of the M7
engineering slices (technical lead, 2026-11-09). It's grounded in the ChitChat API at main
`c56a2b1`: `admin.md` (users, the #191 directory fields, audit filter), `rooms.md`,
`system-settings.md`, `guest-access.md` and `docs/operations/observability.md`. Vocabulary
matches the web's Stewarding rework (#191, web S6 #172), as agreed with the CC visual lead.
Mockup: `StaffPeople` on the canvas. Moderation (the Reports queue, acting on a person) is in
moderation.md.

## What belongs on a phone

An administrator on a phone is usually responding to something: a report, a troublemaker, an
outage. The app covers those moments, and leaves long-form or rare, consequential work to
{installation}'s website.

| In the app | Web only |
|---|---|
| Reports queue and decisions (moderation.md) | Whole-conversation DM inspection and edit-history review (moderation.md) |
| People directory (read view), a person's staff facts, mute, ban, lift, sign out everywhere | The audit log (including per-person audit), global role changes, administrator password reset |
| Room settings, members, moderators, invitations, for rooms you manage | Room deletion and restore |
| Server status (read only) | System settings: retention, registration, guest access, MFA policy, application name |
| Maintenance lockdown on/off (Super-Administrators, behind step-up) | Registration protection, the rules and privacy document editor |

Where a web-only task would naturally be looked for, the app shows a muted line, "**Do this on
{installation}'s website.**", with an explicit **Open website** text button (HTTPS origin only,
never opened automatically). It never pretends the feature doesn't exist.

## Stewarding

The staff area (moderation.md, "Entry"), titled **Stewarding** with the subline "{installation}
· {name}, {role}". Steward-colored throughout. Rows appear only for the viewer's powers:

- **Reports** (queue access);
- **People** (Super-Administrators and Administrators);
- **Rooms** (global room administrators, and owners of at least one room);
- **Server status** (Administrators and up);
- **Maintenance lockdown** (Super-Administrators).

## People (mockup `StaffPeople`)

- **Search:** a username prefix field (case-insensitive and literal, up to 32 characters).
- **Filter chips with counts:** **Everyone**, **Staff**, **Banned**, **Muted**, **Closing**, for
  example "Banned · 3". The counts follow the search and ignore the selected chip (server rule).
- **Sort:** **ID** (default) or **Recently signed in** (newest first, never-signed-in last).
- **Rows:** pebble, username, role badges (steward style), then one secondary line, the most
  important fact first:
  - "Banned until {date}" (or "Banned"), in rose;
  - "Muted everywhere until {date}", or "Muted in {room}" plus "and {n} more rooms";
  - "Closing on {date}" (from `closing.purge_at`);
  - otherwise "Signed in {relative time}" or "Never signed in".
- **Paging:** **More** from `next_cursor`. Changing search, chip or sort starts over. The list
  doesn't claim a frozen order: sign-in sorting can shift while someone is paging (server note).
- **Client check:** `counts` and `next_cursor` present. On an older server, there are no chips,
  no sort and no restriction lines, only search and ID order with **More**.
- Guests aren't in the directory (server rule). Guest actions live on their profile cards.

**A person (staff view)** opens from a row: the profile card's facts, plus:

- roles, "Joined {date}", "Last signed in {date}" or "Never signed in";
- **Active ban:** reason, "by {name}", "since {date}", "until {date}" or "until lifted", with
  **Lift ban**;
- **Active mute:** scope and "until …", with **Lift mute** (room mutes list their room);
- **Closing:** "Their account closes for good on {date}. Until then they can restore it.";
- **Reports about them:** the subject counts (moderation.md, History), which open the filtered
  queue;
- actions from `profile.moderation.everywhere` only: **Mute everywhere**, **Sign out
  everywhere**, **Ban** (forms and disclosures in moderation.md);
- web-only lines: "Roles, password reset and this person's audit history: do this on
  {installation}'s website."

## Rooms

- **List:** rooms the viewer can manage (global room administrators: every listed room; owners:
  their rooms), from the existing room list, in server order. Deleted rooms aren't listed.
  "Deleting and restoring rooms: do this on {installation}'s website."
- **Room settings** (also reachable as **Manage room** from room details for owners), a form
  screen with **Save**:
  - name, info line, visibility (public, unlisted, private, with their existing explanations),
    minimum age, inactivity timeout (the existing presets), guest access (closed, read, write;
    only for public rooms without a minimum age);
  - closing a room to guests or adding a minimum age shows: "Guests in this room will be
    removed now.";
  - the request sends the complete editable state (server rule). It's never retried, and Unknown
    re-reads the room before showing the form again.
- **Members** (from the room snapshot): pebble, name, room role, "joined {date}", and a presence
  dot when connected. Row actions, by rank: **Make moderator** / **Remove moderator**, **Remove
  from room** (with "Tell the room" and the verbatim announcement, as in moderation.md). The
  owner can't be removed and has no actions.
- **Invitations:** pending invitations with the inviter, plus **Revoke**. **Invite someone**
  searches with the room-scoped search (2–32 characters; members and already-invited people are
  excluded), then confirms "Invite {name} to {room}?".
- Ownership can't be transferred (server rule), so the app offers no such action.

## Server status (read only)

From `admin/system-status.php` (Administrators and up). No usernames, bodies, names of files or
addresses are ever shown (server rule).

- **Version:** application version and environment.
- **Health:** database status and query time, database size, file storage used and free.
- **Activity:** live connections and distinct people connected, people present in rooms,
  failed sign-ins in the last 24 hours.
- **Maintenance:** last run and last successful cleanup. When cleanup is overdue, a honey
  level-2 notice: "Maintenance hasn't completed recently. Ask whoever runs the server to check
  it."
- Rate-limit tables and other detailed figures: "More detail is on {installation}'s website."
- Refreshes only on entry and with an explicit **Refresh**.

## Maintenance lockdown (Super-Administrators)

An emergency switch, so it's on the phone, behind step-up.

- **Status card:** "Lockdown is off", or "**Lockdown is on** since {time}", with the message
  people see.
- **Turn on** opens a form screen:
  - the message people see (optional, up to 500 characters; empty uses the server's default
    text);
  - a switch "**Also sign everyone out now**" (off by default), with "Everyone except
    Super-Administrators is signed out at once and sees your message.";
  - what lockdown does, in plain words: "Nobody can sign in or create an account except
    Super-Administrators. People already signed in keep working unless you sign them out.";
  - **Turn on lockdown** (`error`), through `withStepUp`.
  - Success: "Lockdown is on. {n} people were signed out." (the count only when the switch was
    on).
- **Turn off:** a decision dialog, "Turn off lockdown? People can sign in again.", then
  `withStepUp`.
- Unknown results re-read the lockdown status. Nothing is resent.
- While lockdown is on, every member sees the existing lockdown notice (status-presentation.md),
  including this administrator.

## Tests

- **Unit:** Stewarding rows per role; chips, counts and cursor reset on any change; the
  older-server fallback without chips; row secondary-line priority; staff facts from
  `active_ban`, `active_mute` and `closing`; room settings send the full state; guest-removal
  warning conditions; lockdown sign-out count only when requested; no automatic resend
  anywhere; web-only rows never open a browser by themselves.
- **Compose:** steward styling; lockdown form copy and the `error` action; status overdue
  notice.
- **Native:** search, reason, message and room fields follow the native input contracts.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-205 | Stewarding shows only the rows the viewer's role allows. Web-only tasks show "Do this on {installation}'s website." with an explicit Open website, never an automatic browser. | U, C |
| P-206 | People uses search, the Everyone/Staff/Banned/Muted/Closing chips with server counts, the two sorts and cursor paging, and falls back to search and ID order on older servers. Rows show the most important restriction or sign-in fact. | U, C |
| P-207 | A person's staff view shows roles, dates, active ban and mute, closing date and report counts, with only the server-allowed everywhere actions. Roles, password reset and audit stay web-only. | U, C |
| P-208 | Room settings send the full editable state, warn before removing guests, and never resend. Members and invitations follow the ranks. Delete and restore stay web-only. | U, C |
| P-209 | Server status is read-only and shows no personal data. Lockdown is Super-Administrator-only, explains its effects, needs step-up, and reports the sign-out count. | U, C |
