# Copy deck

Status: audit of every user-facing string in `app/src/main/res/values/` (556 strings across
`strings.xml`, `conversation.xml`, `direct_strings.xml`, `rooms.xml` and
`room_composer_strings.xml`) against [communication.md](communication.md)'s voice and status
ladder. Requested by the technical lead (issue #1). **Docs only**: Codex applies each group inside
the slice that already touches those screens. Strings not listed are fine as they are.

## How to use this deck

- Each slice section lists `resource name`, the current text, the proposed text, and notes.
  `{param}` marks a new format argument, which needs a small code change in the same slice.
- **Tests:** the "Tests asserting the exact text" column lists androidTest and test files that
  contain the current text as a literal. Those assertions must be updated in the same PR.
  Prefer switching them to `R.string` lookups or test tags, so future copy edits don't break
  journeys. Very short labels ("Private", "Message", "Refresh", "Account") may also match
  unrelated literals; check before editing.
- **Contract strings** (duplicate-risk consent, non-E2EE, retention and inspection disclosures,
  step-up, recovery, no-replay statements) are **not** in the slice tables. They are in the
  flagged section at the end and need the technical lead's sign-off first.
- **Development disclosures** (`preview_limit`, `auth_preview_limit`, "not in this build"
  lines) stay as they are until the feature ships. `AGENTS.md` requires availability
  disclosures to stay accurate. Each slice updates its own.

Voice reminders (communication.md):
- short, warm, plain;
- "you" over passive voice;
- no "please" and no exclamation marks in errors;
- installation name over "the server" where code can supply it. The name is
  `application.name` from the session (ChitChat#149), else the host; `{host}` in this deck
  means that display name. Where the real address is required (the operator note in
  server-update.md, invite links), specs use `{address}`, which is always the raw host;
- no internal terms (session events, canonical, reconcile, window, acknowledgement, ASCII).

## Onboarding slice (onboarding-restyle.md)

The onboarding spec's copy table already covers `welcome_title`, `welcome_body`, `server_hint`,
`save_server`, `invalid_server`, `https_required`, `server_origin_only`, `tls_error`,
`incompatible_server`, `server_unavailable`, `connected_to_server`, `create_account_title`,
`preparing_registration` and `registration_disabled`. Apply those as written there.

| Resource | Current | Proposed | Tests asserting the exact text |
|---|---|---|---|
| `checking_session` | Checking your server and session… | Looking for {host}… | — |
| `session_expired` | This session expired or changed. Sign in again. | You've been signed out. Sign in again. | — |
| `session_revoked` | (new) | You were signed out from another device. Sign in again. (settled, technical lead 2026-11-11; `401 session_revoked` or `forced_logout` `session_revoked`, account-security.md "Sessions") | — |
| `rate_limited` | Too many attempts. Please wait before trying again. | Too many attempts. Try again in a little while. (unknown delay) / Too many attempts. Try again in {wait}. (ChitChat#157, status-presentation "Waiting times") | — |
| `request_rejected` | The server rejected this request. Check your account or contact the server administrator. | Your server refused this. If it keeps happening, ask the person who runs it. | — |
| `redirect_error` | The server redirected this API request. Check the server address; credentials were not forwarded. | The server tried to send the app somewhere else. Check the address. Your password wasn't sent. | — |
| `mfa_setup_required` | This server requires MFA setup in the browser before this account can sign in. | This server needs you to set up two-step sign-in on its website first. | — |
| `providers_unavailable` | External-provider sign-in needs a native authentication handoff that this server does not yet support. | Signing in with another service isn't available in the app yet. Use your username and password. | — |
| `maintenance_notice` | This server is in maintenance lockdown. Sign-in may be restricted. | {installation} is in maintenance. Signing in may not work right now. | — |
| `registration_policy` | An account is created on this server, not with the app publisher. The server decides your permissions. | Your account is created on {host}, not with the app maker. The server decides what you can do. | — |
| `username_change_required` | (new) | Choose a new username to send messages. (banner; play-readiness.md "Required username change", dependency ChitChat#164) | — |
| `username_taken` | (new) | That username isn't available. Try a different one. (also reserved and placeholder-pattern names; never says why) | — |
| `username_change_not_required` | (new) | You don't need to choose a new username any more. | — |
| `username_policy` | Use 3–32 ASCII letters, numbers, dots, underscores or hyphens, starting with a letter or number. | 3–32 characters: letters (a–z), numbers, dots, underscores or hyphens. Start with a letter or number. | — |
| `password_policy` | Use 12–4096 characters without your username or control characters. The server checks the final password policy. | At least 12 characters, and not your username. Your server may have more rules. | — |
| `birth_date_explanation` | Birth date is optional. The server uses it for age-restricted rooms. | Optional. Only used for age-restricted rooms. | — |
| `registration_ready` | Anti-bot check ready. | Ready | AuthJourneyTest |
| `refresh_registration_check` | Refresh anti-bot check | Try again | AuthJourneyTest |
| `registration_check_failed` | The anti-bot check could not be completed. Refresh it before trying again. | That check didn't finish. Try again. | — |
| `registration_too_fast` | The server requires more time to fill in this form. Refresh the anti-bot check, wait a moment, then submit again. | That was a little fast for the server. Try the check again, wait a moment, then create your account. | — |
| `saved_server_invalid` | The saved server address was invalid and was not used. Enter your server again. | The saved server address couldn't be used. Enter your server again. | — |
| `settings_error` | Could not read or save your settings. Please try again. | Couldn't load or save your settings. Try again. | — |
| `forget_body` | Remove the saved server address from this device. | This removes the server address from this phone. Your account isn't affected. | — |
| `sign_out_to_change_server` | Sign out or cancel sign-in before changing servers. Resolve any session-clearing warning first. | Sign out before switching servers. If a sign-out warning is showing, deal with it first. | — |
| `session_not_available` | Session not available | You're not signed in right now | — |
| `signed_out` | Signed out on this device | You're signed out on this phone | — |
| `clear_local_session` | Clear local session | Forget this sign-in | — |
| `session_storage_warning` | Secure saving could not be confirmed. You may need to sign in again after closing the app. | Your sign-in couldn't be saved securely. You may need to sign in again after closing the app. | — |
| `session_clear_warning` | Saved-session removal could not be confirmed. Retry clearing it before leaving this device. | Your sign-in couldn't be fully removed from this phone. Try again before you leave this device. | — |
| `retry_secure_storage` | Retry secure storage | Try again | — |
| `secure_storage_unavailable` | Secure session storage is unavailable. Reconnect to discard the saved session and try again. | This phone couldn't keep your sign-in safely. Reconnect to clear it, then sign in again. | — |
| `logout_unconfirmed` | Server sign-out could not be confirmed. The remote session may remain until it expires; this device has discarded it. | Your server didn't confirm the sign-out. This phone has forgotten the session; the server ends it when it expires. | — |
| `guest_detected` | This is a guest session. Guest features are not included in this slice; ending the visit is permanent. | (development disclosure; rewrite when guests ship in M7) | — |

## Navigation shell slice (navigation-shell.md: You hub, Activity tab)

| Resource | Current | Proposed | Tests asserting the exact text |
|---|---|---|---|
| `notifications_title` | Notifications | Activity (tab and screen title; keep "Notifications" for the push settings row) | — |
| `privacy_title` | Private by choice | Privacy | — |
| `session_retention` | Your session is encrypted on this device. Sign in again after seven inactive days or thirty days from sign-in. Your server may expire it earlier. | Your sign-in is stored encrypted on this phone. You'll need to sign in again after 7 days without use, or 30 days after signing in. Your server may end it sooner. | — |
| `typing_preference_scope` | (long account-wide explanation) | Applies on all your devices, in rooms and direct messages. When it's off, nobody sees when you type, and you don't see when others type. | — (keep the bilateral meaning exactly) |
| `typing_native_scope` | Native typing indicators work only while you view an eligible foreground conversation. Only your identity is signalled, never the text you type. | Typing shows only while a conversation is open on screen. Others see that you're typing, never what. | — |
| `room_drafts_title` | Saved room drafts | Drafts | RoomDraftJourneyTest |
| `room_drafts_count` | Saved room drafts (%1$d) | %1$d drafts waiting · Manage | — |
| `room_drafts_scope` | (long memory-scope text) | Drafts stay in this app's memory only. Signing out or closing the app clears them. Discarding a draft doesn't unsend anything. | — |

## Transcript slice (transcript-restyle.md)

| Resource | Current | Proposed | Tests asserting the exact text |
|---|---|---|---|
| `conversation_ignored` | Ignored message · Reveal temporarily | Message from someone you ignore · Show | ConversationJourneyTest |
| `conversation_unread` | Unread when you opened this room | New | — |
| `dm_new_divider` | Unread when first loaded | New | — |
| `conversation_jump_new` | New messages available — jump to latest | New messages ↓ | — |
| `conversation_empty` | No messages in this room yet. | It's quiet in here. | — |
| `dm_empty` | No retained messages with this person. | Say hello to {name}. | — |
| `conversation_message` / `dm_message` | Message | Message {room} / Message {name} (placeholder) | CompletionJourneyTest, ConversationJourneyTest, DirectJourneyTest, NativeInputDialogJourneyTest (short literal: verify each) |
| `conversation_cancelled` | Request cancelled. Your unsent draft is preserved. | Cancelled. Your text is still in the box. | — |
| `conversation_muted` | Posting muted: %1$s. Until: %2$s. Refresh to check changes. | You can't post here right now: %1$s. Until %2$s. | — |
| `conversation_membership` | Join this room from its room details before posting. | Join this room to post. | — |
| `conversation_guest_readonly` | Guests may read this room but cannot post. | Guests can read this room but not post. | — |
| `conversation_unavailable` | This room or its history is unavailable, or your permission changed. Refresh to check access. | This room isn't available to you right now. Refresh to check. | — |
| `conversation_read_failed` | Read acknowledgement could not be confirmed. Retry it or refresh; no message will be resent. | Couldn't mark this room as read. Try again; nothing will be resent. | — |
| `conversation_retry_read` | Retry read acknowledgement | Mark as read again | — |
| `conversation_anchor_unavailable` | Your previous position was removed from retained history. Showing the nearest available message. | That message is no longer kept. Showing the closest one. | — |
| `conversation_window` | Showing a bounded contiguous history window. Newer messages were removed while loading older history; jump to latest to return. | Showing an earlier part of the conversation. Jump to latest to come back. | — |
| `conversation_latest_reset` | Your previous position is outside the latest fetched page. The old range was replaced… | Showing the latest messages. Your earlier spot is no longer loaded. | — |
| `conversation_range_unavailable` | This history range could not be synchronized within the app's bounded window… | Couldn't load this part of the conversation. Your place and draft are kept. Refresh, or jump to latest. | — |
| `conversation_empty_range` / `dm_empty_range` | No messages remain in this range. Jump to latest to check for newer messages. | Nothing left here. Jump to latest for newer messages. | — |
| `conversation_pings_private` | Only pings you sent or received are shown. Pings are private attention signals, not room messages. Ignored senders are hidden. | Only pings you sent or received appear here. Pings are private nudges, not room messages. People you ignore are hidden. | — |
| `message_copy_action` | Copy message | Copy text | — |
| `message_actions_unavailable` | This message changed or is no longer available in this view. Close this menu and select it again. | This message changed. Close and select it again. | — |
| `composer_reply_invalid` | The original is no longer available for this reply. Refresh before retrying. Choose another message or remove the reply; your text is kept. | The original message is gone. Choose another or remove the reply; your text is kept. | — |
| `author_temporarily_unavailable` | This action is currently unavailable. Wait for the current operation or refresh the session before trying again. | Not available right now. Wait a moment, or refresh. | — |
| `completion_failed` | Could not load suggestions. Your draft is safe. Refresh suggestions to try again. | Couldn't load names. Your draft is safe. (J-review note on #97) | — |
| `completion_retry` | Refresh suggestions | Try again | — |
| `conversation_attachment` | Attachment: … Downloads arrive in a later milestone. | Replaced by the file card in the files slice (files-media.md) | — |

## Lists slice (lists-restyle.md)

| Resource | Current | Proposed | Tests asserting the exact text |
|---|---|---|---|
| `rooms_joined` / `rooms_invited` / `rooms_discoverable` | Joined / Invited / Available to discover | Section labels: Your rooms / Invitations / Open rooms (row labels go away) | RoomsJourneyTest (`rooms_invited`) |
| `rooms_refresh` | Refresh rooms | Refresh | — |
| `rooms_reconcile` | Check current room state | Check room list | RoomsJourneyTest |
| `rooms_stale` | Room information may be out of date. Refresh before changing membership or creating a room. | The room list may be out of date. Refresh to update it. | — |
| `rooms_empty` | No rooms are available to this account. | No rooms yet. When someone opens a room on this server, it shows up here. | AuthJourneyTest |
| `rooms_lookup_id` | Room ID | Room number | LiveSessionTest |
| `rooms_lookup_hint` | Have an unlisted room link? Enter its numeric room ID. Knowing an ID does not grant private-room access. | Have a link to a hidden room? Enter its room number. A number alone doesn't open private rooms. | — |
| `rooms_lookup` | Look up room | Open room | — |
| `rooms_network` | Room information could not be confirmed. Check your connection, then refresh. Existing details may be out of date. | Couldn't update the room list. Check your connection, then refresh. | — |
| `rooms_key_help` / `rooms_invalid_key` | …ASCII letters… | 3–48 characters: letters, numbers, underscores or hyphens. Start with a letter or number. | — |
| `dm_search_help` | Enter 2–32 username characters: letters, numbers, underscore, dot or hyphen. Results follow the server's prefix search. | Type at least 2 letters of a username. | — |
| `dm_inbox_empty` | No conversations yet. Find someone to start a conversation. | No conversations yet. Tap New message to start one. | — |
| `dm_inbox_cap` | The server lists at most 100 recent conversations. Find a person to open an older conversation. | Showing your 100 most recent conversations. Use New message to find older ones. | — |
| `dm_stale` | History or counts may be out of date. Refresh before sending. | This may be out of date. Refresh before sending. | — |
| `dm_drafts` | Saved drafts on this device session | Drafts | — |
| `dm_local_attention` | %1$d new here (local attention) | %1$d new since you opened it | — |
| `notifications_empty` | No notifications. | Nothing here yet. Mentions and pings will show up here. | — |
| `notifications_more` | Older notifications | Load older | — |
| `notifications_stale` | These notifications may be out of date. Refresh before changing read state. | This may be out of date. Refresh before marking anything read. | — |
| `notifications_historical` | Showing an older window. Refresh keeps this range; Latest returns to new notifications. | Showing older activity. Latest brings you back to the newest. | — |
| `message_search_query` | Search query | Search messages | — |
| `message_search_limit` / `message_search_validation` | Use 2–200 characters after whitespace normalization, including a letter or number… | Use 2–200 characters, including a letter or number. | — |
| `message_search_rate` | Too many requests. Please wait, then choose Search or Retry. | Too many searches. Wait a moment, then try again. (unknown delay) / Too many searches. Try again in {wait}. (ChitChat#157) | — |
| `message_search_incompatible` | The server returned unavailable or incompatible search or privacy information. Refresh the session before trying again. | Search isn't available right now. Refresh, then try again. | — |
| `message_search_empty` | No visible messages matched this search. | Nothing found for "{query}". | — |

## Status slice (status-presentation.md, room-presence.md)

The status spec's copy table covers all `realtime_*` strings. Its tests (`RealtimeStatusTest`)
assert `realtime_connecting`, `realtime_live`, `realtime_offline`, `realtime_tls_error`,
`realtime_global_notice`, `realtime_view_notice` and `realtime_dismiss_notice` by text, and must
change with them.

| Resource | Current | Proposed | Tests asserting the exact text |
|---|---|---|---|
| `conversation_realtime` | Updates while the app is active. Refresh reconciles with the server. | (remove: nothing shows when live) | — |
| `conversation_syncing` / `rooms_syncing` | Updating conversation… / Updating rooms… | Updating… | — |
| `presence_warning` | Room presence will end soon due to inactivity. | You'll leave the room in {m:ss} because it's been quiet a while. | — |
| `presence_remaining` | About %1$d seconds remaining. | (merged into `presence_warning`) | — |
| `presence_expired` | Active room presence ended due to inactivity. Your draft and loaded history were kept. | You left the room after a quiet while. Your draft and the messages here are kept. | — |
| `presence_inactive` | Room activity is paused. Enter the room to appear online and send messages. | You're not in the room yet. | — |
| `presence_reading_only` | Reading without active room presence. | You're reading this room. | — |
| `presence_failed` | Active room presence is unavailable. Refresh permissions or enter the room again. | Couldn't join the room. Try again. | — |
| `presence_entering` | Entering room… | Joining… | — |
| `presence_enter` | Enter room | Join | — |
| `presence_stay` | Stay in room | Stay | ConversationRealtimeJourneyTest |
| `presence_list_stale` | The online list is updating or unavailable. | Who's here is updating… | ConversationRealtimeJourneyTest |
| `presence_people_title` | People online in this room | Here now | — |
| `presence_people` | %1$d people online | %1$d here | — |
| `presence_nobody` | No active people were returned. | Nobody else is here right now. | — |
| `presence_guest` | %1$s · guest | (Guest pill, room-presence.md) | — |
| `rooms_inactivity_value` | Active presence expires after %1$d inactive seconds | People leave "here now" after %1$d seconds of quiet | — |

## Modals slice (modals-restyle.md, room-presence.md)

| Resource | Current | Proposed | Tests asserting the exact text |
|---|---|---|---|
| `rooms_leave_title` | Leave this room? | Leave {room}? | RoomsJourneyTest |
| `rooms_owner_immutable` | Room ownership cannot be transferred here. The owner cannot leave the room. | Owners can't leave their room. | RoomsJourneyTest |
| `rooms_public_help` | Public rooms are discoverable. Joining is required to post and receive room realtime events. | Open to everyone on this server. Join to post. | — |
| `rooms_unlisted_help` | Unlisted rooms are hidden from ordinary discovery until joined or invited… | Hidden from the room list. Anyone with the link can see it and join. | — |
| `rooms_private_help` | Private rooms require membership or privileged moderation access to read history… | Private, by invitation. Invited people can see its details and join. | — |
| `profile_read_only` | Basic profile details. Profile pictures and account actions are not available here yet. | (development disclosure; keep until profile pictures ship, then remove) | — |

## Contract strings: flagged for the technical lead (no change without sign-off)

**Sign-off (technical lead, 2026-11-08, on PR #111):** most rows are approved. Two conditions apply:
- **The "Send again" resend dialog** appears only after a completed history GET, never directly.
- **The edited-retention sentence** stays reachable (message details beside `author_retention`),
  and the tests that assert it move with it.



These carry contract meaning. The proposals keep the meaning and only change voice. Each needs
technical-lead sign-off, and the affected tests are listed.

| Resource | Current (abridged) | Proposed | Why flagged | Tests |
|---|---|---|---|---|
| `conversation_uncertain` | The send may have succeeded. Your draft is preserved, but sending stays blocked… | Not sure your last message arrived. Your text is kept. Check history before sending again. | Unknown-send contract | — |
| `conversation_allow_resend` / `dm_allow_duplicate` | Allow another send despite duplicate risk | Send again (inside the dialog "Send again? We checked and couldn't find it. Sending again may post it twice.") | Duplicate-risk consent | ConversationJourneyTest, DirectJourneyTest |
| `conversation_duplicate_choice` | You accepted possible duplication. Nothing was resent automatically. | Okay. Nothing was resent; press Send when you're ready. | Consent outcome | — |
| `dm_unknown` | Delivery is unknown. Check the latest history before deciding whether to send again; identical text is not proof… | Not sure your last message arrived. Check history before sending again. Matching text isn't proof either way. | Unknown-send contract | — |
| `room_draft_unknown` | Delivery was not confirmed. Check the room history explicitly before choosing another send. | Not sure this draft's last send arrived. Check the room history before sending again. | Unknown-send contract | — |
| `conversation_network` / `dm_network` | The request could not be confirmed… No send is retried automatically. | Couldn't confirm with the server. Nothing was resent. Check your connection, then refresh. | No-replay statement | — |
| `conversation_stale` | …it does not replay an interrupted send. | This may be out of date. Refresh to update it; refreshing never resends anything. | No-replay statement | — |
| `conversation_edited` | Edited · previous versions may be retained by the server | Bubble caption "edited"; the retention sentence moves into message details next to `author_retention` | Retention disclosure placement | ConversationJourneyTest |
| `author_unconfirmed` / `reaction_unconfirmed` / `rooms_unconfirmed` / `notifications_unknown_read` / `typing_unconfirmed` / `ignore_unconfirmed` | (various "may have succeeded… will not be retried") | Pattern: "We're not sure your {change} went through. Check before choosing again; nothing is retried by itself." | Unknown-write contracts | TypingPreferenceJourneyTest (`typing_unconfirmed`) |
| `typing_preference_unknown` | …unknown does not mean enabled. | We couldn't confirm this setting. Until we can, typing isn't treated as on. Refresh, or choose on or off. | Unknown is not consent | — |
| `notifications_read_scope` / `notifications_all_body` | Reading a notification is separate from reading a chat… | Reading an item here doesn't mark the chat as read. / This also marks activity that isn't loaded yet or arrives meanwhile. It doesn't mark chats as read. | Read-scope disclosures | — |
| `dm_read_policy` / `dm_read_failed` / `dm_additional_read` | (whole-peer read explanations) | Keep meaning; plain wording proposal in a later pass with the technical lead | Whole-peer read contract | — |
| `privacy_body`, `dm_privacy_short`, `dm_inspection`, `dm_no_inspection`, `dm_revision_review`, `dm_retained_edits`, `author_retention` | Non-E2EE, retention and inspection disclosures | **No change proposed**: wording matches the approved privacy policy | Disclosure contract | — |
| `step_up_*`, `mfa_recovery_explanation`, `recovery_*`, `step_up_recovery_warning`, `registration_unknown`, `export_privacy`, `export_partial_warning` | Step-up, recovery and export disclosures | **No change proposed** | Security contracts | — |

## Not changed, and why

- Single words and labels that already fit: Sign in, Username, Password, Back, Cancel, Send,
  Reply, Block, Unblock, Join room, Leave room, Create room, Mark as read, Mark all as read.
  Most are also asserted by text in tests.
- Development disclosures (`preview_limit`, `auth_preview_limit`, `push_body`, `dm_no_push`,
  `conversation_commands`, `rooms_create_ownership`, `conversation_manual`): updated by the slice
  that ships the feature. `conversation_manual` and `notifications_link_pending` are still
  referenced in code; revisit their copy when the status and navigation slices touch those
  screens.
