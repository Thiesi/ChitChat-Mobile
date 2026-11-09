# Bounded live-session failure metadata

The [authorized observation](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6730852106)
adds failure-only scalars to the existing opt-in LiveSessionTest. Its original
main8cf1b55 invocation remains failed with ApiFailure/HTTP; the original report did
not retain the request step, status or error code. This observation does not retry
or erase that result and makes no server-version observation.

The existing request order, assertions, chosen origin, test account and session
lifecycle remain unchanged. There is no extra request, acknowledgment, send,
membership change, password recovery, rules acceptance or retry. Ordinary unit
tasks still exclude LiveSessionTest; only its explicit task reads the user-owned
configuration. Credentials and response bodies are never printed or copied.

On failure, emit one static step label and FailureKind, HTTP status100..599 or
unknown, and an allowlisted public machine error code or unknown. Unknown codes
are suppressed even when token-shaped, preventing a malformed response from
putting names or IDs in diagnostics. No Throwable message, arbitrary class name,
error message, user ID, account/room name, content, cookie or CSRF is formatted.
Use the thrown ApiFailure when present. When an assertion follows a session
operation that stores its failure, separately label those scalars as source=session;
do not present that later session state as the thrown exception. Missing values
remain unknown. The original Throwable is rethrown before the existing cleanup.

The authorized re-observation runs exactly once. Its result reports the static
step, status and public code when available. Account/server refusals go to the
maintainer; evidence of a client request defect requires a sanitized JVM regression
and a separate fix. A pass cannot reconstruct the original failed request or
establish live media/device/release acceptance. Focused JVM tests cover privacy
canaries, invalid status bounds and thrown-versus-session provenance without
credentials or a device. Normal current-head gates and two written reviews remain
required before delivery of this diagnostic code.
