# M5g3f: final upload-byte evidence

This prerequisite binds SHA-256, byte count and frozen display/type hints to the
same immutable bytes used by the existing one-shot multipart upload. It adds no
picker, native attachment card, send consent, history lookup or automatic retry.

## Boundary

`PreparedAttachmentPayload.create` accepts **already final bytes**. The future
preparation consumer must first run the delivered photo cleaner or obtain the
original explicit Send anyway choice after failure. This factory does not classify
photos, clean metadata, grant fallback consent or turn a raw picked snapshot into
a privacy-approved photo. That orchestration and its original-selection fences
remain pending. Non-image final bytes remain unchanged.

The factory retains only eventual immutable bytes, filename, nullable MIME hint,
size and digest. It holds no provider opener, URI, grant, raw-photo snapshot,
caption, session or recovery choice. Private construction prevents a caller from
supplying a digest that belongs to different bytes. Its default debug string
contains neither bytes, filename nor digest; these values must never be logged,
persisted or put in saved state.

Hashing runs on `Dispatchers.Default` in one bounded pass with at most8192-byte
transient copies and cancellation checks between chunks and before publication.
It never flattens a large segmented snapshot in full or reopens its provider.
Empty/over-budget input keeps the existing local EMPTY/CLIENT_BUDGET failure kinds.
The32MiB engineering ceiling is not a server limit or an aggregate memory budget.
The later attachment owner must reserve/release actual retained and preparing
memory; this helper does not solve that requirement.

`AttachmentUpload` consumes that payload and exposes its exact digest and size.
The body writes the same immutable bytes, preserving the existing claim-before-
enqueue and write-once rules. Caption PHP trimming, reply IDs, original-session
transport, CSRF, literal multipart fields and ACK-before-refresh stay intact.
The existing repository checks server size/type policy against the eventual
payload. Missing/unknown MIME and octet-stream receive no invented type precheck.
No digest is submitted as an invented API field or used to deduplicate a send.

Later Check history may use the digest only as evidence among the viewer's own
messages after the attempt start, requiring the person's confirmation. A match
never resolves uncertainty or authorizes another POST automatically.

## Verification

Eight focused factory tests passed with Kotlin2.3.21/JDK17, two processors, no
Gradle, emulator, live session or full suite. They cover a known SHA-256 vector,
segmented binary input across multiple chunks, byte-versus-display-hint changes,
unknown type hints, typed local preparation failures, content-safe errors/debug
strings and cancellation before result delivery. Source/JAR/log/dependency hashes
were archived with byte/CRC verification; all eight fixed scratch files were
removed. Focused archive SHA256:
`93c473a89667a272df2daea75e9be6165095da12e62367756e81f2bd70b65313`.

The ordinary JVM suite additionally binds a real JPEG-container cleanup result to
the exact upload body and verifies that its digest differs from the picked original.
The existing one-shot, HTTP/session-policy and late-sign-out tests consume the new
payload type. Those integration checks, full builds/lint/native gates and both
written current-head reviews remain pending until recorded on the delivery PR.
No physical-device, live/provider, native attachment, aggregate-memory or complete
P-157/files-media acceptance follows from the focused factory pass.
