# M5g4a: room attachment draft preparation state

This state prerequisite connects the delivered local read/clean/hash producer to
the real `ConversationViewModel` and its existing room draft table. It adds no
picker, file card, privacy-bypass button, attachment POST or DM consumer. Existing
native files-unavailable disclosures remain accurate.

## Original private owner

An explicit first selection or original-selection-bound Replace freezes the
server, account, generation, session version, room, source entry and private
draft-slot/selection references. Admission checks the actual entry, foreground
room, current session, guest/mute/member policy, existing uncertain-text state,
operation availability and the existing100-slot bound. Invalid/unavailable file
policy or an explicit zero limit prevents preparation before provider open.

File-only, preparing, failed and privacy-choice drafts count in the same table
as captions and replies. There is no eviction. Caption/reply edits keep the same
slot; discard invalidates it before cancelling its producer. Discarding a stale
snapshot cannot remove a successor.

The [accepted lifecycle decision](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6743408023)
allows already-started local work to finish into that exact private slot across
navigation, genuine background and configuration. Completion rechecks the
original session, slot, selection and task, and replaces only the attachment
facts in that private memo. It does not replace the active pane, caption, reply,
uncertain delivery, history, read or typing proof. A fresh actual-entry check
still gates display and Remove/Replace. Session retirement, account/server change,
discard or replacement invalidates results before cancelling work.

Preparing, ready, explicit-privacy-choice and safe local-failure facts contain no
payload bytes or generated private-data debug string. The retained handle remains
private to the owner. Unsupported/failed photos retain their exact original choice;
this slice supplies no unchanged ready photo or Send-anyway action. While a room
has an attachment, the existing text-only Send cannot accidentally send its caption
alone. Reply and caption editing remain independent of that transport restriction.

## Process allocation lifetime

The Application shares one numeric allocation ledger across room owners and session
retirement: one eighth of the process maximum heap, capped at96MiB. This is an
engineering bound, never a server limit or displayed capacity guarantee. Private
bytes remain VM-owned and unpersisted. The task freezes a read ceiling from currently
free capacity, capped at32MiB, then atomically reserves input/output/workspace before
opening the provider. An allocation race may deny that one operation before open;
there is no automatic retry.

Explicit replacement invalidates and cancels the old selection immediately, but
waits for its actual producer termination before starting the new selection's one
initial read. Cancellation alone cannot free the old charge or cause a temporary
budget denial to lose the replacement. Discard/retirement while waiting prevents
the queued source from opening. No provider is reopened. Other concurrent room
preparations still obey the shared cap and may fail safely before opening.

Ready/choice handles retain their source/output allocation charge until removed,
replaced, discarded or retired. Held provider reads retain their charge until the
actual worker returns, including after the VM/session is retired. There is no
global budget reset, HTTP job, POST, automatic send or quota attempt.

## Verification and following slices

Focused JVM tests use the actual VM, draft table, source reader, cleaners, evidence
factory and shared ledger. Held provider fixtures exercise navigation/background,
replacement/discard/session retirement, late-result rejection and memory lifetime;
other cases exercise capacity, fresh-entry guards, caption/reply retention and safe
photo choices. Results and any negative evidence are recorded on the PR.

The original replacement regression failed before the fix: one case, expected
READY but got FAILED because the cancelled provider still held the entire working
charge. Its source/log/XML archive remains retained, SHA256
`ae4afe77ca35400584894ae83acdec2199406e687bac0e5890ab4f7990b30fd3`.
After waiting for actual producer termination (including replacement of a queued
replacement), all45 focused JVM cases passed:15 room attachment,18 existing room
draft retention and12 preparation-task cases. The archive SHA256 is
`0124af0b10be70d4eaede5bbedf0b95bbecb695ab4cbaa3a91b51f8cdd2e46ca`.
Both runs used the checked-in Gradle wrapper/JDK17/Kotlin2.3.21, two processors and
workers, offline, with no emulator/native/live task. The negative is preserved;
the corrected focused pass does not replace current-head full CI acceptance.

Current-head full JVM/lint/debug/release/unfiltered native gates and both written
reviews remain required. No input assertion, deadline, test host or workflow changes.
DM integration, final-byte server-policy checks, explicit privacy consent, serialized
attachment POST/uncertainty and full-multipart termination proof remain following
consumers. Native controls and provider/live/device acceptance remain separate.
