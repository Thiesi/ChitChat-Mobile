# M5g3c: lossless JPEG EXIF colour metadata

Status: internal JPEG preparation only. No picker/provider, final upload-ready
payload/hash, native attachment, upload, viewer or sharing control. Existing in-app
availability disclosures remain accurate. PNG/WebP, colour-preserving fallback,
explicit cleaning-failure consent and complete P-157/device acceptance follow.

M5g3b correctly refuses colour metadata it cannot preserve. This extension keeps
the exact EXIF ColorSpace unsigned SHORT/1 in the standard Exif IFD, including
uncalibrated65535 and vendor values, without guessing a profile or converting pixels.
Known interoperability indexes R98 and R03 (ASCII/4, including NUL) and version0100
(UNDEFINED/4) survive in their standard child IFD when present. Missing fields stay
missing: neither an interoperability index nor a colour space implies a new value.
In particular, uncalibrated is not converted into an sRGB claim. Existing ICC, JFIF,
Adobe and coded scan/table bytes remain unchanged and in their original order.

Create new minimal little-endian directories containing only the permitted colour
fields and an explicit non-default orientation. All original pointers, GPS IFDs,
MakerNotes, thumbnails, XMP, IPTC, comments and other private EXIF fields are removed.
The replacement stays at the original EXIF marker position, keeping a leading JFIF
marker first. Orientation1 can retain its normal default. Colour facts alone never
prove orientation from a separate unparsed XMP block.

Both input byte orders, all eight orientations and unsigned colour values are
supported. Reject duplicate retained fields, invalid types/counts/pointers and
directory cycles. Never relocate a colour-space tag found outside its standard
Exif IFD. Unknown interoperability indexes/versions, calibration/gamma and
non-default chroma positioning still return safe unsupported results; they cannot
silently lose rendering information or authorize sending the original photo.
The original32MiB input ceiling,8192-marker bound, cancellation checks, malformed
container, multi-image/gain-map and opaque-trailer refusals remain unchanged.

JVM regressions independently read the complete output IFD/tag allowlist, validate
exact field values and compare the remaining JPEG/ICC bytes. The matrix combines
both byte orders, all eight orientations, sRGB/uncalibrated/vendor colour values,
GPS/MakerNote removal and idempotence. Partial colour hints remain partial; malformed,
duplicate, unknown, misplaced and truncated colour metadata must fail safely.
Existing orientation, private-metadata, scan, resource and pointer regressions remain.
Current-head full JVM/lint/debug/release, unfiltered native CI and both written reviews
are required separately. No decoder, provider or all-format acceptance follows.

Primary references: AndroidX [ColorSpace](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface#TAG_COLOR_SPACE)
and [interoperability index](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface#TAG_INTEROPERABILITY_INDEX),
the AOSP [IFD/tag definitions](https://android.googlesource.com/platform/packages/apps/Gallery2/+/master/gallerycommon/src/com/android/gallery3d/exif/ExifInterface.java),
and [TIFF structure](https://www.itu.int/itudoc/itu-t/com16/tiff-fx/docs/tiff6.pdf).
This is local preparation; no dependency, backend contract or native input gate changes.
