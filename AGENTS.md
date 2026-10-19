# ChitChat Android engineering

- This repository owns the native Kotlin/Jetpack Compose client only. Never edit, commit, fetch into, or otherwise mutate the sibling `Thiesi/ChitChat` checkout. Backend issues are permitted; backend patches are not.
- Read [discovery](docs/discovery.md), [compatibility](docs/backend-compatibility.md), [parity](docs/feature-parity.md), and [milestones](docs/implementation-plan.md) before implementation. Discovery is complete; application implementation has not started. Resolve the consequential decisions in discovery before full implementation.
- For structural codebase exploration use the installed `codebase-memory` skill. Check index freshness and coverage; read source for missing, stale, or unusable coverage. Do not reindex the backend as part of this task: that writes to its checkout.
- Fetch the Android remote and compare the base before branching. Preserve unrelated work. Use focused commits and reviewable milestone PRs; never merge failing checks or conflicts.
- Existing contracts use PHP session cookies, `X-CSRF-Token`, and foreground SSE. Proposed native-auth/FCM contracts in issue drafts are not existing APIs. Never call them in production until implemented, documented, and detected on the server.
- Use Compose, ViewModels, coroutines/Flow, typed DTOs, and repositories. Keep UI out of networking and authorization decisions on the server. Extract abstractions only for concrete reuse or testing.
- Scope secrets, content, cursors, requests, jobs, and notifications by server, account, and local session generation. A stale callback must never repopulate a signed-out account. Never log credentials, cookies, CSRF, tokens, search terms, or message bodies.
- Preserve DM non-E2EE/retention/inspection disclosures, ignore versus block semantics, guest restrictions, step-up, mute/rank rules, and the difference between membership and active presence.
- No automatic retries of ambiguous message/upload POSTs: the existing API has no send idempotency key. SSE is an invalidation/replay transport, not complete authoritative storage. Reconcile on foreground entry.
- Every milestone must compile, pass relevant tests, update the parity/status docs, and commit. Report exactly what ran. Do not equate mocked tests, compilation, or foreground chat with device/FCM end-to-end success.
- Firebase configuration and signing material must be supplied by the maintainer. No invented identifiers, private keys, service accounts, or production URLs. Keep test fakes out of production source sets.

<!-- codebase-memory-mcp:start -->
For structural codebase exploration, use the installed `codebase-memory` skill.
<!-- codebase-memory-mcp:end -->
