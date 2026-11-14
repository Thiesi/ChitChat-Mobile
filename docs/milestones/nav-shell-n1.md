# Navigation shell N1: structure

Implements [navigation-shell.md](../design/navigation-shell.md), as merged in PR247: two graphs, the bottom bar, tab switching
as departures, saved stacks, Back, and the You hub. N2 follows with the top bar (an IconButton back arrow described "Back",
large titles, P-76), scroll-to-top on reselection, the AMOLED top edge, TalkBack positions ("Rooms, tab, 1 of 4"), the
profile header and the remaining captures.

## Structure

- **Two graphs.** `home` (the root graph's start) is onboarding: server, sign-in and the signed-out Settings action
  (theme and Forget server live there). While `AUTHENTICATED`, the root effect navigates from `home` into the nested
  `shell` graph, whose start is the Rooms root. Every signed-in destination is in `shell`. Leaving `AUTHENTICATED` clears
  every saved tab stack and returns to `home`, with the same cleanups as before.
- **Tabs** (`NavShell.kt`): Rooms (`rooms`), Direct (the scoped `direct?…` inbox), Activity (the scoped `notifications?…`
  center) and You (`you`). A tab root is its route with no visible entry below it (public `NavController` API only).
  The current tab is kept in saved state, so it survives recreation. Direct shows only when the DM models exist, and Activity only with a notice identity.
- **Departures unchanged.** A tab switch changes the current entry, and the existing root effect applies today's
  departure rules: directory work cancelled, pane left, DM inbox and thread backgrounded, account work cancelled, search
  closed. Navigation never cancels or replays a POST.
- **Saved stacks.** A switch saves the current tab's stack (`saveState`). A saved stack is restored only under the scope
  key it was saved in (server, generation, viewer, session version). Otherwise it's discarded, and the tab's root is
  rebuilt from the current session.
- **Stale scope** (technical lead, 2026-11-14). A visible entry whose scope goes stale goes to the Rooms root, the shell's
  start, as "pop to home" did, and every saved stack is cleared. It doesn't rebuild the stale tab, which would start the
  new account's private GETs unasked.
- **Back.** On a pushed destination, Back pops it. At the Direct, Activity or You root, Back goes to the Rooms root; that
  is system Back, and also the N1 text Back button, which stays until N2. At the Rooms root no back callback is enabled,
  so the system handles Back and the app leaves through the ordinary genuine-background path (`ON_STOP`). Reselecting
  the current tab pops it to its root.
- **Push taps** select the Activity tab at its root (`restore = false`).

## Bar

The bar is Material `NavigationBar` with Lantern nav icons, always-visible labels, a bold selected label and a
`primary`-tint indicator. It shows only on a tab root while signed in. It's out of layout and semantics on pushed
destinations, on onboarding and while the IME is visible. The Rooms and Direct roots hold a field (directory lookup, DM
search); the bar sits below the content, so the focused field doesn't move when the bar leaves, and nothing touches focus
or the IME. The Activity badge uses only the existing summary count (`CountBadge`, "99+", a dot while stale), and its
`stateDescription` is the same count text the old hub button carried. Rooms and Direct have no badge.

## You

You holds the rows Notifications and push (only with push), Appearance (`settings`) and Privacy and security
(`account`, which also holds Export). Below them:
- **Server:** the host, plus Change server, disabled while signed in with its existing text;
- **Session:** AuthScreen's authenticated state, unchanged (signed-in-as, retention, recovery codes, Refresh session and
  the outlined Sign out);
- **About and privacy:** the app version and Open-source licences, moved from `account`. Rows appear only when their
  source exists; there are no placeholders and no P-221 row;
- **The privacy card.**

## Restored conversation (condition 2)

The bar is absent on pushed destinations, so a Rooms stack is saved with a conversation on top only when a push tap
selects Activity from inside that conversation. Selecting Rooms again restores the conversation as a re-entry: a fresh
history read, with read proof only through the normal visibility rules. `NavShellJourneyTest` holds the history GET and
proves no read happens before it.

## Verification

- **`NavShellJourneyTest`:**
  - the bar on roots only;
  - a tab switch as a departure, with a pending send surviving;
  - stale scope;
  - Back at each root, and reselection;
  - the restored-conversation re-entry;
  - recreation;
  - sign-out from a tab;
  - the You controls by tag;
  - with the IME up on the Rooms and Direct roots, the bar is absent and the field's bounds are unchanged.
- **`NavShellPresentationTest`:** at real Configuration font scales 1.0 and 2.0, light and dark, every label is
  fully drawn and inside its item (no ellipsized line, no line wider than the drawn label, no wrapped line below it; `TextLayoutResult.hasVisualOverflow` proved unreliable here), with a negative control that the check rejects clipped, ellipsized and cut text, and every item is at least 48dp. Captures go to `ccm-nav-presentation`
  (4 files).
- **Navigation-only migrations:** existing journeys reach Activity, Direct and Rooms through the `openTab` test helper,
  with each journey's original input routing (`performNativeTap` or `performClick`). The You rows replace the old
  Account and Settings actions, and the search action keeps its `message_search_open` tag on the Rooms and Direct top bars.
