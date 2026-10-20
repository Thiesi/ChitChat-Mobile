# M4c1: account-wide typing privacy

This slice adds a native Account control for the existing bilateral typing preference. It does not yet publish typing signals or display other people's indicators. Ignore management, notification center and background push remain separate slices. No backend modification, new endpoint, relay or live-server mutation is involved.

## Verified contract

Baseline: ChitChat v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`. These exact paths were compared read-only with the available checkout; no difference:

- [`public/api/v1/session.php`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/session.php): top-level `share_typing`, authenticated database value; anonymous false does not grant account access.
- [`public/api/v1/account/typing.php`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/account/typing.php): authenticated member POST, current CSRF, JSON Boolean `share_typing`, HTTP200 with the actual Boolean. No step-up or automatic retry.
- [`public/account.php`](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/account.php#L115): both directions, rooms and DMs, no typed text in signals. This is account-wide, not a device notification preference.

## Native behavior and isolation

The shared session owns HTTP/cookies/CSRF and the confirmed nullable preference. Missing/null is unknown, never default opt-in. Strict Boolean deserialization rejects quoted strings; the first new fixture exposed the default serializer's coercion and was not counted as a passing gate. Known values have one accessible switch target; unknown values show explicit choices and Refresh, not a fabricated switch position. Copy states clearly that native signals/indicators are still unavailable.

Preference POST and session bootstrap are serialized through short runtime/state gates, never a lock held over network I/O. A confirmed response is authoritative even if different from the requested value. Error/cancellation leaves unknown state; explicit ordinary session GET reconciles before another attempt. No POST replay on recovery/resume. Cancellation cleanup is non-cancellable, runtime-fenced, releases busy and requires CSRF revalidation when interrupted. Logout/replacement callbacks cannot repopulate preference or error state. The ViewModel binds each request to the original server/account/generation/version, survives rotation and cancels on navigation or genuine backgrounding.

## Verification status

Focused86 JVM tests and the first3 native preference journeys passed. The first full gate had351 JVM tests and clean lint, but82/84 native: Foundation/Auth fixtures expected old hardcoded availability copy. Their original visibility assertions now resolve the updated resources; this failed run is preserved in ignored `.tooling/m4c1-stale-copy-failure`, not counted as a full pass.

The corrected six-task gate passed5m55s: debug,351 JVM, clean lint, R8 release, test APK and84 API36 native tests with `expectSoftKeyboard=true`; zero failures/errors/skips. Reports: ignored `.tooling/m4c1-full-84-pass`. Two additional JVM tests then proved the runtime gates still exclude competing bootstrap/preference calls after an unrelated in-flight request clears global busy with an error. The expanded full JVM suite353/353 and lint passed34s; production and native test sources were unchanged by these two additions.

All3 preference journeys passed29s in actual landscape/font150%, with per-case orientation/font assertions; reports: ignored `.tooling/m4c1-landscape-3-pass`. A separate asserted one-case run returned `OK (1 test)` and captured the scrolled switch layout for visual inspection (`.tooling/m4c1-typing-landscape150.png`); the owned emulator was restored to portrait/font100%. Current-head Claude review and hosted CI remain additional delivery gates. No live two-device preference behavior, native typing, physical-device/TalkBack or push acceptance is claimed.
