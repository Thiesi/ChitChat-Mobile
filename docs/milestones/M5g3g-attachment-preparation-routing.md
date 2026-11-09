# M5g3g: attachment preparation routing

This core prerequisite applies the technical lead's
[classification decision](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6739224912)
to one frozen picked snapshot, then routes supported photos through the delivered
cleaners and final-byte hash factory. It adds no picker, consent button, upload,
automatic fallback, aggregate memory owner or native attachment state.

## Classification and results

- JPEG, PNG and WebP signatures select their cleaner regardless of picker or MIME.
- Recognized unsupported image signatures return `NeedsChoice`: GIF87a/89a, BMP,
  classic/BigTIFF including DNG-style raw, ORF/RW2/RAF-style raw, and HEIF/HEIC/AVIF
  image brands in an initial ISO-BMFF file-type box.
- An image MIME or Photo Picker origin with an unrecognized header also needs a
  choice. These hints only escalate; they cannot override a known signature.
- A File selection with non-image or unknown MIME and an unrecognized header is a
  non-photo: unchanged final bytes, no extra privacy prompt.

These are signature candidates, not image-validity proofs or MIME/permission facts.
A complete known signature with a truncated or corrupt body still goes to its
cleaner or explicit choice. A known ISO image major brand is not downgraded by a
bad declared size. Compatible brands are read only within the advertised/available
file-type box; minor_version and following media boxes are not brands. Unsigned and
extended lengths cannot allocate memory or overflow an index. Scanning is bounded
by the32MiB snapshot ceiling, runs on Default, and checks cancellation in long tables.
Truncated signatures without an identifying marker remain unknown; picker/MIME
escalation still applies. No filename guessing or pixel decoding is performed.

The [presentation clarification](https://github.com/Thiesi/ChitChat-Mobile/pull/167)
distinguishes an unsupported image kind from a failed supported cleaner. The result
therefore carries UNSUPPORTED_IMAGE for unsupported/unknown-image classification
and CLEANING_FAILED for invalid/unsupported JPEG/PNG/WebP cleaning. These map to
their own approved copy; the future UI need not infer a reason from MIME.

The routing result is either a ready immutable payload or a choice tied to the
**exact original snapshot object**. Supported successful cleaning produces
LOCATION_REMOVED and hashes only the cleaned bytes. Non-photos produce NON_PHOTO.
The filename and MIME hint stay frozen; recognition never invents a server-policy
MIME precheck. APNG, animated WebP, malformed or unsupported cleaner layouts return
a choice, never a flattened image or an unchanged ready photo. Empty and local
budget failures retain EMPTY/CLIENT_BUDGET and do not offer a cleaning bypass.
Cancellation propagates without publishing either result.

`NeedsChoice` supplies no payload, consent or send authority. The later native
consumer must fence the actual entry/session/owner and original choice/selection
before the explicit Send anyway action and after every preparation await. It must
retire old results, preserve successor selections, and account for actual retained
and preparing memory. Only that fresh action may prepare unchanged original bytes;
this helper never does so for a failed photo. No source is reopened and no POST,
quota attempt, history reconciliation or retry is triggered here.

## Header references

The recognizer uses format identifiers, not a new decoder/library dependency:
[GIF header](https://www.w3.org/Graphics/GIF/spec-gif89a.txt),
[BMP file type](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ns-wingdi-bitmapfileheader),
[TIFF/BigTIFF header](https://libtiff.gitlab.io/libtiff/specification/bigtiff.html),
[registered ISO brands](https://mp4ra.org/registered-types/brands), and
[AVIF file-type requirements](https://aomediacodec.github.io/av1-avif/v1.2.0.html).
Raw-header identifiers and file-type field layout were also checked against
[pinned AndroidX source](https://android.googlesource.com/platform/frameworks/support.git/+/18ff3abef5c26888c3feb53c02dbcdcc08736b7e/exifinterface/exifinterface/src/main/java/androidx/exifinterface/media/ExifInterface.java).

## Verification and limits

Before the presentation-reason distinction, the focused run passed22 JVM cases (Kotlin2.3.21/JDK17, two processors, no
Gradle/emulator/native/live invocation). It covers every listed signature family,
major/compatible/extended brands, truncation/garbage, incorrect MIME hints, long
brand tables, exact non-photo bytes, successful routing of all three cleaners,
post-clean hash evidence, animation rejection, original-reference identity, local
failures, cancellation and private debug strings. The WebP test is a structural
fixture, not new decoder/pixel proof. Existing native codec acceptance is separate.

The first focused run executed21 cases with one failure: its synthetic VP8L payload
had five bytes, below the existing cleaner's six-byte minimum. Only that fixture
changed; production source hashes are identical between the failing and corrected
runs. The corrected21-case run passed, then the animation case was added and the
final22-case run passed. All source/JAR/log/dependency archives were byte/CRC
verified and all15 scratch files per run removed. Final archive SHA256:
`b817bbd1c2f9a21e11579c5bec9f67cf993076e7d0d56f7080b0c368fc2bf038`.
After aligning the result reasons with PR167, the same22 cases passed again;
that current-source archive SHA256 is
`a9922638f0e32b87fc043d5f1fd40089ec3c66134d9259c919a6ec91fdfcabf2`.
All15 files from that fixed scratch were also verified and removed.
The original failed archive remains retained:
`b7a318ac60f07b9127ad116a72c0b62ba4294581121d894abe9e7a46addc9016`.

Current-head full JVM/build/lint/unfiltered native gates and both written reviews
remain required and will be recorded on the PR. No native controls, original-owner
consent state, aggregate memory, provider/live/device or full P-157 acceptance is
claimed by these focused results.
