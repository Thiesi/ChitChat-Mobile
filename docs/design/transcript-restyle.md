# Transcript restyle: implementation specification

Status: implementation-ready spec for restyling the room and DM transcripts and their
composers. It follows the [theme foundation](theme-foundation.md) and
[navigation shell](navigation-shell.md) slices, and the coordinator schedules it. Mockups:
`RoomCrowded`, `RoomEmpty`, `DirectThread`, `Offline`, `AdminRoom` and their AMOLED variants.

This is where the signature elements become visible: tail-less bubbles, the chit, and
pebbles beside runs. **It is also the most contract-dense surface in the app.** Read
proof, historical bounds, unknown-send guards, ignore collapse, target navigation, author
and reaction recovery, typing and the pinned composer all live here. Every one of them
keeps its meaning. When this document and a milestone contract disagree, the contract wins.

## Today's transcript (2026-11-07)

- **Rooms** (`ConversationScreen.kt`):
  - One `LazyColumn` (`conversation_timeline`) with keys `history_header`, `message:{id}`
    and `history_end`.
  - Every message renders an author-and-time line (a `TextButton` opening ignore actions
    when allowed), a separate **Profile** `TextButton`, then the content: reply preview,
    body, attachment line, "edited", and `FilterChip` reactions.
  - Ignored senders collapse to a reveal button.
  - The unread divider, the requested-message marker, the private pings dialog and the
    details controls render inline.
- **DMs** (`DirectScreens.kt`): the same building blocks (shared `MessageBody`,
  `ReplyPreview`, reactions, `MessageInput`, `MessageSendButton`) in a thread pane.
- **Read proof:** a `snapshotFlow` over the list's `layoutInfo` checks that the newest
  `message:{id}` item's bottom edge is visible (`isReadEdgeVisible`). It combines this with
  lifecycle, window focus and "not obscured", and publishes `model.viewport(anchor, newest,
  edge)`.
- **Sends:** there are **no local message bubbles**. Sending locks the composer. A confirmed
  send arrives as a canonical row. An ambiguous result keeps the text in the composer with
  an uncertain-send state (`uncertainSend`). After an explicit history check
  (`reconciledAfterUncertain`), the duplicate-risk choice is offered
  (`acknowledgeDuplicateRisk`).
- **Composer:** `MessageInput` is deliberately one `BasicTextField` through IME resizes (no
  focus loss). It is pinned with `MessageSendButton` outside any scroller, and constrained
  controls live in Details.

## Correction to earlier design documents

`communication.md` and the `DirectThread` mockup showed "Sending" and "Not sure" as
*bubbles*. That doesn't match the architecture: CCM never renders a message before the
server confirms it, and it must not start doing so here. Local echo would need text-based
deduplication, which the contracts forbid. This PR corrects both: delivery states live on
the **composer**, and only canonical rows become bubbles.

## Layout

### Rows and keys

- Keep the `LazyColumn`, its test tag, and the item keys `history_header`, `message:{id}` and
  `history_end` exactly. A bubble, its chit, reactions and its in-row captions are all part of
  the single `message:{id}` item. Then `isReadEdgeVisible` measures the whole visual message,
  including the chit, which sits inside the item's bottom padding.
- New visual items (day separators) are **not** separate `LazyColumn` items. They render at
  the top of the first `message:{id}` item of their day, so read proof, anchors and
  placement still address messages only.

### Runs (pure function, unit-tested)

A **run** is consecutive messages with the same `senderId`, not split by a day boundary, at
most **5 minutes** apart (createdAt to createdAt), where neither message is:
- an ignored-collapsed row;
- deleted;
- an emote;
- the requested target.

Compute runs in a pure function from the visible message list. They are presentation only
and never affect selection, read proof or anchors.

| Position in run | Incoming (rooms) | Incoming (DM) | Own (rooms and DM) |
|---|---|---|---|
| First | Sender name line above the bubble | — | — |
| Last | Pebble (32dp) beside the bubble, bottom-aligned, plus the chit | Chit | Chit (bottom-right) |
| Single message | Name line, pebble and chit | Chit | Chit |

- Spacing: 3dp between bubbles of a run, 12dp between runs.
- Gutter: incoming room bubbles keep a 40dp start gutter (pebble plus gap), so a run's
  bubbles align. DMs and own messages have no gutter.
- Width: bubbles stretch to their content, at most about 75% of the transcript width and
  never more than 600dp (A-12).
- Mirroring for RTL follows A-09.

### Bubble

