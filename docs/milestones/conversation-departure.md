# Conversation departure: late stops and DM navigation writes

Two defects, fixed together for room conversations (`ConversationViewModel`) and DM threads (`DirectThreadViewModel`).
Both ViewModels are activity-scoped and shared by every conversation destination: the room, notification-room and
search-result rooms, or the DM, notification-DM and search-result DMs.

## 1. A late ON_STOP left a newer conversation (late-stop class, as in #268)

Navigation moves an outgoing entry to CREATED only when its exit transition ends. When conversation B's screen started
on the shared model before conversation A's exit transition finished, A's ON_STOP arrived later. The root saw that A
was no longer the current entry and called `leavePane()`:
- **Room:** `cancel()` → `background()` plus a realtime deselect;
- **DM:** `background()` when nothing was uploading.

Either way, on the model now showing B, this could retire B's load, drop its waiting-to-send state and mark it stale. CI
disables animations, so it couldn't see it.

**Fix:** each conversation screen passes an owner token. `foreground(owner)` records the screen, and `leavePane(owner)`
is a no-op unless that screen still owns the pane. The root's choice is unchanged: a still-current entry means
`background()`, otherwise `leavePane(owner)`.

## 2. DM navigation turned an authorized write into unknown delivery (existing DM navigation path)

`DirectThreadViewModel.leavePane()` preserved only an in-flight *upload*. With a plain text send in flight (or an
author edit, reaction, block or read), navigating away fell through to `background()`. That set `uncertainSend` and
retired the job, so the send became unknown delivery.

**Fix:** `leavePane()` now keeps the serialized job whenever any write is in flight. Like the room model, navigation
preserves every authorized write (AGENTS M5g5e "preserve navigation writes", M5e3b).

## The contract, now pinned

- **A navigation departure never cancels an in-flight POST, in either ViewModel.** That includes the late-stop
  ordering.
- **A genuine app background still cancels and marks unknown delivery, never resent.** Explicit GET reconciliation,
  then duplicate-risk consent, follow (M3b, M5g5c/e). This is unchanged.
- **Known limit, unchanged:** opening a *different* conversation on the same model retires a non-upload write of the
  previous one as unknown delivery. Each ViewModel is a single serialized actor bound to one conversation, and only
  uploads detach from it. Changing that is a separate design.

## Verification

- `DirectStateTest`:
  - a text send, a read and a block each survive a navigation and confirm with no unknown state;
  - a genuine background still makes a text send unknown;
  - thread A's late departure after thread B started leaves B's load, draft and state alone.
- `DirectAuthorChangeTest` and `DirectReactionStateTest`: an author edit and a reaction survive a navigation.
- `ConversationRealtimeTest`: room A's late departure after room B started leaves B alone, including B's send in
  flight. The existing tests already pin that a room send and read survive `leavePane()` and that a genuine background
  still retires the send.
