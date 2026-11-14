# Dialog overflow (P-220 sweep)

[P-220](../design/modals-restyle.md) (presentation lead): when a dialog's text is taller than the window (200 % font,
landscape, split screen), only the text slot scrolls, while the title and buttons stay. A 1dp `outline` divider sits
above the buttons while there is more below, and below the title once scrolled, with none otherwise. Every line and
text action stays reachable by scrolling and by TalkBack.

**Shared pieces (`core/ui`):**
- `DialogScrollBody`: the text slot, a scrolling Column with the dividers.
- `Modifier.scrollEdgeDividers`: the dividers themselves.

The room detail dialog and the ownership-transfer confirm (P-217, PR #238) were the first users and now use
`DialogScrollBody` too.

## Sweep A: push dialogs (this slice)

These are inventory items 4–9:
- Explain;
- the delivery-app choice list (each choice keeps its 48dp row and scrolls in the slot like text);
- Use Google (with its guide link);
- No delivery app;
- Turn off on this phone's card;
- Remove in Notification settings.

**A no-behaviour-change refactor.** Each dialog's `AlertDialog` call moved unchanged into an internal composable in
`feature/push/PushDialogs.kt`. It has the same composition, test tags, enablement, state and callbacks; the screens
keep their state and pass the same lambdas. The only change is that each text slot is wrapped in `DialogScrollBody`,
which adds a `*_body` tag on the scroll container. Existing journey tests run unmodified.

**Captures:** `PushDialogPresentationTest` renders the real dialogs on the debug `FontScaleTestActivity`, at a real
Configuration font scale of 1.0 and 2.0, in light and dark (24 captures, `ccm-dialog-presentation`). They use the AVD's
actual window size. No window height is pinned, because a configuration override may not change the window a dialog
measures against.

**Labelled checks,** at both scales and in both themes:
- a body with scroll range reaches its end and its start through its own scroll action;
- every choice row is at least 48dp;
- the last choice and the Use Google guide link are reachable by scrolling.

## Sweep B: the remaining decision dialogs

These are inventory items 1, 2, 3 and 10:
- forget server (`app/ForgetServerDialog.kt`);
- discard DM draft and block help (`feature/direct/DirectDialogs.kt`);
- Leave room (`feature/rooms/RoomLeaveDialog.kt`).

It's the same no-behaviour-change move as sweep A: the same composition, tags (`dm_confirm_block` included),
enablement, state and callbacks, and only the text slot is wrapped in `DialogScrollBody`.
`RestDialogPresentationTest` adds 16 captures and the same labelled reachability checks, in the same
`ccm-dialog-presentation` folder (40 in all).

**Dialogs without AlertDialog's `text` slot (the check P-220 asks for), reported and not converted here:**
- **`AuthorChangeDialog` (core/ui), edit variant:** a full-window form dialog. It deliberately owns its window for
  IME and geometry (M5b2). Its body already scrolls, with the action row pinned below, so nothing is unreachable. It
  only lacks the P-220 dividers, and P-220 keeps form dialogs' geometry.
- **`AuthorChangeDialog`, delete variant:** an AlertDialog whose text slot already scrolls. It's a decision dialog
  and lacks only the scroll-edge cue, so it can adopt `DialogScrollBody` in a later slice.
- **`RegistrationForm`'s date picker:** the platform Material `DatePickerDialog`. Out of scope.
- **The transfer picker:** a full-screen list, already scrolling.

**Already scrolling but without the cue,** for a later pass:
- the DM details dialog (`DirectScreens`);
- the create-room form (`RoomsScreen`; a form dialog).

With this, the P-220 inventory is converted.
