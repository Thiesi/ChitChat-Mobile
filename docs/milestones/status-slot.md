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
- **Accessibility decision, for J review:** during the seconds countdown the screen-reader text
  deliberately differs from the visible text. Sighted people see "Try again in 12 s" counting down, while
  TalkBack hears "Try again in less than a minute." once. Announcing every tick would read a number aloud
  each second for up to a minute. The announcement key is the notice kind plus a coarse wait bucket, so
  minute and hour waits re-announce at most once a minute.
- **`{host}`** is `application.name` from the session (ChitChat#149), read leniently (trimmed, 1–80
  characters, no control characters), else the host. A malformed `application` never fails the bootstrap.
- **Announcements** are a separate steward-edged card below the slot (P-104), not status.
- **Placement:** the slot sits under the top bar, and moves into Details at compact conversation heights,
  as before.

## Per-screen rows (S1b)

Rooms, conversation, the DM inbox and thread, and notifications report their syncing and stale state to
the slot through `ReportScreenStatus`, a CompositionLocal holder. They no longer show their own syncing,
stale or session lines (checking session, refresh needed, rate limit). A busy session reads as row 11's
"Updating…". The latest reporter speaks, and a leaving screen clears only its own status. Content notices
specified by the lists and transcript specs stay in their screens: uncertain writes, unknown reads,
incomplete ranges and stale footers with their own Refresh.

Where the top slot can't be seen, inside a dialog or in Details at compact heights, one session-only
`StatusSlot` (`connection = false`) keeps Refresh session reachable, one notice per surface.

## Offline live edge (S3)

While realtime is `OFFLINE` and a room or DM transcript shows its latest window, a quiet caption at the
live edge reads "Newer messages appear when you reconnect." There's no "Loaded at" time: no screen records
when it last loaded, and nothing is tracked just for this. The presence subtitle for offline belongs to the
P-86 header and comes with that slice. Online, the caption renders nothing, so journey layouts are unchanged.

## Send when back online (S4a rooms, S4b direct messages; on screen)

S4a covers the room on screen and S4b the direct message on screen. Both use one shared coordinator,
`awaitSendWhenBackOnline` in `core/realtime`, so the two flows can't drift apart.

**What can wait:** pressing Send in the conversation on screen while realtime is `OFFLINE` marks the draft
waiting, but only a clean text or reply draft:
- no send in progress;
- no unknown delivery (in a DM, no unknown block either);
- no pending choice, review or dialog;
- the person may post (a room: member, guest write, not muted; a DM: messaging available, not muted).

**What can't:** an attachment draft keeps today's behaviour and never waits. Waiting is not an attempt.
Cancel, or dropping the flag, returns the text to an editable draft and never touches delivery-unknown
state or consent. The waiting text and reply target are frozen at Send: the field is read-only (selectable,
in ordinary ink, not a disabled field), the reply can't change, and exactly that content is sent. While a
draft waits, a manual send, the attachment paperclip and attachment send all refuse, so a second POST can't
sit beside the waiting attempt.

**The automatic attempt.** The view model runs one attempt per waiting draft, only after all of these, in
order:
1. **Connection:** realtime is `LIVE`.
2. **Session revalidation:** a revalidation strictly newer than this reconnect has completed.
   - `SessionState.bootstrapSequence` counts successful bootstraps in the generation. The view model
     snapshots it whenever the connection leaves `LIVE` and needs a newer value.
   - A reconnect alone never revalidates the session, so this adds **one new session GET per reconnect**.
     It is made only while a draft waits and its conversation is on screen. It goes through the existing session
     refresh, so an in-flight bootstrap (for example a foreground one) is reused rather than duplicated,
     with no retry and no polling.
3. **Conversation reconcile:** the conversation's own post-reconnect sync has finished, so it is loaded,
   not stale and not busy (a room also not syncing).
4. **Policy re-read:** policy and target are re-read after the last await through `canSend()`.

Then it sends the frozen content once through the ordinary send path, with all its guards. A guard refusal
returns the draft to normal with that guard's message.

**Outcomes and limits:**
- **Outcomes:** confirmed clears the draft; a definite refusal returns it; an ambiguous result enters the
  existing uncertain-send flow (Check history, then duplicate risk).
- **No automatic second attempt** after anything. Every new attempt needs a fresh Send press.
- **Classification:** every failure that isn't a definite 4xx, including 5xx and all network errors, is
  treated as ambiguous.
- **429 on the revalidation:** the known local deadline is waited out while on screen, then it revalidates
  once more. Without a known deadline, the draft waits for the next reconnect.
- **429 on the send itself:** a definite refusal with the wait shown. Nothing is resent.
- **Timers:** only that known 429 deadline, and only while on screen.
- **Dropping the flag:** leaving the conversation, opening another one, background, sign-out, a generation
  change or process death drops it. The text stays an ordinary draft, and nothing is saved.

**Notice layout (P-108):** Cancel stays beside the notice only while the notice fits in two lines at the
width left beside it, measured with the current font; otherwise the notice takes the full width and Cancel
gets its own end-aligned 48dp row below. The choice is the measured line count, never a font-scale
threshold, so a narrow phone at 100 % gets the same fallback. Reading order is always notice, then Cancel.

**Scope:** sending for a conversation that isn't on screen (a possible S4c) is in the backlog. Until then the
copy says so (presentation lead, PR227, interim):
- the room composer: "Will send when you're back online, if you stay in this room.";
- the DM composer: "Will send when you're back online, if you stay in this conversation.";
- the offline slot: "No connection. You can read and write. Messages you send will go out when you're back,
  if you stay in the conversation."

## Interim copy

The spec's full row-2 text ("…while ChitChat is open") promises sending from anywhere in the app. That is
the possible S4c, which doesn't exist. The slot uses the on-screen interim copy above instead.

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
