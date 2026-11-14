# Room details, presence and people: implementation specification

Status: implementation-ready spec for how a room shows who is here, how room presence and
inactivity are communicated, and the room details sheet. It follows the transcript and
modals slices, and the coordinator schedules it. Mockups: `RoomCrowded` and `RoomEmpty`
(header), `AdminRoom` (guest marker).

Room presence is an expiring lease, separate from membership (M3c2/3). Contracts that stay
unchanged:
- deliberate entry acquires presence; passive resume and scrolling never reset inactivity;
- send and read activity count only after acknowledgment;
- the warning countdown is local and runs only while the screen is STARTED;
- **Stay** is the existing explicit enter action;
- a stale people list is never shown as current.

When this document and a milestone contract disagree, the contract wins.

## Today (2026-11-07)

`RoomPresencePanel` shows one status sentence per `PresencePhase`:
- reading only, paused, entering, online, warning, expired, failed;
- in WARNING, a remaining-seconds line and a **Stay in room** button;
- in ACTIVE or WARNING, a "N people" button opening an `AlertDialog` with usernames, guests
  suffixed "· guest", or "The online list is updating or unavailable" when stale.

There is no room details surface beyond the directory's detail dialog. Member counts are not
available to the client.

**List snapshot versus room header (ChitChat#153).** The room list's "{n} here now" comes from
`active_count`: other people, viewer excluded, as of the last list fetch. Inside the room, the
header uses the live presence list as below, and it is the authority once the person has
entered. The two can differ for a moment, for example after the person joins, and that's
expected. The header never copies the list's number, and the list never claims to be live.

## Header (from the transcript spec)

- Lantern, room name, and a subtitle derived from presence:

  | Presence | Subtitle | Lantern |
  |---|---|---|
  | ACTIVE or WARNING, list fresh, others present | "{n} here" (n = people in the list) | Glow, when at least one person besides the viewer is present |
  | ACTIVE, only the viewer | "Just you here" | No glow |
  | CONNECTING | "Joining…" | No glow |
  | Paused, expired or failed | "Not in the room right now" | No glow |
  | Reading only (not eligible) | "Reading" | No glow |
  | List stale | "Who's here is updating…" | No glow |
  | Device offline | "Who's here is unknown while offline" (status spec) | No glow |

- **Member counts:** the client has no member count. The "41 members" in mockups
  `RoomCrowded` and `RoomEmpty` is a target that needs backend data. Omit it until an API
  provides it.
- Tapping the header opens the room details sheet. The subtitle is also a button,
  "People here", opening the people sheet directly when the list is fresh.

## Presence moments (above the composer)

Presence states that affect whether you can write are shown **above the composer**, where the
consequence is felt. They use the composer notice stack from the transcript spec, below the
reply chip and above the uncertain-send notice. At most one shows.

| Phase | Notice | Action | Composer |
|---|---|---|---|
| WARNING | **"You'll leave the room in {m:ss}"** plus "because it's been quiet a while." `presenceWash` background, honey clock icon | **Stay** (existing enter, existing `canEnter`) | Normal |
| EXPIRED | **"You left the room after a quiet while."** plus "Your draft and the messages here are kept." | **Join again** (existing enter) | Field editable; Send disabled with the label "Join the room to send" |
| Paused (not entered) | "You're not in the room yet." | **Join** (existing enter) | Same as EXPIRED |
| CONNECTING | "Joining…" (level 1, no action) | — | Send disabled while joining, as today |
| ERROR | "Couldn't join the room." plus the existing explanation | **Try again** (existing enter) when allowed | Same as EXPIRED |
| Reading only | "You're reading this room." plus the existing reason | none in this slice | Composer replaced by this notice, as today |

- The countdown text updates each second only while visible, using today's
  lifecycle-bound timer. **Accessibility:** the WARNING notice is announced once when it
  appears, through a polite live region. The ticking digits are excluded from the live region,
  so TalkBack isn't interrupted every second. It is announced again at 10 seconds remaining.
- **Copy honesty:** the server decides inactivity. Say "because it's been quiet a while",
  never a fixed number of minutes, unless the room's `inactivity_timeout_seconds` is known.
- "Join" here means acquiring active presence (the existing enter), not room membership.
  Membership stays a directory action.

## People sheet ("Here now")

A choice sheet (modals spec), replacing the presence `AlertDialog`:
- Title "Here now · {n}".
- Rows: pebble (identity hue from account ID; dashed pebble for guests), username, and for
  guests a "Guest" pill. Server order is kept.
- Tapping a row opens the profile card with today's profile gating. Rows are inert where
  profiles are unavailable.
- **Stale:** the sheet closes automatically when the list becomes stale or the phase leaves
  ACTIVE/WARNING (today's behavior), and it is never opened on a stale list.
- Empty: "Nobody else is here right now." Use the existing "No active people were
  returned." only when the server returned an empty list unexpectedly.

## Room details sheet

A choice sheet, opened from the header:
- Header: 64dp lantern (lock overlay when private), the room name in Display M, the info line,
  and visibility in words: "Open to everyone on this server", "Hidden from the list; anyone
  with the link can join", "Private, by invitation". These are existing visibility meanings.
- Rows:
  - **Here now** (opens the people sheet; shows the count when fresh);
  - **Drafts in this room** (only when a draft exists; opens the drafts manager focused on
    this room);
  - **Private pings** (the existing pings list);
  - **Leave room…** (decision dialog, existing confirmation, `error`-colored confirm).
- Owners don't see Leave room. They see the explanation instead: "Owners can't leave their
  room. Hand it to someone else first." with a **Transfer ownership…** row that opens the
  transfer flow (administration.md, P-217; guaranteed by the 3.10.0 minimum). After a
  transfer, the former owner is a
  moderator and sees Leave room like anyone.
- Not in this slice: room editing, roles, invitations and moderation (M7). These later go into
  a steward section in this sheet.

## Tests

- **Unit:** subtitle and glow mapping for every phase, including "only the viewer" and the
  stale and offline states.
- **Compose:**
  - WARNING shows the countdown and Stay;
  - Stay calls the existing enter, with `canEnter` gating;
  - the live region announces once and at 10 s, not every tick;
  - EXPIRED keeps the draft editable and disables Send with the label;
  - the people sheet closes when the list becomes stale and never opens stale;
  - owners don't see Leave room;
  - every sheet sets its read and typing suppression flag (modals spec).
- **Existing presence and inactivity tests run unchanged:** passive resume doesn't reset
  inactivity, and send and read count only after acknowledgment.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-130 | The header subtitle and glow follow the mapping. The glow appears only with at least one other present person on a fresh list. No member count is shown without data, and the header never reuses the room list's `active_count` snapshot. | U, C |
| P-131 | Presence moments render above the composer, one at a time, with the existing enter actions and gating. Draft text is never lost. | C |
| P-132 | The WARNING countdown runs only while visible and is announced once plus at 10 s, never every second. | C |
| P-133 | The people sheet is never shown with a stale list, and guests are marked. | C |
| P-134 | The room details sheet offers only existing actions. Owners can't leave; they see the explanation and Transfer ownership (P-217). | C |
| P-135 | No new timers or requests. Presence, inactivity, send and read contracts are unchanged. | R, existing tests |
