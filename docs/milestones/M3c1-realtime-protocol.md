# M3c1: authenticated SSE and presence protocol foundation

This independently buildable prerequisite adds the existing backend's streaming and presence contracts to the shared session transport. It is not yet a foreground lifecycle coordinator or a live room UI: M3b still explicitly uses manual refresh until M3c2 connects the consumer. No background service, message cache, parallel cookie jar, guessed endpoint or live mutation was introduced.

## Contract and implementation

Source baseline remains ChitChat v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`, independently checked against GitHub before branching. Backend graph coverage was checked at generation2026-10-17T18:56:58Z and material source read directly; the backend checkout was not fetched, reindexed or modified. The Android branch started from current, clean `d857756`.

- `SessionHttp.events` uses `GET events/stream.php`, `Accept:text/event-stream` and numeric `Last-Event-ID`; no CSRF is added to this read. The derived client shares the existing origin/TLS/redirect/cookie/dispatcher/retirement policy. [Stream implementation](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/events/stream.php) releases PHP's session lock, requests1000ms retry, sends comments and normally ends at25 seconds.
- Pinned `okhttp-sse:5.4.0` supplies standard framing, not a custom SSE parser. Its [implementation](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp-sse/src/main/kotlin/okhttp3/sse/internal/RealEventSource.kt) does not reconnect or expose retry changes and disables full-call timeout for open streams. A later foreground coordinator must own reconnection/lifetime;1000ms is the verified current-server hint, not an assumed event field. Read timeout and cancellation remain effective.
- A decompressed response budget of8MiB, individual event JSON budget of1MiB and bounded16-signal queue prevent unbounded frame/buffer growth. Overflow fails explicitly and cancels the source; it never silently acknowledges a dropped event. HTTP status/code/Retry-After and TLS/timeout failures remain safe metadata without bodies or low-level exception strings. No transport-owned cursor is advanced.
- The [pinned reader](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp-sse/src/main/kotlin/okhttp3/sse/internal/ServerSentEventReader.kt) carries the preceding event ID into unnumbered frames. Decode the direct `forced_logout` control shape before persistent-envelope ID matching. An unterminated data line at EOF can raise `IllegalArgumentException` in that reader; classify that incomplete transport as reconnectable, not a delivered frame.
- Persistent envelopes require a numeric frame position (see below), a positive envelope ID, matching types, valid nullable room/actor IDs and an offset-aware timestamp. Known room/global event scopes are checked; additive unknown types remain explicit events for safe consumer invalidation. Payload-specific application/reduction and cursor acknowledgment remain the consumer's responsibility.
- Actual [RoomListSignal](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Room/RoomListSignal.php) supplies PHP `[]`, serialized as an empty JSON array, despite the conceptual object in documentation. Both empty representations are accepted for `rooms_changed`; nonempty arrays are not arbitrary payload objects.
- Current persistent forced-logout publishers include the newly assigned `session_version` (for example [AdminService](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Admin/AdminService.php#L228)). It invalidates older versions; replay must not sign out a freshly authenticated version equal to or newer than it. Missing version remains fail-safe invalidation. The stream's unnumbered invalidation is always immediate. Both use the existing generation/vault/runtime clearing path; HTTP401 still uses the ordinary global invalidation path.
- Presence uses existing CSRF `POST presence/heartbeat.php` and `GET rooms/presence.php?room_id=...`, not a guessed presence-list route. Required typed status/user fields include the actual `guest` flag. UUID normalization/versions/variant, room/expiry/idle/warning consistency, configured lease bounds and unique aggregate users are validated. No arbitrary100-user cap is invented. [PresenceService](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Presence/PresenceService.php), [configuration](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Config.php#L72).

## Consumer requirements retained for M3c2

One process-owned connection for the active authenticated generation; no screen-owned duplicate or permanent background service. Stop on background/account/server invalidation, reconcile on foreground and maintain only a generation-scoped cursor until transactional encrypted content storage actually exists. A failed reduction must not advance it. IDs have legal gaps; event IDs are not message IDs. Retention/permission filtering prevents replay from replacing authoritative refresh.

Presence is not membership. Use a stable generation-bound connection UUID and serialize/coalesce its room writes. Passive renewals, draft typing, scrolling, realtime traffic and returning from background must not invent deliberate interaction. The browser's deliberate sources are explicit room selection and successful send/ping; an explicit Stay/Return action is also appropriate on Android. On `expired:true`, the server returns `room_id:null` and idle0: stop renewing the old room until explicit reentry. Sending that room again automatically would reenter/reset idle because the server considers it a room change. Background null cleanup is best effort, never credential retention or a foreground/background blocker; protect new room state from late cleanup callbacks. Refetch aggregate presence instead of inferring it from invalidation events. See [browser presence](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/assets/js/presence.js).

M3c2 must integrate visible live/reconnecting/offline/recovery states, authoritative mutation/history reconciliation, send/event convergence, directory invalidation, presence warnings/expiry/reentry and broadcast presentation. Typing remains M4. The live two-account/lifecycle acceptance gate is still open; no account credentials were displayed or consumed by these ordinary fixture tests.

## Verification status

An early debug/unit command compiled successfully but passed224/225 units: the new unfinished-frame test exposed the reader exception classification. The adapter and regression were adjusted; this failed6m22s run is not a successful milestone gate. The corrected focused nine-test SSE HTTP suite plus debug assembly passed in19s.

The complete frozen-source command `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest --console=plain --max-workers=2` then passed in5m12s (129 tasks:76 executed,11 cached,42 up-to-date). All225 fixture units passed, zero failures/errors/skips; lint reported no issues. Debug, test and shrunk unsigned-release APKs built. Native libraries were packaged unchanged when symbol stripping was unavailable; SDK metadata-version warning did not fail the build. No tool/security suppression was added.

Coverage includes real local TLS HTTP/SSE fixtures, framing/comments/CRLF/multiline, cookie/cursor/CSRF separation, truncation, bounded bodies, status/rate/redirect/MIME errors, cancellation and shared-client reuse, both logout shapes/old versions and vault clearing, failed consumer/replay, presence UUID/status/list validation and policy errors without POST replay. All three new Android TLS/SSE fixture tests then actually passed on the owned API36 emulator at320×640dp/font100% in37s, zero failures/errors/skips. Full current-head hosted/native regression suites and completed Claude review/re-review remain required before merge, not inferred from the focused pass. No live SSE connection, room heartbeat, message, read cursor or membership was changed for this prerequisite.

## Settled resume (v3.10.0)

The minimum backend is v3.10.0, so the stream is always opened as `GET events/stream.php?resume=settled`
([contract](https://github.com/Thiesi/ChitChat/blob/b2791a0/docs/api/realtime.md), ChitChat#286). In the default
mode a lower event ID that commits after a higher one was streamed is skipped; this mode never skips it.

- **Position, not identity.** The decoder reads the frame `id:` as a non-negative position (`[0-9]{1,19}`) and
  never compares it with `data.id`, which stays the event's identity (still positive and strictly checked).
  `RealtimeEvent.position` carries it. Control frames (`forced_logout`, `error`) keep their unnumbered shapes.
- **What is stored.** The coordinator stores only the last frame position, as `max(cursor, position)`, so it never
  moves back, even when a new connection's first frames carry a lower position. It is sent back as
  `Last-Event-ID`. The first connection is unchanged: a fresh runtime sends `Last-Event-ID: 0`, exactly as before.
  A received position of 0 is stored and sent like any other. The cursor lives only in memory per runtime, so an
  app update starts fresh and the default-to-settled switch-over (realtime.md:56) never applies.
- **No duplicates, no losses.** A reconnect may resend events above the position. They are dropped by `data.id`
  through a seen-set of the IDs processed above the stored position: at most 1,000, oldest first (like the web
  client's `realtime-seen.js`), pruned once the position passes them. The old "ID at or below the cursor" drop is
  gone, because it discarded exactly the late commits this mode delivers.
- **Overflow is harmless.** If more than 1,000 unsettled IDs pile up, the oldest is forgotten and a resend of it
  could pass. Message events only invalidate (a coalesced canonical GET, idempotent by message ID), typing and
  presence duplicates only refresh state that expires or is re-read, and broadcasts are listed once per event ID.
- **Protocol break.** A position lower than the previous frame within one connection is a server contract
  violation. It throws `INCOMPATIBLE` through the existing incompatible-frame path: the connection stops in
  `RealtimePhase.ERROR`, the status slot shows its existing incompatible row, and nothing reconnects until the
  person chooses Retry. There is no fast reconnect loop.
- **Bare `id:` lines** are optional per the contract; OkHttp doesn't surface them, and the next event's `id:`
  carries the position.
- **Known, unchanged: backlog replay on a fresh runtime.** `Last-Event-ID: 0` means "after 0", so a fresh session
  receives the retained events (`realtime_event_retention_hours`), bounded by current membership (#276); typing
  expires. They only invalidate canonical reads. Whether a fresh runtime should omit the header is a separate
  decision.
- **Not adopted:** the room list's `unread_settled_message_id` and `unread_counted_ids`. The client doesn't count
  live unread messages; it reconciles by GET.
