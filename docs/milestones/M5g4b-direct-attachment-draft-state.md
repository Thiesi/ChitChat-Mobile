# M5g4b: DM attachment draft preparation state

This slice adds the DM consumer of owned local attachment preparation. Room and
DM ViewModels receive the same Application allocation ledger. Private bytes stay
with their original feature owner; the numeric budget survives owner retirement.
No picker, file card, Send-anyway action, upload POST or usable file UI is added.

## Private owner and serialized actor

The explicit first pick or original-selection-bound Replace freezes server,
account, generation, session version, peer, source entry and private slot/selection
references. Admission requires fresh actual-entry proof, the active current thread,
ready original session, available messaging policy, no mute, free draft
capacity and no serialized operation or uncertain send/block outcome. Invalid,
unavailable or explicitly zero upload policy prevents preparation before opening.
No missing-policy size/type default is invented.

File-only, preparing, failed and privacy-choice states count in the existing100
per-peer draft table without eviction. Caption/reply edits retain their slot.
The [accepted local-work lifecycle](https://github.com/Thiesi/ChitChat-Mobile/issues/1#issuecomment-6743408023)
permits already-started local preparation across navigation/background only into
that exact original private slot. Completion rechecks owner, slot, selection and
task references and updates only its attachment facts, preserving caption, reply
and uncertain/checked state. It does not change the active pane or create history,
read, typing, duplicate-risk, privacy-bypass or send authority.

Local preparation does not use or retire the serialized SEND/READ/BLOCK/AUTHOR/
REACTION actor. Pending writes and their ambiguity stay unchanged. Existing
text-only Send is blocked while attached so a caption cannot be sent without its
file; reply/caption editing remains independently available under the old guards.
Removal retains other draft and uncertainty state. Unsupported/failed photos keep
an original choice and approved reason, never an unchanged ready photo.

## Actions and memory lifetime

Fresh actual-entry proof gates selection, visible-pane display and Remove/Replace.
The inbox's existing discard dialog now captures the original draft rather than a
peer ID. Its confirm rechecks the actual root entry, current owner, stable slot,
caption/reply/uncertainty snapshot and original attachment selection. A preparation
phase transition alone does not invalidate that choice; a successor file or edited
draft cannot be discarded by the old confirm. Stale close callbacks cannot close a
successor dialog. Layout, copy, tokens and keyboard behavior are unchanged.

Replace/discard/session retirement invalidates before cancellation and never
releases a still-running producer's charge. Explicit replacement waits for actual
producer termination before its one initial read; a queued replacement carries the
real predecessor barrier even if its waiting coroutine is replaced. Discard while
queued prevents provider open. There is no reopen or automatic retry.

Room and DM allocations share the delivered process bound (one eighth of maximum
heap, at most96MiB). Input/output/workspace is reserved before provider open; retained
handles keep their conservative source/output charges until their actual last use.
If concurrent work consumes capacity, the new operation fails CLIENT_BUDGET before
opening, and later free space never replays it. These are engineering charges,
never a server limit or displayed device-capacity guarantee. No URI grant, file,
caption, body, digest or private metadata is persisted or logged.

## Verification and following consumers

Real-VM JVM tests cover background/navigation, queued replacements, discard and
late-result rejection, session/server/version retirement, fresh-entry guards,
capacity, caption/reply state, explicit photo choice, pending/unknown send guards
and shared room/DM budget denial without retry. Existing DM state/author and room
attachment/preparation tests provide regression coverage. Exact results and any
negative evidence are recorded on the PR.

The local run passed106 cases:15 new DM attachment,39 existing DM state,25 DM
author,15 room attachment and12 preparation-task cases, with no failures/skips.
Its archive SHA256 is
`c9b0e6cf88016fc238e4d14705b41f4afaf86c5a5c7f0e1f944d737ebd56b9ff`.
One filter named a nonexistent reaction class, so no reaction coverage is claimed
from that run. A separate run of the actual `DirectReactionStateTest` passed all26
cases, archive
`7b3985ddc73348e531e0bc26b2b1a14acd18f618692f25a63af8ce74ef8a7574`.
Kotlin sources were unchanged between the runs. Both used the checked-in wrapper,
JDK17/Kotlin2.3.21, offline/two processors and workers, with no native/emulator/live
task. Source/log/XML archives were SHA/ZIP-CRC verified before generated-file cleanup.

Full current-head JVM/lint/debug/release and the unchanged unfiltered native suites,
product guard and both actual written reviews remain required. No native test,
input predicate, deadline, host or workflow changes. Final uploaded-byte policy
checks, original-bound explicit privacy choice, serialized upload/unknown-delivery
reconciliation and transport termination proof remain following state consumers.
Native controls, provider/live/device and release acceptance remain separate.
