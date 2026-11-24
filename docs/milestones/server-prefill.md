# Server prefill (P1: chitchat://connect, P2: the Play referrer)

The web's "Open it with this server" link opens the app with the installation's address filled in, so a less
tech-savvy person doesn't have to type it. The contract is fixed with ChitChat (CC #417):
`chitchat://connect?server=<host[:port]>` (P1). P2, the Play Install Referrer's `chitchat_server=<host>`, follows with
the same rules on first launch only (see "P2: the Play referrer" below).

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
- **An invalid value is never shown, logged or kept.** Only the fact that the link had no usable address is used:
  `ServerLink` holds only the parsed address (no raw text), and its `toString` names only validity. Each arrival is
  its own instance (identity equality), so an older one is never taken in place of a newer one.

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
| E. Sign-in or create form open | No navigation; the C line at the top of the form. Only Use this address leaves it (to step 2 filled), dropping its typed text. Leaving the form any way drops the offer, so it never reappears later. |
| F. Signed in | The same host does nothing; otherwise the dialog "You're already signed in" (one OK). No request, no switch, no departure; its own window suppresses read and typing proof; never stacked. |
| G. Invalid value | With step 2's field empty (A, B, D): "The link you opened didn't contain a server address you can use. Type it instead." Otherwise silent. |

While a bootstrap runs, the link waits until it settles; in any other state (an update notice, a deletion outcome) it
is taken and ignored.

## P2: the Play referrer (TL, owner, PL)

A fresh install from the web's Play badge carries `chitchat_server=<host>` in its Play install referrer. It prefills
exactly like a link: the value is offered to the same `PendingServerLink` and takes the same router, so a fresh install
shows A (Welcome unchanged, then step 2 filled with the same line, "Filled in from a link. Check the address before you
continue."; the person did tap a link, and the line doesn't vouch for where it came from).

- **The read (`ReferrerPrefill`):** at most one per process, in the app's scope, off the UI (Welcome renders as usual);
  only while no server is saved and nobody is signed in, re-checked when the answer arrives. Google's Install Referrer
  client is bounded at 10 s and always disconnected; its blocking call runs off the main thread.
- **Answers:** OK (with or without a referrer), FEATURE_NOT_SUPPORTED (no Play: a GitHub install, CI's `google_apis`
  images), DEVELOPER_ERROR and PERMISSION_ERROR are definitive; a disconnected or unavailable service, the bound or an
  exception leave it for a later launch.
- **The flag:** one boolean, `referrer_done`, in the backup-excluded settings store. It is set on any definitive answer,
  or as soon as a server is saved or someone signs in, and is never cleared (not by sign-out, not by forgetting a
  server), so a referrer can't re-apply later.
- **Extraction:** exactly one non-blank `chitchat_server` (URL-decoded, the referrer at most 2,048 characters), then
  `ServerAddress.parse` as for typed input. An invalid or missing value is always silent: never offered, shown, logged
  or kept (no G line).
- **Data (TL):** only `getInstallReferrer()` is read; the click and install timestamps, install version and instant flag
  are never touched. Only the parsed host is kept, in memory, plus the flag. No request is made.
- **Package visibility:** the manifest's `<queries>` names `com.android.vending` only, so the referrer service bind works
  on API 30 and later (no `QUERY_ALL_PACKAGES`). The real path is confirmed on the owner's phone: a fresh install from a
  Play test-track link carrying `chitchat_server` arrives filled.
- **Licence:** the client is under the Android Software Development Kit License (owner decision 2026-11-24; see
  `docs/third-party-licenses.md`).

## Tests

- `ServerLinkTest` (JVM): extraction only, parse's verdict, canonical ASCII hosts, take once.
- `LinkEntryTest`: a cold link, a link while running and a link from a foreign task each give one MainActivity in the
  app's task, with the link handed over once; a restored activity replays nothing.
- `ServerPrefillTest`: A to G with their asserts (no request, typed text and the saved server unchanged, no stacking,
  window focus, an IDN filled as ASCII, an invalid value never shown) and the `prefill-*` captures (16, collected into
  `prefill-presentation`). P2: a valid referrer answering late fills step 2 after Welcome rendered (its capture is
  byte-identical to `prefill-step2-light-default`), an invalid one is silent (byte-identical to a plain step 2), a saved
  server means no read, and the real client is definitive without Play (CI).
- `InstallReferrerTest` (JVM): extraction, parse's verdict, the redacted answer, and the bounded read: once per
  process, definitive versus later, the never-cleared flag, never after sign-in or a saved server (also on arrival), a
  link afterwards replacing it.
