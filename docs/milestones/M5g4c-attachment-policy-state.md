# M5g4c: final-byte attachment policy observations

This slice shares the delivered upload transport's precheck predicates with room
and DM draft state. It adds display facts, without native file controls, privacy
consent, an upload action or new server authority.

## Current facts and final bytes

The pure observation keeps missing policy, unavailable, invalid and explicit zero
distinct. Missing policy invents no byte limit or type list. A known policy checks
final prepared size with an exact greater-than comparison and keeps the server's
actual limit. Frozen MIME hints are compared case-insensitively with Locale.ROOT;
absent or application/octet-stream MIME leaves detection to the server. Known MIME
hints can be checked before byte readiness, but no size decision uses an original
photo that has not become the eventual payload.

Room/DM queries require fresh actual-entry proof and the current owner, target,
slot and original selection. They freeze current session policy for the result
and use size only for READY preparation. A successful cleaned photo therefore uses
the smaller cleaned bytes. A failed/unsupported photo still awaiting an explicit
choice has no final-size policy observation, even though its original size is
known privately. Policy updates produce fresh query results without changing its
private draft, caption/reply, memory charge or serialized actor/uncertainty state.
Background, session retirement and replaced selections reject the query.

These observations are cached advertised facts, never upload permission, a server
refusal, privacy-bypass consent or duplicate-risk consent. Local CLIENT_BUDGET is
still a device preparation bound, never the server's limit. No observation makes
a network request, refresh, quota attempt or Send available by itself. Existing
text-only Send remains blocked while attached.

The actual original-session upload transport reuses the same predicates at the
call. Its guest check, error meanings, size-before-type order, cookie/CSRF/runtime
fences, one-shot attempt and lack of retries remain unchanged. It does not trust
an earlier observation or another session's policy. A future server refusal still
wins over cached facts.

## Verification and limits

Pure tests cover policy distinctions, zero, exact size/overflow-free Long bounds,
MIME normalization and absent/octet-stream hints. Real room and DM preparation
tests use a structural PNG with removable text: the original is larger than the
server limit while the cleaned final bytes fit. Both owners retain a large raw
GIF choice without misrepresenting its size as an authorized final payload.
They also verify policy changes without private-state/network/send/memory effects
and reject false-entry, wrong-target, background, retired and replaced callbacks.
This PNG is a state/container fixture, not new codec or pixel acceptance.

Focused validation includes the existing TLS fixtures for original runtime/no
implicit bootstrap, local policy prechecks spending no upload attempt, and late
ACK rejection after sign-out. Results and any original negatives are recorded on
the PR. Full current-head JVM/lint/debug/release, unchanged unfiltered native jobs,
product guard and both actual written reviews remain required.

The focused run passed54 JVM cases with no failures/skips:5 observation,17 room
attachment,17 DM attachment,12 preparation-task and3 original-session TLS upload
cases. It used the checked-in wrapper/JDK17/Kotlin2.3.21, offline/two processors
and workers, with no emulator/native/live task. The source/log/XML archive was
SHA256/ZIP-CRC verified before generated-file cleanup; SHA256:
`09b6f41bdd8e04627bc475a3d552a7716d7d8b512d5f1e8e18f02da170c27e90`.

No native input predicate, deadline, host or workflow changes. Original-photo
choice preparation, upload/unknown-delivery reconciliation and actual multipart
termination proof remain following consumers. Native/provider/live/device and
release acceptance remain separate; files/media is not yet usable.
