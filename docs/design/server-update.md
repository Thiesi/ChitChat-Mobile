# "This server needs an update": presentation specification

Status: presentation spec for the minimum-server-version state the maintainer decided
(issue #1, 2026-11-07). CCM's baseline is the backend version that includes the client's filed
issues, with no fallback paths for older servers. Mockup: `ServerUpdate` on the canvas.

**Detection** (ChitChat#149; baseline decision on issue #1, comment 6729652556). `GET
session.php` (anonymous, member and guest) carries `application: {name, version}` and
`capabilities: {schema_version, …}`. **The minimum is ChitChat 3.4.0** (`{minimum}`), the
release with batch 2 (#160, #159, #155, #158).

The state shows only for a parsed session response that is **definitely older than 3.4.0**:

1. **No `capabilities.schema_version`, or no `application`:** the server is older than 3.3.0.
   Show the state.
2. **Otherwise, compare `application.version`** (reliable since 3.3.0). Parse its leading
   `major.minor.patch` as three non-negative integers and compare them numerically (3.10.0 is
   newer than 3.4.0). Anything that doesn't start with that pattern is **unknown**.
   - Definitely below 3.4.0: show the state.
   - 3.4.0 or newer: don't.
3. **Unknown version:** show the state only when the session-level feature keys are **also all
   missing**. At step 3 that's `policies`. On revalidation it's `policies` and
   `user.recovery_key`. If any of them is present, the state doesn't show: an unknown version
   with the features present is not a reason to block anyone.

**Per-feature checks stay as defence in depth:** `policies` (play-readiness.md §1),
`user.recovery_key` (recovery-key.md), `can_report` (reporting.md) and attachment
`width`/`height` (files-media.md). A missing key hides only its own feature, per its spec. On
its own, it never triggers this state.

- Unknown capability keys are ignored. `false` means not offered.
- `{found}` is `application.version` as reported, shown in the details line and the note.

## Principles

- **The person isn't at fault and nothing is lost.** Their account and messages live on the
  server. Copy says so first.
- **Name the one thing that helps:** the server's operator updating it. Make that easy to ask
  for.
- **Never degrade quietly.** No half-working features on an old server. Show this state
  instead.
- **Only for a definite answer.** A session response that arrived and parsed, and is older
  than the minimum by the Detection rules: a missing capabilities block, a version below
  3.4.0, or an unknown version with the feature keys also missing. Network errors, timeouts
  and other failures keep their existing messages (status and onboarding specs). This state
  is never a guess.

## At server setup (onboarding step 3)

When the found server is reachable and passes the existing ChitChat compatibility check, but
its session response is older than the minimum (Detection), step 3 shows the server card
(lantern, host, lock) and then:

- **Title:** "This server needs an update"
- **Body:** "{installation} runs an older version of ChitChat than this app supports. Ask the
  person who runs it to update the server. Nothing is wrong with your account."
- **Details line** (caption, `textMuted`): "Server version {found} · this app needs {minimum}
  or newer." `{found}` is `application.version` as reported. When it's missing or doesn't
  parse, show "Server version unknown · this app needs {minimum} or newer."
- **Actions:**
  - **Copy a note for the operator** (primary). It copies plain text: "Hi! The ChitChat Android
    app needs our server ({address}) updated to ChitChat {minimum} or newer. It currently runs
    {found}." A snackbar "Note copied" is acceptable here.
  - **Use a different server** (text button), back to step 2 with the field empty.
- No Sign in, Create account or guest buttons while the version is too old.

## On an existing session (revalidation)

When a signed-in session's revalidation (cold start, foreground, or session refresh) gets a
session response that's older than the minimum (Detection):

- The signed-in shell is replaced by a full-screen state. No bottom bar, no tabs, no
  half-loaded lists.
- **Title:** "{installation} needs an update"
- **Body:** "This app can't continue with this server until it's updated. Your account and
  messages are safe on the server, and you'll pick up where you left off once it's updated."
- **Details line:** as above.
- **Actions:**
  - **Copy a note for the operator** (primary);
  - **Check again**, an explicit revalidation (the existing session-refresh GET), disabled
    while one is running;
  - **Sign out** (text button).
- **What stays:** the session stays signed in, so nothing has to be redone after the update.
  Drafts in memory stay while the app runs, as today. No writes, reads or realtime connections
  run against the old server while this state shows. Notifications already set up may still
  arrive; tapping one opens this state.
- Accessibility focus moves to the title. The state is announced once.
- Coming back to the foreground re-checks automatically through the normal revalidation, with
  no extra polling.

## Copy rules

- `{installation}` is `application.name` when present, else the host. On an older server it
  is the host, because the name arrives with the same change. `{address}` is always the raw
  host (never `application.name`) and is what the operator note uses.
- Version numbers appear only in the details line and in the note, never in the title.
- Never mention internal identifiers, API names or error codes on this screen.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-180 | This state shows at step 3 and on revalidation only for a parsed session response that's definitely older than 3.4.0: no `capabilities.schema_version` or `application`; a numerically compared `major.minor.patch` below 3.4.0; or an unknown version with the session-level feature keys (`policies`, plus `user.recovery_key` when signed in) all missing. An unknown version with any of those keys present never triggers it. A missing per-feature key only hides its own feature. Network or other failures never trigger it. | U, C |
| P-181 | Step 3 offers only Copy note and Use a different server. Revalidation offers Copy note, Check again and Sign out, and keeps the session. | C |
| P-182 | While the state shows, no writes, reads or realtime run against that server. Check again is the only server request, and it is explicit. | C, R |
| P-183 | The copied note contains the raw host (`{address}`, never the display name), the minimum and the found version (or "unknown"), and nothing else. | U |
