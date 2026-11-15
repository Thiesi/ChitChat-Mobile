# Server-update check (O1)

Implements [server-update.md](../design/server-update.md) (P-180–P-183) against the minimum backend, ChitChat v3.10.0.

## Contract (released, v3.10.0 `docs/api`)

- `session.md`, "Application and capabilities": `application.{name,version}` and `capabilities.schema_version`
  (#149, since 3.3.0). "A custom operator value that cannot be parsed means unknown"; missing `application` or
  `capabilities` means unknown on older servers.
- `policies.md`: public `policies` (#160, 3.4.0).
- `password-recovery.md`: private `user.recovery_key` (#155, 3.4.0). An explicit `null` (a provider account) still
  counts as present.

## Detection

`serverDiscovery` reads key presence from the raw `session.php` body (facts only). `needsServerUpdate` decides
exactly as AGENTS.md says:
1. No `capabilities.schema_version`: older than 3.3.0, so the state shows.
2. Otherwise the leading `major.minor.patch` of `application.version` is compared numerically with 3.10.0.
3. A missing or unparseable version is unknown, and shows the state only when every observable session-level key is
   also missing: `policies`, plus `user.recovery_key` for a signed-in member.

`schema_version` present with `application` or its version missing is unknown (rule 3), never a second definite
trigger. Per-feature keys never decide the global state. Every bootstrap recomputes `SessionState.updateRequired`
and `reportedVersion` (bounded and printable, for display only). Network and other failures never set it.

## Behaviour

- **At setup:** the onboarding home replaces the sign-in choices, and Change server, with the notice. Its actions are
  Copy a note for the operator and Use a different server (step 2 with the field empty). There are no sign-in,
  account or guest actions (P-181).
- **On revalidation:** a signed-in session's shell is replaced by the full-screen state, with no bar or tabs. Its
  actions are Copy note, Check again (the ordinary refresh, an explicit bootstrap, never polling) and Sign out (the
  normal sign-out, including M6b's push teardown). The session stays signed in.
- **P-182:** while it shows, `SessionRepository.openAccess` refuses new access (`INCOMPATIBLE`), and realtime treats
  it as blocked. Nothing new starts. Work that already holds access finishes under its own rules: a POST is never
  cancelled, and drafts, attachments and unknown-send state stay as they are.
- **The note (P-183):** the raw host, the minimum and the found version (or "unknown"), nothing else.

## Verification

- `ServerUpdateTest` (JVM): the rule as a table, including the technical lead's contradictory case; facts from key
  presence; the displayed version; the note.
- `SessionRepositoryTest`: an old server keeps the session but refuses new access until Check again; an anonymous
  server without capabilities needs the update at setup.
- `ServerUpdateJourneyTest`: the setup notice (no sign-in, the note copied, another server with an empty field). The
  revalidation state replaces the shell, Check again refreshes, and a draft and an unknown-send guard survive the
  state showing and clearing, with nothing resent. Sign out from the state is the ordinary sign-out.
- `ServerUpdatePresentationTest`: captures of setup and revalidation at default and 200%, light and dark
  (`ccm-update-presentation`, 8 files).
- JVM and instrumented test fixtures now report a v3.10.0 server (`application`, `capabilities`), because a body
  without them is, by the rule, older than 3.3.0.
