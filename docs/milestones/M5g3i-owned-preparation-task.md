# M5g3i: owned attachment preparation task

This prerequisite connects the one-shot picked read, classification/cleaners,
final-byte evidence and shared allocation ledger in one resource-owning task. It
does not yet connect the native picker, room/DM draft actor or process-wide device
capacity. It creates no upload, privacy-bypass consent or usable file control.

## Resource and adoption contract

- A task claims one frozen source once. It reserves before provider open; denial
  produces local CLIENT_BUDGET with no read or automatic retry.
- Its working charge is twice the frozen per-file read ceiling plus4MiB for bounded
  parser/hash/segment workspace. It never prices memory from an advertised provider
  size or a server limit. The caller chooses its read ceiling within the existing
  32MiB cap and a conservative process/device budget.
- Read and cleaning use the existing bounded helpers, exact snapshot and final
  hash. Supported cleaning never silently falls through to unchanged bytes.
  Unsupported/failed photos retain the original NeedsChoice and approved reason.
- After those producer children end, the task retains rounded source AND output
  allocation charges plus1MiB padding for backing-array headers/directories and
  bounded chunk/segment tables. Keeping source cost covers shared backing arrays;
  cleaned logical size alone is insufficient. This is conservative client accounting,
  not a measurement or universal guarantee of actual JVM/Android heap usage.
- Delivery is one synchronous Main-thread callback with the **original task object**.
  The feature owner must check its actual entry/session/selection and atomically
  retain the handle before returning true. False rejects and discards it. There is
  no suspension after that transfer. A throwing callback releases the handle and
  never receives a second fallback outcome.
- Cancellation before worker entry allocates/opens nothing. Cancellation during a
  blocking read retains the reservation until the actual worker returns and closes.
  No close runs merely because Job.cancel was requested. Cancellation propagates;
  it never creates a late ready/failure callback or automatically opens again.
- Unadopted/failed work releases its charge. An adopted handle clears its own
  preparation reference and releases idempotently when its owner closes it. The
  owner must keep it alive for every draft/worker/upload using the bytes, and must
  not retain byte references after closing it.

Only the numeric ledger is shared across session retirement; private handles remain
feature-owned. Root/room/DM integration must still bind the real entry and original
session, preserve the existing100-draft rule and POST serialization/uncertainty, and
handle true background versus configuration/navigation. No source URI/grant or
content is saved. A closed handle is not proof that a transport writer ended.
Fresh original-bound Send anyway and full-multipart cancellation/non-delivery proof
remain separate consumers; this task never supplies an unchanged ready photo.

## Verification

Eleven focused JVM cases passed (Kotlin2.3.21/JDK17, two processors, no Gradle,
emulator/native/live invocation). They cover adopted final bytes, cleaned-photo
digest/privacy, both photo-choice reasons, rejection, safe local failures,
pre-open budget denial/no replay, pre-entry cancellation, a real held InputStream
through cancellation/close, throwing adoption and an already retired caller scope.
The held read proves reservation lifetime for that bounded fixture; it is not SAF,
arbitrary provider or OkHttp termination acceptance. The PNG is a structural fixture,
not a new codec/pixel test. No native input assertion, deadline or workflow changed.

The initial10 cases passed before retained padding was raised from64KiB to1MiB;
the same10 passed with the more conservative padding. Adding cleaned-photo adoption
then passed11. All three source/JAR/log/dependency archives were byte/CRC verified
and all16 fixed scratch files per invocation removed. Current archive SHA256:
`c91fe63f9dc3f088369e59ea4b5928f3f9ac220516ad012c47d0a6ddc5448510`.
Earlier observations remain separate, not proof of the later source.

Current-head full JVM/lint/build/unfiltered native gates and both actual written
reviews remain required and will be recorded on the PR. Native draft/provider/live,
aggregate heap profiling, device, complete P-157 and release acceptance are pending.
