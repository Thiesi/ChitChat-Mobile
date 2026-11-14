# Dialogs, sheets and profile cards: implementation specification

Status: implementation-ready spec for every modal surface: message actions, reactions,
author editing, confirmations, details panels, the profile card and the step-up prompt. It
follows the theme foundation and can land with or after the transcript and lists slices.
Mockup: `AdminRoom` (message actions sheet).

Modals carry contracts that a visual change must not disturb:
- they **suppress read and typing proof** while open;
- old buttons must not act on successor requests (original-reference guards);
- work is retained across configuration changes and retired on genuine navigation and
  background;
- author editing has **own-window IME and Save geometry** (M5b2);
- tests reach modals through OS-routed native taps (`performNativeTap`).

Every one of these keeps its meaning. When this document and a milestone contract disagree,
the contract wins.

## Today (2026-11-07)

About 21 Material `AlertDialog`s, one custom `Dialog` (the compact author editor) and a
`DatePickerDialog`, all centered. They include:
- message actions (`message_actions`) and the reaction picker;
- author edit and delete (`author_change_dialog`, `MessageAuthorChangeDialog`);
- reply options (`composer_reply_dialog`);
- conversation details, private pings (`private_pings_dialog`) and the presence list;
- the room drafts manager (`room_drafts_dialog`), the room detail and create dialogs, the
  leave confirmation, the DM block confirmation and discard draft;
- the DM privacy details and the profile card (`profile_dialog`);
- ignore person (`room_person_dialog`);
- step-up, forget server, mark all read, the announcement text and the message-target dialog
  (`message_target_dialog`).

## Three modal kinds

Every modal is one of three kinds. The kind decides its form; content and behavior stay
as they are.

| Kind | Purpose | Form | Members |
|---|---|---|---|
| **Choice sheet** | Pick one of several actions about one thing | Modal bottom sheet | Message actions (with the reaction row and link choices), the reaction picker's Add/Remove, room details (from the lists spec), the presence list, private pings, reply options, the drafts manager, DM privacy details, the announcement text, the profile card |
| **Decision dialog** | A level-3 choice with consequences (communication.md) | Centered dialog | Leave room, block/unblock, discard draft, forget server, mark all read, ignore/unignore confirmations, duplicate-risk send, delete for everyone, the message-target "can't open" outcomes |
| **Form dialog** | Typing that must be saved or cancelled | Today's window type, kept | Author edit (both the compact `Dialog` and the `AlertDialog` path), create room, step-up, the birth-date picker |

**Form dialogs keep their current window type and layout logic.** This slice only re-tints
them, sets type and shape, and restyles their buttons. Their own-window IME and Save
geometry, focus behavior, compact and Details fallbacks, and test tags are untouched. A
form dialog must never become a bottom sheet: sheets resize and move under the IME
differently, which is exactly the geometry M5b2 protects.

## Choice sheets

- Material `ModalBottomSheet` in its own window, re-tinted: `surface`, 28dp top corners, a
  drag handle, and a 1dp `line` top edge in AMOLED black. Scrim per the theme.
- Opens fully expanded when content fits. The partially expanded state is skipped
  (`skipPartiallyExpanded = true`), so there is no half-open sheet whose bottom actions are
  hidden. When content is taller than about 90% of the window, it scrolls inside.
- **Contract mapping:**
  - **Read and typing suppression:** each sheet's open state feeds the same flags its
    dialog fed today (for example `detailsOpen`, `personDialog`, `composerDialog`, the
    actions and reaction state). Suppression never depends only on window focus.
  - **Original references:** each sheet keeps the original-request or selection reference its
    dialog was opened with. A dismissal (scrim tap, Back or swipe down) calls exactly the
    callback the dialog's `onDismissRequest` called, bound to that original reference.
  - **Busy states:** where a dialog refused dismissal while busy (`if (!busy) onDismiss()`),
    the sheet also refuses: `confirmValueChange` blocks hiding while busy, and the drag
    handle is disabled.
  - **Configuration changes** keep the sheet open with its retained work. Genuine navigation
    and background retire it, as today.
  - **Test tags** move to the sheet's content root, keeping the same names
    (`message_actions`, `private_pings_dialog`, `profile_dialog`, …).
