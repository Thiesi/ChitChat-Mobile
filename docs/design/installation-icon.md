# Installation icon

How the app shows an installation's own icon where it already shows the installation's name.

**Status: Dependency ChitChat#211** (open, design first). ChitChat#211 lets a Super
Administrator upload an installation icon, and the session response gains `application.icon`:
`{url, sha256}` for a 512×512 PNG, or `null` for the ChitChat default (field shape as relayed
by the technical lead on 2026-11-09; confirm it against the merged contract). It isn't in
v3.5.0. Like every feature from a later release, it ships only once the technical lead raises
the minimum server version (one rule: the minimum version, server-update.md). Until then,
implementers leave it out and every place below keeps the initial lantern it has today.

## Where it appears

The icon replaces the **initial** inside the installation lantern (squircle, identity hue from
the host), and nothing else changes:

- **Onboarding step 3, "Server found"** (onboarding-restyle.md): the server card's lantern.
  The installation name and the host line stay exactly as specified. The icon is chosen by
  the operator, just like the name, so it never replaces the host as the thing a person checks
  before signing in.
- **The You hub's Server row** (navigation-shell.md): a 40dp lantern leading the row that
  shows the host.
- **The server update notice at step 3** (server-update.md) and **the Invite screen**
  (invite-codes.md), which already show the installation lantern: the same component. The
  QR code's center stays as invite-codes.md specifies; the icon isn't placed inside the code.

It doesn't appear anywhere else:

- **Not the app's icon.** The launcher icon, the notification small icon and the in-app mark
  stay the ChitChat mark (app-mark.md). An operator's icon doesn't rebrand the app.
- **Not in notifications.** Notifications keep the installation name only. A large icon would
  need a fetch while the app is in the background.
- **Not a room or person.** Rooms keep their own lanterns, and people keep pebbles.

## Drawing

- The PNG fills the squircle at the lantern's size (48dp on the step 3 card, 40dp in the row),
  clipped to the squircle, with no tint and no padding. A 1dp `line` hairline on the squircle
  keeps a white or black icon visible on paper, night ink and Pure black.
- Until the image is ready, the initial lantern shows. The decoded icon then swaps in at the
  same size without moving the layout. There's no spinner and no placeholder shimmer.
- **Accessibility:** the icon is decorative. The installation name (or host) next to it is the
  text, so the image has no content description and TalkBack doesn't announce it.

## Fetching and caching

- **When:** only after a successful bootstrap reports `application.icon` with a URL, at step 3
  (anonymous) or in the signed-in session. `null` or a missing field means the initial lantern,
  silently, and a missing field never triggers the server update notice (it isn't a
  session-level feature key).
- **Where from:** the URL is resolved against the chosen origin through the existing session
  transport and same-origin rules. A URL that resolves to another origin isn't fetched.
  Redirects stay off. At step 3 this is the same server the person just chose, so no new party
  is contacted.
- **Checks:** a bounded response (1 MiB), `image/png`, decoded with bounds to exactly 512×512,
  and the bytes' SHA-256 must match `sha256`. Any failure (network, status, type, size,
  dimensions, hash or decode) shows the initial lantern, with no notice, no log of the URL and
  no retry within the session.
- **Cache: memory only** (the image policy in files-media.md and the approved privacy policy's
  "in memory only"). The icon is public branding, so the cache is keyed by origin and `sha256`
  rather than by account. It's dropped when the server changes and when the process ends. A
  changed `sha256` in a later bootstrap is a new icon. Nothing goes to disk.

## Tests

- **Unit:** URL resolution refuses another origin; size, type, dimension and hash checks each
  fall back to the initial; `null` and a missing field give the initial and never the update
  notice; the cache key changes with `sha256` and is cleared on server change.
- **Compose:** step 3 and the Server row show the icon in the squircle when it's ready and the
  initial before and after a failure, with the same bounds; the image has no content
  description and the name and host remain.

## Acceptance

| ID | Requirement | Verified by |
|---|---|---|
| P-210 | When the minimum includes ChitChat#211, step 3, the You hub's Server row, the server update notice and the Invite screen show the installation icon clipped to the lantern squircle, and the initial lantern before it's ready, when it's `null` or missing, and on any failed check. The name and host stay unchanged, and the launcher, notification and app marks stay ChitChat's. | U, C |
| P-211 | The icon is fetched only from the chosen origin (no redirects), at most 1 MiB, PNG, exactly 512×512 and matching `sha256`, held only in memory keyed by origin and hash, and never written to disk or logged. | U |
