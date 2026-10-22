# M5a - Native Reply/Copy

Consumes [scoped contracts/state](M5a1-reply-composer-state.md), backend v3.2.1; no new APIs.

Room/DM tap/long-press opens Reply/Copy with canonical redaction and original scope/entry/row guards. ID-only100-slot reply drafts retain unknown-send guards; invalid targets never downgrade/replay. Room pings ignore quotes; DM slash stays literal. Blocks disable Reply, not Copy/history.

Whole-body Copy exports plaintext that may outlive logout; the sensitive flag is not encryption. Preview/removal and pinned48dp Send remain reachable. Modals suppress read/typing/input/send. View original grants no read/resend consent.

[PR49](https://github.com/Thiesi/ChitChat-Mobile/pull/49) retains all gates/failures, including30d05's local145/146 despite hosted149/149. Passes neither erase failures nor prove an IME fix. Inert-title dismissal isolates clipboard previews without clearing Copy; [PR53](native-ime-server-snapshot.md) observes failures before cleanup.

Original5-second focus/IME checks remain. Buttons use OS taps; long-press/scroll/text use Compose. Local excludes only3 clipboard writers; CI is unfiltered. Full gates/reviews required. Live/API26/device/TalkBack, other M5/push remain pending; issue31 open.
