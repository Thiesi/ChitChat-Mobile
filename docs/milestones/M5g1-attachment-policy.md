# M5g1: advertised attachment policy

Status: protocol/session foundation only. This slice adds no usable upload,
download, image, picker, sharing or attachment-composer control. Existing feature-availability
disclosures stay accurate; native files/media and their own acceptance gates follow separately.

## Contract and scope

Consume only the existing top-level `attachments` field from the ordinary session bootstrap,
originally released after backend #156 and present in the
[minimum ChitChat v3.4.0 contract](https://github.com/Thiesi/ChitChat/blob/v3.4.0/docs/api/attachments.md). Use the existing chosen-origin HTTP client, size bound, session cookie,
runtime fence and refresh operation. Do not add a policy endpoint, request, cache or timer.
The [files/media presentation contract](../design/files-media.md) remains authoritative for
the later native consumer; this prerequisite changes no layout or presentation.

- Missing `attachments` is **Unknown**: no guessed size, MIME or quota defaults. This is a
  wire-state distinction, not an older-server fallback or implementation of the pending
  onboarding/server-update capability check.
- Explicit JSON `null` is **Unavailable**. Signed-out and guest bootstraps never publish a
  member policy, even if a contradictory object was supplied.
- A known object preserves integer `max_bytes` exactly, including zero and limits below
  1 KiB. Zero means uploads are unavailable; it is distinct from an unknown limit or guest
  restriction so the eventual UI can explain it.
- Preserve the allowed and previewable MIME sets, canonicalizing case-insensitive
  type/subtype tokens to lowercase without adding types ([HTTP media types](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.3.1)).
  Previewable types must also be allowed.
  If advertised, preserve the independent positive room/direct quotas as attempt counts and
  window seconds. Absent quota metadata stays unknown; there is no local live quota counter.
- An unusable advertised policy becomes **Invalid**: uploads are disabled without blocking
  the rest of session bootstrap or retaining old known settings. This includes malformed
  valid-JSON values, negative or quoted/fractional/out-of-range integers and invalid MIME
  values. Unknown future fields are ignored. Invalid JSON syntax still rejects the whole
  response through the existing content-safe incompatible-response path; CSRF and other
  required session validation remain unchanged. No diagnostic retains the policy body.

The policy lives only in generation-scoped session memory. The existing bootstrap runtime
fence applies before publication. Refresh clears the old policy while revalidating; a missing
or null successor replaces it. Cancelled refresh, authentication transitions, sign-out,
invalidation and changing the server cannot preserve an obsolete member policy. Nothing is
added to the encrypted vault, saved-instance state, logs or disk.

These are advisory server facts, not room membership, DM availability, a send capability,
read/typing proof or permission to upload. The server still checks actual bytes, detected MIME,
current authorization and quotas on every explicit upload. A future pre-check must use the
post-cleaning bytes actually uploaded; this slice performs no file read or pre-check.

## Verification and remaining work

Focused JVM tests cover missing/null/zero distinctions, actual operator values and independent
attempt quotas, malformed types, the existing single bootstrap GET and safe errors, policy
replacement, authenticated-session survival with an Invalid policy, guest/signed-out
isolation and a late response after session retirement. Invalid is not a guessed limit,
an older-server fallback or permission to upload. Future controls must disable uploads
for Invalid while preserving unrelated rooms/DMs.
Current-head full builds/JVM/lint, original unfiltered native CI and both written reviews
remain delivery gates. Mocked bootstrap tests are not live server or native-media acceptance.

Next reviewable slices supply explicit upload/download transport and bounded refusal metadata,
then scoped pending/uncertain attachment state and its native consumers. Photo location removal,
progress/cancellation proof, explicit history reconciliation and duplicate-risk consent,
memory-only image loading, SAF Save and temporary FileProvider sharing remain unimplemented.
No ambiguous POST replay, authenticated external URL, automatic attachment send or native input
workaround is introduced. Real provider/device/live/accessibility/release acceptance stays separate.
