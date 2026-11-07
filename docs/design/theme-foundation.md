# Theme foundation: implementation specification

Status: implementation-ready spec for the first presentation slice. Every later
presentation slice (onboarding, navigation shell, transcript, lists) builds on it. The
coordinator schedules it.

**Goal:** CCM renders with the Lantern palette, type and shapes, with the appearance settings
from [amoled-black.md](amoled-black.md), **without changing any layout, copy, flow or
behavior**. Screens keep their structure; only tokens change. Component wrappers are added
where Material defaults would break the spec.

Sources of truth: values in [visual-vocabulary.md](visual-vocabulary.md), AMOLED rules in
[amoled-black.md](amoled-black.md), floors in [acceptance.md](acceptance.md). This document
says *how* to wire them into the code as it is on 2026-11-07.

## Today's theme (2026-11-07)

- `app/ChitChatApp.kt` builds an inline `lightColorScheme`/`darkColorScheme` with three teal and
  cream values. These are the only literal `Color(0x…)` values in `app/src/main`.
- Feature code reads only a few roles: `error` (7 uses), `primary` (3), `onSurface` (3),
  `surface`, `background`, `surfaceContainer`. Material components supply the rest of the
  colors from the scheme.
- Material component usage in `app/src/main`: about 124 `TextButton`, 21 `Button`, 8
  `OutlinedButton`, 8 `IconButton`, 21 `AlertDialog`, 12 `OutlinedTextField`, 17
  `CircularProgressIndicator`, 5 `LinearProgressIndicator`, 10 `Badge`, 6 `Card`, 3
  `FilterChip`, 2 `RadioButton`, 1 `Switch`, 1 `TopAppBar`.
- Typography is Material defaults on the system font, with 19 files reading
  `MaterialTheme.typography`. No custom shapes.
- `res/values/themes.xml` uses `android:Theme.Material.Light.NoActionBar`, so the window
  background is light even when the app is dark. `MainActivity` sets transparent edge-to-edge
  bars through `onThemeChanged(dark)`.
- Settings: `ThemePreference { SYSTEM, LIGHT, DARK }` in DataStore key `theme`, decoded by
  `decodeSettings`. The Appearance section is a radio list in `SettingsScreen`.
- Launcher icon: `@drawable/ic_chitchat`, a teal vector that isn't adaptive.

## Structure

