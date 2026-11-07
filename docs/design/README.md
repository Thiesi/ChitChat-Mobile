# ChitChat Mobile presentation direction ("Lantern")

Status: **proposed, 2026-11-07.** Nothing in this folder is an approved contract until the
maintainer has approved the decisions listed below. Until then, implementation agents must
not restyle production screens from these documents. They may use them for planning,
estimates and questions.

Owner: the presentation lead (a Claude session; see issue #1). The project coordinator
plans integration into the roadmap. Codex and other implementers build it. A change to an
approved contract goes through a PR that updates these files; personal agent memory is not
a substitute.

## Documents

| File | What it settles |
|---|---|
| [vision.md](vision.md) | What CCM should feel like, why, and what "recognizable" means in testable terms |
| [visual-vocabulary.md](visual-vocabulary.md) | Color, shape, type, iconography, motion and illustration: the identity kit |
| [communication.md](communication.md) | How CCM tells people what is happening: status ladder, connectivity, delivery, voice |
| [onboarding.md](onboarding.md) | First launch to first message |
| [acceptance.md](acceptance.md) | Accessibility and readability floors, plus numbered, testable presentation requirements |
| [mockups/](mockups/) | Source of the mockup canvas: 13 phone screens and a vocabulary sheet |

The mockups are also published as a private design canvas for the maintainer. The files
under `mockups/project/` are the same sources: open any `.dc.html` file in a browser to
view it without the canvas. Names, rooms and messages in them are fictional.

## How this relates to the existing contracts

Presentation never overrides behavior contracts in `AGENTS.md` and `docs/milestones/`.
Specifically: no automatic POST replay, explicit GET reconciliation before a duplicate-risk
consent, read proof only from real visibility, 48dp targets, and the input and Send controls
pinned together. Where a mockup seems to contradict a milestone contract, the milestone
wins and the mockup is wrong. Report it so it gets fixed here.

Mockups show the target experience. Several elements need data or features that do not
exist yet. Each is marked "Dependency" in the documents, and implementers must leave it out
rather than fake it:

- Liveliness glow and "N here now" in room lists: the room directory API has no presence
  summary. Today, presence is known only inside a room after entry.
- Room previews or recency ordering in the room list: the directory API has no last-message
  summary. Room rows show the room's info line instead.
- Admin "Looking after this server" card, guest visits and moderation actions: M7.
- Invite QR codes: a proposed client-only feature (decision D5).
- Display font: needs decision D2.

## Decisions requested from the maintainer

These are consequential product decisions. The presentation lead recommends the first option
in each case.

| ID | Decision | Recommendation | Alternatives |
|---|---|---|---|
| D1 | Signature color | Move CCM's default identity from the current teal (#32675B, shared with the web default) to **Persimmon on Night ink/Paper**. Teal-green sits in WhatsApp's territory and cannot be "equally distinct". | Keep teal and rely on shape alone; or a violet identity (crowded by Viber/Discord) |
| D2 | Bundle a display font | Bundle **Bricolage Grotesque** (SIL OFL, variable, subset to Latin plus Latin Extended) for display text only; message and UI text stays on the system font. Expected APK cost: roughly 100–200 KB after subsetting, to be measured. | System font only (cheaper, much less recognizable) |
| D3 | Navigation | Bottom bar: **Rooms · Direct · Activity · You**, replacing the current Home hub. | Keep the hub screen; or one merged chat list (not possible honestly without room recency data) |
| D4 | Material You dynamic color | **Off.** Wallpaper-derived colors would erase the identity. | Offer it as an opt-in appearance choice later |
| D5 | Invite codes | Add a client-only QR/link format that carries only the server address (no credentials, no tokens), scanned during onboarding. | Typed address only |
| D6 | Web palettes on Android | CCM ships Lantern as its default. When account palettes arrive (M6), the account palette may replace the hue tokens, but the shapes, chit, type and status language stay. High-contrast stays available. | Lantern only; or full web parity without a CCM default |
| D7 | Backend data for liveliness and previews | File backend issues proposing a directory presence count and last-activity time per joined room. | Leave room lists without liveliness |

Ask before deciding any similar question that is not listed here.
