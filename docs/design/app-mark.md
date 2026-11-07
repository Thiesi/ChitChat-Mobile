# App mark and icon assets

Status: final geometry for the Lantern mark (visual-vocabulary.md, "The app mark") and
ready-to-copy Android assets. Theme foundation P-65 consumes them. The app's name and
application ID are unchanged; final public identity remains the maintainer's decision
(`AGENTS.md`). Preview: the `AppMark` board on the canvas.

## Geometry (108dp adaptive canvas)

| Part | Shape | Values |
|---|---|---|
| Background | Full canvas | Night ink `#14111C` |
| Bubble | Rounded rectangle | x 25–67, y 30–64, corner radius 14, persimmon `#F06A3B` |
| Eyes | Two circles | Centers (39, 47) and (53, 47), radius 3.5, Night ink |
| Chit | Circle | Center (72, 70), radius 5, persimmon |

- Everything sits inside Android's 66dp safe zone (a circle of radius 33 at the center). The
  furthest point, the bubble's top-left arc, is 32.0dp from the center, so circle, squircle
  and rounded-square launcher masks never clip the mark.
- The chit sits down and to the right of the bubble, the speaker corner, mirroring the
  transcript's own-message chit. The mark does **not** mirror for RTL: it is a logo, not a
  layout.
- No glow in the launcher icon. Launchers add their own effects, and a baked glow muddies
  monochrome theming. The honey glow stays an in-app motif (Welcome screen, liveliness).

## Assets

| File | Use |
|---|---|
| `assets/lantern-mark.svg` | Master artwork (Welcome screen, store and docs) |
| `assets/android/drawable/ic_launcher_foreground.xml` | Adaptive-icon foreground |
| `assets/android/drawable/ic_launcher_monochrome.xml` | Themed-icon layer (Android 13+): single color, eyes cut out |
| `assets/android/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive-icon definition. Add `<color name="ic_launcher_background">#14111C</color>` |
| `assets/android/drawable/ic_stat_chitchat.xml` | Notification small icon (24dp silhouette; accent persimmon set in code, push spec) |

Implementation copies these into `app/src/main/res/`, replaces `@drawable/ic_chitchat` in the
manifest with `@mipmap/ic_launcher`, and keeps `ic_chitchat` only if something else still
references it. Min SDK is 26, so no legacy PNG launcher icons are needed. Store and release
artwork (Play feature graphic, F-Droid metadata) is a release-time task, derived from the master
SVG.

## Small-size and in-app versions

- At 24dp and below (notification icon, Rooms tab icon), the eyes shrink to radius 1.3 at
  (7.4, 9.5) and (12.6, 9.5) on a 24dp canvas, and the chit to radius 2.1. Smaller eyes
  would close up.
- The in-app Welcome mark may add the honey radial glow behind the bubble (visual-vocabulary.md).
  It is the only place the glow belongs to the mark.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-140 | The launcher icon is adaptive (foreground, Night ink background, monochrome) with exactly this geometry. No mask clips the mark. | M (circle, squircle and rounded-square launchers; themed icons on) |
| P-141 | The notification small icon is the 24dp silhouette, legible in the status bar at 1× density. | M |
