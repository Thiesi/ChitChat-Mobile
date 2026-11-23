# Server prefill (P1: chitchat://connect)

The web's "Open it with this server" link opens the app with the installation's address filled in, so a less
tech-savvy person doesn't have to type it. The contract is fixed with ChitChat (CC #417):
`chitchat://connect?server=<host[:port]>` (P1). P2, the Play Install Referrer's `chitchat_server=<host>`, follows with
the same rules on first launch only.

## What a link may do (owner, TL, PL)

- **Prefill only.** It fills "Where do you chat?"; the person checks and taps Continue. Nothing connects, signs in or
  sends anything by itself (the session generation is unchanged when a link arrives).
- **No new acceptance rule.** The `server` value goes through `ServerAddress.parse` exactly as typed input.
  `ServerLink.value` only extracts it from exactly `chitchat://connect` (one non-blank `server` parameter, no path,
  port, userinfo or fragment, at most 2,048 characters).
- **Never discard typed input, never switch while signed in.** Any site can craft these links, so this is the
  phishing guard.
- **Shown and filled hosts are canonical ASCII** (`ServerAddress.displayHost()`: IDNs as punycode, IPv6 in brackets,
  port 443 omitted), so a link-borne lookalike reads as `xn--…` in the field and every line. Unicode the person types
  keeps M2a's display.
- **An invalid value is never shown, logged or kept.** Only the fact that the link had no usable address is used.

## Entry (TL)

`LinkEntryActivity` is the only exported VIEW handler for `chitchat://connect`: no UI (`Theme.NoDisplay`), no history,
not in recents. It hands the link to the process's `PendingServerLink` (memory only, a newer link replaces an older one,
taken once) and starts MainActivity with the push tap's flags (NEW_TASK | CLEAR_TOP | SINGLE_TOP). A running
MainActivity gets onNewIntent; a cold start creates one in the app's own task; a caller without NEW_TASK (a messenger)
never gets a second MainActivity in its task. MainActivity's launch mode and filters are unchanged, and its own intent
never carries the link, so a restored activity can't replay one. Push taps, picker results and file sharing are
untouched.

## States (PL rulings)

The root takes the link once and routes it by state. `ServerLinkViewModel` keeps the UI state across configuration
changes.

| State | Behaviour |
| --- | --- |
| A. No server, on Welcome | Welcome unchanged; Get started opens step 2 with the field filled and "Filled in from a link. Check the address before you continue." |
| B. Step 2, field empty | Filled, with the same line. The line goes on edit or Continue; once per arrival (cleared stays cleared). |
| C. Step 2, typed text | Left alone. The same normalized address does nothing; otherwise "The link you opened points to {host}." with Use this address and a 48dp Dismiss. |
| D. Saved server, Server found | The same host does nothing; otherwise "Use a different server" opens filled. The saved server stays until Continue; Back returns to Server found. |
| E. Sign-in or create form open | No navigation; the C line at the top of the form. Only Use this address leaves it (to step 2 filled), dropping its typed text. |
| F. Signed in | The same host does nothing; otherwise the dialog "You're already signed in" (one OK). No request, no switch, no departure; its own window suppresses read and typing proof; never stacked. |
| G. Invalid value | With step 2's field empty (A, B, D): "The link you opened didn't contain a server address you can use. Type it instead." Otherwise silent. |

While a bootstrap runs, the link waits until it settles; in any other state (an update notice, a deletion outcome) it
is taken and ignored.

## Tests

- `ServerLinkTest` (JVM): extraction only, parse's verdict, canonical ASCII hosts, take once.
- `LinkEntryTest`: a cold link, a link while running and a link from a foreign task each give one MainActivity in the
  app's task, with the link handed over once; a restored activity replays nothing.
- `ServerPrefillTest`: A to G with their asserts (no request, typed text and the saved server unchanged, no stacking,
  window focus, an IDN filled as ASCII, an invalid value never shown) and the `prefill-*` captures (14, collected into
  `prefill-presentation`).
