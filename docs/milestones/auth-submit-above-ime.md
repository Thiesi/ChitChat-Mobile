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

## Journey migration (navigation only)

`performScrollTo()` came off the now-pinned Sign in, Restore and sign in, Create account and consent line, because
they're outside the scroller. Create flows that type then tap Create first use the product's Done on the confirmation
field. No assertion changed:
- `AccountRecreationTest`, `AuthJourneyTest`, `RoomAttachmentJourneyTest`, `RoomDraftJourneyTest`,
  `DirectJourneyTest`: Sign in;
- `FindPeopleJourneyTest`: Sign in, plus Create with Done;
- `OnboardingRecreationTest`: Create with Done;
- `GuidelinesPresentationTest`: consent line and submit, with Done before the link.