- Shape: `BubbleShape` (20dp, all corners), no tail. The chit is a 9dp circle in the bubble's
  color, offset outward from the speaker corner (bottom-start for incoming, bottom-end for
  own). It is decorative and hidden from accessibility.
- Colors:
  - incoming: `raised` / `text`;
  - own: `outgoing` / `onOutgoing`;
  - mention highlight: `presenceWash`;
  - links: `steward`, underlined (existing M5d2 rules);
  - code: monospace on a slightly stronger tint.
- Inside the bubble, in this order:
  1. the reply quote (existing `ReplyPreview`, restyled as an inset rounded block with the
     author in label weight; tapping keeps today's guarded navigation);
  2. the body (existing `MessageBody` with all its parsing, mention and literal rules);
  3. the attachment line as a file chip (name, type, size; still no preview or download, M5);
  4. a trailing caption with the time and "edited" when present.
- Own messages show no delivery mark. A canonical row is sent by definition.
- **Emote** (`/me`): no bubble. One italic line in `textMuted`, aligned with the run gutter,
  with the literal emote prefix (M5d2). Never grouped.
- **Deleted** tombstone: a bubble with a dashed `fieldLine` outline, no fill, and the italic
  muted text "Message deleted". Retained reactions still render, and own removal stays
  possible (M5c rules).
- **Requested target** (search, reply or notification navigation): a 2dp `unsureLine` ring
  around the bubble and the existing `RequestedMessageMarker` text above it. The `selected`
  semantics remain.

### Sender line, pebble and per-message actions

Today's per-message author `TextButton` and **Profile** `TextButton` become:
- the **pebble** (identity hue from `senderId`, initial, 32dp drawn in a 48dp target):
  opens the profile card with exactly today's guards (`onProfile`, `interactive()`, not busy
  or stale, current entry);
- the **sender name** on the run's first message: opens author actions (ignore) where today's
  author button does. Otherwise it is plain text. Role badges come from existing message data
  only, never inferred.

Every message must keep both actions reachable, not only messages that show a pebble or name:

