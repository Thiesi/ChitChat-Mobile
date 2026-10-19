# M2a: HTTPS and native session authentication

Status: local gates pass; hosted checks, Claude review and merge are separate gates. M2 is not complete. The Android base matched remote `072ea511` before branching; backend source remains unchanged (audited v3.2.1).

Implemented: origin-bound typed HTTPS/cookies/CSRF, native password and emergency recovery forms, pending-MFA states, Keystore AES-GCM cookie persistence with seven-idle/thirty-absolute-day bounds, cold/foreground revalidation, immediate local logout with one bounded server attempt, stale-callback isolation, explicit storage/network/rate-limit errors and secret clearing on submit/background. Registration/restoration/step-up remain M2b; chat/SSE/push are not implemented. Native passkey/provider flows still depend on B1/B2.

Verification:

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:liveSessionTest --console=plain --max-workers=2`: successful, 2m49s, 50 fixture unit tests plus one real HTTP test; zero failures/errors/skips. Lint reports no issues; release is shrunk but unsigned.
- The live test passed password login, revalidation, repository recreation/restoration, logout and rejected revoked-cookie reuse against the supplied HTTPS v3.2.1 server. No account creation or password change. It uses transient memory storage, not Android UI/Keystore.
- `:app:connectedDebugAndroidTest --console=plain --max-workers=2`: successful, 3m2s, ten API36 emulator tests, zero failures/errors/skips. Native forms, initial-storage-failure navigation guards, saved-state secret clearing, actual Keystore persistence, fresh encryption nonces and tamper rejection pass. This is not live native-UI authentication.

The opt-in `:app:liveSessionTest` alone reads ignored, user-owned `.env.integration` at the repository root: `CHITCHAT_TEST_SERVER`, `CHITCHAT_TEST_USERNAME`, `CHITCHAT_TEST_PASSWORD`, one `KEY=value` per line. Use a dedicated non-admin password-only account. Never print the file or credentials or enable body/debug logging. Normal tests/CI exclude this class entirely; it never silently skips. Live results must stay separate from fixture counts.

Not verified: native live-server UI sign-in, actual process death, API26, TalkBack, chat or push. Recovery fixtures do not establish passkey parity and consumed no real MFA codes. Detailed architecture/parity/build documentation follows in a separate bounded review PR. M2b and later milestones remain pending.
