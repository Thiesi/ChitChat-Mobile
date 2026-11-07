# "This server needs an update": presentation specification

Status: presentation spec for the minimum-server-version state the maintainer decided
(issue #1, 2026-11-07). CCM's baseline is the backend version that includes the client's filed
issues, with no fallback paths for older servers. How the version is detected (for example the
health or session response) and the exact minimum are engineering decisions, made when the
first required backend change merges. Mockup: `ServerUpdate` on the canvas.

## Principles

- **The person isn't at fault and nothing is lost.** Their account and messages live on the
  server. Copy says so first.
- **Name the one thing that helps:** the server's operator updating it. Make that easy to ask
  for.
- **Never degrade quietly.** No half-working features on an old server. Show this state
  instead.
- **Only for a definite version mismatch.** Network errors, timeouts and other failures keep
  their existing messages (status and onboarding specs). This state is never a guess.

## At server setup (onboarding step 3)

When the found server is reachable and is ChitChat, but older than the minimum, step 3 shows
the server card (lantern, host, lock) and then:

- **Title:** "This server needs an update"
- **Body:** "{installation} runs an older version of ChitChat than this app supports. Ask the
  person who runs it to update the server. Nothing is wrong with your account."
- **Details line** (caption, `textMuted`): "Server version {found} · this app needs {minimum}
  or newer." If the server reports no version, show "Server version unknown · this app needs
  {minimum} or newer."
- **Actions:**
  - **Copy a note for the operator** (primary). It copies plain text: "Hi! The ChitChat Android
    app needs our server ({host}) updated to ChitChat {minimum} or newer. It currently runs
    {found}." A snackbar "Note copied" is acceptable here.
  - **Use a different server** (text button), back to step 2 with the field empty.
- No Sign in, Create account or guest buttons while the version is too old.

## On an existing session (revalidation)

When a signed-in session's revalidation (cold start, foreground, or session refresh) finds the
server below the minimum:

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

- Use the installation's name when known, else the host.
- Version numbers appear only in the details line and in the note, never in the title.
- Never mention internal identifiers, API names or error codes on this screen.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-180 | A definite below-minimum server shows this state at step 3 and on revalidation. Network or other failures never do. | U, C |
| P-181 | Step 3 offers only Copy note and Use a different server. Revalidation offers Copy note, Check again and Sign out, and keeps the session. | C |
| P-182 | While the state shows, no writes, reads or realtime run against that server. Check again is the only server request, and it is explicit. | C, R |
| P-183 | The copied note contains the host, the minimum and the found version (or "unknown"), and nothing else. | U |
