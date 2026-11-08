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

## Separate real-MainActivity impact case

The explicit `main-activity-impact` mode builds an opt-in test source set using
`-PmainActivityImpact=true`, verifies both APK signatures, packages and test-only
diagnostic inclusion, then runs one case on the same pinned API35 environment.
It does not run the comparison or the old controls. No invocation is implied by
compiling the opt-in APKs or by the comparison mode.

An outer test rule seeds synthetic settings/auth/account/room/direct/target/notice
ViewModels in the real MainActivity store in API35's pre-created callback. MainActivity
then runs its unchanged production onCreate, content, theme and onThemeChanged code;
the fixture never calls setContent, modifies the window, installs an insets listener,
or requests focus/IME. Other inactive application services retain their normal
signed-out, serverless session. No server or account fixtures are required.

The case opens a synthetic DM through the actual notification/navigation consumer,
recreates the Activity while in that DM, verifies Activity/window replacement and
ViewModel retention, and makes exactly one native composer tap. Within5000ms it
requires focused/displayed input and a positive visible native IME. Then both input
and Send must fit wholly above full WindowMetrics height minus the native IME bottom.
The default-display window must start at origin0,0; decor height is recorded separately
so an adjustResize-shrunken decor cannot cause the IME to be subtracted twice. See
[WindowMetrics bounds](https://developer.android.com/reference/android/view/WindowMetrics#getBounds()). These are
new impact assertions; every ordinary test's positive-Compose-inset predicate is
unchanged. Cleanup closes the keyboard and verifies it hidden before Activity teardown;
a cleanup failure cannot replace the primary failure.

At most five scalar records retain lifecycle identity/timestamps/native IME/geometry
and cleanup status, without text or raw dumps. Metadata failure cannot turn a native
failure green. Original JUnit/HTML, APK hashes/signature proof and metadata are retained
seven days. A pass establishes only this synthetic real-Activity journey on pinned API35;
it does not prove all real devices, room-to-DM transitions, themes or configuration
changes correct, nor establish an API26-34 result. Report the bounded outcome on issue69.
