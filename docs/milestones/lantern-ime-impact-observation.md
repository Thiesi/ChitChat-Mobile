# Lantern boundary and real-Activity IME observations

Issue69 comment6727869808 authorizes measurement only after PR133's theme candidate
failed. PR133 stays frozen at ab76e8f (comment6727903550). Its original failure,
the onCreate-only comparison and the consumed20-cycle measurement remain failed.

## Frozen theme-boundary comparison

One manual dispatch of the existing native-input-observation workflow with explicit
`reproducer_arm=lantern-bisect` runs two fresh API35 jobs. Each runs the unchanged
`ReplyComposerJourneyTest` class once: pre-theme-B `83b448089e69f2fc755e280f24908d6ee71b6a75`
(`3fa2b02^1`) and current main `c49e04766d257d0578d95172abe7d9885479a04b`.
The class and wrapper/catalog/root build files must be identical across the boundary;
their hashes, exact sources and invocation intent are retained before instrumentation.
The API35 Google image rev9, emulator build16740729, precondition checks and disabled
animations match the ordinary API35 setup. No production/test source is patched in
either observed checkout. The original strict input helpers and5000ms predicates stay.

This class-filtered run is not the full acceptance suite. It runs neither the old
100-cycle control nor the consumed standalone a/b/c or20-cycle diagnostic. There is
one instrumentation invocation per arm, no retry and no success rewrite. Original
JUnit/HTML, environment and content-free allowlisted failure lifecycle evidence are
retained for seven days. Any setup/accounting failure stays incomplete. Report both
arms on issue69 before selecting a production or fixture correction.

The separate real-MainActivity impact case is still being prepared; no invocation
of it is implied by this comparison mode. It must preserve the real production
Activity/content/theme lifecycle while substituting only synthetic test operations.
