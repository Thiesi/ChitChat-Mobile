# Account deletion D1: protocol and state

Protocol and state for deleting the account from the app, with no UI. It follows play-readiness.md §2 (P-172) and the
released ChitChat v3.10.0 contract (`docs/api/account.md` "Account closure", `public/api/v1/account/close.php`,
`AccountClosureService::request`, read-only). The API calls it closure; the app says "Delete account". D2 adds the screens.

## The contract

- **The request:** `POST account/close.php` with CSRF, an authenticated session and `requirePrivilegedStepUp`, so the
  existing `withStepUp` applies.
- **A 200:** `{"closure":{"state":"closure_pending","requested_at","finalizes_at","cooling_off_days","session_version"}}`.
  The server atomically:
  - makes the account `closure_pending` and increments its session version;
  - deletes global roles, presence and SSE leases;
  - fires `forced_logout`;
  - then destroys the requesting session.
- **Push data goes immediately:** trigger `users_invalidate_registered_logins` (migration 0051) deletes the account's
  `push_subscriptions` whenever `session_version` or `account_state` changes, and finalization's tombstone trigger
  (0017) deletes them again.
- **Refusals:** `409 account_not_active` and `409 last_super_admin`. Close has no rate limiter of its own (the service
  limits only restore), so a 429 never reached PHP.

## Outcomes (`ClosureResult`)

- **Closed(finalizesAt):** only a 200 with `state == "closure_pending"` and a readable `finalizes_at`. A 200 without
  them may have committed, so it's Unknown.
- **LastSuperAdmin, NotActive, RateLimited:** definite.
- **Unknown:** a lost connection, a timeout, any 5xx (a proxy 502, 503 or 504 can follow a commit), a malformed reply or
  a redirect. It's never resent; only the explicit Check resolves it.
- **Step-up:** only a live session's `403 step_up_required` is thrown, for `withStepUp` (`requestClosure`: one prompt,
  one retry). A verification failure is Failed, never Unknown, because nothing was sent.

## The forced_logout race (TL C1)

The server fires `forced_logout` before it destroys the session and before the 200 arrives. So the app's own SSE, or
another request meeting the destroyed session, can end the session while the POST is still in flight.
- **An app-scoped marker:** `ClosureMarker` (origin, account ID, attempt; no session secret) is set before the POST,
  on the repository, not a runtime or a generation.
- **Waiting:** while it's set, an ended session for that origin and account carries `ClosureState.Waiting`, not plain
  sign-in.
- **Only the deletion survives:** closing a retired runtime's HTTP client would cancel the POST itself. So while the
  marker matches, `SessionHttp.retireExcept()` retires the jar, cancels every other queued and running call (the event
  stream, GETs, sends, uploads) and refuses any new request. Only the tagged deletion call continues, and the client's
  final shutdown (executor and pool) waits for it to settle (TL, #296).
- **The result lands on the marker,** even after its generation retired:
  - Closed becomes `Scheduled`, the confirmation;
  - a definite refusal becomes `EndedWithRefusal`, the ordinary ended-session outcome plus the refusal line;
  - Unknown stays `Unknown`, even when a `forced_logout` was seen, because an administrator can force a logout too.
- **Cancellation:** a POST cancelled after sending (genuine background or navigation, M2b2) is Unknown.

## After a confirmed deletion

The ended-session teardown runs with **no network**. The session is already gone, so there's no logout POST and no push
revoke:
- the vault is cleared;
- this phone's push record is retired (the distributor unregister stays fire-and-forget);
- account-scoped memory goes with the generation;
- the local guidelines acceptance is kept, as on sign-out (restore brings back the same account).

After a later restore, nothing is subscribed on the server or this phone, so push reads Off until it's turned on again.

## Check (TL C2)

`checkClosure()` is the only way out of Unknown, and it never resends.
- **With a live session:** the ordinary session read.
  - Still signed in as that account: `NotClosed` ("Your account wasn't deleted"), and the session continues.
  - An anonymous reply or a 401 ends the session (the teardown runs then) and gives `LikelyClosed`.
  - A failed read (network, 5xx) stays `Unknown`, with Check still offered.
- **When the session already ended:** an anonymous session read for that server gives `LikelyClosed`; a failure stays
  `Unknown`. This path is **reachability only, not evidence of the closure**: with the session gone, an anonymous read
  can't tell a deletion from any other ended session. That's acceptable only because the copy is hedged ("most
  likely"), as play-readiness §2 requires (TL, #296).
- **Dates:** the device clock is used only for D2's labelled "about" date on `LikelyClosed`.
- **Corrected in D2 (#300, TL):**
  - **The ended-session read** (`connect()`) replaces the session state, closure included. The original code put the
    outcome back only after a normal return, so a Check cancelled mid-read lost the Unknown silently. It also wrote the
    Unknown into **another server's** state when a different server was chosen meanwhile.
  - **Now** the outcome is restored in a non-cancellable `finally`, and only into this server's state, never another
    account's or over an outcome set meanwhile. It's `LikelyClosed` only after a completed anonymous read, otherwise
    the same `Unknown`.
  - **The live branch** never replaces the state, so a cancelled read keeps the Unknown there.
  - **Tests:** `SessionRepositoryTest` `anEndedCheckCancelledMidReadKeepsTheUnknown`,
    `anEndedCheckWhoseReadFailsStaysUnknown`, `anotherServerChosenDuringAnEndedCheckGetsNothingRestored` and
    `aLiveCheckCancelledMidReadKeepsTheUnknown`.

## Memory only (TL C4)

`SessionState.closure` is fenced to its origin and account and never persisted. `dismissClosure()` (Done) clears it, a
fresh connection drops it, and process death loses it (the app then lands on onboarding).

## Tests

- **`SessionRepositoryTest`:**
  - a confirmed deletion tearing down with no network;
  - `forced_logout` before the 200;
  - the forced logout cancelling a parallel GET on the same runtime while the deletion lands, and refusing a new
    request on the retired client;
  - a parallel 401 before the 200;
  - `forced_logout` followed by a lost (502) response;
  - a definite refusal after the session ended;
  - refusals on a live session, including a 429 that's definite;
  - step-up only on a 403, with one retry;
  - a 200 without a deadline counting as Unknown;
  - Check still signed in, Check signed out, a failed Check;
  - the fence.
- **`PushRegistrarTest`:** after a deletion and a restore, nothing reads On.
