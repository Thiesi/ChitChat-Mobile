# M5g3e: lossless static-WebP photo privacy

Status: internal static-WebP preparation only. No picker/provider, upload-ready
wrapper/hash, native attachment, upload, viewer or sharing control. Existing in-app
availability disclosures remain accurate. Aggregate memory/state, final exact-byte
evidence, fallback/explicit failure consent and full P-157/device acceptance follow.

Rewrite a bounded immutable RIFF/WebP snapshot off the main thread. Preserve coded
VP8/VP8L, ICCP and external/internal alpha interpretation, feature/canvas data and
reconstruction order. Parse headers without decoding/re-encoding pixels or profiles.
Remove XMP and rewrite EXIF through the delivered bounded classic-TIFF helper, keeping
supported orientation and exact colour/interoperability facts while removing GPS,
MakerNotes, thumbnails and other private directories. Support raw TIFF and the known
Exif-ID-prefixed payload written by older libraries; keep that framing choice.

Only metadata-presence bits and affected RIFF/chunk lengths change. An omitted
orientation1 retains its normal default. XMP without explicit valid EXIF orientation
fails safely; never invent orientation from unparsed XML. The original VP8X flags
must match actual metadata/profile presence, and lossy external-alpha presence.
Never repair a mismatch into a new colour/alpha/orientation interpretation.

Validate unsigned file/chunk bounds, required zero padding, singleton/reconstruction
order, static bitstream header dimensions and canvas agreement. Preserve lossless
alpha hints/flags without pretending to decode their pixels. Reserved layouts,
animation, unknown extensions, malformed/duplicate facts and opaque trailing bytes
fail safely. Unknown ANMF data is never copied or flattened: animation is outside
this initial static scope. Failure is not automatic original-file upload or consent.

Keep the32MiB snapshot/output ceiling, defensive8192-chunk cap, cancellation checks
and shared65527-byte TIFF bound before payload slicing. Metadata editing uses immutable
ranges and small replacements. These are client preparation bounds, not server limits.
The later pipeline must check and hash exactly the eventual bytes sent.

Ten JVM methods cover simple/extended containers, both byte orders and EXIF framings,
all eight orientations/default omission, colour/ICC/alpha retention, metadata ordering,
flag mismatch, duplicates, corrupt lengths/padding/dimensions, truncated prefixes,
unsupported layouts, idempotence and the memory ceiling. Their bitstream fixtures
are intentionally synthetic framing evidence, not valid-image decoder proof.

One new ordinary Android instrumentation method generates own2x2 bitmap output
using platform lossy/lossless WebP encoders, adds GPS/XMP/orientation metadata and
compares actual decoded pixels/dimensions before/after cleaning across both byte orders
and EXIF framings. API30+ uses the explicit formats; older APIs use legacy WEBP.
It runs in the same single unfiltered CI invocation, without Activity/input/focus/IME
actions, deadline changes or retries. Expected suite additions are10 JVM and1 native
case; report actual current-head counts. No local emulator, device or live invocation
is implied. This codec case is not provider, animation, colour-calibration or release
acceptance. Both current-head written reviews and full JVM/lint/debug/release/native
CI remain required separately; all prior failures retain their original status.

Primary references: [WebP RIFF/container specification](https://developers.google.com/speed/webp/docs/riff_container),
[AndroidX EXIF WebP reader/writer](https://android.googlesource.com/platform/frameworks/support/+/b961b38aadaf760cf1a8c184c53b4e54b09b0163/exifinterface/exifinterface/src/main/java/androidx/exifinterface/media/ExifInterface.java),
and [Android codec formats](https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat).
No new library or backend contract is introduced.