Create a `core/theme` package (name is the implementer's choice) containing:

1. **`LanternPalette`**: three immutable token sets, `Light`, `Dark` and `AmoledBlack`, holding
   every token from visual-vocabulary.md (core tokens, `unsureLine`, the eight identity hues).
   `AmoledBlack` is `Dark` with the four overrides from amoled-black.md and nothing else.
2. **`ChitChatColors`**: the extended tokens Material has no role for, provided through a
   `staticCompositionLocalOf`: `signature`, `onSignature`, `signatureText`, `outgoing`,
   `onOutgoing`, `presence`, `presenceWash`, `steward`, `onSteward`, `unsureLine`, `raised`,
   `line`, `fieldLine`, `textMuted`, `identityHues`. Read through an accessor such as
   `ChitChatTheme.colors`.
3. **`ChitChatTheme(appearance, content)`**: chooses the palette, builds the Material
   `ColorScheme`, `Typography` and `Shapes`, provides `ChitChatColors`, and wraps
   `MaterialTheme`. It replaces the inline block in `ChitChatApp.kt`. `onThemeChanged(dark)`
   keeps working for system bars.
4. **Identity helpers**: `identityHue(id: Long)` (a stable hash of the immutable server ID
   into the eight hues; never derived from names), `RoomTileShape`, `PersonShape`,
   `BubbleShape`. They are defined now and used by later slices.
5. **Component wrappers** in `core/ui`, where Material defaults contradict the spec (see below).

## Material color mapping

| Material role | Light | Dark | AMOLED black | Why |
|---|---|---|---|---|
| `primary` | `signatureText` `#B5401A` | `#F06A3B` | `#F06A3B` | Text-safe: about 124 `TextButton`s, radios, switches, progress and focus indicators use `primary` as a foreground. Persimmon `#F06A3B` is only 2.9:1 on paper |
| `onPrimary` | `#FFFFFF` (5.7:1) | `#14111C` | `#14111C` | Only for the fallback of unwrapped filled buttons |
| `primaryContainer` / `onPrimaryContainer` | `outgoing` `#FFD9C4` / ink | `#8A3A1E` / `#F2EDF7` | same as Dark | Selected chips, tonal accents |
| `secondary` / `onSecondary` | `steward` `#3D52C4` / `#FFFFFF` | `#A9B6FF` / ink | same as Dark | Stewardship accents |
| `tertiary` / `onTertiary` | honey `#FFB547` / ink | same | same | Presence accents (fills only, never text on light) |
| `tertiaryContainer` / `onTertiaryContainer` | `presenceWash` / ink | `#4A3A1C` / `#F2EDF7` | same as Dark | Warning strips, mention highlight |
| `background`, `surface` | `ground` | `ground` | `#000000` | Screens draw on the ground |
| `surfaceContainerLowest`, `…Low`, `surfaceContainer` | `surface` `#FFFFFF` | `#1C1826` | `#110E17` | Cards, sheets, dialogs, bars |
| `surfaceContainerHigh`, `…Highest`, `surfaceVariant` | `raised` | `raised` | `#1F1B29` | Raised rows, quiet chips |
| `onBackground`, `onSurface` | `text` | `text` | `text` | |
| `onSurfaceVariant` | `textMuted` | `textMuted` | `textMuted` | |
| `outline` | `fieldLine` | `fieldLine` | `fieldLine` | 3:1 component boundaries |
| `outlineVariant` | `line` | `line` | `#2E2938` | Decorative dividers |
| `error` / `onError` | rose `#C4314B` / `#FFFFFF` | `#FF8A9B` / ink | same as Dark | |
| `scrim` | `#14111C` | `#000000` | `#000000` | Alpha applied by components |
| `inverseSurface` / `inverseOnSurface` | ink / paper | paper / ink | paper / ink | Snackbars |

**Every pair above is covered by the P-02 contrast test**, not only the pairs listed in
visual-vocabulary.md. In particular, `onPrimary` on `primary` and `onError` on `error` must
pass 4.5:1 in all three palettes.

## Component wrappers (in `core/ui`)

Material defaults that would contradict the spec get a thin wrapper. Feature code uses the
wrapper instead of the raw component.

| Wrapper | Replaces | Behavior |
|---|---|---|
| `PrimaryButton` | the 21 filled `Button`s that are a screen's primary action | Container `signature` `#F06A3B`, content `onSignature` ink, in every palette. Full pill, 52dp default height (48dp minimum target). `Button`s that are not the primary action become `OutlinedButton` or `TextButton` per P-13 (at most one persimmon-filled primary button per screen, besides Send and badges) |
| `CountBadge` | the 10 `Badge` uses | Material `Badge` defaults to `error`, which would turn every unread count red. Container `signature`, content ink, "99+" cap kept. `MentionBadge` variant: honey with "@". Never `error` |
| Send button | existing `MessageSendButton` | Re-tint to `signature`/ink when enabled and `raised`/`textMuted` when disabled. Shape and target unchanged |

Everything else keeps the Material component, re-colored by the scheme: `TextButton`,
`OutlinedButton`, `IconButton`, dialogs, fields, progress indicators, chips, radio, switch
and the app bar. Don't introduce other wrappers in this slice.

A source-scan unit test (P-01) fails on:
- literal `Color(0x` outside the theme package;
- raw `Badge(` outside `CountBadge`;
- raw filled `Button(` in `feature/` outside an explicit allowlist that is empty after this
  slice.

## Typography

- **Font:** bundle Bricolage Grotesque (SIL OFL 1.1) as one variable TTF in `res/font`. Use
  the static fallback Google Fonts ships if variable-font weights misbehave on API 26–27 in
  testing. Subset it to Latin plus Latin Extended (for example with `pyftsubset`). Record the
  exact command and source version in a short comment in the font's license file. Commit
  the subset font, not the build step.
- **License:** add `docs/licenses/bricolage-grotesque-OFL.txt`. Before release, the app must
  also show it wherever third-party licenses are listed; add that screen if none exists
  (release gate, not this slice).
- **No downloadable fonts.** Google Play services font providers are not used (no-GMS
  stance).
- **Mapping:** `displayLarge` through `headlineSmall` and `titleLarge` use Bricolage
  Grotesque, weight 700 for `title*`/`headline*` and 800 for `display*`, letter spacing
  −0.01em. `titleMedium`, `titleSmall`, `body*` and `label*` stay on `FontFamily.Default`,
  keeping Material default sizes. Names in list rows use weight 600 when later slices restyle
  lists. Sizes stay at Material defaults in this slice; display sizes are tuned by the slices
  that use them.
- All sizes stay in `sp` (A-04). Message text is never Bricolage (A-11). Today messages use
  `body*` styles, so this mapping keeps that true.

## Shapes

`Shapes(extraSmall = 8.dp, small = 12.dp, medium = 16.dp, large = 22.dp, extraLarge = 28.dp)`
as rounded corners. Buttons stay full pill (Material default). Fields use `medium` (16dp),
cards `large`, dialogs and sheets `extraLarge`. Identity shapes are exposed separately (see
Structure) and are not part of Material `Shapes`.

## Appearance settings

- Extend settings with `pureBlack: Boolean` in a new DataStore boolean key, `pure_black`.
  `decodeSettings` treats a missing or unreadable value as `false`. The existing `theme` key
  and its values are unchanged.
- Appearance in `SettingsScreen` keeps the System/Light/Dark radios and adds the **Pure black
  in dark mode** switch with its supporting text (amoled-black.md P-05). A full `Switch` row
  with `Role.Switch` merged semantics and a 48dp minimum height.
- Changing either value updates the theme immediately without recreating the Activity.

## Window, system bars and cold start

- Add `res/values-night/themes.xml`: parent `android:Theme.Material.NoActionBar`,
  `android:windowBackground` Night ink `#14111C`, light status-bar icons. Change the day theme's
  window background to Paper `#FBF7F1`.
- On API 31+, mirror the in-app Theme choice into `UiModeManager.setApplicationNightMode`
  (`MODE_NIGHT_AUTO` for System, `MODE_NIGHT_NO` for Light, `MODE_NIGHT_YES` for Dark), so
  the cold-start window already uses the right resources. This needs no new permission.
- **Known limit, API 26–30:** when the in-app choice differs from the device mode (for
  example app Dark on a light device), the first frame before Compose draws can still use the
  device's window background. Accept this there. On API 31+, P-08 applies in full.
- Pure black is app-internal. Cold start shows Night ink for the first frames, then black
  (allowed by amoled-black.md).
- `onThemeChanged(dark)` keeps driving transparent edge-to-edge bars with matching icon
  contrast for all three palettes.

## Launcher icon

Replace the teal `ic_chitchat` with an adaptive icon (`mipmap-anydpi-v26`, since min SDK is 26):
- background layer Night ink;
- foreground layer: the persimmon bubble with two ink eyes and the detached chit, inside the
  66dp safe zone;
- a `monochrome` layer for themed icons.

The vector paths follow the mark in visual-vocabulary.md. The app name and application ID
are unchanged; final public identity remains the maintainer's decision.

## Out of scope for this slice

Layout, spacing and copy changes, navigation (D3), bubble and chit rendering, room and
person avatars in lists, onboarding screens and any behavior change. Those are later slices
that consume these tokens.

## Tests and evidence

- **JVM:**
  - P-02 contrast test over every mapped pair in all three palettes;
  - `identityHue` stability: same ID, same hue; rename-independent; distribution over all
    eight hues;
  - settings decoding for `pure_black` (missing means false);
  - the P-01 source scan.
- **Compose:**
  - Pure black applies whenever dark (Dark, or System with the device dark) and survives
    recreation;
  - Appearance switch semantics;
  - `CountBadge` never uses `error`;
  - `PrimaryButton` is at least 48dp.
- **Existing tests:** every native input, IME and visibility test runs unchanged. A theme
  change must not alter tap targets, focus or keyboard behavior. Text-based selectors
  are unaffected, because copy doesn't change.
- **Evidence in the PR:**
  - before and after screenshots of each top-level screen in Light, Dark and AMOLED black,
    at default and 200% font scale;
  - the APK size delta (font and icon).

## Acceptance

This slice satisfies P-01 to P-08 and A-01, A-02, A-10 and A-11, plus:

| ID | Requirement | Verify |
|---|---|---|
| P-60 | No layout, copy, navigation or behavior change. Only tokens, wrappers, settings, window resources and the launcher icon differ. Existing tests pass unchanged in meaning. | R, existing tests |
| P-61 | All Material color roles map exactly as in the table, in all three palettes, and every mapped foreground/background pair passes its contrast floor. | U |
| P-62 | Primary filled actions use `PrimaryButton` (persimmon with ink in every palette). Count badges use `CountBadge` (never `error`). | U (source scan), S |
| P-63 | Bricolage Grotesque is bundled, subset, OFL-licensed in `docs/licenses/`, and used only for the display and title-large roles. | R, S |
| P-64 | On API 31+, cold start uses the window background matching the in-app Theme choice. | M |
| P-65 | The launcher icon is adaptive with a monochrome layer and renders the Lantern mark. | M |
