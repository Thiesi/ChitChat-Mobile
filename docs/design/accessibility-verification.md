# Accessibility verification plan

Status: how CCM proves the A-* floors in [acceptance.md](acceptance.md) for every
presentation slice, and what remains for the release gate. It complements the existing
verification policy in `docs/build-test-release.md` and doesn't replace it. Accessibility,
physical-device and current-Android acceptance stay release gates there; nothing here
weakens a native input test, deadline or retry rule.

## Layers

Each A-* floor is proven at the cheapest layer that can actually prove it. Higher layers are
not repeated where a lower one is conclusive.

| Layer | Runs | Proves |
|---|---|---|
| L1 JVM | Every build (`testDebugUnitTest`) | Token contrast for every palette (A-01, A-02, A-10), identity-hue stability, run grouping, status precedence, day labels |
| L2 Compose semantics | The normal instrumented suite (CI `journey`) | Touch targets (A-03), merged descriptions and custom actions (A-06), live regions and dialog focus (A-07), "never color alone" cues present as text or semantics (A-05) |
| L3 Configuration matrix | Dedicated-emulator gates, per presentation slice | 200% font scale on a 360×640dp window (A-04), RTL (A-09), landscape and wide windows (A-12), animations off (A-08), dark and AMOLED palettes |
| L4 Assistive-technology walkthroughs | Manual, per slice that changes a journey, recorded in the PR | TalkBack order and wording, Switch Access and keyboard reachability, the real IME |
| L5 Release | Release gate (`build-test-release.md`) | Physical devices (API 26 and current Android), live server, all walkthroughs on the final build |

## L2: shared assertions

Add one test-only helper file in `androidTest` (beside `testing/NativeInput.kt`) with small,
reusable assertions, so each slice tests the floors the same way:

- `assertMinTouchTarget(48.dp)`: the node's touch bounds, not its drawn size, are at
  least 48×48dp.
- `assertMergedDescription(expected)`: a row's merged semantics read in the specified order
  (for example "Kitchen, 12 unread, 1 mention").
- `assertHasCustomAction(label)`: bubbles expose "Message actions", "React", "Open profile"
  and "Actions for {sender}" per their gating.
- `assertPoliteLiveRegion()`: the status slot and composer notices.
- `assertNotFocusableDecoration()`: the chit, glow and illustrations are hidden from
  accessibility.

Optional: Compose's Accessibility Test Framework integration (`enableAccessibilityChecks()`
on the compose test rule) catches contrast, label and target issues automatically. It is a
test dependency, so the coordinator decides whether to adopt it. If adopted, it runs in
the normal suite and its findings fail the test. No suppressions without a written reason
in the test.

## L3: configuration matrix

| Configuration | How | Notes |
|---|---|---|
| 200% font scale, 360×640dp | Dedicated emulator, the existing `expectedFontScale` instrumentation argument and recorded/restored font scale (`build-test-release.md`) | The composer, Send and each screen's primary action stay reachable; nothing overlaps or clips |
| RTL | Enable pseudo-locales for **debug builds only** (`isPseudoLocalesEnabled = true`) and run with `ar-XB` | Build change: coordinator. The chit and bubble sides mirror, and icons with direction (back, send) flip |
| Long text | Pseudo-locale `en-XA` (expanded strings) | Labels wrap, and buttons grow rather than truncate essential words |
| Landscape | Existing landscape gate procedure | The bottom bar hides at compact heights, and the transcript width is capped |
| Animations off | Existing CI setup (animations disabled) | Every transition completes with no stuck intermediate state |
| Palettes | Light, Dark and AMOLED black via the Appearance settings | Screenshots for J-* review. Contrast is already proven at L1 |

Each presentation PR lists which rows it ran and attaches the screenshots its spec asks for.
A row it couldn't run is listed as not run, never implied.

## L4: walkthrough scripts

Short, repeatable scripts. Run the ones a slice touches, on an emulator or device with
TalkBack on, and record pass or fail with notes in the PR. The real IME is used. Taps follow
the native input contracts.

1. **First run:** Welcome → server address → Server found → Create account (anti-bot status
   announced) → Find your people → first room.
2. **Read and reply in a room:** open Rooms → room row reads name, info and unread → room
   header reads name and presence → swipe through a run (sender, text, time) → custom action
   "Message actions" → Reply → type → Send → the new bubble is announced in reading order.
3. **React:** custom action "React" on a bubble → reactions sheet → Add → chip reads emoji,
   count and "you reacted".
4. **Uncertain send** (fake server): the composer notice is announced → Check history →
   the duplicate-risk dialog title receives focus.
5. **Offline:** airplane mode → the offline notice is announced once → Send marks "Will send
   when you're back online" → Cancel works.
6. **Inactivity:** a room with a short timeout (fake) → the warning is announced once and at
   10 s, not each second → Stay.
7. **DMs and Activity:** the inbox row reads the preview or "Draft" → open the thread →
   Activity row → Mark read action → Mark all read confirmation.
8. **Switch Access and hardware keyboard:** Tab order reaches every control of the composer,
   bottom bar, sheets and dialogs. No keyboard traps. Esc or Back closes sheets.

## What counts as done

- A presentation slice is accessibility-complete for development when L1 and L2 pass for its
  surfaces, its L3 rows have run with screenshots, and the walkthroughs it touches have
  recorded results.
- **Release** additionally requires L5: every walkthrough on physical devices at API 26 and
  current Android with the final build, as `build-test-release.md` already requires. Passing
  emulator walkthroughs don't stand in for that.
