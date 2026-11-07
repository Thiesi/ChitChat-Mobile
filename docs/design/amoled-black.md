# AMOLED black theme

Status: specification for the theme the maintainer requires before CCM goes live (D6,
approved 2026-11-07). Tokens are summarized in [visual-vocabulary.md](visual-vocabulary.md).
This document covers the full behavior. Mockups: `AmoledRoom`, `AmoledInbox` and
`Appearance` on the canvas, sources under `mockups/project/`.

## Purpose

On OLED screens, black pixels are switched off. A true-black theme saves power when the
screen shows mostly dark content, and many people simply prefer it at night. It is a
comfort and battery option, not an accessibility theme. High contrast remains a separate
palette (D6).

AMOLED black must still look like Lantern. Persimmon, the chit, lanterns and pebbles, the
honey glow and the voice are unchanged. Only the dark grounds go deeper.

## How people choose it

Appearance gets two controls (see the `Appearance` mockup):

1. **Theme:** System · Light · Dark. This is the existing local preference.
2. **Pure black in dark mode:** a switch, off by default.

The switch applies whenever the app is dark: when Theme is Dark, and when Theme is System
and the device is in dark mode. It has no effect while the app is light, but it stays
available so people can set it before night falls. The supporting text says so: "Uses true
black whenever ChitChat is dark. Saves power on OLED screens."

This is a two-control model, not a fourth Theme choice, so "follow the system" and "pure
black" can combine. It replaces the single-choice wording in earlier drafts.

Storage: local device setting next to the existing theme preference (for example
`AppSettings.pureBlack: Boolean = false`). A missing or unreadable value means off. It is
not synchronized with the account, because the web client has no equivalent. Changing it
applies immediately without restarting the Activity.

## Tokens

AMOLED black overrides four dark tokens. Every other dark token, including identity hues,
persimmon, honey, dusk, rose, ember bubbles and text colors, stays as defined for Dark.

| Token | Dark (Night ink) | AMOLED black | Notes |
|---|---|---|---|
| `ground` | `#14111C` | `#000000` | Screen, transcript and list backgrounds |
| `surface` | `#1C1826` | `#110E17` | Fields, sheets, dialogs, bottom bar, cards |
| `raised` | `#272233` | `#1F1B29` | Incoming bubbles, quiet chips, offline strip |
| `line` | `#3A3347` | `#2E2938` | Dividers, and the top edge of sheets and the bottom bar |

Checked pairs (WCAG ratios): `text #F2EDF7` on black 18.2:1 and on raised 14.6:1;
`textMuted #A79FB4` on black 8.3:1 and on raised 6.6:1; `fieldLine #7D7490` on surface
4.3:1 and on black 4.8:1; persimmon on black 6.8:1; honey 12.0:1; dusk `#A9B6FF` 10.8:1;
rose `#FF8A9B` 9.4:1; `onOutgoing` on ember 6.7:1 (unchanged).

## Rules

- **Large areas are true black.** The ground of every screen, the transcript, lists, and the
  system status and navigation bar areas. Content that scrolls sits on black.
- **Edges replace shadows.** Shadows are invisible on black. Sheets, the bottom bar,
  dialogs and floating buttons separate by surface tone plus a 1dp `line` edge where they
  meet black. The FAB keeps its persimmon fill and needs no edge.
- **Scrims** over black use `rgba(0,0,0,0.6)` and the surface above them gets a `line` edge.
- **The glow** keeps its ground-colored gap (now black) inside the honey ring, so it still
  reads as a ring and not a blob.
- **No pure white.** Text stays `#F2EDF7`. Large white areas are what make OLED glare and
  burn-in risk worst.
- **Keep persistent bright areas small.** Persimmon stays limited to actions, badges and
  the active tab pill. Do not introduce permanent bright bars or large tinted headers in
  this theme.
- **Images and avatars** are unchanged. Media content is never darkened or inverted.
- **Black smear.** Some OLED panels smear when dark-gray content scrolls over black. Bubbles
  and rows use `raised` (`#1F1B29`), which is light enough to keep motion readable. Do not
  darken `raised` further to chase power savings.

## System integration

- Edge-to-edge stays on. In AMOLED black the status and navigation bar areas show black
  with light icons.
- Cold start: the window background before Compose draws must never flash light when the
  app will be dark. Use a night-qualified window background of Night ink `#14111C`. Night ink
  is acceptable for the first frames in AMOLED black, because the setting is only known after
  settings load. A light flash is not acceptable in either dark variant.
- The notification accent and launcher icon are unchanged.

## Acceptance

These extend [acceptance.md](acceptance.md):

| ID | Requirement | Verify |
|---|---|---|
| P-05 | The Pure black switch exists in Appearance with the supporting text above. It applies whenever the app is dark (Dark, or System while the device is dark), immediately, without restarting the Activity, and survives process death. | C, U (settings decoding, missing value means off) |
| P-06 | With Pure black on and the app dark, `ground` is exactly `#000000` on every screen, including the system bar areas, and the other three overridden tokens match the table. No other token differs from Dark. | U (theme token test), S |
| P-07 | Sheets, dialogs and the bottom bar have a visible 1dp `line` edge against black. No surface relies on a shadow for separation in this theme. | S |
| P-08 | No light flash at cold start or on Activity recreation while the app is dark, in either dark variant. On API 26–30, this holds when the in-app Theme matches the device mode. See the known limit in [theme-foundation.md](theme-foundation.md). | M |
| A-10 | (existing) The AMOLED palette passes A-01 and A-02, as covered by the token contrast test. | U |

Judgment: placed beside the Dark mockups, AMOLED screens must read as the same product at
night, deeper but not harsher (J-01, J-02).
