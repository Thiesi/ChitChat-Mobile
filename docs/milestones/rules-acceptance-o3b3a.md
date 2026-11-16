# Rules acceptance O3b3a: re-acceptance, refusal detection and the conversation banner

The re-acceptance half of [play-readiness.md](../design/play-readiness.md) §1 ("Re-acceptance" and "Write
refusals"), on [O3b1](rules-acceptance-o3b1.md) and [O3b2](rules-acceptance-o3b2.md). O3b3b follows with one test per
gated write path:
- room send, edit, reaction add, `/ping` and upload;
- DM send, edit, reaction add and upload;
- avatar changes: N/A, since the app has no avatar upload yet.

## Detecting a refusal

Detection lives in the transport.
- **The signal:** `SessionHttp` captures the scope a request starts in. A reply that is exactly
  **`403 rules_acceptance_required`** (no other status or code) reports that scope.
- **The fence:** `SessionRepository` applies it under its lock, only while the runtime, server, account and generation
  are still the ones the request started in. A late reply never flags a successor session.
- **What it sets:** `SessionState.rulesRefused`, plus `rulesRefusal`, a per-session count of refusals.
  - The next bootstrap clears `rulesRefused`: the server's status wins, and it then reports `acceptance_required`
    itself.
  - A confirmed accept clears it too.
  - The count stays, so a new refusal can reopen a dismissed dialog.
- **Coverage:** every write path is covered without per-actor changes.
- **Unchanged:** a 403 was already a definite refusal there (only network, timeout, TLS, redirect, incompatible,
  too-large and 5xx are ambiguous). So the draft, file and edit text stay, no unknown state is set, and nothing
  resends.

## "{installation} updated its rules"

`RulesUpdatedDialog` shows while `user.rules.acceptance_required` or `rulesRefused` holds and a rules version is
published, unless dismissed (below).
- **Container:** a full-screen dialog **over the composed, STARTED shell** (TL ruling). No departure or ON_STOP reaches
  a pane, so an in-flight upload or POST keeps going. The dialog window's focus suppresses read and typing proof under
  the existing rule.
- **Layout** (PL rulings):
  - the background surface, with pane title and heading "{installation} updated its rules";
  - "Updated {date}";
  - "What changed": a raised card with the Label heading. The `change_summary` comes from one
    `show.php?document=rules` GET per version, shown as plain text capped at 6 lines with "Show all" / "Show less";
    semantics carry the full text. The card is omitted on failure or when empty;
  - the Read row (document dialog);
  - the consent line in textMuted;
  - **I agree** (the single primary) and **Not now**. System Back is Not now.
- **I agree:** posts the shown version once.
  - **Outdated:** a GET, then "updated its rules just now".
  - **Unknown:** a GET. The dialog closes if acceptance is no longer required; otherwise it shows the failure line.
  - **Failed:** the status ladder.
  - **The refused write is never resent.** The person presses Send again.
- **Not now:** dismissed in memory for that account, generation, version and refusal count. It comes back on a new
  `403` refusal, on Review, in a new process, or for a newer required version. It never comes back on a plain
  foreground or refresh.
- **Background:** an accept in flight isn't cancelled by a genuine background (only VM clearing ends it). If the process
  dies, the next session GET settles `acceptance_required`, never a resend.

## Conversation banner

`RulesBanner` appears in room and DM conversations while acceptance is required, whether or not the dialog was
dismissed.
- **Text and action:** "Agree to {installation}'s updated rules to send messages." with **Review**, which reopens the
  dialog. The composer stays enabled, so drafts keep working.
- **Placement** (PL): directly under the status slot (or the top bar when there's no slot notice), outside
  `resolveStatus`. The two stack and never overlap.
- **Style:**
  - no background;
  - a 16dp `info` glyph and a textMuted caption, merged for TalkBack;
  - Review as its own 48dp action, wrapping below the sentence when it doesn't fit;
  - a 1dp `line` bottom edge;
  - no live region.
- **Compact heights:** hidden with the slot (`compactConversation`).
- **Precedence** (PL J1 on PR #276, TL-approved): the strip yields while the slot shows a level-2 (BLOCK) notice, so the
  transcript and composer keep their room at 200%. It returns when that notice clears, and level-1 caption lines stack
  with it. A send still opens the dialog on a 403, so nothing is lost. The slot reports its shown level through
  `onShownLevel`.

## Verification

- **`SessionRepositoryTest` (+3):**
  - only `403 rules_acceptance_required` flags. `403 forbidden`, `400 rules_acceptance_required` and
    `409 rules_version_outdated` don't;
  - the bootstrap clears the flag and keeps the count;
  - a stale scope replayed into the old and new runtimes' listeners never flags the successor, while the current
    scope does;
  - a confirmed accept clears the flag.
- **`RulesUpdateViewModelTest` (5):**
  - visibility;
  - Not now and each way it comes back;
  - one POST and no resend;
  - Outdated and Unknown (both branches);
  - "What changed" once per version, and omitted on failure or when empty.
- **`RulesUpdateJourneyTest`** (real room):
  - a refused send keeps the draft, sets no unknown state and opens the dialog;
  - Not now leaves the banner with the composer enabled;
  - Review reopens it;
  - I agree posts once, and nothing resends;
  - an in-flight upload survives the dialog and completes underneath it;
  - a newer message under the dialog gets no read acknowledgment.

  The session fixture mirrors the transport's refusal signal, which is JVM-tested.
- **`RulesUpdatePresentationTest`:**
  - captures: the dialog with a long summary collapsed and expanded (default and 200%), and the banner at 200%, alone
    and under the offline slot notice; light and dark;
  - asserts Review has a 48dp target and the actions are reachable;
  - at 200%, an offline (level-2) notice hides the strip while the transcript and composer stay displayed; the strip
    returns when the notice clears; under a secure-connection failure notice (also level 2), a send while the strip is
    hidden still opens the dialog. Offline isn't used for the send check, since a clean text draft then waits to send
    (S4a);
  - collected by `native_presentation.collect_rules` (12 files).
