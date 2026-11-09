# M5g3h: attachment memory reservation ledger

This core prerequisite supplies one shared, content-free allocation ledger for the
later picked-file workers, cleaner workspaces and retained attachment drafts. It
does not yet integrate those consumers or choose a device capacity. It adds no
picker, consent, upload, cancellation control or usable native file feature.

## Reservation contract

- A positive, fixed client capacity bounds the sum of its live reservations.
  Reserve before allocating. A rejected reservation or growth changes nothing.
- Each lease can grow atomically when capacity permits, shrink after the real
  memory is released, or close. Exact-capacity allocations are permitted.
- Close is idempotent. A closed lease reports zero, cannot grow again, and a stale
  close cannot release a newer lease's charge. Other leases remain independent.
- Reserve, resize, close and scalar observations share one lock. Capacity checks
  subtract available space before adding charges, so Long arithmetic cannot wrap.
- The ledger stores no files, content, URIs, session identifiers, jobs or callbacks.
  A reservation is allocation accounting, never send, consent or server authority.

The future process owner must share the same ledger across conversation and session
retirement. There is deliberately no global reset or automatic release on coroutine
cancellation/sign-out. Cancellation can precede the actual end of a blocking worker;
its charge must remain until that producer finishes and retained bytes are released.
An old producer releases only its own lease, never a successor's reservation.

## Consumer obligations that remain

The client capacity is separate from the server's advertised upload limit. The
eventual consumer must select a conservative device budget and reserve real costs:
input, possible output, segment/working overhead and concurrently retained drafts
and uploads. It must reserve before reading/cleaning, handle local budget failure
with the existing preparation copy, and keep the lease through actual worker end
and retention. No silent draft eviction or automatic retry follows from denial.

Do not shrink a charge merely to the cleaned ByteString's logical size. A view can
retain larger backing arrays. The pinned okio-jvm3.17.0 SegmentedByteString.substring
implementation copies the outer segment-reference array and shares backing byte
arrays; the cached artifact SHA256 is
`3b13dcc3e1573047c2d507fc4f7fcb0bb0cbcd9d395da21d20e09406e2cd6a73`.
The consumer must retain a conservative source-allocation charge or compact under
an already reserved working budget. This ledger cannot inspect actual heap use or
verify that a caller priced its buffers correctly.

Original-entry/session/owner/selection fences, privacy failure choices, 100-draft
capacity policy and one-use POST serialization remain feature-owned. Full-multipart
writer termination is still needed before a native Cancel can claim non-delivery.
No existing preparation or transport behavior changes in this slice.

## Verification

Nine focused JVM cases passed (Kotlin2.3.21/JDK17, two processors, no Gradle,
emulator, native or live invocation). They cover exact capacity, all-or-nothing
growth, shrink/release, stale closed leases, independent ledgers, Long boundaries,
invalid costs, a cancelled producer held until its actual finally, and competing
preparations. The producer is a deterministic coroutine fixture, not provider or
OkHttp termination proof. No native input assertion, deadline or workflow changed.

Source/JAR/log/dependency archive SHA256:
`0c0eebe6293edda7ed491678b594420fbe736d454f44f84ea8579c1036c13da1`.
All archive bytes/CRC and six fixed scratch files were verified before removal.
Current-head full JVM/lint/debug/release/unfiltered native gates and both actual
written reviews remain required and will be recorded on the PR. This is not native
attachment, aggregate heap profiling, provider/live/device or release acceptance.
