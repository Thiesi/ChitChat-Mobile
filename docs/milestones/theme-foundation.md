# Lantern theme foundation

Status: phase A defines the core primitives, preference storage and assets. Phase B
integrates the app, its appearance controls and existing components. Each delivery needs
its own current-head development gates and applicable J review, recorded on its PR.
This is the token prerequisite for later presentation slices, following delivered
M5d4c (PR97, main3093e71). It consumes the approved
[theme brief](../design/theme-foundation.md), [Pure black contract](../design/amoled-black.md)
and adaptive icon assets from PR103. No earlier native exception applies to this slice.

## Delivery split

The complete patch exceeded the existing126,000-byte written-review limit. It is split
without raising that limit ([engineering record](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6715004003)):

- **Phase A, delivered PR112:** palettes, Material/type/shape definitions, identity helpers,
  thin PrimaryButton/CountBadge definitions, local Pure-black storage and VM API,
  licensed font and approved icon/window resources. Debug pseudo-locales and ATF are
  enabled for the new component tests. Existing test stores implement the added setting.
- **Phase B, this slice:** root theme and switch, actual device-night observation and background
  mirror call sites, existing-screen wrapper/color/shape migration, source-policy and
  appearance journeys, and before/after capture infrastructure.

Phase A exposes no Pure-black switch and does not replace the root's existing Material
theme. Its new controls are exercised as components. Full-screen presentation and the
Light/Dark/Pure-black default/200% evidence belong to phase B. The following sections
describe the combined foundation contract; they do not grant phase A consumer acceptance.

## Complete foundation contract

- Immutable Light, Dark and Pure-black palettes; explicit Material roles, extended
  colors, stable ID-based identity hues and shared shapes.
- Bundled Latin/Latin-Extended Bricolage Grotesque, as static700/800 faces at the source's
  default optical size/width, for display, headline and titleLarge
  roles only. Body, message, label and smaller title typography retain system fonts
  and Material sizes. [Font license and reproducible subset provenance](../licenses/bricolage-grotesque-OFL.txt).
- Persimmon/ink primary actions and unread counts; secondary actions keep their
  callbacks and enabled conditions and use outlined treatment. Existing Send geometry,
  input interaction, native taps, deadlines and visibility predicates stay intact.
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

Phase A defines and unit-tests these helpers. Phase B adds their root/MainActivity call
sites; phase A does not install a per-app night override.

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
persistence. Phase B adds the appearance switch/recreation and synthetic production-root
navigation cases. New/restyled
presentation cases enable ATF on API34+ without adding checks to historical keyboard
journeys. No assertion, native tap path or five-second input deadline is weakened.

Each PR's full native gate remains one complete unfiltered/untraced suite. Phase B's separate bounded
capture-only workflow provides before/after evidence at **actual360×640dp**, default and
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

## Follow-on order

Theme primitives (A) → app/component integration (B) → files/media/sharing protocol/state and native slices → M6 durable
push categories → onboarding → navigation shell → reporting → transcript/lists/status/
modals → separate Send when back online → CI41/IME → remaining M6 → M7 → M8, per the
[accepted coordinator plan](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6713312760).
IME PR A may proceed separately while this theme PR waits for CI/reviews, under its
[bounded authority](https://github.com/Thiesi/ChitChat-Mobile/issues/69#issuecomment-6713567378).

The capture workflow accepts manual base/head refs; artifacts expire after seven days.
