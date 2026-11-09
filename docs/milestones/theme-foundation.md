# Lantern theme foundation

Status: phase A delivered in PR112/main95062c7; phase B delivered in PR114/main3fa2b02.
The core primitives, preference storage, assets, root appearance controls and existing
components are integrated. Reusable capture infrastructure delivered separately in
PR117/maina8f7d17. Each delivery's gates and presentation judgment are recorded below.
This is the token prerequisite for later presentation slices, following delivered
M5d4c (PR97, main3093e71). It consumes the approved
[theme brief](../design/theme-foundation.md), [Pure black contract](../design/amoled-black.md)
and adaptive icon assets from PR103. PR114 used its own exact9248fd3 development-only
exception; it grants no later-head or release waiver.

## Delivery split

The complete patch exceeded the existing126,000-byte written-review limit. It is split
without raising that limit ([engineering record](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6715004003)):

- **Phase A, delivered:** palettes, Material/type/shape definitions, identity helpers,
  thin PrimaryButton/CountBadge definitions, local Pure-black storage and VM API,
  licensed font and approved icon/window resources. Debug pseudo-locales and ATF are
  enabled for the new component tests. Existing test stores implement the added setting.
- **Phase B, delivered:** root theme and switch, actual device-night observation and background
  mirror call sites, existing-screen wrapper/color/shape migration, source-policy and
  appearance journeys and before/after captures. PR117 owns the reusable capture workflow.

