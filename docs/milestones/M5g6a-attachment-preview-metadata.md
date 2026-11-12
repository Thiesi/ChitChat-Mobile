# M5g6a: oriented image and preview metadata

This retains the released image-geometry and rendition hints that the client
previously ignored. It is a prerequisite for authenticated downloads and later
native image framing, not a downloader, cache, viewer or complete P-159 delivery.
Files/media remains unavailable in the app; existing availability disclosures
remain accurate. Native upload/picker/privacy/reconciliation controls remain
separate, including the issue184 evidence-presentation decision.

## Released contract and typed hints

The minimum remains3.5.0. This uses the existing released [image-preview contract](https://github.com/Thiesi/ChitChat/blob/v3.5.0/docs/api/image-previews.md),
not a proposed endpoint. The [public formatter](https://github.com/Thiesi/ChitChat/blob/v3.5.0/src/Upload/AttachmentImageMetadata.php#L31)
casts dimensions and sizes to integers, emits both original dimension keys even
when null, and constructs distinct room/DM relative rendition URLs. This was
read through GitHub, with no sibling checkout or live-server access.

`MessageAttachment` retains nullable oriented width/height, a nullable typed
preview and an explicit `dimensionsAdvertised` observation. Both keys present,
including two explicit nulls, advertise geometry. A missing/partial pair remains
unknown and does not invent an axis. The JSON codec preserves this distinction
on round trip, including under the existing explicit-null encoding configuration.
DM's flattened metadata passes through the same codec into both canonical history
and inbox previews, preserving exact attachment/message association and bounds.

`displayDimensions` supplies a ratio only for two positive dimensions. Null,
zero and negative geometry falls back without discarding the original file facts.
Even a large positive original dimension is only a header/framing hint: it grants
no bitmap allocation. The later renderer must apply the design's frame bounds
and an independent decoding/memory budget. Original orientation is already
resolved by the server; it is not swapped again here.

The old `previewable` MIME flag never invents a rendition. A non-null preview
must have the complete typed URL/geometry/MIME/size/SHA descriptor. New integer
fields require actual JSON integers, not quoted/Boolean/fractional values.
Malformed non-null descriptors remain incompatible protocol data, rather than
quietly masquerading as an old server's missing/null rendition.

## Namespace and URL boundary

Room history validates the descriptor before mutation enrichment. DM's bounded
metadata response validates it before history/inbox adoption. A descriptor binds
the exact positive attachment ID and its room or DM namespace. Only the returned
relative `/api/v1/.../attachments/download.php` path is accepted, with exactly
`id` and `variant=preview` parameters in either order. Absolute/scheme/authority
URLs, fragments, traversal/encoded or unrelated paths, duplicate/extra/encoded
query keys, noncanonical/wrong IDs and wrong variants are rejected.

The released rendition has WebP MIME, positive dimensions at most1024 per axis,
positive encoded bytes at most4MiB, a64-hex digest and a bounded URL. These are
the server's rendition contract bounds, not an upload allowance, client memory
budget or new user-facing limit. Metadata and its URL have opaque debug strings.

These checks grant no HTTP or cache authority. A later binary consumer still
needs the frozen original rendered target, current original session/entry/DM
disclosure, chosen origin, current server authorization and byte integrity checks.
It must use the returned URL as given after validation, with no redirect, automatic
retry, browser fallback or credential transfer. Preview304 requires authenticated
revalidation of exact memory-held bytes; preview404 never silently fetches the
larger original. None of those byte/cache actions is implemented here.

Optional display enrichment remains outside upload ACK decoding. A malformed
attachment/preview field on an otherwise positive201 cannot hide the separately
validated committed room/DM message identity or turn it into uncertain delivery.
No endpoint, HTTP budget, timeout, POST, cookie, policy or retry change is added.

## Evidence and remaining acceptance

All169 focused JVM cases passed, zero failures/errors/skips:18 new metadata
boundary/round-trip cases,5 new localhostTLS enrichment cases,105 existing
network cases,29 DM protocol cases and12 session/upload TLS companions.
The TLS cases prove preservation through canonical room history, DM history
and inbox enrichment, cross-namespace rejection and no binary request while
consuming metadata. Original null/missing geometry, strict types, unsafe paths,
queries, descriptor bounds and identity-only positive ACKs are covered.

The eight current Kotlin files match the CRC-verified source/log/XML archive
SHA256 `6854017411ea31273a5988fbd19b366b6b4306957e90348c2a99bdef778baff2`.
The188.09s wrapper invocation used JDK17/Kotlin2.3.21, offline/shared homes/fixed
scratch and two CPU/workers, with no native/live/device invocation. Generated
build/cache output was removed after archive verification and the scratch guard
passed. These fixtures are not live-server or provider acceptance.

Current-head full JVM/lint/debug/release, unchanged unfiltered native runs,
product guard and both written reviews remain required, with original outcomes
and dispositions on the PR. Native framing/autoload/cache/viewer, download/save/
share consumers, guest/provider/device/live/accessibility/release gates remain
separate. No layout, copy, input predicate/deadline or workflow change ships here.
