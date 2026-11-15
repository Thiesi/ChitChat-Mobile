# Visual overhaul handoff

Status: living document, kept by the presentation lead. It prepares the owner-authorized
visual overhaul of CCM by a Fable 5.2 agent (owner decision 2026-11-14, relayed by the
technical lead). The overhaul starts only once CCM is feature-complete, so entries are added
as specs land, and the technical lead asks for a final completeness pass at the readiness
check. Until the start, new work is specified and reviewed in Lantern
([README.md](README.md)), without polish the overhaul would redo.

**First read:** `docs/design/lantern-next.md` in the ChitChat (web) repository. It records
every brand-level change CC's own Fable overhaul makes to Lantern (tokens, type, mark,
icons, motion), with old and new values and the reason for each. This overhaul starts from
that document, not from the Lantern values in this repository.

## What the overhaul may and may not change

- **Open:** everything presentational, including tokens, type, shape, layout, iconography,
  motion and copy tone. Lantern itself may change. CC's own Fable overhaul runs first
  and leads Lantern: for that run the owner suspended the paired-PR rule for shared brand
  changes ([vision.md](vision.md), [visual-vocabulary.md](visual-vocabulary.md)), and
  every brand-level change lands in CC's `lantern-next.md` (see **First read**). Until this
  overhaul starts, web and app differ on purpose, and CCM doesn't follow web changes piecemeal.
  Brand questions go through the CC technical lead.
- **Still binding:** every behaviour, privacy, security and protocol contract in `AGENTS.md`,
  plus the accessibility floors (A-*). Maintainer-approved text is applied verbatim: the
  privacy policy, data-safety facts, contract strings such as duplicate-risk consent, the
  non-E2EE, retention and inspection disclosures, step-up and recovery. A presentation idea
  that conflicts with one of these goes to the owner. It is never settled by weakening the
  contract.
- **Authority** (owner clarification, 2026-11-14): during the overhaul, the Fable agent holds
  authority over the presentation layer, including Lantern and the J-standards. It may also
  propose rule changes. Where a proposal clashes with a hard rule (`AGENTS.md` contracts, the
  A-* floors, verbatim approved text), it raises it for discussion with the technical lead and
  the owner, and doesn't override it. The rule stays in force until it is changed.
  Until the overhaul starts, the presentation lead remains the presentation authority.
- The overhaul lands as ordinary reviewed PRs with every gate.

## Firm positions and why

This document is the agent's starting brief. Below are the positions the presentation lead
holds firmly, with the reason for each, so a change is made knowingly. "Hard" items are rules
or maintainer decisions that need discussion before they change. "Position" items are open to
the agent's judgment, but the reason should be answered, not just dropped.

**Hard**
- **Reachable at 200% font, 48dp targets, never colour-only, TalkBack-complete** (A-* floors in
  [acceptance.md](acceptance.md)). People rely on these, and J reviews keep finding where a
  layout that looks fine at 1.0 fails at 2.0.
- **Approved text stays verbatim:** the privacy policy, data-safety facts, consent and
  disclosure strings (non-E2EE, retention, inspection, duplicate risk, step-up, recovery). The
  maintainer approved the exact words, and the app and the store forms must say the same
  thing.
- **Never show what isn't real.** Leave out dependency-marked elements rather than faking
  data, never claim unshipped behaviour (offline copy waits for what actually ships), and
  never fill a gap with an invented fallback (a version check, a default URL, a role guess).
  An honest app is part of what makes people trust it.
- **Pure black is a required theme** (maintainer, 2026-11-07, needed before going live). Its
  look can change; its existence can't.
- **Captures are evidence.** No byte-identical default/200% pairs. The host applies a real
  font scale. Every state in a J review comes from a real fixture state.

**Position**
- **Layout decisions use measured line count, never font scale** (status-presentation.md
  P-108). Font scale is a poor proxy: the same scale wraps differently by width, language and
  name length, while a measured line count is the actual condition. The web adopted the same
  rule.
- **Rose means destructive; warning colour always comes with its glyph.** Colour semantics
  stay meaningful only if each colour has one job, and the glyph keeps the warning legible
  without colour. Every state is carried by two of colour, shape or glyph, and text or weight.
- **No persimmon fill inside dialogs** (modals-restyle.md). Fill marks a screen's single
  primary action. A dialog confirming a consequence shouldn't push the person toward Yes.
- **Say only what the app can know.** For example, P-217 never explains why someone can't own
  a room, because for an owner that would reveal a ban. Lists omit what can't be chosen,
  rather than showing disabled rows with an unknowable reason.
- **One status notice at a time, on the status ladder** (status-presentation.md). Stacked
  banners make every one of them easier to ignore.
- **Lantern's intent is warmth that invites people back** (vision.md: "not to make CCM look
  better"). The palette and shapes can change, but a premium look that loses the invitation
  misses the brief.