- **TalkBack:** each bubble exposes custom accessibility actions: "Message actions",
  "React", "Open profile" (when today's profile gating allows) and "Actions for
  {sender}" (when today's ignore gating allows).
- **Touch:** long-press, or tap on the bubble, opens the existing message actions modal. The
  restyle adds "View profile" and the author actions to that modal when allowed, wired to the
  same callbacks and guards. No new authorization.

### Ignored senders (M4c3b)

A collapsed row is never part of a run. It shows the pebble in a neutral, non-identity
color, the sender name as the author-actions button (so unignore stays reachable above
the collapsed row), and the muted line "Message from someone you ignore" with **Show**
(existing reveal). A revealed message renders as a normal bubble but stays ungrouped.

### Day separators and the unread divider

- Day separator: a centered caption ("Today", "Yesterday", or a date following the account
  date preference and device timezone, existing M3b rules) with 16dp vertical space. It sits at
  the top of the first message item of a day, including the first loaded message.
- Unread divider (existing `unreadDividerId`): a full-width 1dp `signature` line with the
  label "New" in `signatureText`, at the top of that message's item. Presentation only:
  it is neither read proof nor a cursor.

### Reactions

- The existing chips are restyled: pill, 32dp drawn and 48dp target (keep `heightIn(min =
  48.dp)` on the touch area), `surface` with a `line` border, emoji plus count.
- When the viewer has reacted: a `signature` border and a 12% persimmon tint.
- Aligned under the bubble on its side, wrapping. The test tags `room_reaction:{id}:{index}`
  and the DM equivalents stay. Tapping still opens the explicit Add/Remove choice, never a
  toggle POST.

## Composer

- Keep the single `MessageInput` `BasicTextField` (no swapping fields), pinned with Send
  outside scrollers, and the Details access for constrained heights (room-composer contract).
- Field: full pill, at least 48dp, `surface` fill, a 1.5dp `fieldLine` border, and a 2dp
  `primary` border when focused.
  - Placeholder: "Message {room}" or "Message {name}".
  - Up to 4 lines expanded and 1 line compact, as today.
  - Trailing emoji-picker slot reserved for M5. Don't render a dead button.
- Send: the 48dp circle from the theme foundation (`signature` with an ink arrow when
  enabled; `raised` with a muted arrow when disabled or sending). While sending it shows a
  small progress ring inside the circle, and the field is read-only (today's locking).
  The accessible label keeps today's strings ("Send" / "Sending").
- Above the field, stacked, at most one of each and in this order:
  1. the reply chip (existing `ComposerReplyControls`, restyled);
  2. the **uncertain-send notice**, a full-width rounded block with a 1.5dp `unsureLine` border:
     **"Not sure your last message arrived."** with **Check history**. That is the existing
     explicit reconciliation action, never automatic. After reconciliation finds no
     matching message, the existing duplicate-risk choice opens as a level-3 dialog: "Send
     again? We checked and couldn't find it. Sending again may post it twice." with **Keep
     as draft** and **Send again**. The text stays in the field throughout;
  3. the definite send failure as a level-2 notice: "Couldn't send. Nothing was delivered.
     Your text is still in the box.";
  4. typing (existing `TypingStatus`): honey dots plus "Lena is typing", with the existing
     expiry. No animation when backgrounded.
- Offline (communication.md): Send is disabled with the accessible reason "Send, waiting for
  connection". The field stays editable, and nothing is sent automatically on reconnect.
- The suggestion chips from `RoomEmpty` insert text only (P-33).

## Room and DM header

In the top bar from the navigation shell:
- a 36dp room lantern (identity hue) or peer pebble;
- the room or peer name in `titleLarge`;
- the subtitle "16 here · 41 members" or "Just you here" from the existing presence data
  after entry.

The lantern glows only when that presence count is known and greater than zero (P-14).
Tapping the header opens today's room details or DM details. While offline, the subtitle
reads "Who's here is unknown while offline".

## Newer messages and Jump to latest

The existing "newer available" and Jump-latest controls become a floating pill above the
composer, at the end side: "New messages ↓" on `surface`, with a `line` border and
`signatureText`. Tapping it runs the existing explicit jump. It is never read proof, and it
never widens historical bounds by itself (M4c4b2a).

## Empty and loading states

- Empty room: the `RoomEmpty` design (dashed bubble, "It's quiet in here.", suggestion chips).
- Empty DM: "Say hello to {name}." with the not-end-to-end-encrypted line under it.
- Loading history: up to three static placeholder bubbles (no shimmer beyond about 2 s, P-34),
  then "Still loading…".

## Out of scope

Media previews and downloads (M5), the emoji picker (M5), mention completion UI (separate
slice), private-pings dialog layout (tokens only), guest and moderation actions (M7) and any
change to ViewModels, repositories, request handling or authorization.

## Tests

- **Unit:** the run-grouping function covers same sender, the 5-minute gap, day change,
  deleted, emote, ignored and target breaks, and own versus incoming. Day-label computation
  uses the account date preference and timezone.
- **Existing read-proof and placement tests pass unchanged.** Add one case where the newest
  message is the last of a run with a chit and reactions: read proof requires the item's real
  bottom edge (the chit included) to be visible.
- **Compose:**
  - every bubble exposes the custom accessibility actions per its gating;
  - mid-run messages reach profile and author actions through the actions modal;
  - ignored rows keep author actions;
  - the uncertain-send notice appears only in the uncertain state, and Check history calls
    the existing reconciliation action;
  - the duplicate-risk dialog appears only after reconciliation;
  - nothing renders as a bubble before the server confirms it.
- **Test tags:** `message:{id}`, `conversation_timeline`, `conversation_composer`,
  `conversation_send`, `room_reply:{id}`, `room_reaction:{id}:{index}` and their DM
  equivalents stay. `room_profile:{id}` moves to the pebble, which only the last message of a
  run has. Tests that targeted a mid-run message switch to the accessibility action or the
  actions modal in the same PR. `room_author:{id}` stays on the name line and on collapsed
  rows.
- Every native input, IME and visibility test runs unchanged: one physical tap focuses the
  composer and shows the IME, 5 s deadlines, no retries.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-80 | Bubbles are tail-less and runs follow the grouping rules. Exactly the last bubble of a run carries the chit, mirrored for own messages and RTL (P-12). | U, S |
| P-81 | The item keys, timeline tag and read-proof computation are unchanged. Read proof measures the full message item, chit included. Day separators and the unread divider are not list items. | C, existing tests |
| P-82 | Profile and author actions are reachable for every message (pebble or name where shown; accessibility actions and the actions modal always), with exactly today's guards. Collapsed ignored rows keep author actions. | C |
| P-83 | No message renders before server confirmation. Sending, uncertain and failed states live on the composer, as specified. Check history and the duplicate-risk choice are the existing explicit actions. | C, existing unknown-send tests |
| P-84 | Reactions keep 48dp targets, explicit Add/Remove, viewer-ID selection and their tags. | C |
| P-85 | The composer keeps one text field instance, the pinned Send, Details access and the existing input contracts. | C, existing input tests |
| P-86 | The header glow and "here" count appear only from known presence after entry. Offline shows "unknown". | C |
