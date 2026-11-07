# Invite codes

Status: specification for approved decision D5 (2026-11-07). An invite code lets someone
who already uses a ChitChat installation bring a friend to it without spelling out the
server address. Mockups: `Invite` and `Scan` on the canvas, sources under `mockups/project/`.
Onboarding context: [onboarding.md](onboarding.md).

## What an invite code is

**An invite code is the installation's plain HTTPS origin, for example
`https://chat.schmidt.family/`, shown as a QR code or shared as a link.**

It carries nothing else: no account, no token, no username, no room, no expiry. It
grants no access. It saves typing, and the server still decides who may register or sign
in.

Why a plain HTTPS URL rather than a CCM-only format:

- **It works without the app.** Scanned with any phone camera, or tapped in a chat, it opens
  the web client in a browser. A custom scheme (`chitchat://…`) would be a dead link for
  everyone who hasn't installed CCM yet, which is exactly the person being invited.
- **It needs no backend change.** Android App Links would need a verification file on every
  self-hosted server, so a tapped link opening CCM directly is out of scope. Scanning inside
  CCM covers the app path.
- **There is nothing secret to protect.** The origin is what people would otherwise type.

This refines D5's "client-only QR/link format": it is client-only and carries only the
origin. The format simply reuses an ordinary HTTPS URL instead of inventing a new one.

## Sharing an invite (You → Invite someone)

Entry point: a row "Invite someone" on the You screen, under the profile header. It is
available to every signed-in member. Guests (M7) don't get it.

The Invite screen shows:

- The installation lantern, name and host. If the public name is unknown, the host is the
  name (same rule as onboarding step 3).
- A QR code of the origin. Dark modules are ink on a white quiet zone in **every** theme,
  including Dark and AMOLED black, because inverted or colored codes scan poorly. Minimum
  220dp square, with a quiet zone of at least 4 modules. Error correction level M. The
  lantern logo may sit in the center only if the code still scans with level M; otherwise
  leave the center empty.
- The origin as selectable text under the code.
- **Share link**: the system share sheet with the text "Join me on Schmidt Family Chat:
  https://chat.schmidt.family/". The installation name is used only when known; otherwise
  "Join me on ChitChat: …".
- **Copy link**: copies the origin. A snackbar "Link copied" is acceptable here (level 1, the
  person can see the result).
- One line of honest context: "This only shares the server address. The server decides who
  can join; if sign-up is closed, they'll need an invitation from whoever runs it."

Generation happens on the device. It makes no network request, sends nothing to the server
and writes nothing to disk. The QR bitmap is generated in memory for display and dropped
with the screen.

## Scanning an invite

Entry points:

- Welcome: **Scan an invite code**.
- Onboarding step 2: the "Got an invite code?" card's **Scan**.
- Signed-in switching of servers is out of scope for scanning. The existing rule applies:
  sign out before switching servers.

The Scan screen:

- Camera preview with a square viewfinder, the line "Point your camera at the code", a
  **Choose from photos** button (decodes a QR code from an image through the system photo
  picker, so a code received as a screenshot works without camera permission), and **Type
  the address instead**.
- The camera permission is requested only when the person opens Scan. If they decline,
  the screen shows the photo and type options with "Camera access is off. You can still pick
  a photo of the code or type the address." It never re-asks in a loop, and it offers the
  system settings link only after a permanent denial.
- The camera runs only while Scan is visible and stops immediately on background, on a
  successful decode or on leaving the screen.

What a decoded code may contain, and what CCM does with it:

| Content | Result |
|---|---|
| `https://host[:port]/…` | Take only the origin (scheme, host, port). Discard path, query and fragment unread, then apply the existing M2a normalization and continue to onboarding step 3. |
| `https://user:pass@host/` (credentials in the URL) | Reject: "This code contains a password. For your safety it wasn't used." Never log or store it. |
| `http://…` or any other scheme | Reject: "This isn't a ChitChat invite. ChitChat addresses start with https." |
| Plain text that M2a would accept as a bare host (`chat.example.org`) | Accept as if typed, then continue to step 3. |
| Anything else (Wi-Fi codes, contacts, payment codes, long text) | Reject: "This QR code isn't a ChitChat invite." The content is not shown, copied or opened. |

Rules:

- A scan **never connects or signs in by itself**. It only fills the address. Onboarding
  step 3, showing the found server's name, host and lock, always appears, so the person sees
  where they are going before entering credentials.
- Host names follow the existing M2a IDN rules. Step 3 shows the host as the person will
  see it in the address field, so a look-alike domain is visible before any credentials are
  entered.
- Decoded content is never opened in a browser, never logged and never kept after the
  address field is filled. Path and query can carry other people's tokens and are dropped
  without being read into app state.
- After a rejection, the camera stays open for the next try. Messages are polite live
  regions.

## Accessibility

- Every control on Invite and Scan meets A-03 (48dp) and A-06 (labels). The QR image has
  the content description "QR code for chat.schmidt.family". The selectable origin text
  carries the same information, so the QR code is never the only way to share.
- **Type the address instead** is always visible on Scan, so nobody depends on the camera.

## Implementation notes

- Decoding and encoding need a QR library. Recommendation: ZXing core (Apache-2.0, pure
  Java, no Google Play services) for encoding and for decoding frames and picked images,
  with CameraX for the preview. Google's ML Kit and Code Scanner depend on Play services,
  which conflicts with the no-GMS push stance, so they are not recommended. **ZXing core plus
  CameraX is approved in principle** (technical lead, 2026-11-07). The final compatible
  dependency-set review happens in the implementing slice, scheduled after the onboarding
  restyle (see `AGENTS.md` on compatible dependency sets). Report the APK size impact in the
  implementing PR.
- No new permissions besides `CAMERA`, requested at runtime only. The camera must be
  declared optional (`android:required="false"`) so devices without one can still install
  CCM.
- Nothing in this feature talks to the server until onboarding step 3's normal checks.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-40 | The shared link and the QR payload are exactly the normalized origin with a trailing slash. No path, query, fragment, credentials or user data. | U |
| P-41 | Scan accepts only the inputs in the table above, extracts the origin, and always shows onboarding step 3 before any credential entry. | U (payload parser), C |
| P-42 | URLs with credentials, non-HTTPS schemes and foreign QR types are rejected with the specified copy. Their content is never displayed, logged, opened or stored. | U, R |
| P-43 | The camera starts only on the Scan screen after permission, and stops on background, decode or leaving. Denial leaves the photo and typing paths fully usable. | C, M |
| P-44 | The QR code renders dark-on-white in every theme and scans with a second device's camera at default screen brightness. | M |
| P-45 | Generation and display make no network request and write no file. | R |
