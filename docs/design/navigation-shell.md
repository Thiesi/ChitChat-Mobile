# Navigation shell: implementation specification

Status: implementation-ready spec for approved decision D3, the bottom bar **Rooms · Direct
· Activity · You**. It follows the [theme foundation](theme-foundation.md) slice and comes
before the transcript and list restyles. The coordinator schedules it. Mockups: the bottom
bar on `Home`, `DirectInbox`, `Activity` and `AdminHome`.

**This slice changes structure, not contracts.** The navigation guards in `ChitChatApp.kt`
encode many milestone rules: actual-entry identity, STARTED checks, owner- and
scope-encoded routes, departure cancellation, and summary versus full notification
modes. Each must survive with the same meaning. Where this document and a milestone
contract disagree, the contract wins. Report the conflict so it gets fixed here.

## Today's navigation (2026-11-07)

- One `NavHost` with start destination `home`. `home` is both the onboarding/auth surface and
  the signed-in hub: buttons for Rooms, Direct messages, Search and Notifications (with the
  unread count), Change server, the privacy card and the authenticated `AuthScreen` section
  (signed-in-as, session retention, Refresh session, Sign out).
- Destinations: `rooms`, `conversation/{roomId}`, `direct?…scope`, `direct/{peerId}?…scope`,
  `notifications?…scope`, `notification-room/{roomId}?…scope`, `notification-account?…scope`,
  `message-search?…scope`, `account`, `settings`, `server`.
- A single `TopAppBar` with a text "Back" button (instrumented tests select it by the text
  "Back"), and Account and Settings text actions on `home`.
- A root `LaunchedEffect(entry, session…)` applies the **departure rules**:
  - leaving `rooms` cancels room-directory work;
  - leaving a conversation route calls `conversations.leavePane()`;
  - leaving `direct*` backgrounds the DM inbox and thread;
  - leaving account routes cancels account and typing-preference work;
  - search closes when its entry is not current;
  - routes whose scope no longer matches the session pop to `home`.
- Activity-level `ON_STOP` treats a genuine background (not a configuration change) as
  background for conversations (except while a conversation route is showing), profiles and
  the notification lifecycle.

## Target structure

### Two graphs

1. **Onboarding graph**, shown while not authenticated: the screens of
   [onboarding-restyle.md](onboarding-restyle.md). No bottom bar.
2. **Signed-in shell**, shown while `SessionPhase.AUTHENTICATED`: the bottom bar and four
   top-level destinations, each the root of its own back stack.

Leaving `AUTHENTICATED` (sign-out, invalidation, forced logout) leaves the shell and shows the
onboarding graph's appropriate screen. It applies today's cleanup for every destination,
exactly as the existing "pop to home" guards do.

### The four destinations

| Tab | Root destination | Replaces | Notes |
|---|---|---|---|
| **Rooms** | existing `rooms` | Rooms button on `home` | Start destination of the shell. The drafts manager entry (`room_directory_drafts`) stays at the top of the list. Search action in its top bar |
| **Direct** | existing `direct?…scope` | Direct messages button | The route keeps its scope arguments, built from the current session at selection time. Search action in its top bar |
| **Activity** | existing `notifications?…scope` | Notifications button and count | The full list. Its badge uses the existing summary count (see Badges) |
| **You** | **new** `you` hub | `home`'s signed-in section, `account`, `settings` | See "You" below |

Search (`message-search?…scope`) is opened from the Rooms and Direct top bars. It is not
a tab. Pushed destinations (`conversation/…`, `direct/{peerId}…`, `notification-room/…`,
`notification-account…`, search, server) stack on top of the current tab.

### The You hub (new)

The signed-in parts of `home`, `account` and `settings`, reorganized without changing what
any control does:

- Profile header: initial pebble, username, and a global role badge (steward style) derived from the session user's existing `roles`, shown only for roles the server reports. The badge is display only and grants nothing.
- **Looking after this server** card for roles that can moderate. Dependency: M7. Omit it
  until those features exist.
- **Invite someone**, once [invite codes](invite-codes.md) ship. Omit until then.
- Rows that open today's screens unchanged in behavior:
  - Notifications and push (settings);
  - Appearance (settings: Theme radios and the Pure black switch);
  - Privacy and security (account: typing preference, ignores, sign-in);
  - Export my data (account);
  - Server (shows the host; Change server stays disabled while signed in, with the existing
    "Sign out to change server" text).
- Session section, from `AuthScreen`'s authenticated state:
  - signed-in-as;
  - session retention text;
  - recovery-codes-remaining text when present;
  - Refresh session;
  - **Sign out** (outlined, at the bottom, never persimmon-filled).
