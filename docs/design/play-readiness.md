# Play readiness: terms, account deletion and privacy surfaces

Status: presentation spec for the Google Play requirements the technical lead listed (issue #1,
comments 6713519327 and 6713603503; the maintainer confirmed Play distribution on
2026-11-07). It designs the user-facing flows. **The policy texts in this document are drafts
that need the maintainer's approval**, and the data safety answers stay the maintainer's
decision. Application ID, signing and listing remain maintainer decisions. Mockups:
`Guidelines` and `DeleteAccount` on the canvas.

CCM is a client for self-hosted ChitChat servers. The app maker runs no server and receives
no account or message data; each installation is run by its own operator. Every screen here
keeps that distinction clear.

## 1. Community guidelines at onboarding

Play's user-generated-content policy expects people to accept terms that define
objectionable content before they create content. Installations can't publish their own rules
yet (ChitChat#160), so CCM ships **generic app-level guidelines** now and is built to show the
server's own rules later.

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

**Draft guidelines text** (maintainer approval required):

> **Be kind, be real, be safe.** Don't harass, threaten or bully anyone. No hate based on
> who people are. No sexual content involving minors, ever. No spam, scams or malware. Don't
> share other people's private information. Don't pretend to be someone else. Nothing
> illegal.
>
> You can report any message from its menu, and ignore people in rooms or block them in direct
> messages. Reports go to the people who moderate this server.

### When the server publishes its rules (Dependency: ChitChat#160)

When the server exposes rules or terms and a privacy-policy link with versions (#160's
proposal):

- The same screen shows a second section, "**{installation}'s rules**", with the server's text
  or link and its privacy link. One **I agree** covers both, and the screen says so.
- **At registration**, the accepted server version is sent as the API defines
  (`accepted_terms_version` in the proposal). The onboarding Create account form gets a line,
  "By creating an account you agree to {installation}'s rules," with a link above the button.
- **Re-acceptance:** when the session reports that acceptance is required (the proposal's
  `terms_acceptance_required`), the next foreground shows "**{installation} updated its rules**",
  with what changed (the server's text or link) and **I agree** or **Sign out**. Until then,
  reading may continue, but write actions show the re-acceptance screen instead of acting.
  The server enforces this anyway, and the client mirrors it.
- With nothing configured on the server, only the app guidelines apply, as today.
- Everything in this subsection is **Dependency #160**. Leave it out of the build until the API
  exists.

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
3. **Step-up:** the existing password prompt (form dialog).
4. **Final decision dialog:** title "Delete your account?"; body "You'll be signed out now. You
   can restore it for 14 days."; buttons **Cancel** and **Delete account** (`error`).
5. **After success:** the person is signed out (the server destroyed the session) and sees a
   confirmation screen: "Your account is scheduled for deletion. You can restore it until
   {date}." Use the server's deadline if the response provides one; otherwise "for 14 days".
   **Done** goes to onboarding step 3 for this server.

**Errors:**
- the last Super-Administrator gets the server's refusal in plain words: "You're the last
  Super-Administrator. Give the role to someone else first.";
- an unknown result after the request was sent reads "We're not sure your request went
  through." with **Check**. Check is an ordinary sign-in attempt or restore check that reveals
  the state. **Never resend automatically.**

## 3. Deletion web resource (content for a CCM page)

Play listings must link to a web page explaining how to delete an account. CCM doesn't host
accounts, so the page explains per-server deletion. **Content only. Hosting and URL are a later
maintainer decision. Verify every factual line against the shipped app before publishing.**

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
| Onboarding step 3 (Server found) | "Your server hosts your conversations. Messages are not end-to-end encrypted." (existing line, onboarding spec) plus, with #160, the server's privacy link | Existing; #160 dependency |
| Community guidelines screen | App privacy link; with #160, the server's rules and privacy link | New |
| DM inbox header | "Not end-to-end encrypted. What this means" → the existing DM privacy and retention details (`dm_privacy_short`, inspection and retained-edit strings) | Existing |
| You → **About and privacy** (new row group) | App version, the app's privacy policy, the server's privacy policy (#160), open-source licenses (including Bricolage Grotesque OFL), and "How notifications are delivered" (distributor explanation from the push spec) | New |
| Delete account | Section 2 disclosures | New |
| Export | The existing unencrypted-file and partial-file disclosures | Existing |

### Facts for the data safety form (for the maintainer)

These are facts about the current design. How they map to the form's answers is the
maintainer's decision.

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
| P-171 | Server rules, server privacy and re-acceptance appear only once #160 exists, marked as a dependency until then. | R |
| P-172 | Delete account is reachable from You → Privacy and security. It discloses the per-server scope, the 14-day restoration, finalization and what is kept, uses step-up and a destructive decision dialog, and never resends automatically. | C |
| P-173 | Privacy links and disclosures appear at every surface in the table, with wording consistent with the existing DM disclosures. | R, S |
| P-174 | The guidelines text, privacy policy, deletion page and data safety answers are published only after maintainer approval. | R |
