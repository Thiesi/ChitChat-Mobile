# Form-dialog scaffold (P-220 amendment)

The PL ruled this layout and the TL approved it on 2026-11-21. It came out of reporting R2 (#326): at 200% landscape the report form's
AlertDialog gave the body 0×0 dp (run 44831339730). A follow-up capture also showed the keyboard covering Send and Cancel
in portrait. `imePadding` inside an AlertDialog's window doesn't help, because that window doesn't move out of the
keyboard's way (the M5b2 lesson). StepUpDialog had the same shape.

## Shipped defect on main 90003f7 (local evidence)

Measured locally on an API36 google_apis emulator, 320×640 at 160 dpi (px = dp), animations off, unchanged
StepUpDialog. The only change was a test tag on the field.

| Case | IME top | Verify bottom | Cancel bottom | Field |
|---|---|---|---|---|
| Default portrait | window moved clear (IME inset 0) | 320 | 320 | 188–252 |
| 200% portrait | 357 | **436** | **502** | 273–357 |
| 200% landscape | none: the tap didn't focus | – | – | **88–104 (16 dp)** |

So at 200%, StepUp's buttons sat under the keyboard in portrait. In landscape the password field was squeezed to 16 dp
and one native tap didn't focus it. #326's report form at 200% portrait had the same problem: the IME top was at 357,
while Send ended at 502 and Cancel at 568. The existing assert there passed anyway, because `isDisplayed` checks the
app window's bounds, not whether the keyboard covers a node.

## The scaffold (`core/ui/FormDialog`)

- **Own window above the keyboard.** A `Dialog` with `usePlatformDefaultWidth = false` and
  `decorFitsSystemWindows = false`. The surface is laid out in `safeDrawingPadding().imePadding()` space, so the buttons
  always sit above the keyboard. An outside tap dismisses, as a platform dialog's does.
- **Width as the platform gives an M3 AlertDialog.** A floating WRAP_CONTENT dialog window is first laid out at the
  platform's preferred dialog width (AOSP `config_prefDialogWidth`, 320 dp) and keeps it when the content fits. Compose
  content always reports that it fits. So the surface is M3's 280 to 560 dp inside min(320 dp, the space), in portrait
  and landscape alike. The window's theme sets no fixed or minimum width (probed locally).
- **Pinned or one column, by measurement.** The pinned P-220 layout (title, scrolling body with edge dividers, buttons)
  holds while the body gets at least 120 dp of the height above the keyboard. Below that, title, body and buttons scroll
  as one column, with the 1 dp `outline` dividers where content is hidden. The mode may switch once when the keyboard
  shows or hides; while typing it's stable, because the IME height doesn't change.
- **Focus survives a switch.** The two modes differ only in modifiers, never in the composition tree, so a focused
  field keeps its node and the keyboard stays up. When the space changes, an `onFocusedBoundsChanged` observer scrolls
  the active scroller to the focused field. That's a scroll, never a focus request. Typing then keeps the caret in view
  as usual.
- **The 24 dp padding scrolls with the content.** In a one-column layout the viewport is then the whole surface. With
  the keyboard up at 200% landscape only about 80 dp is left, and a fixed padding would have left 34 dp, too little for
  a 48 dp button.
- **Buttons:** side by side when both fit, otherwise stacked with the primary on top, end-aligned, as M3 does.
- **Outside taps:** an outside tap dismisses, as a platform dialog's does, unless the caller passes
  `dismissOnOutsideTap = false`. That's for a form where an accidental dismissal loses a choice or composed text (PL);
  the report form uses it in every phase. An ignored tap does nothing: no feedback and no dismiss action. Back and the
  dialog's own buttons still close it.
- **By design (PL's rule):** in one-column mode the buttons are reached by scrolling while the field keeps focus. They
  aren't pinned on screen with the keyboard up; every one is reachable above the IME by scroll. This isn't a regression.
- **StepUpDialog and the report form (#326, R2)** use it. Tags, callbacks and copy are unchanged.

## Evidence

- **`FormDialogGeometryTest`** covers StepUp and a report-shaped fixture at default, 200% portrait and 200% landscape.
  - Each case does one native tap, then types. It then scrolls to each button and asserts that its bottom is at or
    above the IME top, read from the dialog window's own IME inset.
  - Each button must be at least 40 dp tall, because a clipped-away node would pass the edge check vacuously.
  - The focused field must be above the keyboard and keep focus. On CI the keyboard must have shown.
  - A six-line entry keeps its caret line above the keyboard.
  - A fitting StepUp-shaped dialog matches an M3 AlertDialog within 1 px: surface, title, body, field, both buttons.
  - Default portrait stays pinned; 200% landscape is one column.
  - Each case writes one scalar line to `ccm-form-dialog-presentation/form-dialog-metrics.txt` for the PL's J record.
- **Captures:** `stepup-light-font200-landscape-top` and `-end`. The 20 portrait StepUp captures are the unchanged
  control.
- **Local results** on the same emulator, all passing:

| Case | Mode (IME hidden / up) | IME top | Send / Cancel bottom | Field |
|---|---|---|---|---|
| StepUp default portrait | pinned / pinned | 357 | 329 / 329 | 197–261 |
| StepUp 200% portrait | pinned / column | 357 | 357 / 357 | 273–357 |
| StepUp 200% landscape | column / column | 110 | 110 / 110 | 28–110 |
| Fixture default portrait | pinned / pinned | 357 | 329 / 329 | 160–260 |
| Fixture 200% portrait | pinned / column | 357 | 357 / 357 | 164–356 |
| Fixture 200% landscape | column / column | 110 | 110 / 110 | 28–110 |
| Six lines, 200% portrait | pinned / column | 357 | 357 / 357 | caret bottom 244 |

- **AlertDialog comparison:** identical at 320×640 dp and at 411×891 dp (1079×2339 px at 420 dpi, surface 840×894 px).
- **Local procedure, not a CI setting:**
  - **Size override:** the 411 dp run set `wm size 1079x2339` and `wm density 420` on an AVD whose override is
    320×640 at 160 dpi, then restored it.
  - **Keyboard setting:** the final 320 dp run was on an AVD with `show_ime_with_hard_keyboard` 0. It was set to 1 for
    that run and restored to 0 afterwards. CI declares its own software-keyboard environment (`expectSoftKeyboard`).
  - **Rotation and font:** the landscape cases used the documented `wm fixed-to-user-rotation` / `user-rotation lock 1`
    procedure with a verified `get-config`. The recorded policies (fixed `default`, user `free`, font 1.0) were
    restored afterwards.
- **One local miss:** StepUp at 200% portrait once had an IME window with no surface. The IME server showed input as
  shown and requested; the IME window read `NO_SURFACE` and not visible. That's the known API36 strict-IME shape.
  Local evidence only; CI on the pushed head is the gate.
