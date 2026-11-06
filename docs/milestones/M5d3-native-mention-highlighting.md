# M5d3: native server-authorized mention highlighting

Room and DM transcripts decorate only mention names supplied in their actual message metadata. Room `broadcast` flags can additionally decorate `@room`/`@here`; DM metadata has no broadcast field. This is passive presentation, with no profile action, completion, local name resolution, membership lookup, notification emission or additional HTTP request. Existing message actions/links remain separate.

## Contract and rendering

The pinned [mention ADR](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/docs/architecture/0004-replies-and-mentions.md) and [browser renderer](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/assets/js/message-content.js) at ChitChat v3.2.1/4f53ff4 define send-time authorization snapshots. Edits and later membership changes do not re-resolve mentions. A newly edited-in name without a supplied hint remains plain; rendering cannot generate a notification or grant read/send consent.

`MessageMentions` keeps the original typed room/DM hint list within the current composition. It derives requested names from parsed **Text** nodes through nested emphasis, paragraphs and quotes, excluding code/link nodes and fenced code. Matching the parsed runs matters for edits such as `_Hi @Alice_`: the closing emphasis marker is not part of the displayed mention name. ASCII token boundaries and case matching follow the browser. Room broadcast flags belong to resolved members, so no literal `room` username or invented target ID is required.

Only names actually present in bounded Text nodes enter the derived set; the complete canonical metadata list is unchanged. The scan is linear in body/hint input and does not construct a room roster. Output debug strings contain no names. No global cache, saved state, account lookup, disk or server mutation is added. Existing canonical row/owner lifecycle and deleted/ignored branches own retirement and redaction.

Parser resource fallback keeps the entire original body undecorated, including room emotes' inline fallback. The literal emote prefix and reply excerpts never receive body mention styling. Copy, drafts, sends and author editors retain their original source. Personal/broadcast decorations use distinct Material container colors; nested emphasis remains intact. Passive scalar kind annotations expose no ID or clickable target. Code and link AST nodes keep their existing styles/actions.

## Verification and limits

Fourteen focused JVM tests cover supplied/unresolved names, case/ASCII/UTF-16 boundaries, broadcast metadata, DM separation, parsed emphasis boundaries, literal prefixes, code/links/quotes, post-edit snapshots, full complexity/length fallback and safe debug strings. Three native app journeys cover actual room/DM decorations, code/link/fenced exclusions, original-text Copy, ignored-author hiding, complex emote fallback and owner retirement. They keep the existing physical-tap helpers and all earlier assertions/deadlines unchanged.

Require full local JVM/lint/debug/release/Android-test builds, complete current-head unfiltered/untraced Linux/API36 native CI and written initial/fresh Claude reviews before delivery. Exact run counts belong on the PR. Manual device/API26/TalkBack/contrast/layout and live acceptance remain separate; no phone/live run is implied. Completion/profile navigation, other M5 tools and push remain pending.

[M5d2/PR75](https://github.com/Thiesi/ChitChat-Mobile/pull/75) delivered native formatting/guarded links after its corrected head passed862 JVM/183 native and both reviews. Its original181/183 destination failures remain preserved. Earlier issue69/main/PR71 input failures and the consumed one-dispatch observation remain separate; this decoration slice cannot waive them or claim a keyboard correction.
