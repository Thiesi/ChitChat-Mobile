# M5g3b: lossless ordinary-JPEG photo privacy

Status: internal JPEG-only preparation. No picker/provider, final upload-ready
payload/hash, native attachment, upload, image viewer or sharing control. PNG/WebP,
fallback re-encoding and complete P-157/provider/device acceptance remain separate.
Existing in-app files/media availability disclosures remain accurate.

The helper rewrites an immutable bounded snapshot off the main thread. It supports
ordinary single-frame Huffman sequential/progressive JPEG containers, retaining
their coded image/scan/table bytes without pixel decoding or re-encoding. Retain
recognised JFIF, ICC and Adobe colour data; dropping those can change interpretation.
Remove other APP data (including EXIF, XMP, IPTC and embedded JPEG thumbnails) and
comments throughout the container, not only before its first scan. Stuffed bytes,
restart markers and scan boundaries remain unchanged.

Read the TIFF0th IFD using either byte order. An explicit orientation must be one
SHORT/1 value in1..8. Values2..8 replace the entire EXIF segment with an orientation-only
record at that segment's original position;1 may use its normal default. Remove
the original GPS/sub-IFDs, thumbnails, XMP and other EXIF facts. No private metadata
is decoded into strings, logged, persisted or put into generated debug output.

Reject malformed lengths/offsets/orientation, duplicate EXIF, unsupported frame or
marker layouts and MPF/multi-image/non-padding trailers. This prevents dropping a
secondary image or gain map while claiming unchanged photo preparation. XMP without
an explicit valid EXIF orientation is also unsupported: do not invent an orientation
from unparsed XML. These are safe cleaning-failure results, never automatic raw-file
uploads or permission to send. The later pipeline must supply an appropriate fallback
or obtain the existing explicit Send anyway consent; this slice implements neither.

The input shares M5g3a's32MiB per-snapshot ceiling (a client resource bound, not a
server limit); at most8192 markers are traversed. Output retains immutable ranges
instead of whole-image copies. Bounds checks precede reads and unsigned TIFF offsets
cannot wrap into indexes. Cancellation checks cover marker/entropy/fill traversal
and output assembly. Actual server size/type checks and SHA-256 evidence must use
the eventual cleaned bytes, never the original; that final readiness wrapper follows.

Primary format references: [JPEG marker/scan syntax](https://www.w3.org/Graphics/JPEG/itu-t81.pdf),
[JFIF](https://www.w3.org/Graphics/JPEG/jfif3.pdf),
[TIFF structure/orientation](https://www.itu.int/itudoc/itu-t/com16/tiff-fx/docs/tiff6.pdf),
and [colour-marker interpretation](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/imageio/metadata/doc-files/jpeg_metadata.html).
No new dependency or backend change is introduced.

JVM fixtures include an own, in-memory-generated8x4 JPEG, valid GPS IFDs in both
byte orders, all eight orientations, lossless byte equality, colour markers and
embedded thumbnails, late metadata, synthetic multi-scan/stuffing/restart traversal,
all truncated prefixes, malformed/ambiguous inputs and resource limits. The synthetic
multi-scan fixture is no progressive-decoder proof. Current-head JVM/lint/debug/release,
unfiltered native CI and both written reviews remain required. No live/native/provider
or all-photo-format acceptance follows from these container tests.
