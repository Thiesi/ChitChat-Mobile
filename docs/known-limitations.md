# Current status and limitations

As of discovery, 2026-10-19:

- **No Android implementation exists.** Native screens, project/toolchain, automated tests/CI, session store, SSE, FCM and APK are all pending M1-M8. Documentation and issue contracts are not working features.
- No live compatible ChitChat server/account, Firebase project/config or release signing identity has been supplied. No live login/chat, rendered browser review, Android build, emulator, TalkBack or FCM device test ran.
- Native passkey origins and secure browser handoff are absent from v3.2.1. Existing recovery-code APIs work as contracts but are emergency credentials and do not complete passkey parity. Provider-only accounts need B2 for native end-to-end workflows.
- Native FCM registration/delivery is absent. Existing Web Push subscriptions cannot accept FCM tokens. Push topology/operations and ordinary-DM notification behavior require the consolidated product decisions in [discovery](discovery.md).
- The effective installation-name override is not public JSON; native capabilities have no explicit discovery contract. The revision-review participant-notification policy flag disagrees with the documented/trigger-derived notification behavior; B5 requests clarification.
- PHP cookies are session cookies with lifetime zero. Android encrypted restoration uses an explicit bounded client policy, never guaranteed server persistence. Guest credentials remain session-only and are not restored across a cold app start.
- SSE is retention-limited and authorization-filtered; there is no explicit expired-cursor or snapshot/current-watermark response. Foreground reconciliation is required even when the connection resumes successfully.
- History has older-ID pagination but no efficient around-message route. Deep links/search results may require many pages and a cancellable loading/unavailable path. Never claim instant navigation or silently substitute unrelated newest messages.
- DM conversations are capped at 100 without pagination. DM read marks all incoming unread messages from the selected peer; precise only-rendered-ID acknowledgment is not available.
- Send/upload endpoints have no idempotency key. Automatic retry after ambiguous network failure can duplicate a message; native UI must preserve an explicit delivery-unknown state.
- DM history/SSE lack complete mutation/file metadata. Separate visible-ID enrichment is essential; a literal `Message deleted.` body is not a reliable deletion flag.
- FCM availability depends on compatible Google Play services and operator/publisher infrastructure. OS permission denial, channels, force-stop and vendor background restrictions can delay/prevent alerts; foreground state reconciliation remains mandatory. No UnifiedPush implementation is currently planned or claimed.
- Persistent local content caching, even encrypted, is not E2EE or a guarantee against a compromised device. Local cache must obey expiry/logout/isolation; server revocation cannot retroactively erase copies a user explicitly exported/shared.

Optional future backend improvements are around-message lookup, snapshot/cursor reset, send idempotency, paginated conversation list and bounded DM read-through ID. These are useful reliability/scale improvements, not fundamental blockers to current browser-feature parity. They are not silently assumed in production code or confused with the required B1-B6 issues.
