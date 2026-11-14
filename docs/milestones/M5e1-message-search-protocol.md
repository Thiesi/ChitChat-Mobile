# M5e1: participant message-search protocol

This independently buildable prerequisite supplies typed participant search through the existing session transport. Native search controls, retained query/page state and exact-result navigation follow separately. In-app search availability remains unchanged because there is no usable search screen yet.

## Contract and boundaries

The pinned [search contract](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/docs/api/message-search.md), [endpoint](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/search/messages.php) and [service](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Search/MessageSearchService.php) define CSRF-protected POST search/messages.php. An immutable request freezes query, scope (all/rooms/direct), limit1..50 and offset0..5000 before any access wait. Terms stay exclusively in JSON, never the URL, logs, saved state or a client cache. The server owns whitespace normalization, Unicode2..200/letter-or-number validation and websearch syntax; the client sends the original query rather than guessing equivalent normalization or search grammar.

The server filters room discoverability/current membership/invitations/minimum age and DM participation before returning current, nondeleted canonical bodies. This is not administrative revision/DM inspection. Native code never expands search authorization, infers a membership from a result or treats room ignore as DM block. Member-only openAccess(expected) rejects guest/anonymous/retired sessions; server-side authorization remains authoritative.

Results are namespace-specific hints, not transcript rows. Room senders can be null after identity removal. DM sender/peer/outgoing are checked against the original viewer; room/DM-only fields and requested scope must agree. Preserve relevance order, distinct (kind,message_id) identities, plain-text excerpts (at most320 Unicode code points), dates, server has_more and the exact progressing next_offset. Identical numeric IDs in different namespaces are valid. No markup parser, HTML/highlight rendering, link activation, notification/read/send call or latest-window fallback is added.

Offset pages are not a stable snapshot. A full page at offset5000 can report another offset beyond the request ceiling: retain that truthful hasMore/nextOffset but expose canLoadMore=false. A future UI must explain the bound rather than issue an invalid request, clamp/repeat offsets or imply global exhaustion. Concurrent edits/deletions can move results across separate requests; future state must not assume cursor continuity.

## Session and privacy

SessionMessageSearchRepository uses the original rendered SessionState with openAccess and authorized across every await. Queries/excerpts/room/user names have no generated field-bearing debug string or persistence. Reuse origin-bound cookie/CSRF, TLS/redirect restrictions, cancellation, shared cooldown and safe typed errors. Search keeps the ordinary1MiB response/error ceiling and25-second timeout. It consumes a rate-limited POST: no automatic replay, bootstrap retry or passive search on resume is introduced.

A future consumer must scope query/results by server/account/generation/version and retire them on genuine background/owner retirement. Opening a hit requires fresh exact-ID authorization through the existing target actor and original navigation/request guards; excerpts are never canonical target adoption or viewport read proof. Search supplies no uncertain-send consent.

## Verification and delivery

The new contract/TLS tests cover literal Unicode/quoted queries in POST bodies, scope/page limits, nullable senders, DM perspective, namespace identity/relevance, Unicode excerpt bounds, strict booleans/required fields, malformed responses, offset ceilings, safe debug strings, CSRF/cookies, refusal/rate limiting/redirects, cancellation and stale identities. Exact completed commands/counts and build/lint/CI/review outcomes are recorded on the delivery PR after execution.

Local verification passed22 new search tests, then the fresh full900-JVM suite with0 failures/errors/skips, lint0, debug/release, app test and opt-in standalone APK builds. All160 tasks executed in345s; signed APK certificates were verified. The complete command was :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest -PfocusDiagnostic=true :focus-diagnostic:assembleDebug --rerun-tasks, using the checked-in wrapper and configured JDK17/SDK36. The first focused run's two fixture mistakes and the initial missing-opt-in task-selection failure remain archived; neither required a production change. Credential/device-free CI-wrapper tests21/21 and208 local documentation links passed. Hosted full native CI and written current-head initial/fresh review remain required.

No native input helper, test deadline, production composer, dependency or ordinary CI filter is changed. Existing186 native regressions remain enabled. No local native/physical-device/live-account/API26/accessibility/release acceptance is claimed. PR76's explicit one-head development exception does not apply to this or future heads; issue69's failures/consumed diagnostic and PR71's hold remain preserved.

## Match ranges and room filter (v3.10.0, #283)

From the [v3.10.0 contract](https://github.com/Thiesi/ChitChat/blob/b2791a0/docs/api/message-search.md):

- **Excerpt.** A message longer than 320 characters now comes back as a window around its first match, with
  `… ` and ` …` where it was cut. It is still plain text, at most 320 code points, and the client renders it as
  given, ellipses included.
- **`excerpt_matches`.** A list of `{start, length}` in UTF-16 code units, relative to the excerpt as sent.
  `ExcerptMatch` carries each range on `MessageSearchHit.excerptMatches`.
  - **All or nothing per row.** The ranges are kept only if every one is a pair of JSON integers, has length ≥ 1,
    lies inside the excerpt, comes in order, doesn't overlap the previous one and doesn't split a surrogate pair.
    Otherwise that row keeps none of them and shows plain text.
  - **The drop is silent on purpose.** The ranges are a presentation hint, so a malformed, mistyped, non-array or
    absent field never fails the search or the row. The rest of the response is validated strictly as before.
    Ranges are never repaired or clipped.
- **Highlighting** (presentation lead, 2026-11-14). Each match gets a `presenceWash` background plus SemiBold:
  two cues, never colour alone, and no underline, because underline means "link". The row's text keeps 4.5:1 on
  `presenceWash` in light, dark and pure black (`LanternTest`). TalkBack reads the excerpt as plain text with no
  added speech.
- **`room_id`** (protocol only, no native entry point yet). `MessageSearchRequest.roomId` is sent as `room_id` only
  when set.
  - Locally, a non-positive ID, or Direct scope with a room, is a `400 validation_error` that never reaches the
    server.
  - A room the caller can't search answers `404 room_not_found`, which is kept as that typed `ApiFailure`.
  - A filtered response must contain only that room's messages; anything else is `INCOMPATIBLE`.
  - A "Search in this room" entry from the room details sheet needs its own small design (presentation lead) and
    follows separately.

