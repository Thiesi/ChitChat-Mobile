# Status and connectivity presentation: implementation specification

Status: implementation-ready spec that turns [communication.md](communication.md)'s status
ladder and connectivity table into code. It can land after the theme foundation, in parallel
with the transcript and lists slices, and the coordinator schedules it. Mockups: `Offline`,
`DirectThread`, `ServerProblem` and the vocabulary sheet's "How CCM speaks".

**This slice changes what is shown, never what happens.** Connection management,
reconnects, reconciliation, session revalidation, rate limits and every write rule stay as
they are. The one new behavior, **Send when back online** (maintainer decision 2026-11-07),
is specified under Offline. It needs ViewModel work, so the coordinator plans it as its own
slice. It is not presentation-only.

## Today (2026-11-07)

- `AndroidRealtimeRuntime` already observes the default network
  (`registerDefaultNetworkCallback`, `onAvailable`/`onLost`). `RealtimeController` exposes
  `RealtimePhase { STOPPED, CONNECTING, LIVE, RECONNECTING, OFFLINE, ERROR }`.
- `RealtimeStatus` renders **a line in every phase**, including `LIVE` ("Session events
  connected. History may still be synchronizing."). It also shows a Retry button on `ERROR`
  and the newest server or room announcement with Read and Dismiss. It sits under the top bar
  on signed-in routes and is hidden in compact conversation heights. The DM inbox embeds it in
  its privacy details.
- Session-level feedback is repeated per screen (for example `RoomSessionFeedback`: checking
  session, "refresh needed" with Refresh session, rate-limit text). Screens add their own
  stale, syncing and error lines.

## One status slot

Each signed-in screen gets **one status slot** directly under its top bar. A pure function,
`resolveStatus(inputs) -> StatusNotice?`, chooses at most one notice. A shared composable
renders it, replacing the free-standing lines.

Inputs, all from existing state:
- realtime phase and error kind;
- session flags (`busy`, `refreshRequired`, `storageWarning`, `retryAtMillis`);
- the screen's own flags (stale, syncing, its error, uncertain states);
- whether the screen has readable content loaded.

### Precedence

Highest first. Only the highest applicable notice shows.

| # | Condition | Level | Copy | Action |
|---|---|---|---|---|
| 1 | Session storage warning (`storageWarning`) | 2 | Existing session-storage copy, unchanged in meaning | Existing retry or sign-out action |
| 2 | Device offline (`RealtimePhase.OFFLINE`) | 2 | **No connection.** You can read and write. Messages you send will go out when you're back, while ChitChat is open. | none |
| 3 | Stream error, TLS (`ERROR` + TLS) | 2 | **Can't connect securely to {host}.** The certificate couldn't be verified; this can't be bypassed. | Retry (existing `retry`) |
| 4 | Stream error, incompatible or redirect | 2 | **{host} sent something ChitChat doesn't understand.** Live updates are off; pull down to refresh. | Retry |
| 5 | Stream error, other | 2 | **Live updates stopped.** Pull down to refresh, or try again. | Retry |
| 6 | Session refresh required (`refreshRequired`) | 2 | **Your session needs a refresh.** | Refresh session (existing, disabled until `retryAtMillis`) |
| 7 | Rate-limited (`now < retryAtMillis`) | 1 | Try again in {s} s. | none (countdown, existing timer) |
| 8 | Reconnecting longer than 5 s (`RECONNECTING` or `CONNECTING`) | 2 | **Can't reach {host}.** We'll keep trying. | none |
| 9 | Recovery: the first `LIVE` after any of rows 2–5 or 8 | 2, auto-dismiss after about 2 s | Back online | none |
| 10 | Screen-specific uncertain state (rooms `mutationUncertain`, notifications `unknownRead`, …) | 2 | As specified in the lists and transcript specs | That spec's explicit action |
| 11 | Screen stale or syncing | 1 | "Updating…" (syncing) / "This may be out of date. Pull down to refresh." (stale) | none |

- `{host}` is the installation's display name once the server provides it (#149), else
  the host.
- `LIVE` alone shows **nothing**. The old "Session events connected" line is removed.
  Per-screen syncing and stale lines (row 11) carry the "history may still be synchronizing"
  honesty where it matters.
- Reconnecting under 5 s shows nothing in the slot. The conversation header subtitle may
  read "Reconnecting…" (level 1).
- `STOPPED` is only expected when the app isn't visible. If it is ever visible, show row 11's
  stale copy.

Rows 10 and 11 coexist with the screen's own content notices: the lists and transcript specs
place uncertain-state notices in their header area. The resolver lets each screen pass
its own row-10 notice, so there is never a second status line.

### Rendering

- Level 2: a full-width block under the top bar, with 12dp vertical padding and an icon plus one
  or two sentences, using the title in bold. Backgrounds:
  - `raised` for offline and reconnecting;
  - `presenceWash` for uncertainty and refresh needs;
  - `surface` with an `error` icon for stream errors.

  It announces through a polite live region, once per change of notice, not on every
  recomposition.
- Level 1: one caption line (`textMuted`) under the top bar, or in the header subtitle where a
  screen has one.
- The Back online notice (row 9) uses a timer bound to the slot's composition and cancelled on
  leaving or backgrounding (P-25). It never runs while the app isn't visible.
- Hidden in compact conversation heights (today's `compactConversation`) and moved into
  Details there, as today. Hidden while the IME is open only if today's rules hide it.
- Screens without a top bar (onboarding) use the inline notice placement from
  `onboarding-restyle.md`.

## Announcements

Server and room announcements (`state.broadcasts`) are not status. They are messages from
the people who run the server. They render below the status slot as a dismissible card:
- `surface` with a 3dp start edge in `steward` (the only use of a side edge, marking "from
  the people who run this server");
- "Server announcement" or "Room announcement" as the label, then two lines of text;
- **Read** (opens the existing dialog with the full text and time) and **Dismiss**
  (existing `dismiss`).

At most one card shows, chosen as today (the first matching broadcast).

## Offline

### Send when back online (approved behavior, own slice)

Pressing Send while `RealtimePhase.OFFLINE` doesn't start a request. It marks the
conversation's current draft **waiting to send**:

- **One per conversation.** It reuses the existing room or DM draft slot, which already holds
  one text plus its reply reference. While a draft waits, the field shows its text
  read-only. Above the composer, a notice reads **"Will send when you're back online."**
  with **Cancel**, which returns the text to an editable draft. Send shows a small clock and
  the accessible label "Waiting to send".
- **One automatic attempt, foreground only.**
  - When the realtime phase next becomes `LIVE` while that conversation's draft is still
    waiting, the app is visible and the session generation and conversation scope are
    unchanged, the ViewModel starts exactly one send through the ordinary send path, with
    all of its existing guards (permissions, mute, block, rate limit, reply validity).
  - "Visible" means the app is in the foreground, on any screen. The person doesn't need to be
    in that conversation.
  - Nothing runs while the app is backgrounded. No WorkManager, alarms or wake-ups.
- **Outcomes** use the existing contracts:
  - confirmed: the canonical row appears and the draft clears;
  - definite failure (the request never reached the server): the draft stays with the
    standard "Couldn't send" notice and is no longer waiting;
  - **ambiguous** result: the standard uncertain-send state with **Check history**, then the
    duplicate-risk choice. **Never a second automatic attempt.**
  - a guard refusal at send time (for example muted, blocked, room access lost or reply target
    gone): the draft stays editable with that guard's existing message.
- **When it does not send:**
  - The app goes to the background, the process dies, the session ends or changes generation,
    or the person signs out or switches account: the waiting flag is dropped. The text stays
    a normal draft wherever drafts survive today; drafts are memory-only and are cleared on
    account change and process death, as today. Nothing ever sends later without the person
    pressing Send again.
  - Rate-limited (`retryAtMillis`) on reconnect: wait until the limit passes while still in
    the foreground. If the app leaves the foreground first, drop the flag.
- **Ordering.** Several conversations may each have a waiting draft. They send one at a time,
  in the order Send was pressed, each only after the previous one finished with a confirmed or
  definite result. An ambiguous result in one conversation doesn't block the others.
- **Classification prerequisite.** The implementer must confirm that "could not reach the
  server" (no route, DNS failure, connection refused before the request body is written) is
  classified as a **definite** failure and anything after the write as ambiguous. If the
  transport can't make that distinction reliably, treat the attempt's failure as ambiguous.
- **Visibility.** The DM inbox and room list show a waiting draft as "**Waiting to send:**
  …" in the row. The drafts manager labels it too.

Other offline composer rules:
- Composer text, drafts and replies stay editable until Send is pressed.
- The offline notice (row 2) copy becomes **"No connection.** You can read and write.
  Messages you send will go out when you're back, while ChitChat is open." 
- Loaded content stays visible. No spinner covers readable content.
- At the transcript's live edge, a quiet caption: "Newer messages appear when you
  reconnect." Add a time ("Loaded at 17:58") only if the screen's state already records when
  it last loaded. Don't add tracking just for the caption.
- Presence: the header subtitle reads "Who's here is unknown while offline". Typing
  indicators clear, as the existing expiry already does.
- Pull-to-refresh while offline does nothing beyond today's behavior. It shows the offline
  notice instead of a spinner.

## Errors elsewhere

- Inline errors (`InlineError`) keep their placement next to the control they concern, restyled
  with an icon and `error` text. They follow communication.md's rule: what happened, what
  was affected, what you can do.
- Error copy that names failure kinds (network, TLS, incompatible, 503) is shared through one
  mapping, so the same failure reads the same everywhere. Strings that carry contract wording
  (registration ambiguity, recovery counts, step-up) keep their meaning verbatim.

## Copy changes

| Old (key) | New |
|---|---|
| `realtime_live`: "Session events connected. History may still be synchronizing." | removed (nothing shown when live) |
| `realtime_connecting`: "Connecting session events." | removed from the slot. Row 8 after 5 s |
| `realtime_reconnecting`: "Reconnecting session events." | "Reconnecting…" (header, level 1). Row 8 after 5 s |
| `realtime_offline`: "Offline. Loaded room chat may be out of date." | Row 2 copy |
| `realtime_tls_error` | Row 3 copy |
| `realtime_incompatible` | Row 4 copy |
| `realtime_error` | Row 5 copy |
| `realtime_stopped` | Row 11 stale copy |
| (new) | "Back online" |
| (new) | "Newer messages appear when you reconnect." |

## Tests

- **Unit:** `resolveStatus` precedence covers every row, ties, the 5-second reconnect threshold
  (injected clock), the recovery notice only after rows 2–5 or 8, `LIVE` with nothing, and
  each screen's row-10 input.
- **Compose:**
  - one slot at most;
  - polite announcement once per change;
  - the Back online timer cancels on leaving and on `ON_STOP`;
  - Send while offline marks the draft waiting; Cancel restores it;
  - exactly one automatic attempt on `LIVE` while visible;
  - no attempt after background or process death;
  - an ambiguous result enters the standard uncertain state with no second attempt;
  - several waiting conversations send in press order;
  - compact heights move the slot into Details;
  - announcements render as a card with Read and Dismiss.
- Existing realtime, reconnect and notification tests run unchanged. Screens with text-based
  selectors on the old realtime strings are updated in the same PR.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-100 | Each signed-in screen shows at most one status notice, chosen by the precedence table. `LIVE` alone shows nothing. | U, C |
| P-101 | Copy, levels and actions match the table. Retry and Refresh session call the existing actions with their existing gating. | C |
| P-102 | Back online appears only after a recovery and dismisses after about 2 s, with a lifecycle-bound timer. | C |
| P-103 | Send when back online: one waiting draft per conversation, made only by pressing Send. At most one automatic attempt, only on reconnect while the app is visible, through the ordinary guarded send path. Never retried; ambiguous results use the existing uncertain-send flow; the waiting flag is dropped on background, sign-out, generation change or process death. No background work. | U, C (fakes), existing unknown-send tests |
| P-104 | Announcements render as one dismissible steward-edged card, separate from status. | C, S |
| P-105 | No new polling, timers beyond P-102 and P-25, or background work. Connectivity comes from the existing callback. | R |
