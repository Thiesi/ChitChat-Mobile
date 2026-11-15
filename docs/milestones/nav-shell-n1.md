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

The bar is a Material `NavigationBar` container whose items are drawn by `ShellBar` itself (`selectable` with
Role.Tab, the `nav_tab_*` tags). Each item has a Lantern nav icon, an always-visible label and a pill: `primary` at
18% behind the selected icon. Selected label and icon use `onSurface`, unselected `onSurfaceVariant` (the presentation
lead's J2). In Pure black only, a 1dp `line` top edge separates the bar from black content (AMOLED P-07, J3).

**Labels (P-78, J1):**
- Each item uses its full width.
- All four labels are measured in bold. If any wouldn't fit on one line, all four share the largest size that fits,
  the same whether selected or not, through the pure `sharedLabelScale` plus a measured step-down bounded by the floor.
- The floor is 18sp (1.5x). Below it, labels wrap and are never clipped.
- At 200%, that's about 182% on a 320dp window and the full size from 360dp. Measured on CI, the one-line boundary
  is about 270dp: at exactly 264dp "Activity" wraps at the floor.

The bar shows only on a tab root while signed in. It's out of layout and semantics on pushed
destinations, on onboarding and while the IME is visible. The Rooms and Direct roots hold a field (directory lookup, DM
search). With the IME up on either root, the field stays focused and wholly above the IME, and the bar is gone from
semantics. The field's position isn't asserted: it moves with the IME (the Rooms lookup sits below the room list and is
scrolled into view, and DM search shifted 3 px on API35), and the bar only ever leaves together with the IME, so a move
can't be attributed to the bar. Nothing touches focus
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
  - the inbox pushed from Activity has no bar, while all four roots do (why a tab root is "a tab route with nothing
    below it");
  - with the IME up on the Rooms and Direct roots, the bar is absent and the field stays focused and above the IME.
- **`NavShellPresentationTest`:** at real Configuration font scales 1.0 and 2.0 on the debug `FontScaleTestActivity`,
  light and dark.
  - Every label is fully drawn: no ellipsized line, no line wider than the drawn label, no character left off every
    line, and no wrapped line below the drawn label. `TextLayoutResult.hasVisualOverflow` proved unreliable here.
  - Each label is inside its item, and each item is at least 48dp with Role.Tab and the right selected state.
  - One line at default, at 200% full width and at 272dp. One shared size; at 200% it's at least 18sp, and below the
    full size on the 320dp AVD. At 240dp the labels stay complete when they wrap.
  - In Pure black, the top row is the `line` edge.
  - A negative control proves the check rejects clipped, ellipsized and cut text.
  - Captures go to `ccm-nav-presentation`: light and dark at default and font200, plus black default.
- **`SharedLabelScaleTest`** (JVM) pins the size rule.
- **Navigation-only migrations:** existing journeys reach Activity, Direct and Rooms through the `openTab` test helper,
  with each journey's original input routing (`performNativeTap` or `performClick`). The You rows replace the old
  Account and Settings actions, and the search action keeps its `message_search_open` tag on the Rooms and Direct top bars.

## N2a: top bar and shell polish

- **Back (P-76):** pushed destinations, onboarding's included, show an `IconButton` with the Lantern back icon
  (`nav_back`, described "Back"). Tab roots have none and use large (`headlineSmall`) titles. System Back is
  unchanged: at the Direct, Activity or You root it goes to the Rooms root, and at the Rooms root it leaves the app.
- **Reselection:** reselecting the current tab at its root scrolls it to the top (`OnTabReselect` in core/ui, one
  count per tab, ignoring the count seen at entry so entering or restoring a root never scrolls). It never refreshes.
  Reselecting below the root still pops to the root.
- **TalkBack:** the bar is a selectable group with collection info, so each tab reads as "Rooms, tab, 1 of 4". The
  Activity count text stays its badge description.
- **Profile header on You:** an initial pebble, the username, and a display-only badge (steward style) for the
  highest global role the server reports for the signed-in user: Super-Administrator, Administrator, Chat
  Administrator or Global Moderator. Nothing for members or unknown roles, and nothing inferred.
- **Tests:**
  - `NavShellJourneyTest`: P-76 on roots and pushed screens; reselect scrolls to the top without a refresh; tab
    positions; the role badge for known, unknown and member roles.
  - `YouPresentationTest`: the session group (outlined Sign out, P-75) and About and privacy (P-77) below the fold, at
    default and 200%, light and dark (`ccm-you-presentation`, 8 files).
- **Affordance changed, text Back → system Back:** journeys that left a tab root by the old text Back now use
  `Espresso.pressBack()`, the same OS back as the P-73 test. None of them has the IME up at that point.

## N2b: accessibility feedback (P-221)

- **Source:** ChitChat v3.14.0's public `session.php` field `accessibility_feedback: {"url": …} | null` (#219). It's
  presence-gated, so the minimum stays v3.10.0. `SessionReply` reads it leniently (a malformed value never fails the
  bootstrap), and `accessibilityFeedbackUrl` keeps only:
  - an `https` URL with a host and no credentials;
  - a `mailto` address;
  - at most 2,048 characters, with no whitespace or control characters.
  Anything else (missing, `null`, another scheme including upper-case `HTTPS:`) is absent: no row, no button, no
  explanation, no fallback address. It's stored on `PublicSession`, refreshed by every bootstrap, signed in or out.
- **Where it shows:**
  - You's About and privacy gets a row, below Open-source licences.
  - Signed out, Sign in and Create account get a footer text button below every action. Restore account doesn't, and
    neither does the first-run screen without a server.
  - The onboarding restyle's "Server found" step doesn't exist yet. It gains the same footer when it's built.
  - The guests' You (M7) gets the row with that layout.
- **Opening:** tapping opens an explicit choice that names the destination (the host, or the mail address) with
  "Open in browser" (`ACTION_VIEW`, browsable) or "Open mail app" (`ACTION_SENDTO`), plus Cancel. Nothing from the
  session is added, and a missing handler shows a short message.
- **Tests:**
  - `AccessibilityFeedbackTest` (JVM): valid https and mailto; missing, null and malformed fields; other schemes and
    unsafe shapes; whitespace and control characters; the 2,048 limit; decoding through `SessionReply`.
  - `AccessibilityFeedbackPresentationTest`: absent without an address (signed out and in You); the mail choice;
    captures of the Sign in footer, the You row and the dialog at default and 200%, light and dark
    (`ccm-a11y-presentation`, 12 files).
