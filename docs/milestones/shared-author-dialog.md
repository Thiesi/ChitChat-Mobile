# Shared native author dialog prerequisite

M5b2 room Edit/Delete is delivered in PR57; its merged main passed166 native and623 fresh JVM tests, lint0 and release shrinking. The recorded local Full159/163 remains failed under the exact development-only #55/#56 exceptions, not a keyboard fix or release waiver.

This prerequisite extracts the existing native author dialog into `core/ui/AuthorChangeDialog.kt` and its own-window IME/Save observations into an androidTest helper. Room-specific validation, canonical requests, authorization, uncertainty and jobs stay with the room ViewModel. `AuthorChangeKind` is shared protocol vocabulary. The presentation has no generated private-text string, persistence or network behavior. Existing native selectors,48dp/focus/IME/Save assertions, five-second deadlines and outcomes are unchanged.

The extraction permits the separate M5b3 DM consumer to reuse the adaptive editor without copying its layout or diagnostics. It adds no DM controls, endpoint, dependency, focus request, keyboard workaround or backend change. DM controls and live acceptance remain pending. Splitting the measured126571-byte DM candidate keeps complete review inputs within126000 bytes, rather than truncating them.

This isolated prerequisite passed debug/release/test-APK build in13m4s (R8 executed),623 fresh JVM tests with0 failures/errors/skips (23 room-author cases), lint0, and6/6 native room-author journeys: baseline134.966s,150%-portrait149.211s,150%-landscape136.045s, exact settings/portrait restored.10 unchanged diagnostic-wrapper Python tests also passed. Full native/current-head CI and completed initial/fresh Claude reviews remain merge gates, with outcomes recorded on its delivery PR. Earlier23-room-author and82-focused tests verified earlier DM-worktree checkpoints only, not these gates. Keep all tests enabled in CI and retain every original failure. No further physical-phone/live-server/clipboard experiments are part of this slice.

The original local sequence stopped on a PowerShell parser error before layout/device changes. Retain its source/diagnostics; correcting the helper's reserved-word name changed no app code/assertion. Continuation ran only previously unstarted checks, not the completed baseline journeys.

## A read-only body holds no focus (#70, #284; PL and TL, 2026-11-23)

After a Save whose outcome is unknown, the body is read-only (unknown, then checked). It used to keep its input focus
there. Review swaps in a fresh request and makes the body editable, so the still-focused field restarted its input
session: the keyboard came up unasked about 50–80 ms after Review, and when its inset landed about 400 ms later, Save
moved 235 dp up. A tap aimed at Save in that window landed on the keyboard. That's #70's DM journey failure and #284's
room one, measured locally (5 runs) and matching both CI occurrences.

- **The rule (PL):** a state change inside a form dialog never gives focus or brings up the keyboard by itself. The
  initial open is unchanged.
- **The read-only body** holds no input focus: `focusProperties { canFocus = !readOnly }`, plus a plain `clearFocus()` on
  the read-only transition itself (no `moveFocus`, so no other node takes focus). The keyboard closes once on entering
  the unknown state, as before.
- **Review** makes the body editable without focus or keyboard, and Save stays put. Only a tap on the body focuses it,
  through the normal one-tap path.
- **Accessibility:** the read-only body stays readable, and Review becomes Save in the same node, so accessibility focus
  stays on it.
- **The look:** the body's decoration depends on `enabled` only, so it looks editable after Review.
- **No change to the writes:** Save, Check and Review, one request each, never resent.
- **Tests:** `aReadOnlyBodyHoldsNoFocusSoReviewBringsNoKeyboardAndSaveStaysPut`, in DirectAuthorJourneyTest and
  RoomAuthorJourneyTest. The #70 and #284 tests are unchanged, as the proof.
