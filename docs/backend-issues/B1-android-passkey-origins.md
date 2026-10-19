# Support explicitly trusted Android passkey origins with the existing WebAuthn RP

Status: proposed backend change, not an existing contract. Depends on Android M6 in the [delivery plan](https://github.com/Thiesi/ChitChat-Mobile/blob/main/docs/implementation-plan.md).

Tracks [ChitChat-Mobile #1](https://github.com/Thiesi/ChitChat-Mobile/issues/1).

## User-facing requirement

People using passkey MFA must be able to complete password-first sign-in, privileged step-up, restoration, and passkey enrollment from the native Kotlin Android client. Recovery codes must remain emergency one-use credentials, not the normal native sign-in path.

## Existing behavior and evidence

Baseline v3.2.1 / `4f53ff497f5572072fddad42d8ac3979e9cdf0d1`. [WebAuthnProtocol::validateClientData](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Auth/WebAuthnProtocol.php#L188) compares the origin exactly to the configured web origin. [Config](https://github.com/Thiesi/ChitChat/blob/4f53ff497f5572072fddad42d8ac3979e9cdf0d1/src/Config.php#L269) supports the browser HTTPS/localhost origin, not Android app origins. Existing JSON MFA option/finish endpoints and PHP session/CSRF binding otherwise supply the required workflow. Closed hardening issue #81 concerns normalization/credential size, not native origin support.

[Android Credential Manager](https://developer.android.com/identity/passkeys/create-passkeys) generates `android:apk-key-hash:<base64url SHA-256 signing certificate>` origins. Ordinary apps cannot set the browser origin. [Digital Asset Links](https://developer.android.com/identity/credential-manager/prerequisites) are also required at the RP domain; publishing DAL alone does not fix server validation.

## Proposed contract

Add explicit operator-configured exact trusted Android origins alongside the existing exact web origin. Default native allowlist is empty. Reuse existing `mfa/*-options.php`, `mfa/*-finish.php`, and account enrollment routes and credential envelope; retain the installation RP ID and account-bound password-first MFA semantics. Publish native support as an additive capability, including supported client identities/registration instructions without exposing credential IDs or user handles. Package/signing identity will be confirmed by the Android maintainer, not invented here.

Document per-installation `/.well-known/assetlinks.json` with package and SHA-256 release certificate fingerprints, Play signing versus upload/debug certificates, explicit certificate rotation, and separate development trust. Scope origin permission to this RP and supported purposes; do not accept arbitrary Android hashes, claimed origins from request headers, or broad suffix matches.

## Security and compatibility

Keep challenge/session/purpose/account/version/source-IP binding, challenge single-use/expiry, RP-ID hash checks, UV/UP, algorithm/attestation limits, credential uniqueness and signature-counter protections. Preserve existing web credentials and exact web-origin rejection rules. No passwordless login, Firebase Authentication, wildcard origin, or weakened certificate verification. Multiple independently hosted servers remain independently configured/trusted.

## Acceptance criteria

- Real Android Credential Manager enrollment and assertion work for an explicitly trusted release certificate and the correct RP, including login, step-up and MFA restoration.
- Unlisted/debug certificates, wrong RP, wrong purpose/account/session, expired/replayed challenge, tampered origin and missing UV/UP fail without authenticating or consuming unrelated state.
- Existing browser MFA and recovery flows continue to pass; administrative-MFA policy remains enforced.
- Operator DAL/trust setup and signed-build certificate rotation are documented and covered by positive/negative fixtures.
- Android M6 remains blocked for these ceremonies until the contract and installation configuration are available; mock credentials are not acceptance evidence.