- **Rows:** 52dp minimum, a 24dp leading icon, a 16sp label. Rows that need a confirmation end
  with an ellipsis ("Delete for everyone…") and open a decision dialog.
- **Sections, in order:**
  1. the quick row (reactions in message actions: the six emoji as 48dp targets);
  2. social actions (Reply, Copy text, link choices, View profile, React…);
  3. own-message actions (Edit…, Delete for everyone…);
  4. "Report" (once M5 reporting exists);
  5. a labelled **steward section** ("As admin" / "As moderator", steward color, shield icon)
     for moderation actions once M7 adds them.

  Absent actions are omitted, never shown disabled without a reason.
- The **reaction picker** keeps explicit **Add** or **Remove** per emoji. The viewer's existing
  reactions are marked selected, with the people lists and the unknown-outcome **Check
  history** recovery inside the same sheet (M5c2b and M5c3b). There is no toggle.

### Message actions sheet (mockup `AdminRoom`)

From top to bottom:
1. a one-line excerpt of the message, plain text, so the person sees which message they
   acted on;
2. the reaction row, then Reply, Copy text and the link choices (each link a 48dp row labelled
   with its destination, keyed by destination as M5d2 requires);
3. View profile and the author actions (from the transcript spec);
4. own-message Edit… and Delete…;
5. later sections as above.

It is opened only by the existing guarded triggers, and every row calls the existing callback
with its existing re-fencing.

## Profile card (choice sheet)

- Header:
  - a 64dp pebble with the identity hue, or a dashed pebble for guests;
  - the username in Display M;
  - the server badge text (`profile_badge`) as a steward-outlined pill when present;
  - "Member since {date}" or, for guests, "Visiting since {date}" (a guest date is the visit
    start, M5f1).
- The "read-only" explanation (`profile_read_only`) as a muted caption.
- Actions: **Refresh** (existing, same gating and new-reference semantics) and **Close**.
  With ChitChat#159, **Report {name}…** sits below Refresh when the profile allows it
  (reporting.md, "Report a person"). No other actions. Avatar availability hints authorize no
  fetch (M5f1/M5f2). "Message" or "Ignore" entry points would need their own decision.
- Loading and error states inside the sheet, with the existing copy.

## Decision dialogs

- Material `AlertDialog`, re-tinted: `surfaceContainer`, 28dp corners, the title in
  `titleLarge` (Bricolage), body text, and buttons at the end.
- Button rules:
  - the dismissive action is a `TextButton` ("Cancel", "Keep as draft");
  - the confirming action is a `TextButton` in `primary`, or in `error` when it destroys or
    affects others ("Leave room", "Block", "Delete for everyone", "Forget server");
  - never a persimmon-filled button in a dialog. Persimmon fill is for a screen's primary
    action (P-13).
- The title states the choice ("Leave Kitchen?"). The body states the consequence in
  communication.md's voice ("You can join again later if the room stays open.").
- Accessibility focus moves to the title on open (A-07). Back and the scrim dismiss through
  the existing dismiss callback.

## Overflowing dialog text

At 200% font scale, in landscape or in a short split-screen window, a dialog's text can be
taller than the window. Then:

- The title and the button row stay in place. Only the text slot between them scrolls,
  including any text actions inside it (Try again, Transfer ownership…, Leave room).
- **Scroll cue:** while the slot can scroll further down, a 1dp `outline` divider sits at
  its bottom edge, above the buttons. Once it has scrolled, a matching divider sits at its
  top edge, below the title. Without scrolling there are no dividers. A clean cut between
  two lines never stands in for the cue, because it reads like a finished sentence.
- Nothing in the slot is unreachable. Every line and action can be reached by scrolling, and
  TalkBack traversal scrolls it into view.
