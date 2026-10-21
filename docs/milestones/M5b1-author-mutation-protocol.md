# M5b1: author edit/delete protocol prerequisite

Status: local implementation/build/unit/lint/native gates passed. Delivery requires current-head CI and completed initial/fresh Claude reviews, recorded in the associated PR. This is **not native Edit/Delete controls or live mutation acceptance**. PR49 remains independently blocked by issue31; no failed check is waived.

## Verified existing contracts

Audited ChitChat v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`, including [documentation](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/docs/api/message-mutations.md) and implementations:

- [Room edit](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/rooms/edit-message.php): POST `{message_id,body}`.
- [Room author delete](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/rooms/delete-own-message.php): POST `{message_id}`, distinct from moderator deletion.
- [DM edit](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/direct-messages/edit.php): POST `{message_id,body}`.
- [DM author delete](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/direct-messages/delete.php): POST `{message_id}`.

All return HTTP200 `{message: <existing mutation metadata>}`. The server authorizes ownership, membership/minimum age and mutable message types. Room edits additionally require post/mute permission; DM edits require messaging availability. DM author deletion remains possible across either participant's block. Edits preserve type, original reply and attachment binary. Bodies use PHP trimming and1–4000 Unicode characters; unchanged edits return409 `message_unchanged`. Slash text is literal, not a new `/me` or `/ping`. Revisions remain retained under the installation's policy.

Both [room](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Room/RoomMessageMutationService.php) and [DM](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/DirectMessage/DirectMessageMutationService.php) services **commit before reading response metadata**. Errors/cancellation can follow a committed write: never automatically replay, including after500 responses. Concurrent later changes may already appear in successful metadata, including a tombstone after an edit. Room_message/message_deleted/direct_message events stay unchanged. No backend change or issue is needed for these existing APIs.

## Client and dependent native behavior

`MessageMutationOperations`/`SessionMessageMutationRepository` take the original rendered SessionState and canonical row, using existing `openAccess(expected)`/`authorized`, cookies/CSRF and invalidation/cooldown handling. Local checks reject invalid/non-author/deleted/system originals; the server remains authoritative. DM writes require the existing privacy disclosure. No second client, cache, worker, automatic step-up or optimistic send is introduced.

Responses validate exact IDs/type/metadata and reconstruct canonical models with original identity/quote/file only while active. Deletion drops body/reply/file/mentions but retains reactions. There is no fallible follow-up GET hidden inside an acknowledged POST. Later canonical history must refresh other rows/off-window quotes; one response is not a conversation snapshot.

Room history retains optional can_edit/can_delete enrichment in transient client-only `authorActions`, unknown unless both actual flags exist. It is not a claimed history field, saved content or authorization proof. DM permissions already existed. Permission/deletion flags reject quoted JSON booleans; private-content classes have no generated content strings.

The dependent native slice must provide accessible author actions, original entry/account/resource/row guards, memory-only edit text, explicit delete-for-everyone/revision disclosures and operation serialization. Suppress underlying read/typing proof while modal, preserve normal drafts/replies/anchors/unknown-send consent, and require explicit GET reconciliation plus a fresh choice after ambiguous author writes. Native guests/moderation remain separate. Protocol availability alone does not complete editing parity.

## Verification boundaries

Credential-free TLS MockWebServer tests use production session/network code: exact routes/bodies/cookies/CSRF; literal/Unicode/trim validation; original-author checks; ID/permission/tombstone/reaction handling; malformed/error replies without replay; missing DM privacy; stale account callbacks, logout, cancellation and redirects. Record actual build/test/native results after execution. Normal CI/native gates omit diagnostic input tracing and phone autofill overrides. No integration-file access, live-server calls, phone interaction or backend-checkout modifications belong to this slice.

Local evidence (2026-10-21):

- Initial debug build and18 focused tests passed (7.151s test report); that report is preserved separately. After response-validation/coverage tightening, the final full suite passed598 tests, including21 mutation tests, with zero failures/errors/skips.
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest --no-daemon --max-workers=2 --console=plain` passed in16m13s. JVM tests, lint and R8 executed; some resource tasks reused Gradle cache. Lint reported0 issues. Existing SDK XML-version/strip/navigation/test-scalar warnings remain visible, not suppressed.
- Installed the hash-verified debug/test pair on the owned API36 emulator (portrait320×640, font1.0). One full129/129 instrumentation run passed in510.551s with `expectSoftKeyboard=true`, no negative statuses, tracing/autofill overrides or retries. Discovery enumeration is not execution. This is regression evidence, not a production correction for issue31.
- All10 native-wrapper Python unit tests passed in0.058s. `git diff --check` passed. No physical phone, live account, Firebase, release signing or end-to-end mutation acceptance was exercised.

Archived APK SHA256: debug `e1de8f625b4de128520bf626bca663d23bd5e06473ae1c19be1ee76d38e2bf37`; unsigned release `668017ad747ffc51a8c153828db80453ed0b1b1444542b1dec66f4b330e4cdae`; test `0693b595c40ab127acc13a0dde3f4751ea4ce9bec93e4b3617794208cf928ee2`. Local ignored archives contain reports, XML and raw instrumentation results; remote CI/review evidence must be checked separately before merge.
