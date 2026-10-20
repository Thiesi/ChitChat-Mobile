# M3b1: room conversation protocol prerequisite

This buildable prerequisite supplies the existing HTTP contracts for the dependent native conversation screen. It introduces no alternate authentication, guessed endpoint, realtime connection, persistent content cache or push implementation. The complete protocol and UI diffs are reviewed separately within the Claude input budget.

## Verified server contracts

The audited backend remains v3.2.1, commit `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`; its remote main was compared again before implementation. The sibling checkout is read-only. Source locations below are relative to that [snapshot](https://github.com/Thiesi/ChitChat/tree/4f53ff497f5572072fddad42d8ac3979e9cdf0d1):

| Contract | Authoritative source and client treatment |
|---|---|
| `GET rooms/messages.php?room_id=42&limit=50&before_id=80` | `public/api/v1/rooms/messages.php`, `src/Room/MessageService.php:52`: ascending IDs, exclusive older boundary, limit1–100, no server `has_more`. Raw page boundaries survive filtering/enrichment; an empty display page does not imply the raw history ended |
| `GET rooms/message-mutations.php?room_id=42&message_ids=…` | Endpoint and `src/Room/RoomMessageMutationService.php`: current body/edit/delete/reaction metadata for at most100 exact authorized IDs. Missing exact IDs are unavailable, not inferred tombstones. Deleted content/reply/file details are cleared; retained reactions remain |
| `POST rooms/send.php` | Endpoint, `MessageService.php`, `src/Realtime/PingCommand.php`:201 with exactly one `message` or private `ping`. PHP ASCII trimming and Unicode code-point limits; normal text and `/me`1–4000, `/ping` optional message at most500. No idempotency key or automatic replay |
| `GET rooms/pings.php?room_id=42` | Endpoint and `src/Realtime/PingService.php`: up to100 private sent/received pings, oldest-to-newest. Ping IDs are not message IDs and are not room transcript rows |
| `POST rooms/read.php` | Endpoint and `src/Room/RoomReadService.php`: monotonic membership-bound read cursor, clamped to current room latest; successful cursor can be0 or already advanced by another device |
| Bootstrap/list context | `public/api/v1/session.php`, `src/Account/DisplayPreferenceService.php`, `src/Moderation/MuteService.php`, `src/Room/RoomService.php`: fresh ignore IDs, global/room mute, date/clock preferences and list-only unread fields. Private-room invitation alone does not authorize history; `RoomAuthorization.php` remains final |

`SessionConversationRepository` reuses generation-bound `SessionAccess` and `SessionHttp`, including origin-bound cookies, CSRF rotation, response bounds, TLS verification,401 invalidation and resource-local403 errors. Context first revalidates the session and obtains fresh ignore/mute/display policy; missing policy fails explicitly rather than briefly exposing ignored messages. Every response is identity/generation fenced. Models have no generated sensitive-content string representation. No response/body logging or disk cache is added.

History validates requested room, ascending unique IDs, exclusive boundary and exact enrichment membership. Send validates status, envelope kind, room and actor. PDO timestamps such as `2026-07-17 08:00:00+00` and RFC3339 ping timestamps are parsed without guessing a missing timezone. Read responses accept the actual server's zero/already-advanced values. No backend modification or issue is necessary for these supported routes.

## Verification boundary

The isolated prerequisite build, fixture counts and lint/release results will be recorded here after execution. The native consumer's tests are separate evidence, not an isolated protocol result. Normal/hosted tests use fixtures and exclude the opt-in live suite. No `.env.integration` is copied to a worktree.

This prerequisite does not establish live message delivery, native UI acceptance, foreground SSE/presence, guest visitation, signed release or complete M3 parity. Message-send/read tests against a live account require explicit approval and a dedicated room; no ordinary-room mutation is authorized by merely configuring credentials.