- The privacy line ("Your server hosts your conversations. Messages are not end-to-end
  encrypted.") and the feature-preview disclosure link while it exists.

Existing `account` and `settings` content may stay as pushed sub-screens reached from these
rows in this slice. Merging them visually is optional and must not change their logic.

## Bottom bar

- Material `NavigationBar` re-tinted by the theme. Four items, each with an icon and an
  always-visible label. Active item: bold label and a persimmon-tint pill behind the icon
  (`primary` at about 18% alpha over `surfaceContainer`). Icons from the vocabulary: Rooms =
  the simplified mark, Direct = person, Activity = bell, You = person in a circle.
- **Visible only on the four tab roots.** Hidden on every pushed destination, on onboarding,
  while the IME is visible and in the existing compact-height conditions (`compactKeyboard`).
  Hidden means removed from layout and semantics, not just transparent.
- 1dp `line` top edge in AMOLED black (amoled-black.md). Items have a 48dp minimum target
  (at least 56dp tall in practice).
- TalkBack reads each item as "Rooms, tab, 1 of 4", plus the badge description when a
  badge is shown ("Activity, 2 unread").

### Badges

Badges must follow existing data rules. They add no polling, background fetching or new
summary requests.

| Tab | Badge | Source |
|---|---|---|
| Activity | `CountBadge` with the unread count, "99+" capped; a plain dot when the count is stale (`countStale`) | The existing notification summary count used on `home` today: refreshed on foreground, navigation and manual refresh (M4c4c2), `limit=1` summary mode while the Activity tab is not showing |
| Rooms | None in this slice | Dependency: the room directory loads only on entry. A badge needs a defined summary refresh policy (coordinator decision). Don't keep directory results around just for a badge |
| Direct | None in this slice | Same dependency: DM inbox state is backgrounded on departure by contract |
| You | Steward-colored dot when moderation work is waiting | Dependency: M7 |

## Behavior rules

- **Selecting a tab** navigates to that tab's saved stack (`saveState`/`restoreState`) with
  `launchSingleTop`. For the root effects, this is a **departure** from the previous
  destination, so today's departure rules apply exactly as when leaving a route today:
  - room-directory work is cancelled;
  - a conversation pane is left;
  - DM inbox and thread are backgrounded;
  - account work is cancelled;
  - search is closed.

  A saved stack restores *routes and non-private UI state* (for example scroll position);
  private content is re-fetched by each destination's existing entry rules. Navigation still
  never cancels or replays a POST. Existing busy-POST protections stay as they are.
- **Restored scope:** when a saved stack contains a scope-encoded route
  (`direct*`, `notification*`, search) whose scope no longer matches the session, that tab
  resets to its root, rebuilt from the current session. This uses the same check that pops
  such routes to `home` today. A stale-scope entry is never shown, not even for one frame.
- **Reselecting the current tab** pops that tab to its root. If it is already at the root,
  the list scrolls to the top. Neither triggers a refresh by itself.
- **System Back:**
  - on a pushed destination, pop it;
  - at the root of Direct, Activity or You, go to the Rooms root;
  - at the Rooms root, leave the app (the Activity finishes, and genuine-background rules
    apply through `ON_STOP` as today).
- **`ON_STOP` handling** stays at the Activity level as today. The conversation exception
  checks the *current* route, including conversations pushed on any tab.
- **Notification lifecycle:** `inNotifications` becomes "the Activity tab root is the current
  entry with a matching scope". Summary versus full mode is still chosen only by root
  lifecycle and navigation (M4c4c2).
- **Deep destinations from Activity** (`notification-room`, `notification-account`) are pushed
  on the Activity tab, keeping M4c4d's atomic prepare, navigate and consume with the
  current entry reference, ID, STARTED state and owner.
- **Profiles:** the existing rule that a profile selection closes when its source entry is no
  longer current stays unchanged, and a tab switch counts as leaving the source entry.
- **Configuration changes** (rotation, font scale, dark mode) keep the selected tab and every
  saved stack, and cancel nothing, as today.

## Top app bar

- Per destination: the title in `titleLarge` (Bricolage, from the theme foundation). The four
  tab roots use a large title ("Rooms", "Direct", "Activity", "You") without a back
  affordance. Pushed destinations show an `IconButton` back arrow with the content
  description **"Back"**, so existing instrumented tests that find "Back" by text are
  updated to find it by content description in the same PR.
- Actions: Search on Rooms and Direct. Account and Settings actions disappear (now under You).
- The realtime status notice (`RealtimeStatus`) stays directly under the top bar on every
  signed-in destination, with today's conditions (communication.md level 2).

## Tests

- Update the instrumented tests that use the text "Back" or the `home` hub buttons to find the
  back icon by content description and to use tab navigation. Add stable test tags:
  `nav_tab_rooms`, `nav_tab_direct`, `nav_tab_activity`, `nav_tab_you`, `nav_back`.
- New Compose tests:
  - each tab switch applies the departure rules above (fakes record cancel, leavePane and
    background calls);
  - a stale-scope saved stack resets to its root without showing the stale entry;
  - Back behavior at each tab root;
  - reselection pops to the root;
  - the bottom bar is absent on pushed destinations and while the IME is visible;
  - tab state survives recreation;
  - sign-out from any tab returns to onboarding and applies every cleanup.
- Every native input and IME test runs unchanged. Hiding the bar when the keyboard shows must
  not move focus or dismiss the IME.
- Landscape gates: the bar hides under the existing compact-height rules. Record the
  landscape behavior in the PR.

## Acceptance

Extends P-30:

| ID | Requirement | Verify |
|---|---|---|
| P-70 | Signed in, the bottom bar shows Rooms · Direct · Activity · You on the four roots only. Not signed in, there is no bar. | C |
| P-71 | A tab switch applies exactly today's departure rules for the destination being left. Navigation never cancels or replays a POST. | C (fakes), R |
| P-72 | Saved stacks restore routes and non-private UI state only. A stale-scope entry resets its tab to the root without being shown. | C |
| P-73 | Back and reselection behave as specified. Leaving from the Rooms root goes through the genuine-background path. | C |
| P-74 | The Activity badge uses only the existing summary count and modes. Rooms and Direct show no badge until a summary policy is decided. No new polling or background requests. | C, R |
| P-75 | You contains every control from the old hub, `account` and `settings`, with unchanged behavior. Sign out is not persimmon-filled. | C, R |
| P-76 | Pushed destinations use an icon back button described "Back". Tab roots have none. | C |
