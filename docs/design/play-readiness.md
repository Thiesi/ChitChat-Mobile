# Play readiness: terms, account deletion and privacy surfaces

Status: presentation spec for the Google Play requirements the technical lead listed (issue #1,
comments 6713519327 and 6713603503; the maintainer confirmed Play distribution on
2026-11-07). It designs the user-facing flows. **The maintainer approved the guidelines text
(§1), the deletion web resource text (§3) and the data safety facts (§4) as written on
2026-11-07 (recorded on PR #108).** The app privacy policy is drafted separately. Mapping the
facts onto the form's answers stays the maintainer's decision. Application ID, signing and listing remain maintainer decisions. Mockups:
`Guidelines`, `GuidelinesServer`, `RulesUpdated`, `PolicyDocument` and `DeleteAccount` on the canvas.

CCM is a client for self-hosted ChitChat servers. The app maker runs no server and receives
no account or message data; each installation is run by its own operator. Every screen here
keeps that distinction clear.

## 1. Community guidelines at onboarding

Play's user-generated-content policy expects people to accept terms that define
objectionable content before they create content. CCM ships **generic app-level guidelines**
on every server. Installations that publish their own rules (ChitChat#160, merged) add them to
the same screen, below.

### Today (no backend dependency)

- **When:** once per account on each device, right after a successful registration or
  sign-in, and before the person can write anything. That means before onboarding step 5
  (Find your people) for new accounts, or before the first screen of the signed-in shell for
  returning ones. Existing signed-in users see it once after the update that introduces it.
- **Screen** (full screen, no bottom bar; mockup `Guidelines`):
  - title "Before you chat";
  - a short list of what isn't allowed, plus how to report and block (draft text below);
  - a line about servers: "{installation} is run by its own operators, who may have
    additional rules and moderate it. The ChitChat app maker doesn't run it.";
  - a link to the app's privacy policy (see section 4);
  - **I agree** (primary, persimmon-filled; the screen's single primary action) and
    **Sign out** (text button).
- **Acceptance** is stored locally with the app guidelines' version, scoped by server and
  account, like other local settings. A new guidelines version shows the screen again. Signing
  out keeps the record, because the person already agreed on this device.
- Nobody can write before acceptance: the screen sits between sign-in and the shell. Back
  leaves the app; it doesn't skip the screen.

**Guidelines text** (approved by the maintainer, 2026-11-07):

> **Be kind, be real, be safe.** Don't harass, threaten or bully anyone. No hate based on
> who people are. No sexual content involving minors, ever. No spam, scams or malware. Don't
> share other people's private information. Don't pretend to be someone else. Nothing
> illegal.
>
> You can report any message from its menu, and ignore people in rooms or block them in direct
> messages. Reports go to the people who moderate this server.

### Server rules and privacy (ChitChat#160, merged)

ChitChat#160 merged in ChitChat main `acaaabe` (contract in issue #1, comment 6727010633;
ChitChat `docs/api/policies.md`). The web UI follows separately. Guest acceptance comes with
M7 and is out of scope here.

**What the server tells the client**

- The public session carries `policies: {rules, privacy, legal_notice}`. Each is an object or
  `null`. `rules` has `{url, version, updated_at}`, `privacy` has `{url, updated_at}` and
  `legal_notice` has `{url}`.
- The account's session (and login and register) carries `user.rules: {accepted_version,
  accepted_at, acceptance_required}`. Versions are positive integers that only increase.
- **Feature check:** the presence of `policies` in `session.php`. When it's missing, or all
  three entries are `null`, nothing is gated and the screens below show only the app's own
  parts. A missing `policies` is **not** a server-update trigger (server-update.md decides
  that from `capabilities.schema_version` alone).
- **`rules: null`:** only the app guidelines apply, exactly as in "Today" above.

**Guidelines screen with server rules** (mockup `GuidelinesServer`)

When `policies.rules` is present, the guidelines screen gains a section below the app
guidelines. Its rows are always shown. The acceptance sentence and the accept request apply only
while `user.rules.acceptance_required` is true (the last bullet covers the other case):

- Section label "**{installation}'s rules**" (Label style), then a row in a `raised` card: the
  `document` icon, "Read {installation}'s rules", the caption "Updated {date}" from
  `rules.updated_at` (device date format), and a chevron. It opens the document screen below.
- When `policies.privacy` is present, a second row "**{installation}'s privacy policy**"
  opens the server's privacy document. The app's own "Privacy policy" link stays and is
  renamed "**The app's privacy policy**" whenever a server privacy row is shown, so the two
  are never confused.
- The operator line stays ("{installation} is run by its own operators …"), and the "may have
  additional rules" clause becomes "and set the rules above" when server rules are shown.
- Above the buttons, in Body and `textMuted`: "**By tapping I agree, you accept these
  guidelines and {installation}'s rules.**" One **I agree** covers both. Reading the server's
  document is offered, never forced: the button doesn't wait for the person to open it.
- **I agree** records the app guidelines' local acceptance (as today) and sends `POST
  policies/accept.php {version}` with the `rules.version` that was shown. It shows busy in
  place ("Saving…") and the screen stays until the server confirms. Then the person moves on.
  - **`409 rules_version_outdated`:** the rules changed while the screen was open. Re-read
    the session, then show the re-acceptance screen for the new version with the level-1
    line "{installation} updated its rules just now. Please look again." Never accept the new
    version automatically.
  - **Unknown result** (timeout or connection lost after sending): a session GET checks
    `acceptance_required`. If it's false, move on. If it's still true, the screen stays with
    **I agree** enabled again. Never resend on its own.
  - **Other failures:** the existing status ladder (offline, server problem). The screen stays.
- When server rules exist but `acceptance_required` is false (for example, accepted on another
  device or at sign-up), the screen shows the rows as reading links only, with no acceptance
  sentence about the server, and **I agree** covers the app guidelines alone.

**At sign-up** (onboarding step 4b)

- When `policies.rules` is present, the Create account form shows a line above **Create my
  account**: "By creating an account, you agree to **{installation}'s rules**." The linked
  words open the document screen. With `policies.privacy`, the line continues "and
  acknowledge its **privacy policy**."
- Registration (and OIDC registration) sends `accepted_rules_version` with the version from
  the session the form rendered with.
  - `409 rules_version_outdated`: the account wasn't created. Re-read the session and show the
    level-2 notice "{installation} updated its rules while you were signing up. Look at them,
    then create your account again." The form keeps its fields (the password fields follow
    their existing clearing rules). Never resubmit automatically.
  - `400 rules_acceptance_required` means the client sent no version. It's a client bug: show
    the generic server-problem notice, and keep the form.
- After a successful registration, `acceptance_required` is false, so the guidelines screen
  then asks only for the app guidelines and lists the server rows as reading links.

**Re-acceptance** (mockup `RulesUpdated`)

- **When:** `user.rules.acceptance_required` turns true on a signed-in session (cold start,
  foreground, session refresh), or a write is refused with `403 rules_acceptance_required`.
- **Screen** (full screen over the shell, no bottom bar; the same layout as the guidelines
  screen):
  - title "**{installation} updated its rules**";
  - "Updated {date}" caption;
  - when the document's `change_summary` is non-empty: a `raised` card "**What changed**"
    with the summary as **plain text** (no formatting, up to 6 lines, then "Show all");
  - the row "Read {installation}'s rules" to the document screen;
  - "By tapping I agree, you accept {installation}'s updated rules." in `textMuted`;
  - **I agree** (primary) and **Not now** (text button). This replaces the earlier draft's
    Sign out (technical lead decision, 2026-11-08): reading is never gated by the server.
- **I agree** posts the shown version with the same outcomes as above.
- **Not now** returns to where the person was. Reading continues: rooms, DMs, Activity,
  search, files and settings work. A small level-1 banner sits at the top of each conversation,
  "**Agree to {installation}'s updated rules to send messages.**" with **Review**, and the
  composer stays enabled so drafts keep working.
- There is no Sign out on this screen. Signing out stays in You, as always.
- The `change_summary` comes from `GET policies/show.php?document=rules`. If that GET fails,
  the screen shows without the What changed card.

**Write refusals**

- The server refuses new content with **`403 rules_acceptance_required`**: messages, edits,
  adding reactions, pings, uploads and avatar changes. It's a **definite refusal**: nothing was
  committed.
- The draft stays in the composer (text, reply and attached file). An edit dialog keeps its
  text. A refused reaction leaves the chip unchanged. No unknown-send state is set, because the
  outcome is known.
- The re-acceptance screen opens at once.
- After **I agree**, the person returns to the same place with the draft intact. **Nothing
  resends automatically.** The person presses Send (or adds the reaction) again.
- Never gated, and never showing this screen: reads, deleting one's own messages, removing
  reactions, block, ignore, report, devices and push, account, security, export, account
  closure, moderation and admin.

**Document screen** (mockup `PolicyDocument`; full screen, top bar with Back)

- Title: "{installation}'s rules" or "{installation}'s privacy policy". The caption under it
  reads "Version {n} · Updated {date}" for rules and "Updated {date}" for privacy.
- **Hosted documents** (`source: hosted`) render the **`markdown`** field through the bounded
  formatter, extended to the server's display subset, and never as server HTML. No WebView.
  - The subset is CommonMark core. Raw HTML is shown escaped as literal text.
  - Links become tappable only for `http`, `https` and `mailto`. Other schemes stay plain text.
    Tapping a link follows the existing M5d2 explicit link choice ("Open in browser").
  - Images are dropped and never fetched. Their alt text is not shown.
  - Headings at level 2 and below. A level-1 heading renders as level 2, because the screen
    owns the title.
  - Blockquotes are flattened to ordinary paragraphs. Code renders as plain body text.
  - If parsing fails or exceeds the formatter's bounds, the text shows as literal plain text
    (the existing literal fallback), never a blank screen.
  - **Typography:** body copy uses Lantern UI text (system sans, Body style, `text`), never
    Bricolage. Headings use the type roles: level 2 is Title, level 3 and below is Label. Lists
    use the body style with 8dp item spacing. The column is limited to the reading width on
    large screens.
- **External documents** (`source: external`, `html` and `markdown` null): a short screen
  "{installation} publishes this document on its website.", the host of the URL on its own
  line, and **Open in browser** (primary). Only `https` URLs get the button. Anything else
  shows "This document's address isn't secure, so the app won't open it. Ask the person who
  runs the server." and no button.
- **`404 policy_document_not_found`:** "{installation} hasn't published this document yet."
  This isn't an error state.
- Loading and failures use the existing status ladder, with **Retry** (a GET). Nothing is
  cached on disk. The document lives in memory while the screen is open.
- The screen opens from the guidelines and re-acceptance screens, the Create account line,
  onboarding step 3 and About and privacy.

**Legal notice:** when `policies.legal_notice` is present, You → **About and privacy** gets a
row "**{installation}'s legal notice**" with the `open-external` icon. It opens the URL with the
same explicit "Open in browser" handling (`https` only, as above). There is no document screen
for it.

**Privacy-policy updates in Activity:** the server adds a `privacy_policy_updated`
notification kind. It's timeline-only and never pushed. CCM tolerates it as it does
`direct_message_received`: the row shows the server's rendered text with the `document` icon in a
`raised` circle, and tapping it opens the server's privacy document screen. Until that
destination is built, the row shows without a destination, like other unsupported kinds. An
unknown kind is never an error.

### Required username change (ChitChat#164, merged)

**Status: Dependency.** ChitChat#164 is merged (`363fcde`) and ships in the release after
3.7.0. Like every later feature, it ships only once the technical lead raises the minimum server
version to include it (one rule, server-update.md), with no capability check. Until then,
implementers leave it out. The contract is `docs/api/username-change.md` on ChitChat main.

**What the server does.** A Global Moderator or above can require a new username when a name
breaks the community rules. The server replaces the name at once with a placeholder such as
`member-k7q2xm` and flags the account. The session then carries `user.username` (the
placeholder) and `user.username_change: {required, required_at, reason,
sign_in_with_previous_username}`, or `null` when nothing is required. The account keeps reading
and signing in, but new content is refused until a name is chosen
(`POST account/username.php {username}`).

**Choose a new username** (full screen over the shell, no bottom bar; the same layout as
re-acceptance)

- Title "**Choose a new username**".
- A `raised` card labelled "**From {installation}'s moderators**", holding the `reason`:
  - **plain text** only, never markup or links; newlines are kept;
  - shown in full, with no "Show all": the server caps it at 500 characters. The screen scrolls,
    and Save stays reachable at 2.0 font on a 360×640dp screen;
  - when `reason` is missing, `null` or empty after trimming (defensive; the server always
    sends text), the card reads "{installation}'s moderators asked you to choose a different
    username." It doesn't claim the name was reported, because the app can't know that.
- Body: "For now, your username is **{placeholder}**. Choose a new one to keep writing. You can
  still read everything." `{placeholder}` is the session's `user.username`.
- When `sign_in_with_previous_username` is true, one more line in `textMuted`: "Until you
  choose, you can still sign in with your previous username." The previous name itself is
  **never shown**: the session doesn't carry it, and CCM never keeps it from earlier state.
- One field, "**New username**", with the supporting text from the copy deck's
  `username_policy` ("3–32 characters: letters (a–z), numbers, dots, underscores or hyphens.
  Start with a letter or number."). Single line, no autocorrect, no automatic capitals and no
  suggestions; the keyboard's Done key saves. The value is sent exactly as typed.
- **Save** (primary) and **Not now** (text button). Save is disabled while the field is empty.
  Back is Not now.
- **Local format check:** before any request, the field is checked against the documented
  username rules. A miss shows the invalid line below and makes no request, so the rate budget
  isn't spent on a typo. The server stays the authority for everything else.

**Save outcomes** (Save shows busy in place; the field keeps its text)

- **`200`:** the screen closes, and the person returns to where they were with every draft
  intact. A level-1 confirmation reads "Your username is now **{name}**." from the returned
  `user.username`. The app adopts the returned user as its usual session update. Nothing that
  was refused earlier resends.
- **`400 invalid_username`:** the field shows the line "Use 3–32 letters (a–z), numbers, dots,
  underscores or hyphens, starting with a letter or number." with the `error` glyph.
- **`409 username_taken`:** "**That username isn't available.** Try a different one." The same
  copy covers a name in use, a reserved name (including the person's own previous name), and
  anything in the placeholder pattern. It never says why, because the server deliberately
  hides moderation history.
- **`429 rate_limited`:** the copy deck's `rate_limited` line ("Too many attempts. Try again in
  {wait}.", or "in a little while" without a delay). Save stays disabled until a known wait has
  passed.
- **`409 username_change_not_required`** (for example, a name was chosen on another device):
  re-read the session, close the screen and show "You don't need to choose a new username any
  more." This isn't an error state.
- **`401`:** the ordinary session-ended flow. `403 guest_not_allowed` and `400
  validation_error` aren't reachable from this screen; if one arrives, show the generic
  refusal line (`request_rejected`).
- **Unknown** (timeout or connection lost after sending): "**Not sure your new username was
  saved.**" with **Check**, which is one session GET. If no change is required any more, show
  the 200 confirmation with the session's name. If one still is, the field keeps its text and
  the line reads "Your username wasn't changed. Try again." **Save is never resent
  automatically.**

**When the screen opens**

- When `user.username_change.required` turns true on a signed-in session: cold start,
  foreground, session refresh or sign-in. It opens once per change, not on every foreground.
- When a write is refused with `403 username_change_required` (below).
- From the conversation banner, the You row and the notification (below).
- **Rules come first.** When `user.rules.acceptance_required` is also true, the re-acceptance
  screen opens first. After **I agree**, the chooser follows directly from the session, without
  waiting for a second refused write. Only one of the two banners shows at a time, rules first.

**Not now**

- Returns to where the person was. Reading continues, as after re-acceptance's Not now.
- A small level-1 banner sits at the top of each conversation, "**Choose a new username to send
  messages.**" with **Choose**. The composer stays enabled so drafts keep working.
- You: under the name in the account header, a level-1 row "**Choose a new username**" that
  opens the screen. It goes away once a name is chosen.

**Write refusals: `403 username_change_required`**

- Gated, exactly the set of `rules_acceptance_required`: room and DM sends and edits, attachment
  uploads, adding reactions, private pings, and profile picture uploads.
- It's a **definite refusal**, handled like P-175: nothing was committed. The draft stays in the
  composer (text, reply and attached file). An edit dialog keeps its text. A refused reaction
  leaves the chip unchanged. A refused profile picture leaves the current one in place. No
  unknown-send state is set.
- The app re-reads the session (one GET), then the chooser opens. After **Save**, the person
  returns to the same place with the draft intact. **Nothing resends automatically.** The
  person presses Send (or adds the reaction) again.
- Never gated, and never showing the chooser: reading, discovery and search, sign-in and
  sign-out, reporting, block, ignore, removing one's own messages, reactions or profile picture,
  security, device and notification settings, export, closure and restoration.

**Signing in.** The sign-in form doesn't change. While a change is pending, the previous
username and the placeholder both sign in. After sign-in, the chooser opens as above. Passkeys
and the browser handoff don't use the username. Account labels (onboarding, You, sessions)
always show the server's current `user.username`. CCM never shows a name the session has
replaced, including from any locally remembered label.

**Notification `username_change_required`**

- In Activity, the row shows the server's rendered text ("Choose a new username", with the
  reason) as plain text, with the `edit` icon in a `raised` circle. Its link,
  `/choose-username.php`, maps to the chooser through the guarded native-link flow, never to a
  browser.
- Tapping it opens the chooser while a change is required. Otherwise it shows the level-1 line
  "You've already chosen a new username." and stays in Activity.
- When it's pushed, it goes to the `account` channel like other unlisted kinds
  (push-presentation.md). The lock-screen public version stays "New notification", so the
  reason never shows on a locked screen.
- Until the destination is built, the row shows without a destination, like other unsupported
  kinds. An unknown kind is never an error.

**Other people's renames**

- `user_renamed {user: {id, username}}` updates every rendered occurrence of that user ID:
  author names, mention and reply labels, presence and typing, the DM inbox, profile cards and
  the mention suggestions. If it's the viewer's own ID, the app re-reads the session.
- **No visual cue** (presentation lead decision). A "renamed" mark would point at a moderation
  action that the server deliberately keeps private, and the same event also arrives when the
  person picks their new name. Names simply change in place, without animation.
- Accepted limits, as the server documents them: a typing indicator may show the old name for
  its last few seconds; "@OldName" typed inside existing message bodies is people's content and
  stays as written; a replayed older event can briefly show the old name until the later
  `user_renamed` follows.
- **Later (not in this section):** the moderator's side (`can_require_username_change` on the
  profile card, the `username_change_required` resolution code) belongs in moderation.md.

## 2. Deleting the account in the app

Play requires that apps which create accounts let people delete them in the app. ChitChat
calls this **account closure** (`POST account/close.php`). This section presents it under the
words people look for: **"Delete account"**.

### What the server does (docs/api/account.md)

- Requires recent password step-up (existing `withStepUp` flow). The last active
  Super-Administrator can't close their account.
- Immediately:
  - the account becomes `closure_pending`, with a **14-day** cooling-off deadline;
  - all sessions are signed out;
  - global roles are removed;
  - normal sign-in is refused.
- **Restore** (`account/restore.php`, already in the app's onboarding as "Restore a closed
  account") works with username and password until the deadline.
- **After 14 days**, maintenance finalizes the closure:
  - the username becomes "Closed account #{id}" and is released for reuse;
  - the password becomes unusable;
  - birth date, last-login data, pending invitations, DM block preferences and login records
    are cleared.
- **Kept after finalization:** shared room and direct-message history, message revisions,
  attachment evidence, open moderation evidence, membership attribution, bans and audit
  records. They stay under the server's own retention policies and show as "Closed account
  #{id}".

### Flow

1. **Entry:** You → Privacy and security → **Delete account** (`error`-colored text row at the
   bottom of the section).
2. **Explanation screen** (mockup `DeleteAccount`), title "Delete your account on
   {installation}":
   - "This deletes your account **on {installation} only**. Accounts on other servers aren't
     affected."
   - **Right away:** "You're signed out everywhere, and you can't sign in normally."
   - **For 14 days:** "You can change your mind: choose *Restore a closed account* when signing
     in."
   - **After 14 days:** "Your name becomes 'Closed account', your personal details are
     cleared and your username is freed."
   - **What stays:** "Messages and files you sent in rooms and direct messages stay visible to
     the people who received them, shown as 'Closed account', for as long as this server keeps
     them. Moderation and security records are kept under the server's policies."
   - **Export my data first** (existing export flow; a text button).
   - **Continue** (`error` text button) and **Cancel**.
3. **Decision dialog, before any request:** title "Delete your account?"; body "You'll be
   signed out now. You can restore it for 14 days."; buttons **Cancel** and **Delete account**
   (`error`). Only **Delete account** sends `POST account/close.php`.
4. **Step-up, only if the server asks:** the request goes through the existing `withStepUp`
   wrapper. The password prompt (the existing step-up form dialog) appears only when the
   server answers `403 step_up_required`, followed by its one retry. If recent step-up is
   already valid, no prompt appears.
5. **After success:** the person is signed out (the server destroyed the session) and sees a
   confirmation screen: "Your account is scheduled for deletion. You can restore it until
   {date}." Use the server's deadline if the response provides one; otherwise "for 14 days".
   **Done** goes to onboarding step 3 for this server.

**Errors:**
- the last Super-Administrator gets the server's refusal in plain words: "You're the last
  Super-Administrator. Give the role to someone else first.";
- an unknown result after the request was sent reads "We're not sure your request went
  through." with **Check**. Check is the existing **session revalidation GET** (no POST):
  - if the session is no longer authenticated, that is **consistent with** closure but doesn't
    prove it (the session may have expired, or an administrator may have signed it out). So
    don't show the definite confirmation. Show instead: "You've been signed out, which means the
    deletion most likely went through. If it did, you can restore your account until about
    {date} with *Restore a closed account*. If you can still sign in normally, it wasn't
    deleted." Then **Done** goes to onboarding step 3. The definite "scheduled for deletion"
    confirmation (step 5) appears **only** after a confirmed success response;
  - if the session is still authenticated, nothing changed: "Your account wasn't deleted."
    The person may start the flow again explicitly.

  **Never resend automatically.**

## 3. Deletion web resource (content for a CCM page)

Play listings must link to a web page explaining how to delete an account. CCM doesn't host
accounts, so the page explains per-server deletion. **Text approved by the maintainer
(2026-11-07).** Hosting and URL are a later decision. Re-verify every factual line, especially
"What the app maker has", against the shipped app before publishing.

> **Delete your ChitChat account**
>
> ChitChat is an app for self-hosted chat servers. Your account doesn't belong to the app
> maker: it lives on the ChitChat server you signed up with, run by that server's operators.
>
> **In the app:** open ChitChat, go to **You → Privacy and security → Delete account**, and
> follow the steps. This deletes your account on that server only.
>
> **Without the app:** sign in to your server's website and use its account closure option,
> or contact the people who run your server.
>
> **What happens:** you're signed out right away. For 14 days you can restore the account by
> signing in with *Restore a closed account*. After that, your name is replaced with "Closed
> account", your personal details are cleared and your username is freed. Messages and files
> you sent stay visible to the people who received them, under that server's retention
> policies, as do moderation and security records.
>
> **What the app maker has:** nothing to delete. The app talks to the server you chose, and to
> the notification delivery app you choose if you turn notifications on. It has no analytics
> or advertising.

The last paragraph must be re-verified before publishing: no analytics or ads dependencies
exist today, and push delivery goes through the distributor the person chooses.

## 4. Privacy surfaces

Where privacy information appears, so the app and the data safety form say the same thing:

| Surface | Content | Status |
|---|---|---|
| Welcome screen footer | "Privacy" link to the **app's privacy policy** (CCM-owned page, URL a maintainer decision) | New |
| Onboarding step 3 (Server found) | "Your server hosts your conversations. Messages are not end-to-end encrypted." (existing line, onboarding spec) plus, when `policies.privacy` is present, "{installation}'s privacy policy" | Existing; #160 merged |
| Community guidelines screen | The app's privacy policy; when present, the server's rules and privacy policy (§1) | New |
| Create account form | When present, the server's rules and privacy policy in the agreement line (§1) | #160 merged |
| DM inbox header | "Not end-to-end encrypted. What this means" → the existing DM privacy and retention details (`dm_privacy_short`, inspection and retained-edit strings) | Existing |
| You → **About and privacy** (new row group) | App version, the app's privacy policy, and when present the server's privacy policy, rules and legal notice (§1), open-source licenses (including Bricolage Grotesque OFL), and "How notifications are delivered" (distributor explanation from the push spec) | New |
| Delete account | Section 2 disclosures | New |
| Export | The existing unencrypted-file and partial-file disclosures | Existing |

### Facts for the data safety form (for the maintainer)

These facts were approved by the maintainer (2026-11-07). They describe the current design;
re-verify them at release. How they map to the form's answers is the maintainer's decision.

- **Leaves the device, to the server the person chose:**
  - account details (username, password at sign-in, optional birth date);
  - messages, reactions and reports;
  - files and photos (location metadata removed by default, files spec);
  - push subscription keys (when notifications are on).
- **Leaves the device, to the notification distributor the person chose:** encrypted Web Push
  payloads (RFC 8291). The distributor can't read them.
- **To the app maker or other third parties:** nothing. There are no analytics, crash-reporting
  or advertising SDKs, which must be re-verified at release.
- **Encryption in transit:** HTTPS only. **End-to-end encryption:** none. Server operators can
  technically access content, and some servers enable DM inspection (existing disclosures).
- **Deletion:** in-app closure (section 2), with the 14-day restoration and the retained shared
  data disclosed.

## Acceptance

| ID | Requirement | Verify |
|---|---|---|
| P-170 | No write action is possible before the guidelines are accepted for the current version, server and account. Acceptance is stored locally and versioned. | C |
| P-171 | With `policies.rules`, one I agree covers the app guidelines and the server rules and posts the shown version. Sign-up sends `accepted_rules_version`. `409` and unknown results re-read the session and never accept or resend on their own. `rules: null` or a missing `policies` shows only the app guidelines. | U, C |
| P-175 | A `403 rules_acceptance_required` is a definite refusal: the draft, reply, file or edit text is kept, no unknown state is set, re-acceptance opens, and nothing resends after I agree. Ungated actions never show re-acceptance. | U, C |
| P-212 | When the minimum includes ChitChat#164, the Choose a new username screen shows the moderators' `reason` as plain text (newlines kept, no markup or links, up to 500 characters in full, with Save reachable at 2.0 font on 360×640dp) and generic copy when it's missing or empty. It shows the placeholder, the previous-name sign-in line only when `sign_in_with_previous_username` is true, and never the previous name. One field and Save: a local format check makes no request; `invalid_username`, `username_taken` (one copy for taken, reserved and placeholder-pattern names), `429`, `username_change_not_required` and Unknown each show their copy. Unknown checks with one session GET, and Save is never resent automatically. | U, C |
| P-213 | A `403 username_change_required` is a definite refusal, as in P-175: the draft, reply, file or edit text is kept, no unknown state is set, the session is re-read and the chooser opens, and nothing resends after Save. When rules acceptance is also required, re-acceptance comes first and the chooser follows. Ungated actions (reading, search, reporting, block, ignore, removals, settings, export, closure) never show the chooser. | U, C |
| P-214 | The `username_change_required` notification is tolerated in Activity, opens the chooser through the guarded link flow (or the "already chosen" line), and its lock-screen public version is "New notification". `user_renamed` updates every rendered occurrence of that user ID without a visual cue, and the viewer's own ID re-reads the session. No surface shows a name the session has replaced. | U, C |
| P-176 | Hosted policy documents render `markdown` through the bounded formatter's display subset (escaped HTML, http(s) and mailto links only, no images, headings level 2 and below, flattened quotes, plain code) with literal fallback, never server HTML or a WebView. External documents and the legal notice open only `https` URLs, through an explicit Open in browser. | U, C |
| P-177 | `privacy_policy_updated` notifications are tolerated in Activity and never fail parsing. `change_summary` shows as plain text. | U |
| P-172 | Delete account is reachable from You → Privacy and security. It discloses the per-server scope, the 14-day restoration, finalization and what is kept, shows the destructive decision dialog before the request, uses step-up only on 403 step_up_required, checks unknown results with a session GET, and never resends automatically. | C |
| P-173 | Privacy links and disclosures appear at every surface in the table, with wording consistent with the existing DM disclosures. | R, S |
| P-174 | The guidelines text, deletion page and data safety facts are used as approved (2026-11-07). The privacy policy is published only after its own approval. Factual lines are re-verified against the shipped app before release. | R |