## 1. Capture inventory

**Matrix:** every entry is captured in light, dark and Pure black (`amoled-black.md`), at
default and 200% font. "Pure black" applies where the screen shows a dark surface. Names follow
the existing pattern `{set}-{theme}-{scale}-{state}.png`.

**Validity check:** for every set, confirm that no default/200% pair is byte-identical. A
fixture once captured "200%" at 1.0, which a hash comparison caught (PR #238, head c44a6d2).
The host must apply a real `Configuration.fontScale`, the way a person's setting reaches
dialogs and other windows.

**Harness** names where a capture exists today: a set collected by
`.github/scripts/native_presentation.py` into the `android-journey-reports` artifact.
"None" means one has to be built before the start.

### Existing sets

| Set | States | Themes and scales today | Missing for the matrix |
|---|---|---|---|
| `theme` | server, auth, home, settings, rooms, room, direct, thread, activity, account, search | light, dark, black × default, 200% | none |
| `status` | storage, offline, tls, incompatible, stopped, refresh, rate, rate-unknown, offline-edge, waiting-room, waiting-dm; details (default only) | light, dark × default, 200% | black; details at 200% |
| `push` | off, waiting, on, refused, needs-setup, explain, google, google-instead | light, dark × default | 200%; black |
| `transfer` | row, none, failed, picker, confirm, success | light, dark × default, 200% | black; unknown-in-dialog |
| `search` | results (with match highlighting) | light, dark × default, 200% | black; empty, no-results and failure states |
| `licences` | list, detail | light, dark × default, 200% | black |
| `nav` | bottom bar, Rooms selected, Activity badge (N1, PR #251); black default | light, dark × default, 200% | black at 200% |
| `you` | header (steward badge), root bar, pushed bar, session, about (N2a, PR #256) | light, dark × default, 200% | black |
| `completion` | composer with and without the mention list | synthetic fixture pairs | 200%; black |
| `a11y` | Accessibility feedback link on sign-in, You and the confirm dialog (N2b, PR #258) | light, dark × default, 200% | black |
| `update` | server needs an update: setup, session; scrolled `-actions` (O1, PR #259) | light, dark × default, 200% | black |
| `onboarding` | welcome, address, found, found-top (host only); step 2 certificate failure at 200% (O2a, PR #260) | light, dark × default, 200% | black; failure at default; name-plus-host card |

### Screens and states with no capture yet

Grouped by area. Each needs a fixture state, not a live server.

- **Onboarding:** Welcome (with the preview disclosure sheet); Where do you chat? (empty,
  invalid address, checking); Server found (with and without guest visits, and with the
  accessibility link, ChitChat#219, released in v3.14.0); Sign in (Forgot password button versus the
  static line, throttled); Create account (anti-bot progress, optional birth date); recovery
  key display; rules acceptance; server update notice.
- **Transcript:** room and DM with long, short, edited, deleted, reply, mention, reactions,
  link and code messages; the day separator; the unread marker; Jump to latest; the composer
  (empty, multi-line, reply attached, mention completion, blocked, Details marker states,
  unknown send, waiting draft in two lines per P-108).
- **Attachments:** picker entry, preparing, ready draft with caption, photo privacy choice,
  uploading, finishing, unknown with Check history, duplicate-risk decision, image and file
  bubbles, the gone file.
- **Lists:** Rooms directory (your rooms, other rooms, join, create dialog); room detail
  dialog states (member, owner, unknown transfer); DM inbox, including P-218 relationship
  states; Activity, including unread, read, unsupported kinds and empty.
- **Modals:** message actions sheet, reaction picker, profile card, room presence sheet,
  ignore and block confirmations, Leave room, forget server, discard draft, step-up dialog,
  and every P-220 sweep dialog in [modals-restyle.md](modals-restyle.md), at 200% in a short
  window.
- **Account:** Privacy and security, devices, typing preference, ignores, export (picker
  handoff and progress), delete account flow, recovery codes.
- **Push:** Notification settings and the distributor choice list.
- **Still to be specified or built** (added as they land): nav shell and You hub, About and
  privacy (with Accessibility feedback, P-221), guests, invite codes, moderation and
  administration (M7, including P-209 server status), reporting.

## 2. Design debts and Lantern weak spots, ranked

Ordered by user impact. Each item names its evidence, and the agent should re-check each one
against current main before acting.

1. **Text that can't be reached at 200%.** Dialog text slots that don't scroll (P-220 inventory
   of 10, scheduled as the next A-floor slice). Where the fold lands cleanly between lines, a
   cut-off state line reads as complete. Evidence: PR #238 captures at 979e799 (none and
   failed at 200%).
2. **200% and Pure black coverage is uneven.** Several sets lack 200% or black entirely (table
   above), so regressions there go unseen. The first overhaul step should be the full capture
   job, not a redesign.
3. **State lines buried below generic copy.** The room detail dialog leads with the long
   presence explanation, which pushes owner and transfer state below the fold at 200%. This is
   an ordering question for the overhaul; P-217 deliberately left it alone.
4. **Notice level and colour discipline.** Lantern reserves rose for destructive actions and
   pairs warning colour with the triangle glyph. The web showed rose used for plain
   information (S8 finding C, fixed in CC #366), so CCM should be audited for the same
   pattern. Every state must carry at least two of colour, shape or glyph, and text or weight.
5. **Text-button alignment in dialogs.** Material's TextButton padding indents in-body
   actions ("Transfer ownership…", "Try again", "Leave room") about 12dp past the text
   column. This is accepted today. The overhaul should decide one rule for in-body actions
   (aligned link-style rows versus inset buttons) and apply it everywhere. The You hub's rows
   (Appearance, Privacy and security, Open-source licences) are full-width TextButtons, so at
   default their labels sit centred under left-aligned text (N2a, PR #256 `you-*-about`).
   Long pushed-bar titles at 200% ellipsize ("Open-source li…") under gap-audit F1's
   one-line rule, with the full text in semantics. Whether a pushed screen repeats a long title
   as a wrapped in-content heading at large scales is open.
6. **Metadata spacing.** The "10:00 · edited" spacing is an open follow-up. Separator rhythm
   in timestamps and meta lines generally deserves one rule.
7. **Dialog details not yet in Lantern.** Destructive confirms (Leave room, Block, Forget
   server, Discard draft) still use `primary`, not `error` as modals-restyle P-113 specifies,
   because the modals restyle was deferred to the overhaul. The discard-draft body is still
   development copy ("in-memory draft and its local resend warning"); the copy deck has the
   replacement. The push guide link appears both as the in-body "How to choose a delivery app"
   (Use Google) and as the button-row "How to choose one" (No delivery app found): pick one
   form and one label. Evidence: PR #244 and #246 dialog captures.
8. **Older gaps.** [gap-audit.md](gap-audit.md) gaps 1–10: development disclosure weight, the
   missing nav shell, transcript and list styling, onboarding depth, the account export wall.
   Several have shipped since; confirm each against current captures.
9. **Lantern identity load.** Persimmon carries the brand, primary actions, links
   (signatureText), focus and the send button at once. presenceWash and steward each carry
   one meaning. Whether the palette can keep "invites people to chat" while separating those
   roles is the core visual question for the agent. The web's S7–S8 J-reads (evidence below)
   show where it's strained.
10. **Bottom bar at 200%.** The Activity badge grows with the font and covers about half the
    bell, and the selected pill sits flush with the bar's top edge (12dp at default). Labels
    already share one measured size on narrow windows (navigation-shell.md P-78). Whether
    the bar should hold its height or a badge should cap its growth is open. Evidence: PR #251
    `nav-presentation` captures.
11. **Onboarding after O2a.** O2a (PR #260) shipped behaviour, copy and tags only. The
    lantern glyph and identity hue on the server card, the server chip on the forms,
    labels-above 56dp fields and the always-dark Welcome (onboarding-restyle.md) are the
    overhaul's. The outlined "I already have one, sign in" was raised to 52dp to match Create
    before merge; full-width secondaries paired with a primary keep that height. The step 2
    failure is the existing rose `InlineError` text above the field, not status-presentation's
    level-2 block (icon plus full-width block); see also debt 4. Hosts break only at their dots
    (display-only U+200B, PR #259 and #260); keep that rule. Evidence: PR #260
    `onboarding-presentation` captures.
12. **Trailing glyph after wrapped labels.** At 200% the wrapped "Accessibility feedback"
    label keeps its constraint width, so the open-external glyph sits at the row's end, not
    right after the last word (PR #258 `a11y` captures). Decide whether trailing glyphs follow
    the text or the row.
13. **Pinned bottom actions over a scroller.** Find your people pins Start chatting below its
    list (O2b, PR #264), and content scrolls under it with no edge: at 200% a line is cut
    mid-way. Pinned actions over scrolling content want the same 1dp scroll-edge divider that
    P-220 uses for dialogs, shown only while content is hidden beneath. Evidence: PR #264
    `find-people-presentation` captures.

## Evidence

- CCM capture sets: the `android-journey-reports` artifact of each PR's journey run (see the
  table). PR #238's final J pass is recorded on the PR.
- Web J-reads (shared Lantern): `C:\Users\Thiesi\Local\Dev\ChitChat-worktrees\captures\`
  (jread-2026-11-14, -s7g, -s7h, -s7h-fix, -351) and
  `C:\Users\Thiesi\Local\Dev\ChitChat-worktrees\jread-s8-captures\` and `jread-s8b-captures\`.
- Specs: every file in this folder; A-* in [acceptance.md](acceptance.md), and P-* in each spec's
  acceptance table.
