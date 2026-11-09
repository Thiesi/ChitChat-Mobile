# UX gap audit of the real app (2026-11-09)

Status: presentation lead's audit of today's app against the Lantern specs, written for the
technical lead to sequence and scope the restyle slices after files/media and push (technical
lead, 2026-11-09). It describes what the app shows now. It changes no contract.

## Evidence

- **Captures:** one dispatch of the reusable capture workflow from #117
  (`theme-foundation-captures.yml`), run
  [42584612459](https://github.com/Thiesi/ChitChat-Mobile/actions/runs/42584612459), both jobs
  successful. "After" is production and fixture at main `3f164bb`. "Before" is the pre-theme
  base `3093e71`, used only for contrast.
- **Matrix:** 11 screens (server, sign-in, home, settings, rooms, room, direct inbox, DM thread,
  activity, account, search) in light, dark and Pure black, at font scale 1.0 and 2.0, on the
  pinned API36 emulator at 360×640dp. That's 66 current captures. Their SHA-256 hashes are in the
  run's `provenance.json` (`captureCollectionComplete: true`). The artifacts are kept for 7 days.
  Re-dispatch the workflow for fresh images.
- **Accessibility checks:** every capture runs Compose's accessibility checks (API 34+). All six
  cases passed, so the automated checker found no errors on these 11 screens.
- **Limits:** these are fixture captures, not device, keyboard, TalkBack or live-server
  acceptance. They don't show modals, status notices in their various states, the onboarding
  steps after sign-in, RTL, landscape or own messages. Findings about those come from the specs
  and the code, and are marked as such.

## What already matches Lantern

The theme foundation (#112, #114) and icon set (#123) landed well:

- **Tokens everywhere.** All 11 screens use the Lantern ground, surface, text, muted text and
  line tokens in all three palettes. Pure black has a true `#000000` ground with raised surfaces
  that still separate (A-10). No stray Material purple remains.
- **Type.** Screen and section headings use the display face. Body and message text use the
  system face and follow the font scale (A-11).
- **Action color.** Text actions use `signatureText`, and badges use `onSignature` ink on
  persimmon, never white. Both are covered by the token contrast test (A-01).
- **Settings → Appearance** matches theme-foundation.md: System, Light and Dark radios, plus
  the "Pure black in dark mode" switch with its supporting line.
- **Fields and Send.** Fields have `fieldLine` outlines with 3:1 or better boundaries (A-02).
  Send is the round icon button pinned beside the input, kept together at 2.0 (A-04 for the
  composer).
- **Counts aren't color-only.** Unread counts say "12 unread", and Activity rows say "Unread"
  (A-05).
- **DM bubbles.** DM threads already render rounded bubbles on a raised surface. That's the
  start of the transcript slice, not its finish.

## Gaps, ranked by user impact

Each gap names the slice that fixes it. **Impact:** High means everyone meets it on first use or
in every conversation. Medium means it's frequent but local. Low is polish.

### 1. Development disclosure text dominates every screen (High · copy deck and status)

Seen on server, home, rooms, room, DM inbox, DM thread, activity and account. Paragraphs written
for testers ("This development build supports sign-in, foreground room and direct
conversations…", "Manual refresh; realtime and active presence are not implemented yet.",
"'New here' is local attention, not a restored server unread count.") sit above the content.

- At 1.0 they take a third to half of the first screen. At 2.0 they push the actual content
  (rooms, messages, notifications) mostly or entirely below the fold.
- Home is almost entirely this text. There's nothing to do there except read it.
- The AGENTS.md requirement to disclose what's missing stays. Lantern's answer is the
  copy deck (copy-deck.md) and the status ladder (status-presentation.md): one short line where
  it matters, details behind "Messaging details" or About, and one status notice at a time.
- **Fix:** apply the copy deck to the feature-availability strings early, ideally inside each
  slice below rather than as a separate pass. It's the cheapest large gain.

### 2. No navigation shell (High · nav shell, P-70 to P-76)

- Home is a hub of text with **Account** and **Settings** as persimmon text links in the top
  bar. There's no bottom bar, so Rooms, Direct and Activity are reached only from the hub.
- Every pushed screen uses a persimmon text "Back" instead of the icon back button (P-76).
- Titles repeat: "Rooms" in the bar and again as a heading, and the same for "Direct messages"
  twice. A room shows the generic bar title "Conversation" above the room name.
- **Fix:** the nav-shell slice as specified. It also removes the duplicated headings, and the You
  tab absorbs `account` and `settings` (P-75).

### 3. Room transcript isn't styled yet (High · transcript, P-80 to P-86)

- Room messages are plain text rows. Every message repeats "Peer · 10/21/26 10:00 AM" above its
  text: no bubbles, runs, chit or pebbles, and a full date on every line.
- DM threads have bubbles but repeat the sender and full date in each one, and leave an empty
  band at the bottom of each bubble.
- The room header carries status prose stacked above the transcript (see 1) instead of the
  header glow and "here" count (P-86) and the single status slot (P-100).
- Own messages aren't in the fixture, so the outgoing color and alignment can't be judged here.
- **Fix:** the transcript slice (runs, bubbles, chit, day separators and time on the last bubble
  of a run), with the status slice for the header.

### 4. Text breaks and clipping at 200% font (High · A-04 floor violations)

- **Home title:** "ChitChat" breaks mid-word as "ChitC / hat" next to the Account and Settings
  links. The nav shell removes those links, but the bar title also needs a no-break,
  single-line rule.
- **DM thread header:** "47 new here (local" is cut off at the edge of the bounded header area.
  It's bounded on purpose (the composer must stay visible), but a cut-off line reads as broken.
- **Room header:** the same, with a half-visible line at the bottom of the status area.
- **Rooms, Activity and DM inbox:** the explanatory text and full-width Refresh buttons fill the
  first screen. The first room, notification or conversation starts at or below the fold, so the
  primary content isn't reachable without scrolling (A-04 asks that the primary action stays
  reachable).
- **Fix:** the status slice (one notice, short copy) removes most of this. The header clipping
  should be checked again after it, not patched separately.

### 5. Lists use cards with stacked facts (Medium · lists, P-90 to P-96)

- Room rows are rounded cards that put each fact on its own line: name, "Joined", description,
  "Private", then the unread pill. One room takes about 170dp at 1.0, so only two fit on the
  first screen.
- There are no lanterns for rooms or pebbles for people, and no shared row anatomy. The unread
  pill is very small text in a tight capsule.
- Activity rows are cards with "View destination" and "Mark as read" as separate text links.
- DM inbox rows are cards with the date and "100 unread" on separate lines.
- Full-width outlined **Refresh rooms** and **Refresh notifications** buttons sit at the top of
  every list. lists-restyle.md moves plain refresh to pull-to-refresh and an overflow
  "Refresh" item, keeping explicit reconciliation as its own notice (P-92).
- **Fix:** the lists slice. The two-line row anatomy roughly triples the rows on one screen.

### 6. Composer Details dot is color-only (Medium · A-05 and A-02 violation · status and transcript)

- The ⋮ Details button carries a small dot (`AttentionBadge`). Its color alone says whether
  something is generic (persimmon), uncertain (`unsureLine`) or failed (`error`). TalkBack gets a
  state description, but sighted people get no text, shape or icon difference (A-05).
- A persimmon fill on paper is 2.9:1, and visual-vocabulary.md says it must never be the only
  signal (A-02).
- The dot shows whenever realtime isn't LIVE, so in today's foreground-only app it's on almost
  all the time. That makes it noise rather than a signal.
- The ⋮ is a text glyph, not a Lantern icon.
- **Fix:** status-presentation.md and transcript-restyle.md move delivery states onto the
  composer with words ("Not sure your last message arrived." with Check history, and
  "Couldn't send. Nothing was delivered."), and connectivity into the status slot. The dot
  can then go, or be kept only together with a word.

### 7. Onboarding is one screen plus a form (Medium · onboarding, P-50 to P-56)

- First launch opens straight to "Your conversations. Your server." with the address field,
  then the development disclosure. There's no Welcome step and no Get started, and the steps
  aren't separate.
- The sign-in screen shows "Connected to your server" with the raw URL instead of the host, and
  uses "Restore account" and "Change server" as equal-weight actions under Sign in.
- Sign in and Save server look disabled with empty fields (tonal, muted label). That's correct
  for disabled, but the enabled state isn't in the captures.
- **Fix:** the onboarding slice. It's the first impression for every new person, but it runs once
  per person, so it ranks below the everyday screens.

### 8. Account is a wall of export text (Medium · M6 account and security)

The Account screen opens on three paragraphs about personal data export, and the 2.0 capture is
nothing but that text. Privacy and security grouping (account-security.md P-142) doesn't exist
yet. This is M6 work, not a restyle slice, but the copy deck can shorten the export text
earlier.

### 9. Search is close (Low · lists, P-95)

Search roughly matches lists-restyle.md: query field, explicit search button, scope line and the
not-read note.

- The search icon button measures about 2.3:1 against paper with an empty query. That's
  acceptable only while disabled. The enabled state should be checked for 3:1 or better.
- The scope menu is a ⋮ glyph rather than a scope chip.
- At 2.0 the bar title wraps to two lines next to the text "Back" (fixed by the nav shell).

### 10. Modals (not captured · modals, P-110 to P-115)

No modal is in the capture set. From the code, today's dialogs are Material `AlertDialog`s with
the Lantern colors. The DM privacy details, for example, scroll inside an `AlertDialog`. The
choice-sheet, decision-dialog and form-dialog classification isn't applied yet. Before scoping
this slice, extend the capture fixture with one sheet, one decision dialog and one form dialog.

## Suggested order

Ordered for user impact per unit of work, and to avoid restyling a screen twice:

1. **Status and copy deck together.** One notice at a time, plus short feature-availability copy.
   This fixes gap 1, most of gap 4 and gap 6's connectivity part across every screen, and it
   doesn't move any layout contracts.
2. **Nav shell** (gap 2). Every later slice then lands inside the final frame, and duplicated
   titles and text Back disappear at once.
3. **Transcript** (gap 3, the composer half of gap 6). It's the most-used screen, with the most
   input contracts to preserve, so it comes after the frame is settled.
4. **Lists** (gaps 5 and 9).
5. **Onboarding** (gap 7). Can run in parallel with 3 or 4, since it touches separate screens.
6. **Modals** (gap 10), after the fixture shows them.

Accessibility work that isn't a slice: add a capture or Compose check that the bar title stays
on one line at 2.0, and make the clipped-header check from gap 4 an explicit test once the
status slice lands. Both are A-04.
