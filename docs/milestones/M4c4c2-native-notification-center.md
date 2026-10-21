# Native notification list, read controls and unread indicator

This slice consumes [M4c4c1 state](M4c4c1-notification-state.md) in the normal native application. Home exposes Notifications with the independent server unread count; unknown and stale counts are labeled. The scrollable Material list includes plain-text title, body/details, account-aware timestamps, read/unread state, older pagination, refresh and an explicit Latest action for historical or incomplete ranges. Displaying a notification does not mark it or a chat read.

Individual read buttons and a separate All confirmation use the existing session/CSRF repository. All explicitly includes unloaded notifications and arrivals before server processing. Confirmed changes survive failed follow-up GETs through the local overlay; no timestamp is invented. Unconfirmed writes remain unknown until an explicit full GET/current-state review. Refresh never repeats a POST. Safe older-page GETs remain independent from permission to submit another read choice.

## Ownership, lifecycle and compatibility

The Activity ViewModel owns the bounded in-memory body and operations. Navigation stores only server/account/generation/version metadata; a restored old-owner route cannot expose another account's notices. Each rendered callback checks the actual current back-stack-entry reference before touching its original scoped model. Full-center selection is also bound to the original owner: an account switch fetches only a count summary until its own center entry is selected.

Root START/STOP owns foreground synchronization, distinguishing configuration recreation from genuine backgrounding. Navigating away cancels the All choice and read eligibility, not an active read POST. A hidden-center GET uses existing `list.php?limit=1` solely for `unread_count`; it never adopts that row as history or grants unknown-read reconciliation. Foreground and navigation refresh counts. There is no polling timer, background service, additional cookie jar/SSE connection, or invented notification event. The UI does not promise instantaneous remote-notice updates.

The backend baseline remains v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`. [Browser behavior](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/assets/js/privacy-notifications.js) separates View message from Mark as read. This slice supplies list/read controls and the indicator, not destination consumption. Linked rows explicitly disclose that opening the destination is pending; there is no fake View action or browser fallback.

The following routing slice must reuse the exact-target actor while preserving authorization, drafts, fixed historical bounds and unknown-send guards across rooms/peers, and handle private pings. Parsing a link is not authorization. This split keeps complete reviews bounded; it does not omit the remaining parity requirement.

Guest notifications await native guest access (M7). Background push, notification preferences/devices and connector/FCM fallback remain M6. This list does not claim push delivery. No Firebase identifiers, credentials, new endpoint, backend source change or disk cache is introduced.

## Actual verification

The five-task gate passed on the current production/test revision in **6m 38s**:

```text
assembleDebug testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest
--no-daemon --max-workers=2
```

This includes **531 JVM tests**, zero failures/errors/skips; debug lint with no issues; debug APK, unsigned R8 release APK and instrumentation APK. Reports and all three hashed APKs are archived under ignored `.tooling/native-center-five-531-pass`. Builds ran with the dedicated emulator stopped; device instrumentation ran separately against that same APK pair.

The nine new native journeys passed on their first portrait/font-100% run: actual `OK (9 tests)`, **55.026s**. They cover Home/count/list/plain text/explicit read/back, DESC pagination, All cancellation and arrivals, unknown-read recovery, confirmed-read overlays, permission loss, stale entry callbacks, account switching and a short viewport. All three fake-only portrait captures were pulled and inspected. The constrained 200dp fixture tests component scrolling, not separate production chrome.

With the correct dedicated-emulator policy, the unchanged nine-case suite also passed actual landscape 640×320/font-150%: `OK (9 tests)`, **59.196s**, including background/resume and per-case orientation/font assertions. All three corrected landscape captures were pulled and inspected.

The first full device run passed **112/113**, then failed the existing DM reply journey's second composer tap after target adoption: focus acquired, IME inset zero for the unchanged five-second predicate. The actual failure summary is preserved in `.tooling/native-center-full-113-result.log`; its first console chunk is truncated, not a complete per-case transcript. Narrow TestRunner/ImeTracker metadata correlates the second show request with `PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT`, not merely a stale Compose inset. The cause remains unproved. One bounded diagnostic cohort of the unchanged seven reply journeys passed `OK (7 tests)` in **40.113s** against the same APKs. This is not a keyboard fix or an erased failure. [Issue31 records this evidence](https://github.com/Thiesi/ChitChat-Mobile/issues/31#issuecomment-6307376349) and remains open.

One independent complete confirmation then passed all **113 native tests** in **281.556s**, with the same APKs and assertions. The complete file `.tooling/native-center-full-confirmation-113.log` contains `OK (113 tests)` and 113 passing status records, zero failed/skipped records; its wrapper explicitly validates the result rather than trusting adb's exit code. The original 112/113 failure remains separate evidence. No guessed production fix, extra tap, forced focus/keyboard, autofill disable, timeout increase, or assertion relaxation was used. Completed current-head Claude review, a deliberately requested fresh review, and exact-head hosted CI remain delivery gates.

## Preserved corrections and acceptance limits

Earlier gates found two unread-count resources needing proper plurals, then direct StateFlow access in a short-viewport test fixture. Both 530-case JVM runs passed, but lint correctly failed. Correct singular/other plurals and observable fixture state fixed these without suppression. The pagination fixture also compares lists rather than a LongProgression. A first complete five-task 530-case pass preceded the separately tested original-owner mode refinement; it is not substituted for the current 531-case gate. These reports remain separately archived.

Two landscape setup failures are also preserved. A rotation lock/font-1.5 alone passed seven cases, then failed two strict pre-test orientation checks after background/resume returned the host to portrait. An orientation-ignore setting alone did not help: the next nine cases failed before their bodies. Diagnostics showed NOSENSOR/portrait despite the requested lock. The previously verified setup requires `wm fixed-to-user-rotation enabled` plus `wm user-rotation lock 1`, with actual landscape checked before instrumentation. The corrected pass above retains every assertion. [Reusable setup/restoration guidance](../build-test-release.md) now records this.

The captured original emulator policies were restored: fixed rotation default, orientation-ignore false, user rotation free, accelerometer 1/user_rotation 0, font 1.0. Actual portrait and software-IME preconditions were verified before full-suite execution. The older PowerShell configured-user launcher later returned Access denied; explicit configured-user PowerShell 7 worked. No ACL, profile, signing key or shared service was changed.

The earlier merged-main run39519742182 also failed once at 103/104 and passed a maintainer-requested same-commit retry at 104/104. Neither that retry nor a later green run resolves issue31. All current passes use CI-aligned disabled emulator animations; animated/physical-device/API26/TalkBack and live-server acceptance remain unperformed. No integration credential file was read and no live installation was contacted or mutated in this slice.
