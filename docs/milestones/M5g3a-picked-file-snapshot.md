# M5g3a: bounded picked-file snapshot

Status: internal raw preparation only. No picker/provider adapter, photo cleaning,
reconciliation hash, draft, upload, progress/cancel UI, download or sharing control.
Existing in-app files/media availability disclosures remain accurate.

PickedAttachmentRead freezes a prepared basename/MIME hint and one source opener.
One atomic claim releases the stored opener reference and allows one read attempt,
including after failure. Concurrent callbacks cannot reopen a mutable selection.
The future picker adapter must freeze its original URI/reference, use scoped grants
and bind owner/entry/lifecycle callbacks; this helper creates no session authority.

Read on Dispatchers.IO into bounded immutable ByteString segments. The caller must
provide a positive client allocation budget, at most32MiB per snapshot, and may
choose a lower device budget. Read at most one byte beyond that budget to distinguish
an exact fit from overflow; never buffer the extra byte. Empty input is distinct.
A zero-length bulk read falls back to one byte rather than spinning. No guessed
server limit/type policy is introduced. A raw photo can shrink during cleaning,
so the server's size/type prechecks still apply to the eventual cleaned bytes.

Close the stream on success, read failure and cancellation when the provider returns.
Cancellation checks surround blocking reads and prevent late chunks/results from
publishing. This helper does not promise to interrupt an arbitrary blocking provider;
the native bridge must implement provider-specific cancellation and its acceptance
tests before use. Cancellation wins over a failing read/close. Opening, reading or
closing failures retain only a bounded enum, with no raw exception message/cause,
URI, path, name, bytes, saved state or disk cache. No automatic retry/reopen.

The result is explicitly raw picked bytes, not an upload-ready photo. It computes
no digest over the original that might later be mistaken for uploaded-byte evidence.
Location removal for JPEG/WebP/PNG, orientation preservation, explicit Send anyway
after cleaning failure and SHA-256 over exactly the cleaned bytes follow separately
under [files/media](../design/files-media.md), before any native Send wiring.
This memory-only source does not add staging files or durable URI grants.

JVM regressions cover byte immutability, exact/overflow/empty budgets, one extra byte,
invalid budget before open, zero-read progress, safe open/read/close failures,
no reopen after failure, concurrent claims and cancellation during a bounded test
provider read. Mock streams are not actual cloud/document provider or photo privacy
acceptance. Current-head JVM/lint/debug/release, unfiltered native CI and both written
reviews remain required. No live call or native-input gate change is authorized.
