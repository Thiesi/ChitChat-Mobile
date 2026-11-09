# M5g5b: retained upload allocation ownership

Resource-lifetime prerequisite for serialized room/DM upload state. Files/media
remains unusable: no actor, picker/card, Cancel, reconciliation or Send control is
added. Existing final-byte policy, original-session access and POST semantics bind.

## One allocation, two bounded holds

A private READY draft can hold one transport allocation over that exact preparation
reference. This keeps the same immutable payload and existing conservative memory
reservation, including any backing-allocation overhead. It neither copies bytes nor
reserves another charge. A concurrent transport hold or a different/retired READY
reference is rejected. A hold grants no send, privacy, retry or delivery authority.

The draft and transport hold end independently. Closing the private draft clears
its preparation reference immediately, but cannot release its reservation while
transport still holds the bytes. Closing transport preserves a surviving draft.
Only the last hold releases the reservation; repeated/stale closes cannot release
a successor. Handles have no content-bearing generated strings. A closed transport
allocation exposes no payload through its getter.

Unchosen photos cannot acquire a READY hold. Their existing exclusive takeOriginal
transfer and preparation cancellation protocol remain unchanged. This is a narrow
draft/transport lifetime protocol; thumbnails and other future byte users still
need explicit lifetime coordination.

## Concrete transport consumer

SessionAttachmentRepository has original-session room/direct overloads accepting
the retained handle, exact READY reference, frozen caption and reply. They acquire
the hold synchronously and release it in finally around the existing concrete
upload operation. Construction, access or policy failures close the transport hold
without discarding the draft or spending a network attempt. No coroutine producer
is launched by the overload, so cancellation before function entry creates no hold.

Once HTTP starts, the M5g5a upload-only finally waits for actual callback completion
AND writer end before the concrete operation returns or throws. Consequently the
new finally cannot free the allocation merely because its caller cancelled. No
listener notification, ring percentage or coroutine cancellation is a stop proof.
Original runtime retirement/CSRF/policy/ACK validation, timeouts and single POST
remain unchanged. There is no extra GET, bootstrap, retry or transport replacement.

The following feature owners must still freeze and recheck actual entry, original
session/target/selection/draft slot/attempt and caption/reply before a send or any
adoption. These overloads do not serialize other writes or resolve uncertainty.
ACKs do not clear drafts here; refusals do not synthesize retry consent. Retired
drafts are not resurrected by completion, and outcomes never populate a successor.

## Verification

Negative first: an actual authenticated TLS upload holds its request writer after
delegate writing. The draft closes and the caller cancels while the writer remains
held. The original ownership released its 128-byte reservation to 0, allowing memory
to be reused too early. The one-case failed source/log/XML archive is preserved,
SHA-256 7b3839d79f5f9d534dc6e6e2fd6773aef42193622e95ef63528c939aa7f7d39d.
The fixture always releases/joins the actual writer; no sleep or native input is
used. This demonstrates the ownership gap in the new retained transport seam,
not a user-facing files feature already present in the app.

The focused run passed 31/31, zero failures/errors/skips: 4 allocation cases,
7 original-session/TLS cases, 12 preparation-task cases, 7 original-photo-task cases
and 1 exclusive-transfer case. This includes 8 new JVM cases. Real held room/DM
writers retain their charge through cancellation; ACK/refusal/stale access preserve
the draft; construction/policy failure makes no upload. Pure checks cover aggregate
budget denial, exact original/concurrent-hold rejection, stale closes, closed payload
access and unchosen photos. Wrapper/JDK 17/Kotlin 2.3.21, offline/two CPU/workers/shared
homes/fixed scratch; zero native/live invocations. Source/log/XML CRC/hash/current
Kotlin bytes verified; SHA-256 19e485f046638363a4705e8fd3c2f144c5973ab0b587ef5eea0827c72c3faaeb.

Full current-head JVM/lint/builds, original unfiltered native observations and both
actual written reviews remain required. No native input assertion, deadline,
workflow, behavior/presentation gate, local device/emulator or live-server run is
changed or implied. Actor integration is next: room first, then DM serialization.
