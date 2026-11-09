# M5g5a: multipart progress and actual transport termination

Internal transport prerequisite for the following room/DM serialized upload owners.
The existing original-session endpoints, policy checks, receipts and private draft
rules remain authoritative. No native file control, upload actor, background queue
or retry is added. Files/media is still not usable.

## One attempt, distinct facts

AttachmentUpload keeps frozen filename/MIME/size/digest/caption/reply metadata.
Its body remains claimed once before enqueue and written once. File bytes are
handed to the existing sink in bounded8192-byte chunks; fileBytesBuffered is local
buffer progress, not server receipt or whole-request completion.

The full MultipartBody is wrapped too, including fields, headers and final
boundary. Its known length is frozen. Before each delegated write, a monotonic
attempted upper bound includes the entire chunk: a write can send some/all bytes
and then throw. A smaller successful lower count could falsely imply non-delivery.
The wrapper emits its buffered tail into the caller's sink without closing it or
adding a flush. Only the actual requestBodyEnd event with the full matching length
reports complete body writing; file progress alone never switches that fact.

Cancellation is another monotonic fact, even if reported after callback completion.
It never reopens a stopped exchange, releases bytes twice, establishes event order
or proves that cancellation prevented delivery. Snapshot strings are opaque;
private per-attempt facts stay out of logs, saved state and global caches. Future
consumers must recheck their original owner/entry/attempt before any adoption.

## Actual stop boundary

A coroutine cancellation requests Call.cancel; it does not synchronously stop the
request writer or response callback. Only upload calls supply the common execute
function's narrow optional completion hook. Its onResponse/onFailure finally marks
the actual asynchronous callback finished, after the non-duplex interceptor chain
unwinds and response handling/closing ends. Other HTTP calls omit this hook and
retain their existing prompt cancellation and response/error semantics.

The upload operation's finally suspends in NonCancellable until BOTH the actual
callback is finished AND its owned body writer has ended. The completion signal is
independent of caller cancellation. No polling, extra deadline, POST retry, global
operation lock or replacement dispatcher/pool/jar is added. Existing120s call/write,
10s connect/20s read and1MiB JSON/error bounds remain unchanged.

The writer marks itself active before accessing the delegate. A completed callback
that wins before entry prevents any later writer/source read. After both stops,
clear the wrapped-body and attempt byte references before signaling completion.
Metadata remains available for uncertainty evidence; neither it nor this boundary
authorizes another send. Event listeners compose with existing listeners and keep
no Call/request reference. callEnd/callFailed/canceled notifications alone are not
the release boundary; notifications can precede writer-stack unwinding.

The future actor must move/hold its retained READY allocation through the entire
operation, including cancellation and retirement. This prerequisite supplies the
actual stop boundary; it does not connect those private allocation owners yet.
No non-delivery UI/outcome is synthesized here. Interruption after full body or a
possibly transmitted final chunk remains uncertain for the later explicit-history/
review/duplicate-risk consumer, never automatic replay. ACK identity validation and
original-session retirement fences still run before any later canonical adoption.

## Pinned primary references

The existing OkHttp5.4.0 [Call listener API](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Call.kt)
adds listeners without replacing the factory. Its
[EventListener contract](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/EventListener.kt)
allows cancellation notifications concurrently and after completion. The pinned
[non-duplex server interceptor](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/CallServerInterceptor.kt)
finishes request-body writing before response handling; the
[async callback contract](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Callback.kt)
warns that failure can follow remote acceptance. No library upgrade or new client
transport is introduced.

## Verification boundaries

Negative first: a real credential-free TLS network interceptor held the actual
request writer after its delegate returned. After cancel plus deterministic test
scheduler drain, the caller incorrectly completed while that writer was held.
The one-case FAILED result is preserved, SHA256
c1130006524373be5fb247c1122e2376f7a75ee3326ea24a9ae628a940074204.
The fixture always releases/joins its worker, no sleep, input action or retry.

A second deterministic negative uses a real unexecuted OkHttp Call's late cancel
event after a modeled callback stop. It exposed a dropped cancellation fact; its
one-case FAILED result is preserved, SHA256
b6704445405a2e5f901cbadd3f88d8483c7c7c11adb11df3bad2aca20049babf.
The correction records the fact without reopening the stopped state or altering
release count. This event-order case performs no network request.

Focused cases cover held-writer cancellation, post-full-body interruption, both
namespaces' actual TLS multipart bytes/count/events, rejection/redirect one-shot
behavior, late original-session ACK rejection, policy checks spending no attempt,
held-writer/callback races, pre-entry retirement, opaque strings and a final write
that consumes all bytes before throwing. One initial focused compile exposed a
test fixture trying to implement sealed BufferedSink; the fixture now uses an
exact-size real segment and ForwardingSink, with production unchanged for that
correction. Its compile-failed archive is retained; its stale prior XML is not
claimed as a new test result. Compilation is distinct from execution.

The final focused run passed16/16 with0 failures/errors/skips:2 real TLS
termination cases,5 exchange cases,6 existing multipart cases and3 existing
original-session TLS cases. It used the checked-in wrapper/JDK17/Kotlin2.3.21,
offline/two processors/workers/shared homes/fixed scratch, with0 native/live
invocations. Source/log/XML archive SHA256/CRC/current Kotlin bytes verified:
10b028bb7154e19f97aeb17963bda2a12b73caba1ebbecfdb3a7daf9c755a6b9.
The earlier focused pass is retained too; it preceded the added late-cancel
regression and is not substituted for this result.

Full current-head JVM/lint/debug/release, original unfiltered native jobs and both
actual written reviews remain required and recorded on the PR. Mock TLS/pure IO
is not live/provider/device/native-media or release acceptance. No native input
assertion, deadline, host, workflow or presentation change is included.
