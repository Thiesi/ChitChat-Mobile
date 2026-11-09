# M5g3d: lossless static-PNG photo privacy

Status: internal preparation only, without picker/provider, final upload-ready bytes
or hash, native attachment controls, upload, viewer or sharing. Existing in-app
availability disclosures remain accurate. WebP, fallback/explicit failure consent,
aggregate attachment memory/state and complete P-157/device acceptance follow.

Rewrite an immutable bounded static-PNG container off the main thread. Keep coded
IDAT bytes, header/interlace/dimensions, palette/transparency, background, pixel aspect
and supported colour data exactly in order, without decompressing or re-encoding.
Colour preservation includes iCCP/sRGB/gAMA/cHRM/sBIT and the newer cICP/mDCV/cLLI HDR
chunks; treating every ancillary chunk as disposable would lose rendering facts.
Only mutually compatible known layouts are accepted; no new profile or missing value
is inferred. The parser is not a complete image/profile decoder or colour validator.

Remove all tEXt/zTXt/iTXt chunks, not just GPS-named properties, without decompressing
or decoding private content. Remove timestamps and disposable palette hints. Rewrite
eXIf through the same bounded classic-TIFF payload helper as JPEG: preserve supported
orientation and exact colour/interoperability fields, remove GPS, MakerNotes, other
private directories and thumbnails. PNG eXIf contains TIFF directly, without JPEG's
APP1 length/Exif-ID prefix; recompute the rewritten chunk CRC. An omitted default
orientation1 remains1. A textual XMP/raw-EXIF profile without an explicit valid EXIF
orientation is unsupported, never an invented rotation from unparsed text.

Validate signature, unsigned chunk bounds, names and CRCs, required header/palette
relationships, singleton/order rules for retained rendering facts, contiguous image
data and terminal IEND. Reject corruption, missing required facts, duplicate EXIF,
opaque trailers, APNG and unknown extensions. In particular, never flatten animation
or copy an unknown private chunk under a location-removed claim. These unsupported
results need later preservation/fallback or explicit cleaning-failure consent, not
automatic original-byte upload.

Keep M5g3a's32MiB per-snapshot resource ceiling, an8192-chunk traversal cap and
cancellation checks in chunk/CRC loops. CRC processing uses bounded8KiB chunks; no
whole-payload temporary byte array is allocated. Shared classic-TIFF input is capped
at65527 bytes (the existing JPEG-compatible bound) before PNG takes its payload slice.
Large EXIF returns unsupported, not a fabricated server-size refusal. The JPEG framing
adapter retains all16 existing JPEG methods unchanged; no input helper, test predicate,
deadline, gate or backend behavior changes.

JVM fixtures are generated entirely in memory. RGB/RGBA/indexed transparency cases
compare exact coded bytes and actual JDK-decoded pixels/dimensions. EXIF fixtures cover
both byte orders/all eight orientations, private GPS/MakerNote removal, colour retention
and idempotence. Other cases cover retained ICC/HDR/order, unproven textual orientation,
CRC/length/header/palette/order/duplicate/truncation failures, unsupported animation/
extensions/trailers and resource limits. Synthetic HDR retention is not calibrated HDR
or Android decoder acceptance. Current-head full JVM/lint/debug/release, unfiltered
native CI and both written reviews are separate required gates. No provider, live,
native media or all-photo acceptance follows from these container fixtures.

Primary format contract: [PNG third edition](https://www.w3.org/TR/png/) (chunk framing,
rendering facts, textual metadata and eXIf). Shared EXIF details remain in
[M5g3c](M5g3c-jpeg-colour-metadata.md); no new dependency is added.
