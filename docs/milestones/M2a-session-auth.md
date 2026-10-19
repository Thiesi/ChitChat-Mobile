# M2a: HTTPS and native session authentication

Status: code verification/review in progress, not M2 completion. Base matched remote `072ea511` before branching; backend source is unchanged (audited v3.2.1).

Implemented: typed origin-bound HTTPS/cookies/CSRF, native password/emergency recovery, pending MFA, bounded Keystore AES-GCM session persistence, cold/foreground revalidation, immediate local logout with bounded server attempt, stale-callback isolation, safe errors and secret clearing. Registration/restoration/step-up are M2b; chat/SSE/push and native passkey/provider flows are not implemented.

Local debug/release builds, lint and 50 fixture unit/ten API36 emulator tests passed before splitting the PR, as did one opt-in live password/revalidation/repository-restoration/logout/revoked-cookie test. This does not establish live native-UI sign-in, process death, MFA/passkeys, API26 or TalkBack acceptance. Release is unsigned.

For complete review, the optional live-test harness (three parser tests plus live test), three auth UI journeys and expanded docs follow separately. This code PR retains 47 fixture unit tests and seven device tests, including 19 session lifecycle regressions and real Keystore persistence/nonce/tamper checks. Its debug/unit/lint/shrunk-release rerun passed in26s (47 tests, zero failures/errors/skips); hosted/device/review results remain separate gates. The private `.env.integration` stays ignored and user-owned; never print or commit it.

The initial review was rejected by its full-diff audit. Claude Code's [inline maximum](https://code.claude.com/docs/en/tools-reference#output-limits) is 128000 characters, not150000; the workflow now uses that setting and a stricter126000-byte preflight. No incomplete review counts as approval.
