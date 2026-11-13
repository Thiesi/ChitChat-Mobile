# Status slot (S1a)

A signed-in screen shows **at most one** connection or session notice, in one slot directly under the
top bar, following [status-presentation.md](../design/status-presentation.md). This slice changes what is
shown, never what happens: connection management, reconnects, reconciliation, session revalidation, rate
limits and every write rule stay as they are.

## What it does

- **`resolveStatus(StatusInputs)`** is a pure function that chooses the highest applicable row of the
  spec's precedence table:
  1. session storage warning;
  2. offline;
  3. TLS stream error;
  4. incompatible or redirect stream error;
  5. other stream error;
  6. refresh required;
  7. rate-limited;
  8. reconnecting for more than 5 s;
  9. Back online;
  10. the screen's uncertain state;
  11. syncing or stale.

  `LIVE` alone shows nothing; the old "Session events connected" line is gone. `STOPPED`, if ever visible,
  reads as stale.
- **`StatusSlot`** renders the chosen notice:
  - level 2 is a full-width block with a glyph and a bold title;
  - level 1 is one muted caption line;
  - it is announced politely once per change of notice;
  - Retry and Refresh session call the existing actions with their existing gating;
  - the storage action is the existing refresh.
- **Timers:** besides the existing session wait gate (P-25), there are only two (P-105 as amended):
  - the row-8 five-second one-shot;
  - the Back online dismissal, after about 2 s, shown only for the first `LIVE` after rows 2–5 or 8.

  The gate wakes the slot when the wait ends, so row 7 clears and Refresh session enables. Under a minute
  it counts down each second; longer waits wake once when they drop under a minute. All of these are bound
  to the slot's composition, never run while the app isn't visible, and carry no elapsed time across the
  background.
- **Waits** (ChitChat#157): only a positive `retry_after_seconds` on a 429 shows a number. It is always
  rounded up: "12 s", "about a minute", "about N minutes", "about N hours". Otherwise the copy reads "Try
  again in a little while". The session layer's 30 s fallback is never presented as the server's wait.
  The delay is display-only.
- **`{host}`** is `application.name` from the session (ChitChat#149), read leniently (trimmed, 1–80
  characters, no control characters), else the host. A malformed `application` never fails the bootstrap.
- **Announcements** are a separate steward-edged card below the slot (P-104), not status.
- **Placement:** the slot sits under the top bar, and moves into Details at compact conversation heights,
  as before.

## Interim gaps

- **Closed by S1b:** a screen can still show the slot plus its own syncing, stale or session line (refresh
  needed, rate limit, checking session). S1b moves each screen's row-10/11 state into the slot and removes
  those lines. Until then P-100's "one notice" isn't met on those screens.
- **Offline copy:** the spec's row-2 text promises that messages go out when you're back. That is Send when
  back online (S4), which doesn't exist yet. The slot reads "No connection. What's here stays readable.
  Sending needs a connection." until S4 ships.

## Verification

- **JVM tests:**
  - `StatusResolverTest`: every row in order, the 5 s threshold, Back online only after a disconnection,
    an unknown delay never showing a number, and the rounding table.
  - `InstallationNameTest`.
- **Compose `StatusSlotTest`:**
  - `LIVE` shows nothing; each row's copy and action;
  - the host fallback;
  - Refresh session keeps its gate;
  - known and unknown waits;
  - the reconnect threshold and the Back online dismissal on the virtual clock;
  - the announcement card's Read and Dismiss.
- **Captures:** `status-{light,dark}-{default,font200}-{row}` and `status-{light,dark}-default-details` go
  to the presentation lead for J review.
