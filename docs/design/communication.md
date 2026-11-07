# How CCM communicates

This document governs what CCM says during normal operation, where it says it, and how
loudly. It sits on top of the behavior contracts in `AGENTS.md`. It describes how to show
states; it never changes when they happen.

## The status ladder

Every piece of status information uses the *lowest* level that does the job.

| Level | Form | Use when | Lifetime |
|---|---|---|---|
| 1 Ambient | In place: a word under a message, a badge, a header subtitle, a muted line | The fact concerns one item, and the person can carry on | As long as the fact is true |
| 2 Notice | A strip directly under the top app bar, full width, icon plus one or two sentences | The fact affects the whole screen (offline, room access lost, read-only) | As long as the fact is true; a recovery confirmation may auto-dismiss after about 2 s |
| 3 Decision | A dialog or sheet | The person must choose before anything else happens: duplicate-risk send, destructive action, step-up | Until the person chooses |

Rules:

- No snackbars or toasts for facts that matter. They vanish before slow readers and screen
  reader users catch them. A snackbar is acceptable only to confirm something the person
  just did and can see anyway ("Copied").
- Never stack two notices. When several apply, show the most limiting one (for example,
  *offline* outranks *reconnecting* outranks *read-only room*).
- Level 2 and 3 messages are announced to accessibility services: notices as polite live
  regions, dialogs through their title.
- An error message names what happened, what was and was not affected, and what the person
  can do. "Something went wrong" is never acceptable on its own.

## Connectivity

CCM must cope with network loss at any moment and recover without user effort. The
presentation rules:

| State | Detection | What the person sees |
|---|---|---|
| Online | Normal | Nothing. No "connected" badge. |
| Device offline | `ConnectivityManager` network callback (event-driven, no polling) | Notice: **"No connection.** You can read and write. Sending waits until you're back." Send is disabled with the accessible label "Send, waiting for connection". Drafts stay in the box. |
| Server unreachable while the device is online | Failed requests or stream errors | Notice: **"Can't reach Schmidt Family Chat.** We'll keep trying." (installation name, else host). No technical codes in the strip; details live behind "Details". |
| Reconnecting | Stream reconnect in progress after a drop | Header subtitle: "Reconnecting…" (level 1). Escalate to the notice only if it lasts longer than about 5 s. |
| Back | First successful reconciliation after a drop | The notice turns into "Back online" for about 2 s, then disappears. Content updates in place without jumping the scroll position. |
| Session ended | Server says the session is invalid | Decision screen: "You've been signed out of Schmidt Family Chat" with Sign in. Never silent. |

What reconnection does and does not imply:

- Reconnecting refreshes what is shown. It never sends anything on the person's behalf.
  Drafts that were waiting stay drafts until the person presses Send.
- Content loaded before the drop stays visible while offline, with a quiet marker at the
  live edge: "Loaded at 17:58. Newer messages appear when you reconnect." Do not blank the
  screen or show a spinner over readable content.
- Presence and typing are unknown while offline. Show "Who's here is unknown while
  offline" rather than a stale count.

Battery: these states come from callbacks and from the existing foreground stream. Never
add polling or wake-ups to keep a status indicator fresh. The only timers allowed are the
short presentation delays above (dismissing "Back online", escalating "Reconnecting…"),
bound to the visible UI and cancelled when it leaves or the app goes to the background. In
the background, CCM shows nothing because it renders nothing.

## Message delivery

Shown under or inside your own bubble only. Other people's messages never carry delivery
states.

| State | Visual | Words | Actions |
|---|---|---|---|
| Sending | Bubble at reduced emphasis, small clock icon | "Sending" | none |
| Sent (server confirmed) | Normal bubble | Time only; "Sent" under the latest one if useful | none |
| Not sure it arrived (ambiguous result) | Bubble keeps full emphasis with a 2dp honey inset outline | Pill: "Not sure this arrived · Check" | **Check** runs the explicit history check. If the message is found, it becomes Sent. If not, a level-3 decision follows: "Send again? We checked and couldn't find it. Sending again may post it twice." with **Keep as draft** and **Send again**. |
| Failed before delivery (definitely not sent) | No bubble; notice above composer | "Couldn't send '…'. Nothing was delivered. Your text is back in the box." | The text is restored to the composer |

These map exactly to the existing unknown-delivery contracts: automatic entry or reconnect
is not the explicit check, and "Send again" is the explicit duplicate-risk consent. CCM has
no read receipts for other people; do not invent check marks that suggest them.

## Presence, typing and attention

| Concept | Wording | Visual |
|---|---|---|
| People present in a room | "16 here" / "Just you here" | Glow on the room lantern, avatar stack (max 3) in the header |
| Members | "41 members" | Text only |
| Typing | "Lena is typing", "Lena and Jonas are typing", "Lena, Jonas and 2 others are typing" | Honey dots |
| Unread in a room | Number badge; "99+" above 99, as the server caps it | Persimmon badge, ink number |
| Mention of you | "@" badge in honey before the count; mention text highlighted with `presenceWash` | |
| Private ping | Activity entry "Lena pinged you" | Persimmon paper-plane badge |
| Ignored person (room) | Existing collapsed-row behavior | Muted text, no identity hue |
| Blocked person (DM) | "You blocked this person" | Desaturated pebble |

"Here" means active presence, which differs from membership. Never say "online" for
members whose presence is not known.

## Disclosures that must remain visible

Presentation may restyle these but never remove or bury them below the first screen:

- DM inbox: "Not end-to-end encrypted" with a link to the existing retention and inspection
  disclosure.
- Activity: "Reading an item here doesn't mark the chat as read."
- Exports: the existing unencrypted-file and partial-file disclosures.
- Step-up and recovery-code warnings, as specified in M2b.

## Voice

CCM talks like a friendly person who runs a good café: short, warm, plain, never cute when
something is wrong.

| Do | Don't |
|---|---|
| "4 here now" | "4 users online" |
| "Nothing was delivered" | "Error 503" |
| "Your text is back in the box" | "Please try again later" |
| "Ask the person who runs it" | "Contact your administrator" |
| "It's quiet in here." | "No messages" |
| "Delete for everyone…" (ellipsis = a confirmation follows) | "Delete" when it will act immediately on others |

- Use the installation's name when known; otherwise the host name. Never "the server" when
  a name is available.
- Address the person as "you". Avoid "please", exclamation marks in errors, and jokes in
  error states.
- Times: follow the account date/clock preferences and device timezone (existing M3b rules).
- Numbers: "99+" where the server caps; no rounded "1.2k".

## Notifications (push, M6)

Push delivery is M6. When it lands, notification presentation follows these rules:

- Channels match people's intent: *Mentions and pings* (default on, high importance),
  *Direct messages* (pending product decision B6), *Account and security* (default on),
  *Server announcements*. No "promotional" channel ever.
- Title = the person or room; text = the message excerpt the server provides. The
  notification accent color is persimmon; the small icon is the monochrome mark.
- Lock-screen visibility is private by default because rendered notices can contain
  usernames and mute reasons (see M4c4a).
- Never send a local notification to re-engage someone ("You haven't visited in a while").
