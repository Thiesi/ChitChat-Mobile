# ChitChat Mobile presentation direction ("Lantern")

Status: **approved direction, 2026-11-07.** The maintainer approved decisions D1–D7 as
recommended (see below). This folder is the presentation contract for new UI work. The
technical lead (the coordinator role; see issue #1) decides when and in which slices existing screens are restyled;
implementers should not restyle screens outside a planned slice.

Owner: the presentation lead (a Claude session; see issue #1). The technical lead (coordinator)
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
| [onboarding-restyle.md](onboarding-restyle.md) | Implementation spec mapping onboarding onto today's code, copy and tests |
| [invite-codes.md](invite-codes.md) | Sharing and scanning the server address as a QR code or link (D5) |
| [room-presence.md](room-presence.md) | Implementation spec for the room header, presence and inactivity moments, the people sheet and room details |
| [copy-deck.md](copy-deck.md) | String audit against communication.md, grouped by the slice that applies each change, with affected tests and flagged contract strings |
| [store-listing.md](store-listing.md) | Proposal: Play listing fields, description, icon, feature graphic, screenshot set (maintainer decision) |
| [privacy-policy.md](privacy-policy.md) | App privacy policy (approved 2026-11-08; published with the first Play build after its pre-publication checklist passes) |
| [server-update.md](server-update.md) | The "This server needs an update" state at setup and on revalidation (minimum server version) |
| [play-readiness.md](play-readiness.md) | Google Play: guidelines acceptance, in-app account deletion, deletion web page content, privacy surfaces |
| [account-security.md](account-security.md) | M6 account and security: Privacy and security layout, profile picture, password, two-step sign-in, recovery codes, sign-in methods, sessions |
| [recovery-key.md](recovery-key.md) | Recovery key display after sign-up and reset, native password reset, and key management in Privacy and security (ChitChat#155) |
| [guests.md](guests.md) | M7 guest visits: starting, the reduced shell, limits as honest copy, ending, becoming a member |
| [moderation.md](moderation.md) | M7 moderation: Reports queue, message and person cases, acting within ranks, guests; DM inspection and edit-history review stay web-only |
| [administration.md](administration.md) | M7 administration: what's on the phone versus web-only, Stewarding, People, Rooms, server status, lockdown |
| [reporting.md](reporting.md) | M5 participant reporting: entry, form, who-sees-what, outcomes, follow-up actions |
| [files-media.md](files-media.md) | M5 files, images and sharing: attach, upload states, inline images, viewer, save, share in/out |
| [push-presentation.md](push-presentation.md) | M6 design for background notifications: channels, layout, grouping, lock screen, settings |
| [modals-restyle.md](modals-restyle.md) | Implementation spec classifying every modal as choice sheet, decision dialog or form dialog |
| [status-presentation.md](status-presentation.md) | Implementation spec for the status slot, connectivity notices, announcements and offline Send gating |
| [lists-restyle.md](lists-restyle.md) | Implementation spec for the room directory, DM inbox, Activity and search lists |
| [transcript-restyle.md](transcript-restyle.md) | Implementation spec for room and DM transcripts and composers: runs, bubbles, chit, actions, composer delivery states |
| [navigation-shell.md](navigation-shell.md) | Implementation spec for D3: bottom bar, You hub, tab and Back behavior under existing navigation contracts |
| [installation-icon.md](installation-icon.md) | The operator's installation icon (ChitChat#211) in the installation lantern at step 3, the You hub's Server row, the server update notice and the Invite screen: drawing, same-origin fetch checks, memory-only cache |
| [gap-audit.md](gap-audit.md) | Audit of today's app against Lantern (2026-11-09 captures): what matches, gaps per restyle slice ranked by impact, A-* violations, adopted order, the A-floor fixes brief and the modal capture note |
| [theme-foundation.md](theme-foundation.md) | Implementation spec for the first slice: tokens, Material mapping, wrappers, font, settings, window, icon |
| [amoled-black.md](amoled-black.md) | The true-black dark variant required before going live |
| [acceptance.md](acceptance.md) | Accessibility and readability floors, plus numbered, testable presentation requirements |
| [accessibility-verification.md](accessibility-verification.md) | How each A-* floor is proven: test layers, shared assertions, configuration matrix, TalkBack scripts |
| [app-mark.md](app-mark.md) and [assets/](assets/) | Final mark geometry and ready-to-copy launcher, themed and notification icons |
| [mockups/](mockups/) | Source of the mockup canvas: 21 phone screens (onboarding, everyday use, situations, AMOLED black, invite codes) and a vocabulary sheet |

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

- ~~Liveliness glow and "N here now" in room lists~~ and ~~recency in room lists~~:
  available since ChitChat#153/#154 merged (2026-11-08), designed in lists-restyle.md.
  Recency is shown, never used for ordering. Room message previews stay out: the API gives
  time only.
- Admin "Looking after this server" card, guest visits and moderation actions: M7.
- Invite QR codes: specified in invite-codes.md (D5), not yet implemented or scheduled.
- ~~Installation icon~~: ChitChat#211 shipped in v3.6.0 and is inside the 3.7.0 minimum,
  designed in installation-icon.md. Its presets and name-following initial (ChitChat#241)
  shipped in v3.8.0 and need no app change.
- ~~Required username change~~: ChitChat#164 shipped in v3.8.0, designed in play-readiness.md
  and required for the first Play release. The minimum stays 3.7.0; it's gated by the session
  fields.
- ~~Signed-in sessions and authenticator codes~~: ChitChat#205 and #204 shipped in v3.7.0, the
  minimum since 2026-11-11, designed in account-security.md.

## Approved decisions (maintainer, 2026-11-07)

| ID | Decision | What was approved |
|---|---|---|
| D1 | Signature color | CCM's identity moves from the current teal (#32675B, shared with the web default; teal-green sits in WhatsApp's territory) to **Persimmon on Night ink/Paper**. |
| D2 | Display font | Bundle **Bricolage Grotesque** (SIL OFL, variable, subset to Latin plus Latin Extended) for display text only; message and UI text stays on the system font. Measure and report the APK size cost in the implementing PR (estimate 100–200 KB). Keep the OFL license text with the other licenses. |
| D3 | Navigation | Bottom bar **Rooms · Direct · Activity · You**, replacing the current Home hub. |
| D4 | Material You dynamic color | **Off.** |
| D5 | Invite codes | A client-only QR/link carrying only the server origin (no credentials, tokens, usernames or room IDs), scanned during onboarding; server confirmation (onboarding step 3) is always shown. Specified in [invite-codes.md](invite-codes.md) as the plain HTTPS origin, so the same code also opens the web client for people without the app. |
| D6 | Palettes | Lantern is the default. **The backend has no account-level theme or palette** (the web client stores its six palettes per device, in the browser; backend audit, issue #1). So CCM's appearance is a local device setting, like the web's. If CCM later offers additional palettes, they may replace the hue tokens while shapes, chit, type and status language stay. High-contrast stays available as a local option. **An "AMOLED black" theme must exist before CCM goes live** (see visual-vocabulary.md). |
| D7 | Backend data | Backend issues filed: [presence count #153](https://github.com/Thiesi/ChitChat/issues/153) and [last activity time #154](https://github.com/Thiesi/ChitChat/issues/154) per joined room. Both merged 2026-11-08 (ChitChat main `9cf052c`); room lists now show them per lists-restyle.md. |

Ask the maintainer before deciding any similar consequential question that is not listed
here.
