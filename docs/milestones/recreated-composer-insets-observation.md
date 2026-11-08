# Recreated composer inset observation (#69)

The [technical lead's bounded diagnostic](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6726488531)
investigates PR129's original API35 native/Compose divergence, not the standalone a/c no-surface signature.
The original run42475781799 remains FAILED247/248. Its exact-head development exception grants no retry,
new-head, device or release waiver; demonstrated product defects still block.

## Fixed measurement

One manual dispatch of `native-input-observation.yml` with explicit `reproducer_arm=recreated-insets`,
pinned API35 Google x86_64 revision9,
emulator16740729,2cores, animations0. One instrumentation invocation runs20 parameterized fresh-fixture
cases: room reply/draft/recreation, then DM reply focus. Existing fixtures are fake sessions and data;
no live account/server is used. No production or existing journey code is changed. The original native
helpers, focus/display/positive-IME predicates and5000ms deadlines remain intact, without tap retry,
forced focus/IME, inset dispatch or readiness polling. Normal fixture IME cleanup remains before destruction.

The diagnostic class is compiled only with `-PrecreatedInsetsDiagnostic=true`; it is absent from ordinary
test APKs. The registered manual workflow can measure the committed diagnostic branch before its merge;
the existing consumed a/b/c arms and100-cycle control are not selected or changed by this mode.
Ordinary CI compiles it in the build job, but ordinary native jobs use their own default source
set and full unfiltered suite. The manual measurement is never a merge/acceptance substitute. The first
authorized dispatch consumes this measurement even if setup fails; no retry is implied.

## Observations and limits

The pinned foundation-layout1.11.4 stores its inset holder per `LocalView`. The Activity-root probe reads
that holder and records whether this root is also the focused native editor. Its direct Compose inset and
the original unfenced mirrored field are separate scalars, with producer/root/Activity/window identities.
Read-only draw/attach/disposal and control samples include native IME visibility/bottom and attachment,
window focus, Compose accessor count and native apply-listener presence. No inset or animation listener
is replaced. Private listener/holder reflection can be unavailable: null remains unknown. Observation
adds work and may perturb timing; a diagnostic pass cannot establish a fix or erase the original failure.

Each cycle retains at most64 control records plus the latest128 draw/Compose records; dropped progress
is counted. All output is an explicit scalar allowlist, with no text, raw assertion, tree or dumps. Timestamps
order separate observations, not an atomic native/Compose snapshot. Unknown/partial/inconsistent cycle
accounting is never called a complete passing measurement. Reports, APK hashes/certificate and verified
environment are retained for7 days; installation refuses existing packages and cleans up only its own.

The original [42522293944](https://github.com/Thiesi/ChitChat-Mobile/actions/runs/42522293944) is FAILED:
20 started/finished case records report20 DM handoff failures, with no fixture/package cleanup failure.
The current attached/focused root has native bottom259 and direct Compose/mirrored bottom0 in every case;
the first retained divergence precedes the failure sample by4346–4890ms. No stale-positive-holder mirror
was observed, and no progress was dropped. Native apply-listener reflection is unknown, not absent.
The [full report](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6727081243) retains the
artifact hash, environment and limits. This single measurement is consumed and must not be replayed.

The original collector also incorrectly required cleanup_end to be the last row, although cleanup can
cause later draw/Compose progress. Its measurementComplete=false is preserved. The corrected parser
requires the last control marker (not the last progress record) to be cleanup_end, and retains explicit
JUnit status per case for future accounting. The correction is device-free; no original result is replaced.

A test-only comparison now contrasts MainActivity's onCreate content installation with the original
fixture's later onActivity installation after resume. It preserves the old recreation journey as its
baseline negative and adds pinned-composer/native-keyboard geometry coverage using the same native
helpers and strict5000ms predicates. A test-owned ActivityLifecycleCallbacks registration installs content
when the same ComponentActivity calls super.onCreate, then unregisters after rule teardown; the original
host class/theme/window declarations and release behavior stay unchanged. This comparison is not yet
evidence that initialization timing is the cause or that a fixture
correction fixes the problem; its own full results/reviews must be inspected before selecting a fix.

The report must distinguish lagging Compose state, no received inset and a retired producer/window where
the evidence permits. It must leave unavailable causes unknown and propose any app or fixture correction
separately. No additional measurement or product/release acceptance follows from this infrastructure.