Phase A shipped component definitions without a Pure-black switch or root-theme replacement.
Phase B supplied those consumers and the Light/Dark/Pure-black default/200% evidence.
The review limit stayed126,000 bytes: complete code/tests remained in B; only narrative
status/evidence prose moved into this immediate documentation follow-up, as
[approved](https://github.com/Thiesi/ChitChat-Mobile/pull/114#issuecomment-6718210680).
The following sections describe the delivered combined foundation contract.

## Complete foundation contract

- Immutable Light, Dark and Pure-black palettes; explicit Material roles, extended
  colors, stable ID-based identity hues and shared shapes.
- Bundled Latin/Latin-Extended Bricolage Grotesque, as static700/800 faces at the source's
  default optical size/width, for display, headline and titleLarge
  roles only. Body, message, label and smaller title typography retain system fonts
  and Material sizes. [Font license and reproducible subset provenance](../licenses/bricolage-grotesque-OFL.txt).
- Persimmon/ink primary actions and unread counts; secondary actions keep their
  callbacks and enabled conditions and use outlined treatment. Enabled outlined actions
  share a1dp `fieldLine` border and `text` label; the source-policy scan rejects raw
  OutlinedButton call sites outside the shared helper. Existing Send geometry,
  input interaction, native taps, deadlines and visibility predicates stay intact.
- Attention dots distinguish `error`, `unsureLine` uncertainty and neutral retained-range
  Persimmon; explicit kind/status text keeps color from being the only signal.
- A local `pure_black` Boolean, independent of System/Light/Dark, defaulting to false
  for missing or wrong-type data. Its merged Switch row has a 48dp target. The switch
  applies immediately whenever the resolved theme is dark and survives recreation.
- Paper/Night-ink window resources, matching system-bar icons, approved adaptive and
  monochrome launcher layers, debug-only pseudo-locales and ATF only on new presentation
  tests (API34+, as required by the library).
- The approved notification silhouette is staged for the M6 notification consumer,
  retained by one explicit `tools:keep` resource declaration. No lint rule is disabled.
  Launcher XML bytes match PR103; `mipmap-anydpi` is equivalent at min26 and avoids the
  redundant-version lint finding. The night navigation-bar attribute is API27-qualified.

No navigation, transcript structure, chat authorization or delivery behavior is added.
All state and network decisions remain with their existing owners. Theme and palettes
are device preferences: the backend has no account-level theme synchronization API.

## System mode and cold start

Phase A defines and unit-tests these helpers; delivered phase B installs their
root/MainActivity call sites under the conditions below.

The app resolves one `dark` value for its palette, Pure black and bar icons. Once settings
are loaded, System reads the actual device/global configuration, rather than the app's
possibly stale per-app night override. It re-reads on every ON_START and configuration
broadcast, with no polling. While settings are loading, Compose follows the current
platform window's night configuration to avoid an intermediate opposite-color flash.

On API31+, MainActivity mirrors a loaded Theme choice to `setApplicationNightMode` only
in a genuine non-configuration onStop. An unknown in-memory last-mirrored value syncs
once at the next genuine stop; repeated unchanged stops do not write. Foreground changes
never call the setter or recreate the Activity.

Accepted limits ([technical-lead decision](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6713372317)):

- Switching to System can leave platform-drawn View surfaces using an old per-app night
  resource until the next background sync. Compose colors and bar icons update immediately.
- Process death before that sync can leave the next platform first frame using the prior
  night mode. API26–30 have no per-app mirror and can initially use the device's window
  background when an explicit app choice differs. Pure black initially uses Night ink
  before Compose draws black.

These limits need to be included in presentation review. ComponentActivity recreation
tests do not establish MainActivity cold-start/manual-device acceptance.

## Validation and evidence

Across A+B, JVM coverage checks all mapped text/control contrast pairs, exact Material role mapping,
four-only Pure-black overrides, immutable-ID identity stability, font-role isolation,
source token ownership, safe settings decoding/write failure, startup resolution and
background mirror timing/deduplication.

Phase A instrumentation checks the new primary/count components and real preference
persistence. Phase B covers the appearance switch/recreation and synthetic production-root
navigation cases. New/restyled
presentation cases enable ATF on API34+ without adding checks to historical keyboard
journeys. No assertion, native tap path or five-second input deadline is weakened.

Each delivery used its then-current full unfiltered/untraced native gate, with B's failure
preserved under its explicit exception. PR117's separate manually dispatched, non-gating
capture workflow accepts base/head refs, retains artifacts for seven days and supplies
before/after evidence at **actual360×640dp**, default and
200% resource font scale. Before uses exact production source3093e71 plus only the new
navigation test fixture; after uses the PR head. Eleven scenes cover server setup, auth,
home, Settings, Rooms, room thread, Direct inbox/thread, Activity, Account and Search.

Before has Light/Dark only. Its Dark images are the explicitly labelled reference for
new Pure black, which did not exist before. The after matrix is Light/Dark/Pure black.
Captures are owned-window synthetic fixtures, never real account content or other-app
UI. The job verifies PNG dimensions, resource font scale and complete scene names; APKs,
provenance and hashes accompany images in Actions artifacts. Normal320dp CI evidence
must never be labelled360dp, and a capture pass cannot replace any failed full gate.

APK size comparison, current-head full build/JVM/lint/native results, written code
review/re-review and the presentation lead's J review must be recorded on the PR before
delivery. Controlled fixtures do not establish manual-device or live acceptance. API26/current
physical-device, launcher appearance, cold-start, live, release/signing/distribution
and the third-party-license screen remain separate acceptance boundaries.

## Delivered evidence

- **A112**, main95062c7:236 native/1113 JVM/lint0/debug+release, two clean written
  reviews and the component-scope J pass. Actual unsigned R8 APKs were3286322B with
  the variable font and3144150B with static700/800, a142172B reduction. Compressed font
  entries fell from224875B to82531B; static font source is159008B. The source's default
  optical size is96. These are retained measured APK/payload comparisons, not estimates.
- **Infrastructure117**, maina8f7d17:240 native/1113 JVM/lint0/builds,52 helpers and two
  clean written reviews. The earlier timeout/no-screenshot capture run42439374886 stays
  an infrastructure failure, not a native or presentation pass.
- **B114**, exact9248fd369dcfb78db770f139b0cfb9912a3e298e → main3fa2b02:
  [run42456751329](https://github.com/Thiesi/ChitChat-Mobile/actions/runs/42456751329)
  passed1116 JVM/lint0/debug+release and **failed247/248 native,0 skipped**. The sole
  failure was the existing DirectAuthor initial editor's strict5000ms IME check, before
  text replacement or recreation. It remains failed under the maintainer's
  [exact-head development-only exception](https://github.com/Thiesi/ChitChat-Mobile/pull/114#issuecomment-6718681878),
  with no retry, weakened input check or root-cause claim. Both written reviews
  [6718476459](https://github.com/Thiesi/ChitChat-Mobile/pull/114#issuecomment-6718476459)/
  [6718486751](https://github.com/Thiesi/ChitChat-Mobile/pull/114#issuecomment-6718486751)
  and the final [J review6718555475](https://github.com/Thiesi/ChitChat-Mobile/pull/114#issuecomment-6718555475)
  passed. Delivery/source/merged-tree verification is on the PR.
- **B captures**, [run42456785748](https://github.com/Thiesi/ChitChat-Mobile/actions/runs/42456785748):
  complete44 before/66 after PNGs,11 scenes at both font scales, with actual viewport
  and font scale checked and restored. Before has Light/Dark; after adds Pure black.
  Final J reviewed auth/rooms/activity/direct/thread across all three palettes and both
  scales; outlined-button contrast and dot-severity regressions are resolved.
- **B size**, retained unsigned R8 APK3161426B,17276B above delivered A; its font payload
  remains82531 compressed bytes with identical font hashes. Whole-B growth is distinct
  from the variable-to-static font comparison.

Original capture/fixture negatives and the independent ReplyTargetHost subscription
regression fixed in PR115 remain recorded on their PRs. Later passes do not erase them
or resolve issue69. Existing bounded-status clipping at200% belongs to the status slice;
existing DM bubble padding belongs to the transcript slice. Controlled captures and
component checks do not establish manual-device, real launcher/cold-start/provider,
live, complete accessibility, third-party-license-screen, signing or distribution acceptance.

## Follow-on order

Delivered A/B, API35/API36 infrastructure PR129/131 and reduced PR133 test-host hygiene →
this documentation PR126 → review-bootstrap PR128 after main merge/revalidation → files/media/sharing
protocol/state/native slices → M6 push categories → onboarding/server-update → navigation shell → reporting → transcript/lists/status/
modals → separate Send when back online → CI41/IME → remaining M6 → M7 → M8, per the
[accepted coordinator plan](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6713312760).
IME diagnostics A delivered in PR113 and the standalone reproducer in PR121. Its a/b/c
measurements are consumed49/50,50/50,48/50, with all failures retained and cause unproven.
The maintainer's separate [gate-platform decision](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6720079251)
authorized the delivered CI follow-up, preserving strict inputs and API36/release acceptance.
The final bounded alignment attempt failed both ReplyComposer cases and is stopped.
PR133 delivered only test-host theme/manifest hygiene; it is not a fixture or keyboard fix.
The [final disposition](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6728684050)
keeps the mechanism at lower priority, with no further experiment scheduled. The
[temporary gate rule](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6727888625)
continues for exactly its named signature and ends on a fixture fix or demonstrated user impact.
M6 includes the approved opt-in/off-by-default ordinary DM alert once its backend
contract merges, plus the existing UnifiedPush Web Push path and embedded FCM distributor
fallback; see the [current plan](../implementation-plan.md) and
[push presentation contract](../design/push-presentation.md).
