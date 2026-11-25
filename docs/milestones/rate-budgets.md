# Rate budgets (#323)

ChitChat has no global API limiter. Every limit is a named per-action, fixed-window policy
(`docs/operations/rate-limiting.md` at the minimum backend v3.10.0, b2791a0). Every limiter refusal comes from
`src/Http/RateLimiter.php:108–114`: HTTP 429, code exactly `rate_limited`, `retry_after_seconds` >= 1 and `Retry-After`.
`session.php` and the stream have no policy, so a bootstrap or revalidation 429 can only come from a proxy.

Before #323 every 429 went through `failLocked`, which set the session-wide wait. One policy's refusal (an eleventh
upload in an hour, for example) then blocked reading, sending and everything else until the window ended.

## #323a: the core (TL plan and conditions, 2026-11-23)

- **`RateBudget`:** one value per server policy, each citing where its endpoint consumes it (file:line at b2791a0).
  None is broader than its policy: a missed local gate costs one more server 429, while over-gating would refuse what
  the server allows.
- **Opt-in:** a call site passes `authorized(access, budget = RateBudget.X)`. Its policy's own `429 rate_limited` then:
  - sets no session-wide wait or error (no row 7, no P-25 gate);
  - records that budget's deadline: the transport's wait (Retry-After first, capped at 86,400 s) on a monotonic clock
    (`SystemClock.elapsedRealtime`, which counts deep sleep, so the gate never outlasts the server's window);
  - while that deadline runs, refuses the same budget locally: a typed `ApiFailure(429, "rate_limited")` with the
    remaining wait and `localGate = true`, and no request. It's read only at press time, with no timer.
  - An unknown wait (no positive `retry_after_seconds`) sets no gate; the next press goes to the server.
- **Unchanged, session-wide:** a 429 without `rate_limited` JSON (a proxy's), any 429 on a call site without a budget,
  503, 401, `csrf_failed`, network failures and timeouts.
- **Fencing:** budgets live in the session runtime, stamped with generation and account. Sign-out, server forget and
  any runtime replacement drop them; a different generation or account ignores and removes them. Memory only.
- **Definite refusals:** each mapped endpoint consumes its policy before any write work, so a write's `rate_limited` 429
  is definite. Its typed result (already a definite RateLimited outcome on every #323a path) is unchanged.

| Budget | Server policy | Consumed at (b2791a0) | Client call sites |
| --- | --- | --- | --- |
| `MESSAGE_REPORT` | `message_report` | `reports/message.php:24` | room and DM message reports |
| `USER_REPORT` | `user_report` | `reports/user.php:24` | person reports |
| `PUSH_SUBSCRIPTION_MANAGEMENT` | `push_subscription_management` | `push/update-preferences.php:23–24`, `push/revoke-device.php:23–24` | preferences, device revoke, Turn off's revoke, the unregistered revoke |
| `PERSONAL_DATA_EXPORT` | `personal_data_export` | `account/export.php:24–25` | the export |
| `ATTACHMENT_UPLOAD` | `attachment_upload` | `attachments/upload.php:25–26` | room uploads |
| `DIRECT_MESSAGE_ATTACHMENT_UPLOAD` | `direct_message_attachment_upload` | `direct-messages/attachments/upload.php:25–26` | DM uploads |
| `PRIVILEGED_STEP_UP` | `privileged_step_up` | `PrivilegedStepUpService.php:37` (via `step-up.php:24`) | the password step-up |
| `MFA_RECOVERY` | `mfa_recovery` | `MfaService.php:263`, before :269 (via `mfa/step-up-recovery.php:22`) | the recovery-code step-up |
| `RECOVERY_KEY_MANAGEMENT` | `recovery_key_management` | `RecoveryKeyService.php:27`, before :28 (via `account/recovery-key.php:22`) | Create or Replace key |

**Left unmapped on purpose (session-wide as before):**
- `push/subscribe.php`: it can refuse under `push_subscription_refresh` (`subscribe.php:23–24`) or
  `push_subscription_management` (`PushSubscriptionService.php:48/79`, the latter after a classification commit). It
  doesn't match exactly one policy.
- The app has no client for `sessions_management` or MFA management.

**Visible change:** none in the typed-429 UIs themselves (uploads, recovery key, step-up, export, push, reports). What
goes away is the session-wide reaction to these refusals: row 7, the P-25 gate, AccountScreen's session line
(`AccountScreen.kt`, `now < session.retryAtMillis`), and every other screen's wait.

## #323b1: sends, ping and typing (TL split and PL ruling, 2026-11-23)

| Budget | Server policy | Consumed at (b2791a0), before any work | Client call site |
| --- | --- | --- | --- |
| `ROOM_SEND` | `room_send` | `rooms/send.php:31–32`, when `PingCommand::parse` (:29) finds no ping | room send |
| `ROOM_PING` | `room_ping` | the same consume, for a ping | room `/ping` |
| `ROOM_BROADCAST_MENTION` | `room_broadcast_mention` | `rooms/send.php:39–41`, after `room_send`, for a non-ping body with `@room`/`@here` | room send with a broadcast mention (#323b3) |
| `DIRECT_MESSAGE_SEND` | `direct_message_send` | `direct-messages/send.php:23–24` | DM send |
| `TYPING` | `typing` | `typing.php:30` | room and DM typing |

- **Which policy a room send hits:** `isPrivatePingCommand` (phpTrim, then `/ping` or `/ping `) mirrors
  `src/Realtime/PingCommand.php:14–15` exactly. A bare `/ping` is that parse's 400, before any consume, unchanged.
- **Guests:** a guest also consumes `guest_room_send` (`rooms/send.php:37`) for the same action. Guests are M7 and the app
  has none, so there's no budget for it.
- **Display:** `SessionState.rateWaits` mirrors each known deadline (written under the repository lock, memory only, gone
  with the state on any runtime replacement). `rateWaitMillis(budget, now)` also fences it to the generation and
  account. Screens read the same monotonic clock as the gate through `LocalMonotonicClock`, never wall time.
- **The composer line** (`RateWaitLine`, PL copy): "You've sent a lot of messages recently. Try again in {wait}. Your
  message is kept." and "You've sent a lot of pings recently. Try again in {wait}." A level-1 muted line, never error
  colour; Send announces it (below).
  - **Placement (PL amendment after the J on 3f0b798):** directly above the composer block (above the reply chip when
    there is one), outside the room's bounded header and the DM's top block, in full width. In the header it was
    invisible at 1.0 in the room, clipped in the DM, and not displayed at 200% or in landscape with the keyboard up.
  - **Composer first: full, short or hidden** (PL refinements after the re-Js on b2c34c4 and 12c3a36; TL-approved, the
    TL's condition 3 amended for the hidden state). With the timeline's height measured with the line absent (its height
    plus the drawn line's own slot, both from the same layout pass; the timeline is the column's only weighted block and
    the composer is unweighted):
    - **Full** only where the timeline keeps one 48dp row beside the full line.
    - **Short**, "Try again in {wait}." / "Try again later.", where the short form still leaves the composer its natural
      height (field at least 48dp, never shorter than without the line).
    - **Hidden** otherwise (compact with the keyboard up; on 12c3a36 the short line squeezed the 200% landscape field to
      about 30 dp): no line above the composer, the Details control shows its compact attention dot.
    - Short or hidden puts the full line first in Details (silent text, no tag); it's left out while the full line shows.
    - A drawn line with the timeline at 0 may be hiding a squeeze, so that measurement reads hidden.
  - **Stable:** `RateWaitSlot` decides once per notice and window: width, the height above the keyboard, font scale, and
    whether the reply chip and the attachment card or notices are present (PL: layout, not draft content). Never from the
    draft, so it can't flip while typing; the keyboard hiding or a reply started re-decides.
  - **No frame wait (TL condition):** timeline measurements are tagged with the window (`onGloballyPositioned`), so a
    notice arriving in a laid-out window decides at once. While a measurement for a new window is pending the previous
    form holds; with none yet, the short form shows. Measuring inside the column's own layout pass would have meant a
    custom layout in both screens' composer columns.
  - **A refusal that resolves to hidden hides the keyboard (PL rulings, TL-approved):** each refusal event, first or
    repeat (`sendLimitRefusals`), hides the soft keyboard at most once, and only if that notice's form in the current
    window, decided after the refusal (post-layout, `RateLineFit.settled`), is hidden. It's a plain hide answering the
    tap: focus, draft, caret and reply stay; the window re-decides, the line shows by the rule and the dot clears.
    - One pending flag per refusal event, consumed at the first settled decision; no polling, no timer.
    - Guards: the notice is still current, the composer still has focus, no dialog is open; otherwise nothing hides.
    - Only a refusal ever hides: tapping the field brings the keyboard back and the line hides behind the dot, with no
      hide and no announcement. A restored count (configuration change, re-entry) is seen, never acted on.
  - **The wait line itself carries every announcement (PL and TL, 2026-11-23).**
    - **Why not an appearing node:** Compose UI 1.11.4's accessibility delegate reads `LiveRegion` only to fill in node
      info and diffs semantics snapshots every 100 ms (`SendRecurringAccessibilityEventsIntervalMillis`). A node that
      appears (a line drawn when a notice arrives or the keyboard hides) looks new with its final text, so it isn't
      reliably announced; only a content change on a node from the previous snapshot is.
    - **Why not Send:** Send carried it first (298d9a5 to e096392). A button changes several properties when a refusal
      lands (label "Sending" to "Send", enabled, progress), and on a live node each is an event: run 45222016407 counted
      three announcements per appearance.
    - **So the line is one persistent node,** always composed, never smaller than 1dp, inside the timeline's block (the
      timeline, then the typing line and the wait line with their own 6dp top spacing). When nothing is drawn (no notice,
      or hidden) it's a 1dp strip with **zero layout footprint** (#348, option A2, TL and PL): it reports 0 height to its
      parent and places its 1dp node at its slot's bottom edge, overhanging into the 6dp gap and the composer's top
      padding, where no semantics node sits. Nothing moves (the timeline and the composer are measured as with no node),
      the strip draws nothing and takes no input, and with no notice it has no text, description or action, so it's no
      swipe stop. Why: it was zero height with no notice until run 45299066691. A zero-size node is outside the semantics
      snapshot, so a notice giving it size and text in one change arrived as a new node and didn't reliably announce (on
      both platforms, the only counted event came from the later keyboard-hide move). A plain 1dp strip inside the block
      wasn't enough (run 45307781872): a packed window (200% portrait, a reply chip and the keyboard up) allots the
      weighted block 0, and its strip with it. Its only semantics: a permanent polite live region, a
      description that's the full line while a notice is current and nothing otherwise, and its test tag. The visible
      text (full or short) isn't semantics, so visible and spoken text may differ (static text, as under P-25).
    - **Once on appearance, once per refused tap:** the description appearing is one change on an existing node. Each
      refused tap (the local gate or a repeated server 429) flips one trailing space, present or absent, on that
      description only (never the visible text, never Details), never on a timer, so each refusal is a real snapshot
      difference. TalkBack says "You've sent a lot of messages recently. Try again in about 5 minutes. Your message is
      kept." It's the one swipe stop on the wait, on the visible text.
    - **Silent otherwise:** clearing (the deadline, an unknown notice dropped on the next edit, a successful send)
      empties the description; full, short and hidden change only the node's size and visible text; typing, the
      keyboard, rotation and Send's own changes don't touch it.
    - **Hidden (compact with the keyboard):** the same node becomes a 1dp full-width strip at its place, directly above
      the composer block, keeping its description (TL's branch 2, PL-agreed): a zero-height node sent nothing at all on
      run 45233308933. It's the one accepted extra swipe stop, in that rare state, its focus box a 1dp strip at the
      composer's top edge (zero footprint since #348: nothing gives up space).
      The timeline's block has `heightIn(min = 1.dp)` (after run 45244641951), but **that never guaranteed 1dp**
      (correction, run 45307781872): a weight child gets exactly its share, and a min can't exceed the incoming max of 0
      in a packed window. #342's appearance-height check passed only because the refusal had hidden the keyboard first.
      The zero-footprint strip above is what keeps the node 1dp there; since #348 the hidden strip uses it too, so it no
      longer takes 1dp from the composer or the timeline.
    - **Counting (TL and PL rule, a model of TalkBack, not a proof):** an announcement is a carrier event with a non-empty
      spoken text that differs from the last counted one. Any move or resize of a live node also sends events (`SUBTREE`,
      identical text; run 45234305565): those aren't counted but stay listed in the test output. The owner's TalkBack
      check is the gate, and an open release item: no speech on a keyboard swap, rotation or full/short swap, one per
      appearance and per refused tap. Speech on swaps would be a defect to fix before release.
    - **Send** keeps its label and reads the full line as its state description while a notice is current, with no live
      region: focusing Send reads "Send, You've sent…" (PL condition 1).
    - **Tested** with real accessibility events (`LiveAnnouncements`: UiAutomation, content changes from a source that
      still has its live region). Every count is the difference between two delivered test sentinels (the positive
      control), and a timed-out sentinel fails only after the case's capture. Compose 1.11.4 sends no events on CI
      without its internal test flag (the manager is enabled but lists no service), so the tests set it by reflection
      (`ComposeAccessibilityForcing`, test-only, fails loudly if the field moves, tied to 1.11.4; the public
      `enableAccessibilityChecks` path doesn't set it). Listing UiAutomation as a spoken-feedback service was tried and
      dropped (the list stayed empty).
    - **On the owner's TalkBack list:** two refused taps give two announcements with nothing extra spoken (if TalkBack
      drops the parity, the fallback is another inaudible variant under the same limits, never a timed change); the number
      of swipe stops and where the focus box lands, with the line visible and hidden; whether a notice appearing while
      hidden is spoken.
  - **Unknown wait:** the send line keeps "Your message is kept." ("Try again later." replaces only "Try again in
    {wait}."); the ping line has neither.
  - **{wait}:** computed from the stored deadline at render and frozen until the notice changes; minutes and hours never
    tick; under a minute reads "less than a minute".
  - **At the deadline:** one composition-bound one-shot clears the line. It never sends; there's no other timer.
  - **An unknown wait** sets no gate and shows "…Try again later." until the next draft edit or leaving the conversation
    (a genuine background, a departure, the resource-denial reset; TL, #360). A known wait stays across leaving and
    returning until its deadline; a configuration change keeps both.
- **Definite:** the draft, reply and attachment stay; nothing becomes unknown; nothing resends. Nothing changed on the
  server, so history isn't marked stale and Send stays enabled. A tap during the wait is refused locally with no
  request and keeps the line.
- **Typing:** silent. During its wait the signal is refused locally and the coordinator shows nothing; only actual input
  attempts, at the existing cadence.
- **A proxy's 429** on a send keeps today's handling: the error line, the session-wide wait and row 7.

## #323b2: author edit/delete and reactions (TL plan, PL ruling, 2026-11-24)

A contract change to M5b1, like O3b3b: on all eight paths only the policy's own `429 rate_limited` (the server's or the
local gate's) is definite.
- **The budgets (server facts at b2791a0):**
  - `room_message_mutation` covers `rooms/edit-message.php`, `delete-own-message.php`, `react.php` and `unreact.php`;
  - `direct_message_mutation` covers `direct-messages/edit.php`, `delete.php`, `react.php` and `unreact.php`;
  - each endpoint consumes at :23–24, after CSRF and the actor and before its service (:29). So nothing was written, and
    an edit, a delete and a reaction gate each other within their budget (`theMutationBudgetsGateEachOtherLocallyAnd
    OnlyWithinTheirOwnPolicy`).
- **Definite:** no author or reaction uncertainty, history isn't marked stale, the edit dialog keeps its text, the
  reaction choice isn't sent or kept, and nothing resends. A later Save or choice is a fresh one. A proxy's or any other
  429 keeps M5b1's possibly-committed handling (a guard test per screen and kind).
- **The line (PL copy):** "You've changed a lot of messages and reactions recently. Try again in {wait}." ("…Try again
  later." with an unknown wait).
  - **Where (TL and PL, after the re-J on a942d9a):**
    - edit: in the body directly after the field. On appearance (a 429 while open, a known wait at opening, a refused
      tap) it's brought into view (BringIntoViewRequester: no focus or caret change, the full text), with at least 48dp
      of the field visible. In compact a refusal also hides the keyboard (a plain hide, focus kept, as the composer's
      rule b), so the line shows beside the field; opening with a known wait leaves the keyboard alone. The fixed area
      and the action row are exactly main's.
    - The two earlier placements failed J: below the fold at 200% (2f560da), and in the fixed area, where even the short
      form hid the field at 200% portrait and pushed Save under the keyboard in compact (a942d9a).
    - With no notice the line has zero footprint everywhere (its padding applies only while drawn, outside spaced
      columns), so the edit and delete dialogs and the picker are identical to main;
    - delete: the first item of the dialog's text (its gap below is drawn padding);
    - the reaction picker: above the emoji rows;
    - never on the Check history path.
  - A wait already known (another edit, delete or reaction started it) shows as soon as the dialog opens; opening sends
    nothing (`mutationNotice`).
  - The same persistent carrier as the composer's (`RateWaitDialogLine`): a polite live region with the full line as its
    description, one trailing space flipped per refused tap, and a zero-footprint strip when there's nothing to say.
  - It ends at the deadline (one one-shot), on closing the dialog (an unknown wait's next interaction) or on a confirmed
    change.
- **Captures:** `rate-mutation-*` (PL list 5–8), collected into `rate-mutation-presentation`.

## #323b3a: the @room/@here budget (a #323b1 fix; TL plan, PL ruling, 2026-11-24)

- **The defect:** `rooms/send.php` consumes `room_send` and then, for a non-ping body where
  `MentionParser::containsBroadcastToken` (`src/Mentions/MentionParser.php:62–70`) finds `@room` or `@here`,
  `room_broadcast_mention` (:39–41), both before any work. #323b1 put every send 429 under `ROOM_SEND`, so a broadcast
  refusal gated every room send for its wait: more than the server.
- **The fix:**
  - `containsBroadcastToken` mirrors the PHP exactly: case-insensitive, not after `[A-Za-z0-9_.-]`, trailing `.`, `-`
    and `_` dropped, raw text with no exemption for code or quotes;
  - `roomSendBudget`: a ping goes to `ROOM_PING`; a broadcast body to the narrower `ROOM_BROADCAST_MENTION` (the 429
    doesn't say which policy refused; narrower never over-gates); anything else to `ROOM_SEND`;
  - a broadcast send is also gated by a known `ROOM_SEND` wait, checked first (`authorized(…, alsoGatedBy)`); the local
    refusal names that budget (`ApiFailure.gatedBudget`), so the send line takes precedence (PL);
  - a plain message during the broadcast wait goes to the server as usual; the draft is kept and the refusal definite.
- **The line (PL copy):** "You've used @room or @here a lot recently. You can send without it now, or try again in
  {wait}. Your message is kept." (unknown: "…or try again later. Your message is kept."); short form "Send without @room
  or @here, or try again in {wait}." / "…or try again later.". The send line's placement, carrier, parity, forms and
  announcement counts.
  - It shows only after a refused send for that budget, and ends silently once the draft no longer has a counted
    mention; typing one back doesn't bring it back, the next refused tap does (one announcement).
  - A known wait also ends at its deadline; an unknown one on the next edit or on leaving, as for send.
- **Captures:** `rate-broadcast-*` (light and dark × default and 200%, the unknown variant at light default), collected
  into `rate-presentation`.
- **An engine fix in #323b1's `RateWaitSlot` (PL's J on 734417c, TL):** hidden was decided by the timeline-versus-short
  comparison alone, so a realistic two-line draft at 200% portrait with the keyboard down showed only the Details dot.
  Hidden now needs `hiddenAllowed`, the keyboard up (TL and PL; compact alone was too narrow: 200% portrait with a reply
  chip and the keyboard up leaves the timeline nothing, as in #348). With the keyboard down the line is full (with a 48dp
  timeline row) or short (the timeline absorbs it). In the packed keyboard-up state a refusal resolves to hidden, the app
  hides the keyboard once, and the line returns unclipped with the composer at its baseline. "Never hidden with the
  keyboard down" is asserted at every check, with a two-line send-set case.
- **A drawn line is always whole (PL's re-J on 325af51, TL):** with the keyboard down, where even the short form doesn't
  fit whole (3 lines at 200% with a reply chip), the bounded header yields exactly the shortfall first: composer, then
  line, then header, then timeline. The shortfall is measured before any yield and latched per notice and window, so it
  can't oscillate; the header keeps scrolling within its smaller cap and is back to normal once the line ends or a window
  fits. No motion or announcement of its own.
  - **The one keyboard-down exception:** if even the header at its floor (`RateHeaderYieldFloor`: its 48dp More band
    plus a 48dp viewport; compact has no header) can't make room, the line goes hidden with the Details dot and the full
    line first in Details, never clipped or ellipsized. It's reached only after full, short and the header's yield; the
    carrier still announces once. The fixtures assert it's never reached.
  - **Asserts:** the line's drawn height equals its laid-out text height (`didOverflowHeight` from its own text layout)
    wherever it's drawn; `unclipped` compares the ancestor-clipped `boundsInWindow` with the full size.

## #323b3b: search, people search and @-completion; the final unmapped list (TL plan, PL ruling, 2026-11-25)

| Budget | Server policy | Consumed at (b2791a0), before any work | Client call site |
| --- | --- | --- | --- |
| `MESSAGE_SEARCH` | `message_search` | `search/messages.php:23–24` | message search |
| `DIRECT_MESSAGE_USER_SEARCH` | `direct_message_user_search` | `direct-messages/users.php:21–22` | DM people search |
| `ROOM_MENTIONABLE_USER_SEARCH` | `room_mentionable_user_search` | `rooms/mentionable-users.php:21–22` | room @-completion |

- **Search and people search (PL):** "You've searched a lot recently. Try again in {wait}." (unknown: "…Try again
  later.") replaces the old rate line in place, on the persistent carrier (polite live region, one trailing space
  flipped per refusal). The query and results stay, nothing is sent on its own, and message search's Retry stays an
  explicit press (refused locally during a known wait). A known wait ends at its deadline, an unknown one at the next
  edit; a successful search ends either. A proxy's 429 keeps the old error.
- **@-completion (PL ruling b):** silence. No line, no announcement, no status notice, the draft untouched; an open
  strip closes on the refusal, and completion stays quiet (not even the reserved names open) until a lookup succeeds,
  each refused locally with no request during a known wait.
- **Captures:** `rate-search-messages-light-default` and `rate-search-people-light-default` (PL list 9–10), collected
  into `rate-presentation`.

**The final unmapped list (condition 5).** After #323b3b, only proxy (non-JSON), bootstrap and revalidation 429s reach
row 7 and the session-wide wait:
- `push/subscribe.php`: `push_subscription_refresh` or `push_subscription_management`, two policies (#323a), unchanged
  by design.
- The anonymous auth flows (`register.php`, password reset, MFA login, TOTP, account restore, native auth): outside the
  session, already typed on their own forms (AuthScreen's `rate_limited`, ResetPasswordForm's wait line), never row 7.
- `guest_room_send`: guests are M7.
- No client exists for `sessions_management`, MFA and TOTP management, moderation and admin, `application_icon_update`,
  `username_change` or `policy_preview`.
