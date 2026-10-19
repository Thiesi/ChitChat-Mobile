# Add secure browser authentication handoff into native PHP sessions

Status: proposed backend contract, not implemented. Android dependency: [M6 account/provider integration](https://github.com/Thiesi/ChitChat-Mobile/blob/main/docs/implementation-plan.md).

Tracks [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1).

## User-facing requirement

The native app must support the installation's Google/Twitch sign-in and account creation, linking, provider reauthentication, account restoration and explicit profile-picture import. A provider-only account must not be stranded because the native HTTP session is separate from the browser.

## Existing behavior

At v3.2.1, [OidcService](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Auth/Oidc/OidcService.php#L25) stores state, PKCE and nonce in a browser PHP session. [callback.php](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/public/api/v1/oidc/callback.php#L17) redirects to fixed browser pages, including sign-up/MFA continuation, account and step-up completion. Custom Tabs do not share the app's OkHttp cookie jar. No result exchange exists in these routes. Existing provider verification, registration protection, MFA, bans and lockdown must remain authoritative.

## Proposed versioned API contract

Names below are proposals for backend review; Android must not call them before support ships.

1. `POST /api/v1/native-auth/start.php`, using the app's current anonymous/authenticated PHP cookie and CSRF: `{client_id,purpose,provider,code_challenge,code_challenge_method:"S256",redirect_uri}`. Purpose is an explicit allowlisted existing workflow. Server returns `{transaction_id,browser_url,expires_at}`. Generate a high-entropy browser authorization nonce; never expose an app session identifier in that URL. Bind authenticated purposes to the current account/version and require existing step-up where applicable.
2. Browser opens `browser_url` in Custom Tabs and performs provider verification under the existing authorization rules. Display server/account/purpose and require explicit completion, especially when a different browser account is already signed in. Keep OIDC's own state/PKCE/nonce separate from the native transaction verifier. For native-bound mutations, stage the verified result: do not commit linking, restoration, new-account creation, native elevation or picture availability merely because the browser callback completed. A completion redirect carries only opaque one-use `code` plus transaction state, never session cookies, provider tokens or reusable authorization.
3. `POST /api/v1/native-auth/exchange.php`, app cookie/CSRF plus `{transaction_id,code,code_verifier}`. Atomically consume a short-lived matching result, recheck current account/version/authorization and apply the approved purpose to the app's own session. Rotate session/CSRF as appropriate and return the existing authenticated or pending-MFA/sign-up state; establish any granted native step-up after rotation, since ordinary login clears elevation. Linking/picture import/step-up apply only to the bound native account. Provider authentication must not bypass that account's MFA. Picture bytes remain staged until the authorized native exchange makes them available through the existing import endpoint.

## Security and compatibility

Use an allowlisted client identity and exact redirect contract. For arbitrary self-hosted domains, an app-owned verified return domain or an app-specific redirect protected by PKCE needs an explicit distribution design; do not pretend arbitrary host App Links are automatically verified. Bind transaction to chosen server, initiating native session, client, verifier, purpose, account/version, and expiry. Prevent open redirects, session fixation, login CSRF/account substitution, wrong-account linkage, replay and result interception. Suppress result codes in logs/referrers; clear browser transaction state on completion/cancel. No browser-cookie extraction, Firebase Auth, embedded WebView login, or provider secret in APK.

Keep all existing web routes unchanged and disabled native clients rejected by default. Document cancellation, expired attempts, sign-up challenges, network transitions and reauthentication behavior.

## Acceptance criteria

- Real browser/native sign-in, provider-only sign-up, MFA continuation, linking, step-up, restoration and picture import complete into the intended native session.
- Wrong verifier/client/server/account/purpose/session, unapproved redirect, replay, expiry, tampering and browser account substitution cannot authenticate or mutate the native account.
- Cancellation leaves original actions unapplied and app state understandable; pending MFA never counts as authenticated.
- Browser-only flows and existing authorization/registration/lockdown tests remain green.
- Contract, client trust configuration and integration fixtures are documented; no fake exchange is reported as functional.
