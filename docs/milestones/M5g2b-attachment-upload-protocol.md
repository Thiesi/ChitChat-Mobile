# M5g2b: explicit attachment upload protocol

Status: internal protocol prerequisite. No picker, provider read, photo cleaning,
draft, progress, cancellation UI, download, image or sharing control is added.
Existing in-app files/media availability disclosures remain accurate.

Use the released v3.4.0 [room](https://github.com/Thiesi/ChitChat/blob/v3.4.0/docs/api/attachments.md)
and [DM](https://github.com/Thiesi/ChitChat/blob/v3.4.0/docs/api/direct-message-attachments.md)
contracts. Explicit calls require original rendered SessionState/SessionAccess,
chosen-origin client, PHP cookie jar and CSRF. Retirement cancels calls and rejects
late results. No independent session, additional bootstrap or enrichment GET.

AttachmentUpload holds immutable in-memory ByteString bytes, a bounded basename,
declared MIME hint, PHP-trimmed optional caption (at most4000 Unicode code points)
and original reply ID. Empty captions stay empty: the server chooses the filename
body. /ping remains a literal attachment caption. No URI/provider is reopened.
The later preparation consumer must bound memory, remove photo location from the
actual uploaded bytes (or obtain explicit Send anyway consent after failure),
and supply exactly those frozen bytes. This constructor does not prove photo
privacy. Disk-backed sources need their own bounded immutable-source lifecycle
before native use. No payload enters saved state or generated string output.

The attempt is claimed once before enqueue and its body is one-shot. Reusing that
reference on either route cannot POST again, even after failure before writing.
Multipart fields are room_id or recipient_user_id, caption, optional
reply_to_message_id and file. No redirects or network retries. The derived client
shares the runtime's pool, dispatcher, jar and cancellation, with a bounded
120-second total/write budget and unchanged10-second connect/20-second read.
Native deadlines are unchanged. JSON responses/errors retain the common1MiB bound
and M5g2a metadata, including proxy HTML413 and JSON429 hints. No error is retried.

Known Unavailable/Invalid/zero policies and known size/type violations fail locally
without spending an upload attempt or invalidating the session. Unknown implies
no invented limit/type list. Missing/octet-stream MIME leaves detection to the
server. Policy facts grant no write permission. Server membership, age, mute,
DM privacy and block checks still win. The future room/DM actor must serialize
uploads with other mutations, bind original entry/reply/canonical callbacks and
preserve read/typing and unknown-send contracts. No user action is wired here.

Validated201 returns namespace-specific confirmed IDs before any fallible
enrichment GET. Check positive ID, original room/viewer or DM sender/peer,
outgoing/attachment flags and exact reply ID/kind. Optional text, reactions,
attachment metadata and previews cannot hide the POST acknowledgment. DM upload
has no inline attachment metadata; later canonical hydration uses its existing
bounded metadata GET. A receipt grants no read, typing, history-adoption or
second-send consent. Invalid acknowledgment identity remains uncertain.

There is no idempotency key. Cancellation, network errors, malformed ACKs and
commit-before-response failures prove no non-delivery. Future state must retain
uncertainty, reconcile explicitly and require a fresh duplicate-risk choice
before constructing a new attempt. No outcome state or cancellation proof here.

Credential-free TLS fixtures cover multipart fields/bytes/cookies, literal/empty
captions, ACK before optional enrichment, namespace/identity/reply rejection,
refusal hints, no redirect/replay, one-shot writes, policy prechecks and original/
retired session fences. Current-head JVM/lint/debug/release, unfiltered native CI
and both written reviews remain required. Mock HTTP is not live upload, native/
provider/photo, cancellation/reconciliation, device or release acceptance.
