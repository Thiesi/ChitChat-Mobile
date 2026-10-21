# M4c4d — Native notification destinations

Member notifications open supported room/DM/Account links, exact messages and private pings. View is independent from Mark read; foreign/unknown/self-DM/guest-registration links stay inert. No browser fallback or auth mutation. Push and guest consumption remain pending.

## Handoff and privacy

Links are not authorization. Reuse strict same-HTTPS-origin parsing and read-only context/history/ping lookup (including existing metadata POSTs); missing/denied targets stay at the original center with Retry/Cancel. No backend contract or checkout change.

Freeze owner/entry snapshots; before atomic prepare+navigation+consumption, check the original row/request and ACTUAL entry reference/ID/STARTED lifecycle. Ignore superseded effects. The actor stores no Activity/View/saved body; protected routes encode ownership only. The real Activity/provider test also invokes the destroyed controller's callback.

Reuse canonical panes and historical bounds; preserve both resources' drafts/unknown sends and never cancel/replay active writes. No temporary latest or read/resend consent. Generic latest views can acknowledge actually visible chat, independently from notice reads.

Ping IDs, participants, ignore policy and the latest100 limit stay separate. Selection precedes navigation, blocks stale read/send/typing and scrolls the private modal to the target. Missing pings never fall back to chat. Close only the original selection and require fresh visibility.

## Verification and remaining gates

The first JVM gate failed1/552: reload cleared ping selection. Fixed with regression coverage; subsequent five-task gates passed552/552, lint, debug/R8 and test APK. Original reports are archived.

First native run passed8/10. Correcting an overstrict limit1-probe assertion fixed one case; the two-case diagnostic still failed Activity restoration. Freezing root entry/session snapshots fixed it: the unchanged test APK passed2/2, then10/10 portrait. Typed fixtures are not live-server acceptance.

Final build:552 JVM, lint, debug/R8/test APK passed; native10/10 portrait,10/10 landscape150 and125/125 full suite (287s) passed with real IME checks. Display policies restored. Publication requires exact-head CI and initial/fresh Claude reviews. Issue31/live/push remain separate; no integration secrets used.
