# Lists restyle: implementation specification

Status: implementation-ready spec for the four list surfaces, the tab roots of the
[navigation shell](navigation-shell.md): the room directory, the DM inbox, Activity
(notifications) and message search results. It follows the theme foundation, navigation
shell and transcript slices, and the coordinator schedules it. Mockups: `Home`, `FirstRooms`,
`DirectInbox`, `AmoledInbox`, `Activity`.

As with the other slices, every existing contract keeps its meaning: explicit membership
writes and reconciliation (M3a), DM drafts and attention (M4b), notification read controls
and summary modes (M4c4c), and search privacy and original-result opening (M5e). Where
this document and a milestone contract disagree, the contract wins.

## Shared row anatomy

All four lists use one row pattern, a shared `core/ui` composable. Its content varies per
surface:

- Leading identity, 48dp: a room **lantern** (squircle with the identity hue from the room
  ID) or a person **pebble** (circle with the identity hue from the account ID). Guests get a
  dashed pebble once guests exist.
- Main column: a primary line (name, 16sp, weight 600, one line, ellipsized) and a secondary
  line (14sp, `textMuted`, one line, ellipsized). Bold parts of the secondary line use `text`.
- Trailing column: time or status (12sp, `textMuted`) above a badge (`CountBadge`, or the honey
  `MentionBadge`).
- Minimum 68dp tall, 16dp horizontal padding, and no dividers. Spacing does the separation.
  The whole row is one click target with merged semantics: "{name}, {secondary}, {time},
  {n} unread".
- At 200% font scale the row grows in height; lines never overlap and the badge never
  covers text (A-04).

Rows are list items, never cards. The current `Card`/`OutlinedCard` rows go away.

## Room directory (Rooms tab)

### Today

One `LazyColumn` (`rooms_directory`):
- a header with the title, the slice disclosure, session feedback, room feedback, stale and
  syncing lines, a busy indicator with Cancel, a full-width **Refresh** button (labelled
  **Reconcile** when `mutationUncertain`), and **Create room** for allowed roles;
- one `Card` per visible room (name, joined/invited/discoverable, info line, visibility,
  unread `Badge`). Tapping a room opens the detail dialog, which holds Join, Leave and Open;
- a footer with the presence disclosure and room lookup by ID.

### Target

