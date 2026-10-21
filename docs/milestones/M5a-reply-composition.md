# M5a - Native Reply/Copy

Consumes [PR48's contracts/state](M5a1-reply-composer-state.md), audited against backend v3.2.1; no new APIs.

## Behavior

Tap/long-press opens guarded room/DM actions. Canonical previews redact ignored/deleted/unloaded content; original owner/entry/row/selection guards reject stale callbacks. Reuse ID-only100-slot drafts and unknown-send protections. Room pings ignore quotes (explained); DM slash text stays literal. Invalid targets retain drafts for removal, never downgrade/replay. DM block disables Reply, not Copy/history.

Copy explicitly exports the whole body, not substrings; plaintext may outlive logout and its sensitive flag is not encryption. Expanded/compact preview/removal and48dp Send keep input reachable. Modals suppress read/typing/input/send. View original reuses guarded historical adoption; selection makes no request.

## Verification and limits

[PR49](https://github.com/Thiesi/ChitChat-Mobile/pull/49) retains the failed/revised fixture, layout and keyboard runs. Its historical final head passed579 JVM,134 clipboard-safe native and strict150% portrait/landscape9 each, but hosted CI stayed136/137 (new Reply/Copy12/12 passed). Later passes do not erase failures or prove an IME fix.

[PR50 diagnostics](native-ime-handoff-observations.md) and [PR52 OS-routed taps](system-native-taps.md) are integrated. Reply journey button taps use the shared system helper; long-press/scroll and semantic text injection remain Compose actions. Existing focus/strict-IME assertions and5-second deadlines are unchanged. Every head needs build/native/CI and initial/fresh reviews; current results are recorded on PR49. Local runs exclude only3 clipboard writers; CI is unfiltered. Live/API26/manual consumer-device/TalkBack, other M5 tools and push remain separate. No integration credentials/live endpoints were accessed. Issue31 stays open.

Rebased local gates: debug/release/test builds,600 fresh JVM, lint0,146/146 native; focused9/9 and150% portrait/landscape9/9 each. Setup-only orientation failure is preserved on PR49; no assertion was waived. CI/reviews remain separate.
