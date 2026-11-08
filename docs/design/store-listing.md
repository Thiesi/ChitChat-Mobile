# Google Play store listing: proposal

Status: **proposal**, requested by the technical lead (issue #1). The application ID, final app
name, listing text and graphics are **maintainer decisions**. Nothing here is submitted. Facts
in the listing must match the build being released: features not shipped at release are left
out, never promised. Screenshots are **placeholders** taken from the Lantern mockups until real
captures exist. Mockup: `FeatureGraphic` on the canvas.

Fixed by maintainer decisions: CCM is the **generic ChitChat app**, the publisher is **NetWork
XXIII**, and the public pages are at **https://ChitChat.NetWorkXXIII.de**.

## Listing fields

| Field | Proposal | Limit |
|---|---|---|
| App name | **ChitChat: Community Chat** (alternative: "ChitChat for your server") | 30 characters |
| Short description | **Chat with your own community, on your own server. Rooms, DMs, no ads.** (71) | 80 characters |
| Category | Communication | — |
| Tags (Play's list) | Messaging, Group chat (pick from Play's available tags at submission) | up to 5 |
| Contact email | chitchat@NetWorkXXIII.de (once the alias works) | — |
| Website | https://ChitChat.NetWorkXXIII.de | — |
| Privacy policy | https://ChitChat.NetWorkXXIII.de/app/privacy | required |
| Account deletion URL | https://ChitChat.NetWorkXXIII.de/app/delete-account | required |
| Ads | None | — |
| In-app purchases | None | — |

### Full description (≤ 4,000 characters)

Sections in [brackets] are included only if the feature has shipped in the release build.

> **Your community's chat, on your community's server.**
>
> ChitChat is the app for ChitChat servers: self-hosted chat run by families, clubs, teams and
> friends. Enter your server's address, sign in or create an account, and you're in.
>
> **Rooms where people gather.** See who's here right now, join the rooms you like, and pick
> up conversations where you left them.
>
> **Direct messages** with the people on your server, plus reactions, replies, mentions and
> search.
>
> [**Photos and files.** Share pictures and documents. Location data is removed from photos
> before they're sent.]
>
> [**Notifications without Google lock-in.** ChitChat uses UnifiedPush and works with the
> delivery app you choose, with an automatic fallback when none is installed.]
>
> **Calm by design.** No ads, no tracking, no analytics. Light, dark and pure-black themes for
> OLED screens. Built for TalkBack, large fonts and keyboard use.
>
> **Honest about privacy.** Your messages are stored on your server, run by the people you
> chose. They aren't end-to-end encrypted, and the app tells you what your server's retention
> and inspection settings are.
>
> **Safe spaces.** Report messages, ignore people in rooms, block people in direct messages,
> and agree to community guidelines before you post.
>
> ChitChat needs a ChitChat server. If someone invited you, they'll know the address. Server
> operators: ChitChat is open-source and self-hosted.
> [Link to the server project only if the maintainer wants it in the listing.]

Rules for the description:
- No claims about features not in the release build. Remove the bracketed sections if they
  haven't shipped.
- Never say "secure messaging" or "encrypted chat" without the "not end-to-end encrypted" line.
- Accessibility claims ("built for TalkBack…") only after the release accessibility gate
  (accessibility-verification.md L5) has passed.

## Graphics

### App icon (512×512)

The Lantern mark from [app-mark.md](app-mark.md) on Night ink, exported from
`assets/lantern-mark.svg` at 512×512 (full-bleed square; Play applies its own mask). No text and
no glow.

### Feature graphic (1024×500)

Proposal, shown on the `FeatureGraphic` board:
- Night ink background with one soft honey glow behind the mark (the only place besides Welcome
  where the glow belongs to the mark);
- the Lantern mark on the left third;
- the wordmark "ChitChat" in Bricolage Grotesque 800 and the line "Your community's chat" in
  `textMuted`;
- no screenshots, no device frames, and no small text. Play may crop or overlay the edges, so
  keep important content inside the central 80%.

### Phone screenshots (placeholders)

Eight portrait screenshots, 1080×1920 or larger, in this order. **These are the mockup boards
until real emulator captures from the release build replace them.** Use real captures in Light,
with one Pure black shot, and fictional demo content on a demo server (never real users'
data).

| # | Screen (mockup board) | Caption (on the image, Bricolage, ≤ 6 words) |
|---|---|---|
| 1 | Crowded room, `RoomCrowded` | "Where your people gather" |
| 2 | Rooms home, `Home` (without dependency-only elements) | "All your rooms at a glance" |
| 3 | Lots of DMs, `DirectInbox` | "Direct messages, simply" |
| 4 | Welcome, `Main` | "Your community. Your server." |
| 5 | Empty room, `RoomEmpty` | "Start the conversation" |
| 6 | Files, `RoomFiles` (only if shipped) | "Photos without your location" |
| 7 | Pure black, `AmoledRoom` | "Easy on OLED, easy on eyes" |
| 8 | Report form, `ReportMessage` | "Safe spaces, one tap away" |

- Screenshots must not show features the release lacks, such as presence counts in lists
  (#153), member counts or mention cards. Retake from the real build rather than editing
  mockups.
- Captions are text on a Night ink band above the screenshot, in persimmon and paper, with
  contrast at least 4.5:1.
- Tablet screenshots aren't proposed until a tablet layout exists.

## Content rating and policy notes (for the maintainer)

- **User-generated content:** yes. Covered by guidelines acceptance, message reporting,
  reporting a person (ChitChat#159, reporting.md), ignore and block.
- **Users interact:** yes (chat). **Shares location:** no (photo location is removed).
  **Digital purchases:** none.
- **Data safety:** use the approved facts in `play-readiness.md` §4.
- **Account deletion:** in-app (Delete account) plus the web page URL above.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-190 | Every listing claim matches the release build. Bracketed sections are removed unless shipped. | R |
| P-191 | Screenshots are real captures from the release build on fictional demo data, in the proposed order, with no dependency-only elements. | R |
| P-192 | Icon and feature graphic follow app-mark.md (no glow in the icon, glow allowed in the feature graphic). | R |
| P-193 | Name, description, graphics and submission are approved by the maintainer. | R |