- **Sections** in the same list, in server order within each:
  1. **Your rooms** (`isMember`);
  2. **Invitations** (`invited`; omitted when empty);
  3. **Open rooms** (the rest).

  Section labels use the Label style. Rooms are **never re-sorted** by unread count or
  activity, not even now that recency exists (#154). The server's order is kept, because
  moving rows on every refresh breaks spatial memory. Recency is shown, never used to sort
  (technical lead, 2026-11-08).
- **Row content:**
  - lantern, name, and as secondary line the info line;
  - for private rooms, a small lock overlay on the lantern;
  - unread `CountBadge` (99+) for joined rooms with `unreadCount > 0`.

  - **Liveliness (ChitChat#153, merged in main `9cf052c`):** joined-room entries carry
    `active_count`, the number of *other* people active in the room (the viewer is
    excluded). It's a snapshot taken when the list was fetched.
    - `active_count ≥ 1`: the lantern gets the honey **glow** (visual-vocabulary.md), and
      the secondary line starts with **"{n} here now"** in `text` weight 600, then " · " and
      the info line, as in mockup `Home`. The presence text never truncates; the info line
      does.
    - `0`, `null` or a missing field: **nothing**. No "0 here", no "quiet", no glow.
    - It refreshes only on the directory's existing triggers (entry, foreground, existing
      room invalidations, manual Refresh). No polling and no SSE for it.
    - **Never shown stale:** while the directory is stale or the device is offline (status
      spec), the glow and "here now" are hidden and the row shows only its info line. A
      snapshot isn't presented as current.
  - **Recency (ChitChat#154, merged):** joined-room entries carry `last_activity_at`, the
    creation time of the newest visible message. It sits in the row's trailing column above
    the unread badge, in Caption and `textMuted`:
    - today: the time ("14:02", in the device's 12- or 24-hour setting);
    - yesterday: "Yesterday";
    - within the last 6 days: the weekday ("Tue");
    - older: a short date ("12 Oct"; the year is added when it differs from this year).
    - `null` or missing: nothing. Never "No activity" and never "Never".
    - It's converted from UTC to the device time zone on display. The relative buckets use
      the device's local date.
    - It includes messages from accounts the viewer ignores (ignore is a display filter), so
      it shows **time only**: never a sender, preview or "new message" wording. The time may
      therefore be newer than the last message the viewer sees in the transcript, and that's
      expected.
  - **Mentions (shipped in v3.9.0, ChitChat#266/#267).** Joined-room entries gain
    `unread_mention_count`: an integer from 0 to 100,
    capped like `unread_count`, counting unread room-mention notifications after the viewer's
    read position (deleted messages and ignored senders excluded). Reading past the mention
    clears it; non-joined rooms get 0. Contract: `docs/api/rooms.md` at `v3.9.0`. It's
    inside CCM's 3.10.0 minimum; the app still reads the field's presence, never a version.
    - `unread_mention_count ≥ 1`: the honey **`MentionBadge`** with "@" (theme-foundation.md)
      in the trailing column, on the badge line, to the start of the unread `CountBadge`
      with a 4dp gap. It's a marker, not a count: the number is never shown, because the
      unread badge already carries the count.
    - `0`, `null` or a missing field: no marker. A missing field never triggers the server
      update notice.
    - It refreshes with the unread count on the directory's existing triggers (entry,
      foreground, existing room invalidations, manual Refresh), with no polling, SSE or
      extra request. Like the unread count, it shows the last fetched value while the
      directory is stale; it's a backlog marker, not a "now" claim like presence.
    - It never reorders rooms, and the Rooms tab keeps no badge (navigation-shell.md,
      decided).
  - **Accessibility:** the row's content description reads, in order, the name, "{n} people
    here now" (when shown), the info line, "Last message {time or day}" (when shown), the
    unread count and "mentioned" (when the marker shows).
  - Invitations and open rooms carry none of these fields (`null` or 0 when not joined) and
    show none of them.
- **Tap behavior** (a deliberate flow change, using existing guarded callbacks):
  - a joined room opens the conversation directly through the existing `openRoom` path,
    with its current guards;
  - invited and open rooms open the restyled **room sheet**: today's detail dialog content
    as a bottom sheet, with Join, Leave, Open and visibility help, using its existing gating
    and `canMutate` rules;
  - joined rooms reach the same sheet through long-press or the row's "Room details"
    accessibility action.
- **Header area** (scrolls with the list):
  - the drafts entry (`room_directory_drafts`) as a compact row: "2 drafts waiting ·
    Manage";
  - room feedback and session feedback as level-2 notices (communication.md);
  - stale and syncing as level-1 lines;
  - the busy state as a linear indicator with Cancel.
- **Refresh:** pull-to-refresh calls the existing `model.refresh`, and so does an overflow menu
  item "Refresh". When `mutationUncertain`, an explicit level-2 notice replaces the plain
  refresh: "We're not sure your last room change went through." with **Check room list**,
  which is the existing reconcile. It stays visible until the reconcile finishes. Pull-to-
  refresh never doubles as reconciliation consent.
- **Create:** an extended FAB "New room", only for roles allowed today (`canCreateRooms`),
  enabled per `canMutate`. It opens the existing create dialog, restyled with the theme.
- **Footer:**
  - "Open a room by its number" collapses the lookup by ID into one row that expands the
    existing field and button;
  - the slice and presence disclosures sit at the bottom in caption style. They stay while
    the parity docs require them.
- **Empty:** "No rooms yet. When someone opens a room on this server, it shows up here." plus
  the FAB for creators.
- **Not in scope:** the "Jonas mentioned you" card at the top of mockup `Home`. The notification
  summary used outside Activity is `limit=1` and never adopts bodies (M4c4c2), so the card has
  no honest data source today. It would need a product decision on fetching the newest
  mention for display.

## DM inbox (Direct tab)

### Today

A `LazyColumn`:
- a header with refresh, stale, error, privacy notice and realtime details, the no-push
  note, the person search field and button, help and results;
- a **Drafts** section (name, uncertain flag, reply flag, Discard);
- the cap note;
- `OutlinedCard` conversation rows (username, preview, time, unread text, local attention).

### Target

- **Header:**
  - the not-end-to-end-encrypted line with "What this means" (opens the existing
    privacy details) stays at the top;
  - the no-push note becomes a caption under it while it applies;
  - person search moves behind the top-bar search action as a full-screen picker with the
    existing field, validation, results and tags (`dm_search_input`, `dm_person:{id}`).
    Its behavior is unchanged.
- **Filters:** two chips, "Unread · n" and "Drafts · n". These are local view filters over
  already-loaded data with no new requests, and they are hidden when their count is zero.
- **Conversation rows:**
  - pebble and name;
  - secondary line: the preview, with today's rules (deleted reads "Message deleted";
    attachments show their name; outgoing reads "You: …"; 160 characters max). A draft for
    that peer replaces the preview with "**Draft:** …" in `signatureText`;
  - trailing: the time per account display preferences, and an unread `CountBadge`;
  - local attention (`LocalAttention`) becomes a persimmon dot on the pebble, with its text
    kept in the row's semantics.

  Typing in the inbox (mockup `DirectInbox`) is **not** in scope. Typing is subscribed per
  viewed target only (M4c2).
- **Drafts for peers without an inbox row** appear in a "Drafts" section at the top, with
  "Not sure it arrived" for uncertain drafts (existing flag) and Discard. The existing
  confirmation stays.
- **Relationship states (ChitChat#274, v3.9.0, inside the minimum).** Each `conversations.php`
  row carries `user.available` and `relationship {blocked_by_me, messaging_available}`, with
  `block-status.php`'s meaning. The row shows at most one state, in this order:
  1. `user.available == false`: **"Account no longer active"**, with a desaturated pebble.
     A finalized closure also arrives with the `Closed account #<id>` name, shown as sent.
  2. `relationship.blocked_by_me == true`: **"You blocked this person"**, with a
     desaturated pebble.
  3. `relationship.messaging_available == false` (and neither of the above): **"Messaging
     unavailable"**, with the pebble unchanged. This is deliberately neutral, the same
     meaning as the thread's existing `dm_unavailable` line. It never says or suggests that
     the other person blocked the viewer, and nothing else in the row, sheet or
     description may imply it.
  4. otherwise, or when the fields are missing: no state, and nothing changes. A missing
     field never triggers the server update notice.

  The state is the start of the secondary line in `textMuted`, never truncated, then " · "
  and the draft ("**Draft:** …", which keeps its precedence) or the preview, which
  truncates. The unread badge, time and local attention are unchanged. The content
  description adds the state after the name. The state never disables opening the
  conversation: history stays readable, and the thread keeps its own send gating and
  copy. Only `blocked_by_me` is ever shown as a block; an unavailable peer's block in the
  other direction is never revealed (server rule). The states refresh with the inbox's
  existing triggers only.
- **FAB** "New message" opens the person picker.
- Keep the test tags `dm_conversation:{peerId}` and the draft and person tags.

## Activity (notifications)

### Today

A `LazyColumn` (`notification_list`):
- a header with the count, an unknown-read notice, Refresh, Latest and **Mark all read**
  (confirmation dialog);
- `Card` rows with text, time, **View** and **Mark read**;
- a footer with Refresh and **More**.

### Target

- **Top bar** (from the navigation shell): "Activity" with the overflow items "Refresh",
  "Latest" and **Mark all read**. The confirmation dialog, its tags and the
  `requestAll`/`confirmAll`/`cancelAll` flow are unchanged.
- **Sections:** "New · n" (unread) and "Earlier" (read), in server DESC order. A section
  split never reorders items across pages.
- **Rows:**
  - leading: the actor pebble when the notification names a user, with a type badge
    overlay (honey "@" for mentions, persimmon paper plane for pings, steward shield for
    moderation and account), else a type icon in a `raised` circle (a document icon for
    `privacy_policy_updated`, ChitChat#160, which opens the server's privacy document per
    play-readiness.md §1). `room_ownership_received` and `room_ownership_removed` (ChitChat#273,
    v3.9.0, in-app only, never pushed) name no actor: they get the room's lantern (identity
    hue from the stored room ID) with the steward shield badge, and open the room through
    the existing guarded room destination (M4c4d). Their text is the server's rendered text,
    like every other kind;
  - primary: the rendered title text as plain text;
  - secondary: the rendered context ("Kitchen · …");
  - trailing: the time;
  - unread rows get a `signature` dot before the title and a 7% persimmon row tint.
- **Actions per row:** tapping the row is **View** (the existing guarded destination opening,
  M4c4d). **Mark read** becomes a trailing 48dp icon button on unread rows (check icon, label
  "Mark read"), with the same `canRead` gating, plus a swipe-free accessibility action. No
  swipe-to-dismiss: notification read is explicit.
- **Unknown read:** when `unknownRead`, a level-2 notice "We're not sure your last 'mark read'
  went through." with **Check** (existing refresh/reconcile). Read controls stay disabled until
  then, exactly as today.
- **Paging:** "Load older" at the end (existing **More**). Never automatic infinite scroll,
  because More is an explicit GET on fresh ranges.
- The footer line "Reading an item here doesn't mark the chat as read." (communication.md)
  stays.
- Keep the test tags `notification_list`, `notification:{id}`, `notification_view:{id}`,
  `notification_read:{id}`, `notifications_all`, `notifications_all_confirm`,
  `notifications_more`, `notifications_refresh` and `notifications_count`. The count tag
  moves to the top-bar subtitle.

## Search results

### Today

`message_search_screen`:
- a query field with submit and cancel icon buttons;
- details with scope `FilterChip`s, the privacy-missing state, refresh session and limits;
- results with Previous/Next and an explicit retry.

### Target

- **Query bar:** a pill field (48dp) with the search icon, "Search messages" placeholder,
  and a clear button. Submit stays an explicit action (IME Search or the trailing icon), never
  search-as-you-type, so there are no requests per keystroke.
- **Scope:** segmented chips "All · Rooms · Direct" under the field, 48dp. The existing
  privacy gating disables Direct and All with the existing explanation until privacy is valid.
- **Result rows:**
  - room results get the lantern and room name; DM results get the pebble and peer name;
  - secondary: the server's excerpt, with its matches highlighted from `excerpt_matches`
    (v3.10.0, ChitChat#283; presentation lead, 2026-11-14). Each match gets a `presenceWash`
    background plus SemiBold weight, two cues and never colour alone; ink on `presenceWash`
    keeps 4.5:1 in light and dark. Ranges are UTF-16 offsets into the excerpt and apply
    all-or-nothing per row: only if every range lies inside the excerpt, in order, without
    overlap, with length ≥ 1 and without splitting a surrogate pair. Otherwise that row's
    excerpt shows plain; a range is never repaired or clipped. The server's "… " and " …"
    render as given. TalkBack reads the excerpt as plain text and announces nothing extra.
    The app never finds matches itself;
  - trailing: the time;
  - tapping opens the original through the existing guarded flow (M5e3b).
- **Paging:** "Previous 25 / Next 25" buttons at the end, using completed search context, as
  today.
- **Empty:** "Nothing found for '…'." with the query echoed only in the visible text, never
  logged or saved.
- Keep all existing `message_search_*` tags.
- **Later:** "Search in this room" (the v3.10.0 `room_id` filter) from the room details sheet
  needs its own small design (scope chips hidden, the room named in the field). Until then
  `room_id` stays unused.

## Tests

- Compose:
  - each list renders the shared row with merged semantics and 48dp targets;
  - section grouping follows the data;
  - a joined-room tap calls `openRoom` and others open the sheet;
  - `mutationUncertain` shows the Check room list notice and pull-to-refresh does not clear
    it;
  - DM filters make no requests;
  - Activity's Mark read stays gated by `canRead`, and View keeps M4c4d's guards;
  - search submits only explicitly.
- Every existing test tag listed above keeps working. Tests that depended on card structure or
  button text are updated in the same PR, with the reason given.
- Screenshots: each list in Light, Dark and AMOLED black, at default and 200% font scale, with
  long names, 99+ badges and empty states.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-90 | All four lists use the shared row anatomy, with lanterns for rooms and pebbles for people (P-10, P-11), and no cards. | S, R |
| P-218 | (v3.9.0, inside the minimum.) DM inbox rows show at most one relationship state, in order: "Account no longer active" (`user.available` false), "You blocked this person" (`blocked_by_me`), or the neutral "Messaging unavailable" (`messaging_available` false). Nothing implies the other person blocked the viewer. The state starts the secondary line and is in the description; rows stay openable; missing fields show nothing. | U, C, S |
| P-91 | The room directory shows Your rooms, Invitations and Open rooms in server order and never re-sorts by recency, presence or mentions. No mention data is shown beyond P-215's per-room marker. | C, R |
| P-215 | (v3.9.0, inside the minimum.) A joined row shows the honey "@" `MentionBadge` beside the unread badge only for `unread_mention_count ≥ 1`, never a number, and nothing for 0, null or missing. Its description adds "mentioned". It refreshes only with the unread count; no new requests, timers or sorting, and a missing field never triggers the update notice. | U, C, S |
| P-96 | Joined rows show the glow and "{n} here now" only for `active_count ≥ 1` on a fresh, online directory, and nothing for 0, null or missing. Recency shows `last_activity_at` as time, Yesterday, weekday or date, and nothing for null, never with a sender or preview. No new requests or timers. | U, C |
| P-92 | Membership writes, reconciliation and the uncertain state behave exactly as today. Check room list is explicit, and pull-to-refresh never reconciles. | C, existing tests |
| P-93 | DM rows keep today's preview rules, draft precedence and local attention, and filters make no new requests. The privacy line stays visible. | C |
| P-94 | Activity keeps explicit View, Mark read and Mark all with confirmation, unknown-read gating, explicit More and server order. | C, existing tests |
| P-95 | Search stays explicit-submit with privacy-gated scopes and guarded opening. Excerpts highlight only server `excerpt_matches` ranges (`presenceWash` plus SemiBold, ink at 4.5:1), all-or-nothing per row with strict validation, plain otherwise, with nothing extra for TalkBack. | U, C, existing tests |
