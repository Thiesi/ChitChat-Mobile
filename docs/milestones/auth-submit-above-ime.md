# Sign in and Create stay visible above the keyboard (#275)

A product fix for #275, whose cause the #277 failure-only scalars named: `performScrollTo()` positioned Sign in for the
pre-keyboard viewport, then `imePadding()` shrank the viewport and clipped the button to 0×0. A person had to scroll to
reach the primary action. Technical lead decision: fix it in the product. The presentation lead set the design, and
the technical lead added the consent requirement.

## Pinned action slot

`ScreenContent` provides a `PinnedActionSlot`. A form's `PinnedAction` draws below the scroller, above the keyboard:
- the screen's own background;
- 16dp around the button;
- a 1dp `line` top edge only while content is hidden beneath it.

Without a host, the action renders in place.

## The focused field keeps its label (B, #351, P-78)

The PL ruled the clipped floating label at 200% a defect (wrap, never clip). `ScreenContent` keeps a focused text field
in view with its label whole (`core/ui/ScreenKeepInView`), by four tiers in order (`keepInViewDistance`; PL 2026-11-25,
the bottom margin and the order 2026-11-27, A2's order). Each is the minimum move, so a field already placed doesn't move:
1. **Both margins** when both fit: the label's top at least 8dp below the top edge, and the field's bottom (with its
   supporting line) at least 8dp above the bottom edge;
2. **else the top margin:** the label plus 8dp, the bottom flush or as close as the move leaves it (the bottom margin
   gives first);
3. **else the field whole,** both edges inside;
4. **else the caret's line wins,** and the label is entirely in or entirely out of view, never half (the form dialog's
   label rule).

The bottom edge is the viewport's: the keyboard's top, or the pinned action's edge while the keyboard is hidden.

M3's outlined field draws the floating label centred on its top border, half above the field's focus bounds, so the
label's top is half the label's line height above them (from M3 1.4.0's bytecode; see `ScreenKeepInView`). The caret's
line is the field's own bring-into-view request; before it arrives, the field's first text line stands in for it (half
the label's line, the outlined content padding, bodyLarge's line height), which for a single-line field is the caret's
line. A request's extent is never what's kept, so the two requests a tap makes (the caret and the focus box) land on one
target.

Where it applies:
- **Each viewport height change** (every frame of the keyboard's slide, a rotation, the pinned block arriving or
  leaving): in the placement pass, before the scroller places its content, so the frame drawn at the new height has the
  move. It uses the focused field's place in the content from the last placement, valid while the content isn't
  re-measured (the content takes an unbounded height in the scroller and reads no viewport size in layout). After a
  rotation, a width change, text re-wrapping or content arriving, it falls back to the scroller's position callback,
  which can draw one frame at the old placement (diag run 45986560901: such a move is laid out only at the next layout
  pass, and when that pass is the draw's own, the frame is drawn first). One claimed height per change, so a change
  applies once. It moves only when the field breaks the rule, by exactly the
  distance the edge would otherwise cover, so the field rides the keyboard; a field already clear doesn't move. A move
  cancels Compose's running bring-into-view, so nothing moves afterwards and the end state doesn't depend on how far that
  animation had got (#351's 409–412 variance).
- **Focus moves in a steady view** (Next, a tap): Compose's animation stays, landing by the same rule through the
  screen's `LocalBringIntoViewSpec`. Other requests keep the default behaviour.
- **An action absent while the keyboard shows** (the Create block and the reset action block, `hideWhileImeVisible`)
  leaves in the same frame the keyboard's inset starts: `ScreenContent` reads `isImeVisible` itself, and the row also
  collapses in layout (`collapsedWhile`: neither measured nor placed, so no semantics) once the inset is above 0, the
  pass `imePadding` reads it in. With animations off the inset jumps in one frame, after composition ran. Clearing it
  from the action's own disposal landed a frame late, and for that frame the inset and the block both took the
  viewport. It returns as before. Sign in and the reset MFA button stay pinned always.

Only for a focused text field (the view's `onCheckIsTextEditor()`: a text input session is active). A focused button or
accessibility focus never triggers it, and with nothing focused a height change doesn't scroll. A field the person
scrolled out of view stays where it is (Compose's own rule). With animations off, Compose's animation is already a
snap; nothing here changes that.

**Tests:** `ScreenKeepInViewTest` (each tier at its boundary, both requests of a tap in either order); and
`AuthPinnedPresentationTest`, which asserts the tier that applies and its placement after every keyboard capture (the
label's top, unclipped, against the `screen_viewport` scroller) and after a focus move in a steady view (Next from the
password to the confirmation, the viewport's height asserted unchanged).

## Sign in

- **Pinned always:** the button never moves between the scroller and the bar, so it doesn't jump under a finger.
- **IME:** the password field's IME action is **Go**, which submits.
- **Secondary actions** stay in the scroller in their order, with the P-221 footer last.

## Create my account

The consent line plus Create form **one block**, so the line is visible whenever Create can be tapped:
- **Keyboard hidden:** the block is pinned.
- **Keyboard up:** the block is absent; there's no Create while typing. The last field's IME action is **Done**, which
  hides the keyboard and brings the block back. A hardware keyboard never shows the IME, so the block stays.
- **Very short windows** (the block would take more than half the height): the block sits in flow at the end of the
  scroller. A tap on Create while any part of the line is off screen brings the block into view and doesn't submit. A
  polite announcement says "Review the agreement, then tap Create my account." It's announced only, with no visible
  text.

## Tests

- **`AuthPinnedSubmitJourneyTest`:**
  - Sign in is displayed above `imeBottom` with the IME up, with no scrolling, after a native tap that proves the IME;
  - Create and the consent line are absent while the IME is up, and back with the line fully visible after Done;
  - on a 260dp-tall window, an activation with the line off screen announces, doesn't submit, and brings the line into
    view; the next tap submits.
- **`AuthPinnedPresentationTest`:**
  - both forms with the keyboard up (default and 200%), and Create's pinned block at 200%; light and dark;
  - collected by `collect_auth` (10 files).
- **`AuthJourneyTest.enterCredentials`:** asserts Sign in is displayed without `performScrollTo()`, so it now checks the
  product guarantee directly. The #275 scalars stay in place.

## Label

The Create form's submit reads **"Create my account"** (`onboarding_create_account`, onboarding-restyle 4b and the
announcement), replacing the old "Create account". It's PL copy authority (J1 on PR #281); the unused `create_account`
string is removed.

## Journey migration (navigation only)

`performScrollTo()` came off the now-pinned Sign in, Restore and sign in, Create account and consent line, because
they're outside the scroller. Create flows that type then tap Create first use the product's Done on the confirmation
field. No assertion changed:
- `AccountRecreationTest`, `AuthJourneyTest`, `RoomAttachmentJourneyTest`, `RoomDraftJourneyTest`,
  `DirectJourneyTest`: Sign in;
- `FindPeopleJourneyTest`: Sign in, plus Create with Done;
- `OnboardingRecreationTest`: Create with Done;
- `GuidelinesPresentationTest`: consent line and submit, with Done before the link. It also **asserts the Create
  block's mode**: pinned at the default scale, and in flow at 200% in portrait, because its rules-plus-privacy line is
  taller than half the window. That's normal and consent-safe (PL and TL), and its `guidelines-*-font200-create`
  captures cover it.

**Assertion text follows spec:** `AuthJourneyTest`, `OnboardingRecreationTest` and `FindPeopleJourneyTest` match
"Create my account" instead of "Create account". Only the label text changed, per the PL's copy (J1).
