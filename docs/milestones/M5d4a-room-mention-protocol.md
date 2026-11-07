# M5d4a: original-session room mention candidate protocol

Consumes existing backend v3.2.1/main4f53ff497f5572072fddad42d8ac3979e9cdf0d1 after delivered native profiles PR83/mainbf443d0. This is protocol foundation, not native autocomplete, IRC Tab completion, a draft change, mention resolution or notification delivery.

## Existing API and bounds

`GET rooms/mentionable-users.php?room_id=...&search=...&limit=8` requires an authenticated member and server-authorized room history. The existing endpoint consumes the room_mentionable_user_search rate policy. Prefix normalization uses shared literal PHP trim; empty is allowed, nonempty has only ASCII letters/digits/underscore/dot/hyphen and at most32 characters. Room IDs are positive. The native protocol deliberately requests8, within the server's1..25 range.

The existing SessionHttp supplies the original chosen HTTPS origin, current cookie, GET/no mutation-CSRF header and ordinary1MiB/25-second bounds. There is no second transport, cache, broad user search or automatic request/retry. Repository arguments are frozen before openAccess(expected); authorized(access) fences the same runtime before/after HTTP. Each result retains the ORIGINAL request reference and canonical prefix; future UI still must fence its original owner/entry/draft/selection after every await and at consumption.

Required users/id/username fields, positive distinct non-self IDs, account-name bounds and the complete8-result cap are checked. Empty arrays remain valid hints; malformed/null data is never an empty policy. Preserve server ordering and the full bounded list. Names/prefixes never acquire a generated private-data string or enter logs/saved state/disk. Unknown enrichment is ignored, never copied into chat/ignore/typing state or permission.

## Candidate meaning and compatibility

[RoomRepository.searchMembers](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Room/RoomRepository.php) returns active member-account rows in the room, excludes the actor and orders by lower(username),id. These are suggestions, not complete membership, active presence, send/read/typing permission, duplicate-risk consent or a promise that sending will notify someone. The server resolves and authorizes actual mentions at send time.

The current SQL LIKE pattern does not escape accepted underscores. A returned name therefore need not literally start with the prefix; do not reject an otherwise-valid authorized response on that assumption. [Backend issue152](https://github.com/Thiesi/ChitChat/issues/152) records the limited-result matching/UX defect. No backend checkout/patch or live query was made; this does not block the compatible native GET.

Browser room autocomplete additionally supplies reserved room/here keywords with NULL account IDs. They are not user rows and will require separate native choices, never invented IDs or a guessed online-only meaning. DM autocomplete uses only the original selected peer locally, never this room endpoint or global user search. Native token/caret/draft/state/keyboard integration and IRC-style completion follow separately, keeping current feature copy explicit that completion is pending.

## Validation and gate boundary

Twelve schema/normalization/privacy cases and15 real-TLS/session cases cover exact cookie/origin/room/query/reference, PHP trim, malformed/self/duplicate/oversized replies, original owner/version/logout/cancellation, typed errors/cooldown/no replay and existing underscore behavior. Focused JVM evidence is not a full native or live gate.

All48 existing native source files, CI/input helpers and UI/composer/IME/deadline contracts remain unchanged. Expected complete totals are1043 JVM and215 ordinary unfiltered/untraced native, plus lint/debug/release/R8/test/standalone builds, original certificates and both actual written current-head Claude review rounds. Archive all source/APKs/reports before prompt generated cleanup.

PR83's own a6b84ab215/215 pass and independent mainbf443d0's later214/215 search-to-DM first-composer IME failure remain separate records in issue69. The control/new profile cases passed; cause is unresolved and the failed main run is not replayed or called fixed. This actual new protocol head needs its own complete gates. No inherited ceefb8a/4a808b9 exception, input repair, trace or deadline/predicate change is granted. Guest/native UI/live/device/accessibility/release acceptance remains separate.
