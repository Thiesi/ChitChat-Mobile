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

## Sweep B (follows)

Inventory items 1, 2, 3 and 10: forget server, discard DM draft, block help and Leave room. It also covers the check
of dialogs built without AlertDialog's `text` slot:
- `AuthorChangeDialog`: a form dialog. Per P-220 it keeps its geometry; it gets a report only, no conversion here.
- `RegistrationForm`'s date picker: a platform component.
- The transfer picker: already a full-screen list.
