# Received files F2: the memory-only cache and the load state

F2 is the second received-files slice. It adds no UI: the transcript (F3) will drive it. Files remain unavailable in the
app until F3, so the availability disclosures stay accurate. It follows
[files-media.md](../design/files-media.md) "Image loading policy" and "Image states", the TL's rulings of 11-09 and
11-19, and [F1](received-files-f1.md) for the bytes.

## What it does

- **`NetworkConditions`** reports whether the default network is online and metered, from `ConnectivityManager`'s
  existing state, with no requests. An unknown network counts as metered.
- **`ImageDecoding`** (`AndroidImageDecoding`): `BitmapFactory` reads the **bounds first**.
  - **Beyond 16 megapixels it's `TooLarge`** and nothing is decoded, even downsampled.
  - Otherwise the decode uses a power-of-two sample size, so it never decodes smaller than the frame and never
    allocates the full original. Unreadable bytes are `Unreadable`.
  - **The lease comes before every allocation** (M5g3h): the decoder sets the memory charge to the bitmap's estimated
    size before decoding, and leaves it at exactly the kept bitmap's real allocation. A refused lease is `NoMemory` with
    nothing allocated.
  - **EXIF orientation (TL, #308), originals only.** The server's previews are already in display orientation and are
    never rotated. For an original, `androidx.exifinterface` reads the orientation from the bytes already held (no
    second fetch), and all eight values are applied with a `Matrix` (rotations, flips, transpose and transverse).
    - It's **a hint**: missing, malformed or a throwing parse means unknown, so no rotation, and the image still shows.
    - The downsampling target comes from the oriented frame; the 16 Mpx guard is unaffected.
    - **The rotation briefly holds two bitmaps:** that peak is leased before the second bitmap is created, then the
      source is recycled and the lease shrinks to the final bitmap. With no lease for the peak it's `NoMemory`.
    - The upload privacy cleaners are untouched.
- **`AttachmentImages`**, the loader, is main-confined and keyed by origin, account, generation, namespace and
  attachment ID.
  - **The automatic rule:** a frame that's on screen, online and unmetered, whose bytes are at most 2 MiB, loads by
    itself. "Its bytes" are the preview when there is one, else the original. Otherwise it shows Show.
    - A frame gets **at most one automatic attempt**. **Show** and **Try again** are the explicit requests.
    - Once shown, an image stays shown for that message during the session: evicted and back on screen, it reloads.
  - **States:** NotLoaded (POLICY for Show, OFFLINE with Show disabled, WAITING), Loading, Loaded, Failed (network, a
    SHA-256 mismatch, unreadable bytes), NoMemory ("can't show right now"), TooLarge, Gone and NotAllowed, as in the
    spec's table.
    - **Too large** is decided from the metadata before any request, even for Show.
    - **A missing preview** falls back to the original under the same rule and is never requested as a preview again
      that session.
  - **The 429 rule (TL, #306):** a download 429 makes the session wait (F1). During the wait **nothing starts
    automatically and nothing is queued**: a frame that would auto-load shows WAITING. When the wait ends, frames
    **still on screen auto-load once each**; frames scrolled away don't. A load that itself got the 429 waits too, and
    may auto-load once afterwards.
  - **Back online:** frames on screen are evaluated once under the ordinary rule.
  - **Memory only** (TL 11-09; the privacy policy's "in memory only"; nothing touches disk):
    - each held image keeps F1's memory-budget lease, **resized to the decoded size**. Decoding runs off the main
      thread and the lease is thread-safe, but eviction is main-confined: a refused lease comes back to the loader,
      which drops images not on screen (least recently used first) until it fits, then decodes once more. Otherwise
      it's NoMemory;
    - least-recently-used images not on screen are evicted beyond the loader's capacity, releasing their leases;
    - **any change of server, account, generation or phase drops every image and job.**
  - **Revalidation:** a held preview keeps its ETag and is revalidated with `If-None-Match` when it comes back on screen,
    if a request may start (online, no wait), and **at most once per conversation entry** (TL, #308). Scrolling back
    and forth within one visit to a room or DM gives one conditional GET at most; `enteredConversation()` (F3) opens the
    next visit.
    - **A 304** keeps it.
    - **Gone or not allowed** removes it.
    - **Anything else** leaves the shown image as it was.
    - An original has no validator and is held as shown (TL 11-09).
  - **Read proof:** the loader never touches read or typing proof (TL condition).

## Verification

- **JVM: `AttachmentImagesTest` (20),** with a fake source and decoder (which leases like the real one) on virtual time:
  - **Loading rules:**
    - automatic loading, with the lease following the decoded size;
    - metered or over 2 MiB waits for Show;
    - offline disables Show, and back online loads once;
    - a failure is never retried automatically, only by Try again;
    - a mismatch or unreadable bytes give Failed with the lease freed;
    - the preview fallback;
    - beyond the caps nothing is fetched, Show included.
  - **The 429 rule:**
    - nothing starts during the wait, and afterwards on-screen frames load once each (not a frame that scrolled away,
      and no second pass);
    - a rate-limited load waits and then loads once.
  - **Memory and revalidation:**
    - revalidation (a 304 keeps it, Gone removes it);
    - at most one conditional GET per visit however often a frame scrolls back, and one more after a fresh entry;
    - eviction freeing leases;
    - NoMemory when the decoded image can't fit;
    - a refused lease drops an off-screen image on main and decodes once more; with nothing to drop, no second decode
      and the image on screen is kept;
    - only originals are oriented, never previews;
    - a session change dropping everything;
    - the sample-size arithmetic.
- **Instrumented: `AndroidImageDecodingTest` (9):**
  - a downsampled decode within the frame;
  - a valid 5000×4000 PNG is TooLarge from its bounds, and a truncated header is Unreadable;
  - unreadable bytes;
  - an orientation-6 JPEG decodes to its oriented width and height, and a horizontally flipped one is mirrored;
  - the same JPEG as a preview isn't rotated;
  - a malformed EXIF block shows unrotated;
  - the rotation peak is leased before it's allocated and then shrunk to the kept bitmap, and with no lease for the
    peak it's NoMemory.
- **Dependency:** `androidx.exifinterface` 1.4.2, the current stable release (minSdk 21; runtime dependencies only
  `androidx.annotation` and `org.jspecify`, both already on the classpath), with its Apache-2.0 entry in
  `oss_licenses.json`.
