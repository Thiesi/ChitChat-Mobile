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
- **What was in view stays in view (#369; TL and PL, 2026-11-25 and 2026-11-26).**
  - **The defects:**
    - A switch swaps scrollers, which lost the scroll position. The old rule then sent a field taller than the view to
      its top, so with long report details at 200% the caret could leave the view after a keyboard show (measured on
      API35, N=40, runs 45649326278 and 45650479874).
    - **Text set through semantics, as an accessibility service sets it, never brings the caret into view by itself**
      (measured on #371's first head): the legacy text field does that only on the keyboard's edit path.
  - **The caret's line comes from the field itself.** A field that reports it (the report details field) publishes its
    caret's line, from its own selection and text layout, to `LocalFormDialogCaret`. It's known whatever put the text
    there. The details field is the `MessageInput` pattern: a `BasicTextField(TextFieldValue)` with M3's
    `OutlinedTextFieldDefaults.DecorationBox`, exactly what `OutlinedTextField` composes with its parameters, so it looks
    and behaves as before, and adds `onTextLayout`. StepUp's single-line secret publishes nothing and is unchanged.
  - **On a height or mode change:** the scaffold records, on every layout and focus change, where the focused field and
    its caret's line sit relative to the top of the scroller in use, in that viewport's own coordinates (not window
    coordinates, which measured off by the inner scroller's value in this dialog; #372). After a height or mode change
    it restores the same offset in the scroller now in use, clamped so the caret's line stays inside. A field that fits
    whole, with the label's margin, then stays whole in view, as Compose's own keep-in-view did before this step
    superseded it (#371's captures had left a short filled field cut at the bottom edge). It acts on every height
    change, so during the keyboard's slide the field rides the edge.
    **A3 (TL, 2026-11-26), corrected by #378: the restore lands in the placement pass.** It runs once per height or
    mode change, from a layout modifier directly before the scroller in use's `verticalScroll`: after the scroll node is
    measured (the new layout's scroll range is known) and before it's placed. The node reads the value in its own
    placement, so the content is placed at the restored offset in the same pass. The inputs come from the focused field's
    place in the body column's own content, recorded at the last placement, which a mode switch doesn't move (the body is
    one node in both modes); they're used only while the content wasn't re-measured.
    #376 first ran the restore in the outer content's position callback and claimed the first frame drawn at the new
    layout was then already at the restored place. That was wrong (#378). A scroll made in a position callback is laid
    out only at the next layout pass, so when that pass was the draw's own, the frame was drawn at the old placement first
    (2 of 5 restores, the caret out of view; diagnostic run 45986560901). And the body's own position callback, later in
    the same dispatch, recorded the pre-restore placement; with a new key every frame of an animated keyboard, the next
    restore read that stale record as its baseline, and the targets alternated: the field shook 38 px every frame (#376's
    regression, diagnostic run 46031148507; 2cebb7a, before #376, didn't). The position-callback restore stays as the
    fallback, for a change whose content was re-measured.
    **One baseline per keyboard transition (#378 b, TL and PL, 2026-11-27).** With an animated keyboard, each frame of
    the slide used to restore from the previous frame's place, so an intermediate layout with a smaller viewport than
    the final one clamped the carried offset and the clamp was carried forward: the end depended on the frames the slide
    passed through (run 46037971164: text-first ended at 1443, keyboard-first at 1402, the notice at 1662, against 1318,
    1318 and 1511 with animations off; every source fresh). Now every restore during a keyboard transition, and at its
    final layout, reads the place from before the transition. The boundary is the dialog window's IME inset against
    Compose's `imeAnimationSource` and `imeAnimationTarget`: unsettled while the inset differs from the target or the
    source from the target, read in the outer scroller's placement (before that pass's restores). Compose's
    `InsetsListener` sets all three to the window's insets whenever no IME animation runs (`onApplyWindowInsets` without
    one, and `onEnd`, called for a finished or a cancelled animation), so a settled placement ends every slide, finished,
    reversed or cancelled (a reversal keeps the inset off its target: diag run 46043589704, API36 notice, 244/0/0). With
    animations off a change is one unsettled placement at the old inset, then the settled one, so the baseline is the
    record before the change, as before. A field change or no earlier record releases; an edit during a transition
    re-baselines; with no animation reported (API < 30) it never freezes. `KeyboardTransitionBaselineTest` (JVM) replays
    the logged sequences. A selection change since the field's place was recorded (a tap that moves the caret in the
    frame the keyboard starts) restores from the live caret and moves the baseline's caret by the same distance, so the
    step after the frame finds nothing more to move (diagnostic run 46056107630, API36 text-first: 3080, then 3004). While the
    view is held (a notice holding it), a restore claims nothing; the hold is read in the placement, so the pass where it
    releases claims the change and restores in that pass (before, a held pass used up the change and the step after the
    frame restored it up to ~200 ms later, with the dialog's top drawn at the swapped-in scroller's 0 meanwhile;
    diagnostic runs 46056107630 and 46056124120, API36 notice re-tap).
    **Limit: the switch between pinned and one column (#383, after the Play upload).** With an animated keyboard the
    slide's frames apply the full rule from the transition's baseline, so the field moves with the viewport, but the
    first frame after a mode switch can move against the slide: text-first's hide moves the caret line up 56 px in the
    first pinned frame (201 px viewport, the counter kept), the label goes out in the first pinned frame of an arrival and
    comes back in the first one-column frame, and API36 shows a 2 px step at an arrival's switch. Main has the hide's move
    too (diagnostic run 45992933725, where the label was half; the separate-label rule makes it out). Diagnostic runs
    46056107630 and 46056124120 hold the frames. Before A3, a mode switch drew one frame at the swapped-in
    scroller's old value (often the top) first (37 of 40 text-first and 33 of 40 keyboard-first repetitions; diagnostic
    runs 45903840557 and 45903834879). A target outside the scroll range is left to the step after the frame, which stays
    as the fallback and keeps the clamp; later callbacks for the same change never re-apply it. A scroll already in progress isn't waited for, it's cancelled: Compose
    starts its own bring-into-view on the swapped-in scroller in that same pass, which would align a field taller than
    the viewport to its top, against the label rule. Waiting for it brought the frame at the old value back (#376's
    probe, run 45914151572: the guard skipped 34 of 40 and 40 of 40); letting it run moved the field off the restored
    place (the landscape 200% case: 28 px to the top edge, run 45923378397). Main cancelled it implicitly through the
    step after the frame; now the restore takes the scroll lock right after it applies, in the same frame, and the step
    after the frame takes it again. The earlier place it restores from is read once per change and kept for that change's later read
    (the step after the frame), so a record written in between can't shift the baseline; every change starts afresh,
    including one held by a notice
    (#376's portrait text-first probe: once in 20, the step after the frame restored from a stray record). That
    stray record is also stopped where it was written: a record takes its height or mode change and its frame from the
    layout pass, which sets them first, not from the calling code, which can be a composition behind; and a record
    measured against a frame taller than the dialog's viewport is refused.
  - **On a text or selection change:** if the change put the caret's line outside the view, it's brought back, just
    enough, then the label rule applies. When the line is still inside, nothing moves, so keyboard typing (already kept in
    view by the field) adds no scroll.
  - **The old rule applies only when the field wasn't in view before,** or has no earlier position (it just gained
    focus), with the label rule's margin. A recorded position is reused only for the same focused field.
  - **The caret's line, when known, is always inside the view** afterwards, so a field entering from unfocused or taller
    than the view never leaves its caret out.
  - **The label rule:** a field's label is whole with 8 dp between its top and the viewport's top edge, or entirely out
    of view (its bottom at or above that edge), never half. The caret's line wins when both can't fit. A half label moves
    by the minimum distance: up (the label's top to 8 dp) if the kept range below stays wholly in view, else down
    (entirely out) if the range's top stays in view.
    - **A separate label (#378, TL and PL, 2026-11-27).** A field whose label is a Text above it (not a floating label in
      its border) publishes that node to `LocalFormDialogCaret`, and the rule keeps the whole node, every wrapped line,
      in or out; it unpublishes when it leaves composition. Fields that publish no label keep the floating-label band
      (bodySmall's line height at the field's top edge). The inventory: the report form's `report_details_label` is
      separate and publishes; step-up's `step_up_secret` has a floating label (and publishes no caret, so the rule doesn't
      run there). The band model missed the report form's label from #326 on, before #371 wrote the rule: at 200% the
      label wraps to 3 lines above the field, so a field top 58 px down read as "label whole" while two of its lines were
      cut (#378, diagnostic run 46037971164, keyboard-first with animations on). `FormDialogSeparateLabelTest` (JVM)
      checks whole, out, up and down on those inputs.
  - **A disabled action doesn't hide its reason (A2; PL ruling, 2026-11-26).** While the caret is on the field's last
    line (by line index; an empty field counts), the view also keeps the field's bottom edge and its trailing line in
    view when they fit with the caret's line. The trailing line is the report form's counter ("Too long: 1012 / 1000"
    while Send report is disabled). The span runs from the caret line's top to the lower of the field's bottom and the
    counter's bottom, and moves into view by the minimum distance, in both the height and the text steps; when it doesn't
    fit, the caret's line wins. The counter publishes its position to `LocalFormDialogCaret` and unpublishes when it
    leaves composition; fields that publish no trailing line behave as before.
    - **Priority (TL and PL, 2026-11-26):**
      1. the caret's line;
      2. the span (the field's bottom and the counter) when it fits;
      3. an 8 dp bottom margin under the span, which gives first;
      4. the label rule.

      The bottom margin keeps the scroll-edge divider from reading as an underline under the counter (in Lantern, an
      underline means a link). The label rule moves the label in only if the kept range (the span with its margin, else
      the span, else the caret's line) stays wholly in view; otherwise it takes the label entirely out if the range's top
      stays in view; never half. `FormDialogKeepInViewTest` (JVM) checks these on the pure geometry.
    - **Two end states, both intended (PL, 2026-11-26).** Keyboard first, typing brings the span in from below, so it ends
      8 dp above the bottom edge. Text first, A's restore keeps the person's place across the keyboard's swap, and a span
      already clear of the margin doesn't move. In both, the caret's line, the field's bottom and the counter are in view
      (N=40 run 45820138541: 40/40, 35 keyboard-first and 5 text-first). The `form-toolong` capture uses the keyboard-first
      order (the keyboard shown and settled before the input); text first is covered by asserts.
    - **Assert:** `ReportFormLayoutTest` at default size and 200% portrait, in the too-long state (typed through
      semantics, the caret on the last line): the counter line is in the viewport with the caret's line, logged to the
      metrics.
  - **Asserts:** `ReportFormLayoutTest` at 200%, portrait and landscape, with long details typed through semantics (the
    accessibility path). The caret's line is inside the viewport of the scroller that holds the field, which already
    ends above the keyboard, after each of:
    - typing;
    - a keyboard hide;
    - a paste;
    - the late-keyboard order (text first, then the keyboard), which is also the keyboard showing again over existing
      text.

    The label is never cut in half. A change that keeps the caret's line in view leaves every scroller where it was. In
    portrait, a caret placed in the viewport's upper third keeps its offset within 1 px across the keyboard hide (one
    column to pinned): the restore holds it, not the clamp.
  - **How the asserts measure:** against the container that clips the field: the pinned body's viewport
    (`form_dialog_body_viewport`) or, in one column, the whole dialog's (`form_dialog_viewport`). These are semantics-only
    tags on two existing nodes. The field's position is taken in that container's own layout coordinates, plus M3's
    outlined content padding and the caret's rect from the field's layout. There's no padding constant and no
    scroll-value arithmetic, and each step logs a cross-check against `boundsInWindow`, written to the collected
    `form-dialog-metrics.txt` before its asserts. In this dialog the window-coordinate paths (`positionInWindow`,
    `localToWindow`) disagreed with `boundsInWindow` by the inner scroll value (#372). There is
    no "first tap after focus moved away" step: in touch mode nothing in this dialog can take focus from the field
    without a tap on an already-focused field, which the strict input contract avoids. A rotation recreates the Activity
    and focus doesn't survive it.
  - **The `report-light-font200-form-toolong` capture's reasons-list variant** was test timing, not something a user can
    reach. The keyboard arrived after the 1,012-character input, and the act's own `performScrollTo` to the counter
    moved the view to the reasons list, even when the counter was already visible (N=40 runs 45746286440 and
    45753087572). The act now waits for the dialog's layout to reach the keyboard's final inset (a bounded positive
    condition) and doesn't scroll on its own: the form dialog keeps the caret's line in view. The counter below it
    can be out of view at the default size; keeping it in view is a separate follow-up (PL).
- **The 24 dp padding scrolls with the content.** In a one-column layout the viewport is then the whole surface. With
  the keyboard up at 200% landscape only about 80 dp is left, and a fixed padding would have left 34 dp, too little for
  a 48 dp button.
- **Buttons:** side by side when both fit, otherwise stacked with the primary on top, end-aligned, as M3 does.
- **Outside taps:** an outside tap dismisses, as a platform dialog's does, unless the caller passes
  `dismissOnOutsideTap = false`. That's for a form where an accidental dismissal loses a choice or composed text (PL);
  the report form and StepUpDialog use it in every phase. An ignored tap does nothing: no feedback and no dismiss
  action. Back and the dialog's own buttons still close it. StepUp joined on 2026-11-22 (PL): the dialog moves up as the
  keyboard shows, so a tap meant for Verify could land beside it and discard a typed password or recovery code (#333's
  run 45032291123, where a test's click centre read before the move cancelled an export). Journeys that type into a
  form dialog and then press its button do so under the native input contract: one physical tap with the strict IME
  proof, then the text, then an OS-routed tap whose bounds are read after the move.
- **Holding a notice (`holdFocusedInView`, PL and TL, 2026-11-22):** a hook for a caller whose in-form notice must outlive
  a layout change. While it's true, the keep-the-focused-field-in-view effect does nothing on a keyboard show or hide or a
  mode switch, and the caller's notice re-brings itself into view, keyed on `LocalFormDialogLayout`. The caller sets it
  when a notice appears and clears it at the person's next interaction:
  - (a) the IME going from hidden to shown while the field is focused (a finger tap, TalkBack's double-tap activation, any
    re-show), read from the dialog window's own insets;
  - (b) a focus gain on the field;
  - (c) typing, IME composition included;
  - (d) a choice (the report form's category).

  An IME already up when the notice appears is no transition and doesn't clear it. After (a) the report form brings the
  field back into view once, since the focused-field effect may have run while the hold was on; otherwise the
  focused-field effect behaves as before. It's a scroll only: no focus, caret or accessibility-focus change, and no
  announcement. Off by default, so StepUp and other form dialogs are unchanged. The report form uses it for every in-form
  line after Send.
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
  - **Collection limits (TL, 2026-11-26):** the collector copies the file into `form-dialog-presentation/` if it has at
    most 128 lines and 32 KiB, every line matching its scalar pattern. It was 32 lines and 16 KiB until several tests
    shared the file and passed that, which dropped it silently on every run since about #336 (the PL's downloads). A
    rejection or a missing file now shows in the job summary as well as the log, naming which limit failed, with counts
    and the first bad line number, never a line's content.
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