- One shared modifier provides the slot and its dividers (first used by administration.md
  P-217's room detail and confirm dialogs), not per-dialog code.
- **Scope:** decision dialogs, the room detail dialog and other read-only dialogs. Form
  dialogs keep their window type, sizing and IME geometry (below). One of them adopts the
  slot only if its non-field text can overflow, and only after the M5b2 geometry tests pass.
- **Sweep:** the remaining dialogs are converted in one A-floor slice. The inventory (the
  engineer's grep of AlertDialog `text` slots without a scrolling container, at 6ea3e52) is:
  1. Forget server confirmation (`forget_body`), app/ChitChatApp.kt;
  2. Discard DM draft (`dm_discard_warning`), feature/direct/DirectScreens.kt;
  3. Block help (`dm_block_help`), feature/direct/DirectScreens.kt;
  4. Remove push in Notification settings (`push_remove_body`),
     feature/push/NotificationSettingsScreen.kt;
  5. Push explanation (`push_explain_body`), feature/push/PushPhoneCard.kt;
  6. Delivery app choice list, feature/push/PushPhoneCard.kt. The choices scroll in the slot
     like text, each keeping its 48dp row;
  7. Use Google (`push_google_body` and the lines under it), feature/push/PushPhoneCard.kt;
  8. No delivery app found (`push_none_body`), feature/push/PushPhoneCard.kt;
  9. Remove push on the phone card (`push_remove_body`), feature/push/PushPhoneCard.kt;
  10. Leave room confirmation (`rooms_leave_warning`), feature/rooms/RoomsScreen.kt.

  The create-room dialog and the room detail dialog already scroll, and administration.md
  P-217's confirm adopts the slot first. The grep is a heuristic: the slice also checks
  dialogs built without AlertDialog's `text` slot, and adds any it finds to this list.

## Form dialogs (restyle only)

- Same tokens as decision dialogs. Fields use the 16dp form-field shape and `fieldLine`
  borders. Save or Create is a `TextButton` in `primary`, enabled per the existing rules.
- **No change** to window type, sizing, IME handling, focus requests, compact or Details
  routing, or test tags. Any visual change that moves the Save button's on-screen position
  relative to the IME must be verified against the existing M5b2 geometry assertions before
  merging.

## Tests

- Existing modal tests run unchanged in meaning. Selectors scoped to a modal (for example
  "Refresh" inside `profile_dialog`) keep working, because tags move to the sheet content
  root.
- **New Compose tests:**
  - each converted sheet sets the same suppression flag as its dialog did, and read proof
    pauses while it is open;
  - scrim, Back and swipe dismissal call the original-reference callback;
  - a busy sheet refuses dismissal;
  - sheets stay open across recreation;
  - no partially expanded state exists;
  - a decision dialog's destructive confirm uses `error`, and nothing in a dialog is
    persimmon-filled;
  - at fontScale 2 in a short window, each converted dialog's last line and every text
    action are reachable, and the dividers show only while the slot can scroll that way.
- **Native input:** form dialogs keep every native tap, focus, IME and geometry test. Choice
  sheets with no text input need no IME test. Any sheet containing a text field, which none
  does in this slice, would have to join the native input suite first.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-110 | Every modal is classified as a choice sheet, decision dialog or form dialog per the table, and rendered in that form. | R, S |
| P-111 | Choice sheets keep today's suppression flags, original-reference dismissal, busy refusal, configuration retention and test tags. There is no partially expanded state. | C |
| P-112 | Form dialogs keep their window type, IME and Save geometry, focus behavior and tests, with only visual tokens changed. | C, existing input tests |
| P-113 | Decision dialogs use the specified button rules (`error` for destructive confirms, never persimmon-filled) and move accessibility focus to the title. | C |
| P-114 | The profile card shows only existing facts and actions (Refresh, Close, and Report per reporting.md P-165), with guest dates as visit start. | C |
| P-115 | The message actions sheet orders sections as specified, and every row uses the existing guarded callback. | C |
| P-220 | When a dialog's text is taller than the window (200% font, landscape, split screen), only the text slot scrolls, while the title and buttons stay. A 1dp `outline` divider shows above the buttons while there is more below, and below the title once scrolled; there are no dividers otherwise. Every line and text action is reachable by scrolling and by TalkBack. One shared modifier serves all dialogs; form dialogs keep their geometry. Captures: each converted dialog at default and 200% in a short window, in light and dark, with the divider visible. | C, S |
