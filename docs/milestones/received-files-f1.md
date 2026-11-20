# Received files F1: the download protocol

F1 is the first of the TL-approved received-files slices (F1 protocol, F2 memory cache and load state, F3 transcript,
F4 viewer, F5 Save, Share and Open). It adds no UI. Files remain unavailable in the app until F3, so the existing
availability disclosures stay accurate. The contract is the released v3.10.0 one (`docs/api/attachments.md`,
`direct-message-attachments.md` and `image-previews.md`); the presentation contract is
[files-media.md](../design/files-media.md).

## What it does

- **`SessionHttp.download`** makes one GET on the runtime's own client (its jar, dispatcher and pool, so retirement
  cancels it; same-origin and no redirects, as for every request). It's idle-bounded at 30 s per read and 10 minutes
  overall, rather than the 25 s total of small JSON.
  - The body is streamed to a sink in 64 KiB chunks and hashed on the way.
  - It must be exactly the expected length: the `Content-Length` must match and the body may not run short or long. Any
    other length is `INCOMPATIBLE` with the code `length_mismatch`.
  - A failing sink is `STORAGE`. A preview must be `image/webp`.
  - A 304 answers a preview's `If-None-Match`. Errors keep the ordinary typed failures, including a positive 429 wait.
  - It's never retried or replayed. Cancelling cancels the call, and a stream sink then holds a partial file.
- **`AttachmentDownloads`** (core/media):
  - **Session binding:** each request is bound to the rendered session it was asked for. `openAccess(expected)` refuses
    a stale one, as well as the update state and a busy session; `authorized` keeps the generation fence and the
    session-wide effects.
  - **Paths:** a preview is fetched only from its M5g6a-validated relative `preview.url`, and an original only from the
    attachment's ID in its own namespace (rooms or DMs). A preview URL that fails validation is never requested.
  - **`load` (display):**
    - **Decided from metadata, before any request:** an original over 32 MiB or 16 megapixels is `TooLarge`
      (TL caps).
    - **The memory lease:** it's taken from the shared `AttachmentMemoryBudget` before allocating. Without one the
      result is `NoMemory`, with no request.
    - **The result:** the verified bytes hold the lease until `release()`, and the lease is released on every other
      outcome.
  - **`stream` (Save and the share copy):** the original goes straight to the caller's stream, with no display cap and
    no buffer. The caller decides what to do with a partial or mismatching file: a chosen document is never deleted,
    and a private share copy is. Since [F5a](received-files-f5a.md) the destination is opened only at the first body
    byte, a fence runs before every chunk, and a streamed download may take 60 minutes overall.
  - **Integrity:** the bytes' SHA-256 must equal the metadata's (or the preview's). A mismatch, or a wrong length, is
    `IntegrityFailed`, and the bytes are never returned as good.
  - **Outcomes:**
    - `Bytes`, `NotModified` and `Saved` for success;
    - `Gone` (410), `NotAllowed` (403, or 404 other than a missing preview) and `PreviewUnavailable`
      (`404 attachment_preview_unavailable`, never replaced by the original);
    - `RateLimited` (the server's positive wait, never retried automatically);
    - `IntegrityFailed`, `TooLarge`, `NoMemory`, and `Failed` (everything else, typed).
- **A download 429 is session-wide by design (TL):** like every authorized request, it sets the session's wait
  (`retryAtMillis`), which briefly holds other session requests. A 429 is the server or its proxy telling this session
  to slow down, and there's no documented per-endpoint scope to narrow it. F2 must therefore never let an automatic
  preview load start or prolong that wait, and must never queue loads for a burst once it ends.
- **Policy:** the upload policy (`attachments` block, `max_bytes: 0`, Invalid) and 413 don't govern downloads.
  Received files stay readable in every policy state.
- **Privacy:** nothing here is persisted or logged; the bytes live only in the caller's memory. Display caching is F2,
  memory only, and the only disk use comes in F5's share copies.

## Verification

`AttachmentDownloadsTest` (12) runs a real session against MockWebServer. It covers:
- a preview from its own URL, verified, charged until released;
- If-None-Match and a 304 that keeps nothing charged;
- originals in both namespaces, with no validator;
- a same-length mismatch and a short body as `IntegrityFailed`;
- 410, 404, 403 and preview-unavailable typed, plus a 429 with its wait in one request;
- the display caps and a missing lease deciding with no request;
- a forged preview URL never requested, and a stale rendered session getting nothing;
- `max_bytes: 0` not blocking a download;
- streaming every byte (and beyond the display cap), with a mismatch reported;
- a failing destination as `STORAGE`.
