# Rules acceptance O3b3b: every gated write path's refusal

One test per gated write path for `403 rules_acceptance_required` ([play-readiness.md](../design/play-readiness.md) §1,
"Write refusals"), on [O3b3a](rules-acceptance-o3b3a.md). Each test proves the refusal is **definite**:
- the draft, file or edit text stays, and a refused reaction leaves the chip unchanged;
- no unknown state is set (send, author or reaction);
- the transcript stays readable;
- after the session's rules flip to accepted (what I agree does), **nothing resends**.

The dialog opening is O3b3a's transport signal, which is JVM-tested there and covered by `RulesUpdateJourneyTest`.

| Path | Test |
| --- | --- |
| Room send | `ConversationViewModelTest.aRulesRefusedSendKeepsTheDraftSetsNoUnknownAndNeverResends` |
| Room `/ping` | `ConversationViewModelTest.aRulesRefusedPingKeepsTheDraftSetsNoUnknownAndNeverResends` |
| Room edit | `RoomAuthorChangeTest.aRulesRefusedEditKeepsItsTextIsNotUnknownAndNeverResends` |
| Room reaction add | `RoomReactionStateTest.aRulesRefusedReactionLeavesTheChipUnchangedIsNotUnknownAndNeverResends` |
| Room upload | `RoomAttachmentUploadTest.aRulesRefusedUploadKeepsTheFileAndTranscriptIsNotUnknownAndNeverResends` |
| DM send | `DirectStateTest.aRulesRefusedDirectSendKeepsTheDraftIsNotUnknownAndNeverResends` |
| DM edit | `DirectAuthorChangeTest.aRulesRefusedDirectEditKeepsItsTextIsNotUnknownAndNeverResends` |
| DM reaction add | `DirectReactionStateTest.aRulesRefusedDirectReactionLeavesTheChipUnchangedIsNotUnknownAndNeverResends` |
| DM upload | `DirectAttachmentUploadTest.aRulesRefusedDirectUploadKeepsTheFileIsNotUnknownAndNeverResends` |
| Avatar upload | N/A: the app has no avatar upload yet |

## Product fix (an existing, unreleased path)

The new tests showed that in both the room and the DM actor, a rules refusal on an **edit** or an **added reaction**
was remembered as author/reaction *uncertainty*. Both actors treat any exception after a mutation starts as possibly
committed (M5b1). That contradicts §1, and the released contract says the refusal comes before any commit.
ChitChat v3.10.0 `docs/api/policies.md`:

> Until current rules are accepted, these operations return `403 rules_acceptance_required`: room and DM sends, edits,
> attachment uploads, adding reactions, private pings, and uploading a profile picture (including a cropped provider
> picture). Rules-row locks serialize checks with the content transaction and a material publication.

**The fix:** `ApiFailure.isRulesRefusal()` (exact `403` plus `rules_acceptance_required`). Both actors drop the pending
author or reaction write on it, so nothing is remembered as unknown. M5b1's possibly-committed rule stays for **every
other** failure.
- **Room guard:** `RoomAuthorChangeTest.aDifferentForbiddenEditStillLeavesAuthorUncertainty`.
- **DM guard:** the existing `DirectAuthorChangeTest.forbiddenPostIsAlsoUncertainAndAFailedRecoveryGetCannotClearIt`.

Send, `/ping` and upload were already correct: they treat 4xx as definite, and `rules_acceptance_required` isn't an
access-loss code, so the transcript stays.

## Verification

Room and DM VM tests: 399/399 locally, with 10 new tests (the 9 paths plus the room guard). Full gate: see the PR.
