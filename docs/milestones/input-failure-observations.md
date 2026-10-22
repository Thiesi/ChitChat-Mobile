# Input verification: async authentication and failure observations

Test-only prerequisite for native room Edit/Delete; not a production keyboard correction. No server, dependency, focus, timeout, single-tap or IME-predicate change. Preserve the maintainer's closure of issue31 and all failed evidence. Passing subsets, traced runs or hosted checks cannot silently waive a known failed full gate.

## Changes

- Corrected-login fixture holds the next response with a cancellable deferred. Assert exactly two submissions, no premature authenticated UI, and cleared password while held; release in `finally`, then await the actual signed-in outcome within5s. Existing username/logout/server-isolation checks remain. This covers asynchronous completion, not a proven cause of the earlier missing-node failure.
- Reply's unchanged5s focus/display/positive-IME wait now reports failure-only selection presence, action-dialog count, send eligibility, bounds, scalar native-window/IME-server observations and optional input-session trace. Snapshot the Compose IME value immediately after timeout, separately from later observations. Queries can synchronize/recompose: these sequential samples are not atomic or proof the predicate was satisfied before its deadline. No bodies, usernames, raw UI/dumps or exception contents are added to diagnostics.
- The standalone100-cycle native-input control now accepts the existing `traceNativeInput=true` opt-in. Off by default, including CI/full gates. The observer always delegates, but can change timing; traced results are diagnostic only. No cycle truncation, tap replay, readiness wait or forced focus/show/hide.

## Investigation before this branch

The room-author candidate compiled with623 fresh JVM cases/lint0, and its six native author cases passed baseline/150%-portrait/landscape and within Full. Full still failed157/163: login1, Reply4, standalone first-tap1. Exact sources/APKs/logs remain archived locally.

A diagnostic APK (same production APK, revised fixtures) compiled2m6s. A fixed untraced login/Reply cohort passed4/5 in212.978s: controlled auth passed; one room Reply handoff timed out. Later failure samples showed selection/focus/visible editor/IME259, not deadline-time proof. A traced standalone control failed at cycle10 after creating an input connection; later IME259/HAS_DRAWN likewise does not erase its timeout.

The long-running emulator had1435 host threads; guest load was11.03 across4 virtual CPUs during sampling (lower by shutdown). One matched cold-start comparison preserved AVD data/configuration hash, font1.0/portrait and keyboard settings; threads fell to195. The same traced control still failed0/1 in18.16s at cycle0, with an input connection but client IMEfalse/0 and a later IME-window DRAW_PENDING sample. Resource pressure is not an established cause or correction. No further restart/replay is planned to seek green.

## Pre-publication verification checkpoint

This branch excludes the room-author feature. Its five-task build passed9m17s: debug/release/test APK,600 fresh JVM cases with0 failures/errors/skips, lint0. R8 came from cache, not fresh execution. The diagnostic-wrapper tests passed10/10 in0.038s. Exact207-file source/build inputs, reports and APKs are hash-verified and archived locally.

Discovery160/local157 is enumeration only. Untraced focused7/7 passed279.862s, including login/four Reply cases, the100-cycle control and observer journey. Full157 is now in progress. No baseline full pass, current CI/review or product acceptance is claimed by this checkpoint. Later outcomes must be recorded on the PR, without erasing these prior failures. New deadlineIME metadata has not yet executed on a failure path. No live server, integration file, phone or host clipboard was accessed; API26/manual keyboard/TalkBack/live acceptance remain separate.
