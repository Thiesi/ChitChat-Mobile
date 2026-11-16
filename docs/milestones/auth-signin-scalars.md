# AuthJourneyTest sign-in scalars (#275, #262)

Test-only, failure-only diagnostics for two intermittent API36 failures around sign-in in `AuthJourneyTest`. Neither
cause is named yet. Same IME-timing family, different moments (technical lead decision: diagnostics first, no
focus/IME or timing repair).

- **#275 (before submit):** `enterCredentials()` asserts "Sign in" is displayed after scrolling to it. On failure it
  rethrows the original assertion with:
  - a sample timestamp;
  - the node's bounds in root and in window, and the compose root's bounds in window;
  - the resumed window's visible display frame and decor size, separately timestamped;
  - the existing `NativeInputWindow` snapshot (imeVisible, imeBottom, focus, window focus, its own timestamp).
- **#262 (after submit):** every `openTab` right after sign-in goes through `shellTab()`. On timeout it rethrows with
  #262's existing scalars (phase, login count, tab, title and form counts), plus a sample timestamp, the visible frame
  and `NativeInputWindow`. #262's original block in `accountNavigationIsAuthenticatedAndRetiresBackStackOnLogout` is
  folded into the same helper; its fields are unchanged.

**Unchanged:** the same assertions, the same 5-second `openTab` wait, no retry, no added waits, no keyboard dismissal, no
text in the scalars. Successful runs are unchanged. A recurrence names the cause from these values. Both issues stay
open until then, and neither becomes a known signature.
