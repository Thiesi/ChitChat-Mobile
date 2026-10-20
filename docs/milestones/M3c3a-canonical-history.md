# M3c3a: bounded canonical room-history reads

This is the independently buildable protocol prerequisite for M3c3b's room/directory realtime and presence consumer. It adds a real typed `ConversationOperations.window` operation over existing history/mutation endpoints, not a new server API, cache or production fake. Current room UI remains explicit manual-refresh until its consumer ships. The foreground session/status/announcement runtime from PR19 is unchanged. Splitting this reader and its focused fixtures keeps the complete consumer review under the126000-byte input gate; the dependent UI is already in progress, not silently omitted.

## Verified contract and policy

Backend baseline remains [v3.2.1 /4f53ff4](https://github.com/Thiesi/ChitChat/tree/4f53ff497f5572072fddad42d8ac3979e9cdf0d1). Source was checked read-only, with graph coverage at generation2026-10-17T18:56:58Z; no backend checkout fetch/reindex/write occurred.

- [MessageService history](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Room/MessageService.php#L52) accepts exclusive `before_id`, limit1–100, and returns ascending IDs after a descending SQL selection. There is no server `has_more`, after-ID, around-message or snapshot field. Existing `SessionHttp.roomHistory` derives raw boundaries/count evidence before exact-ID mutation enrichment can omit rows during retention races.
- Fresh history also hydrates [attachments and off-window quoted targets](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Room/MessageService.php#L549). [Mutation metadata](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Room/RoomMessageMutationService.php#L45) alone preserves old attachment/reply objects and cannot refresh those quotes. Deleted targets become unavailable/tombstones as the existing browser model specifies; no text-placeholder guessing.
- `WindowSnapshot` has only client-derived canonical messages, coverage, pagination boundary and older-availability evidence. It is not a server snapshot/watermark guarantee or a generated content-bearing string.
- Query a captured older range directly with `before_id=upperInclusive+1`, not by traversing potentially millions of newer messages. At `Long.MAX_VALUE`, no before-ID is needed to include everything at/below that maximum. An absent upper bound starts at newest. Follow strictly decreasing raw cursors, even through entirely omitted enriched pages, at most six requests/pages with limit100 (600 raw rows maximum). Validate room/message/order/cursor consistency and cancellation.
- Keep only rows within the captured inclusive bounds. Coverage requires crossing the captured lower boundary or actual raw exhaustion; exhaustion before an old boundary still proves retained rows disappeared. An incomplete result is explicit, never stitched to cached rows. The displayed range keeps its captured lower pagination boundary, not a surviving enriched ID or a below-boundary raw row. Raw rows below that boundary imply Load older remains possible even when the final raw page was short.
- Production captures **one** existing `SessionAccess`/authorized transport for the entire traversal and verifies it before/after every page. It cannot reopen a replacement account's capability mid-read. No writes, automatic retries, cookie jar or credential copy was added. The interface's default is the same real bounded reader over `history`, enabling ordinary test operations without a fake production fallback.

M3c3b must adopt only proven coverage, preserve raw cursor/anchor/draft/unknown-send/read state, queue invalidations behind user operations, refresh personalized reactions/quoted metadata canonically and expose bounded gap/retention/error states. It must not cancel/replay send/read POSTs for event GETs. An incomplete live-edge result cannot reuse the old lower cursor: keep the old window visibly stale or perform an explicit latest-page reset with its valid raw boundary. See [consumer constraints](../implementation-plan.md#m3c3-canonical-room-consumer-constraints).

## Verification

Thirteen new tests cover fixed older access, raw omitted-page progress, inclusive filtering/exhaustion/retention,6-page bound, MAX overflow, schema/cursor errors and cancellation; three are real local TLS HTTP fixtures covering fresh off-window quote edit/delete/removal and file metadata, whole omitted-page enrichment, and logout during enrichment with no stale result/next page. They use no live account/credentials.

The independent frozen prerequisite passed this command in4m41s (129 tasks:82 executed,47 from cache):

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest --console=plain --max-workers=2
```

All265 JVM tests passed with zero failures/errors/skips; lint reported `No issues found.` Debug, test APK and shrunk unsigned release compiled. The local unsigned release SHA256 was `4E4B72556EACBBE48B510961C4BC756047B5F8CDB71C98F87E1335FE432CFE38`; this is not a signed/public release or a reproducibility claim.

All61 current native tests passed on the owned API36 emulator at320x640dp/font100% in2m45s, zero failures/skips (`:app:connectedDebugAndroidTest --console=plain --max-workers=2`). These are the unchanged production UI/lifecycle regressions; this protocol prerequisite adds no new native screen. Result XML is preserved separately before dependent consumer testing.

Hosted checks and actual current-head Claude reviews must pass before merge. No live two-account, native room-sync/presence, physical-device, API26 or TalkBack acceptance is claimed by this prerequisite. Backend/source ownership and decentralized push decisions remain unchanged.
