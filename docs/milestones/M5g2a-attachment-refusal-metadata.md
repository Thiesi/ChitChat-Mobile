# M5g2a: bounded upload-refusal metadata

Status: protocol metadata only. No upload/download request, picker, file read,
photo cleaning, attachment draft or native control is added. Existing in-app
files/media availability disclosures remain accurate.

Consume the released v3.4.0 [size-refusal](https://github.com/Thiesi/ChitChat/blob/v3.4.0/docs/api/attachments.md)
and [retry-delay](https://github.com/Thiesi/ChitChat/blob/v3.4.0/docs/operations/rate-limiting.md)
contracts through the existing bounded chosen-origin error path:

- `ApiFailure.maxBytes` is an optional nonnegative actual JSON integer from
  `error.max_bytes`, adopted only for HTTP413. Preserve zero as a known limit,
  distinct from missing/null/invalid metadata. It is advisory for that response,
  not a new global policy, permission to send or a reason to allocate a buffer.
- A non-JSON proxy413 remains an HTTP413 refusal with unknown code/limit, without
  keeping its HTML body or treating it as a successful-response decoding error.
  The future upload consumer must distinguish that definite size refusal from
  ambiguous delivery; this slice creates no upload-outcome state machine.
- An actual positive JSON429 `error.retry_after_seconds` supplies a missing or
  unparseable Retry-After header. A valid header keeps its existing precedence,
  including HTTP-date behavior. Use the existing transport's 86400-second bound
  for either source, protecting the existing runtime deadline arithmetic.
  Neither source means permission to replay a POST. Unknown remains nullable;
  existing auth/SSE backoff policies do not become advertised quota values.
- Quoted, fractional, negative, out-of-range or non-scalar optional hints stay
  unknown without erasing the authoritative HTTP status or parsed machine code.
  Malformed JSON retains the existing error-envelope fallback. No English message
  parsing, response-body diagnostic or guessed file limit is introduced.

The common HTTP and existing SSE refusal paths share this metadata handling;
their response bounds, original client/jar, redirects, cancellation and request
order remain unchanged. No new automatic request or write retry is introduced.
The session/consumer that owns the failed request still owns error publication.
No hint is added to saved state, a vault, logs or a persistent quota counter.

JVM regressions cover optional-value isolation, zero versus unknown size,
JSON429 fallback/bounds, header precedence, status scoping, actual HTML413,
one POST attempt and content-safe exception strings. Current-head full JVM/lint,
debug/release, unfiltered native CI and both written reviews remain required.
Mock HTTP is not live upload/provider/device acceptance. Subsequent slices add
explicit multipart transport, original-owner pending/uncertain state, cleaned-byte
pre-checks and native consumers under [files/media](../design/files-media.md).
