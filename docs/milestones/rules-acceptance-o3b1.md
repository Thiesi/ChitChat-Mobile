# Rules acceptance O3b1: protocol and state

Protocol and local state for community-rules acceptance: released ChitChat v3.10.0
[docs/api/policies.md](https://github.com/Thiesi/ChitChat/blob/b2791a0/docs/api/policies.md) and
[play-readiness.md](../design/play-readiness.md) §1 (P-170, P-171). No UI here.
- O3b2 adds the guidelines screen and the Create-form consent line.
- O3b3 adds re-acceptance and the `403 rules_acceptance_required` handling on every gated write.
- Guests' acceptance is M7.

## Account status

`user.rules {accepted_version, accepted_at, acceptance_required}` on the session, login and registration users becomes
`SessionState.rules`.
- **Decoding** reads it from the raw body and attaches it beside the reply, so it's never part of `SessionUser`'s
  identity.
- **It's strict:** `accepted_version` is a non-negative JSON integer, `acceptance_required` a JSON boolean, and
  `accepted_at` a string or null.
- **Missing or malformed means unknown (null), never "required".**

## Accepting

`SessionRepository.acceptRules(version, expected)` makes one CSRF `POST policies/accept.php {"version":N}` for the
version that was shown, through the rendered session's access.
- **Accepted:** the confirmed status is published into that same session.
- **Outdated** (`409 rules_version_outdated`): re-read, then a new choice.
- **Unknown** (network, timeout, 5xx): it may have committed. A session GET settles `acceptance_required`, never a
  resend.
- **Failed:** any other refusal, typed.

Nothing is retried.

## Registration

`register.php` carries `accepted_rules_version` **only** when the session the form rendered with publishes
`policies.rules` (that session's version); otherwise the field is omitted.
- **`409 rules_version_outdated`:** the account wasn't created. It isn't marked as possibly created, the ordinary
  session refresh re-reads the new version, and nothing is resubmitted.
- **`400 rules_acceptance_required`:** the existing typed refusal. O3b2 adds its notice.

## The local guidelines acceptance (P-170)

`GuidelinesAcceptanceStore` records that this account accepted the **app's own** guidelines on this device.
- **What it keeps:** only the minimum: the server origin, the account ID and the guidelines version
  (`APP_GUIDELINES_VERSION`, now 1); presence means accepted. No names, text or tokens. Malformed entries are dropped
  on read.
- **Where it lives:** in the existing `settings` Preferences DataStore. The manifest excludes it from backup and device
  transfer, like all app data (`allowBackup="false"`, `fullBackupContent="false"`, and `data_extraction_rules.xml`
  excludes every domain for cloud backup and device transfer).
- **Coverage:** per server and account, at least the current version; a higher version asks again.
- **Sign-out keeps it,** per the spec ("already agreed on this device").
- **Forgetting the server removes that server's records,** for every account, and only that server's.

## Verification

- `SessionRepositoryTest` (+7):
  - login and bootstrap carry the status, and anonymous has none;
  - a missing or malformed status is unknown;
  - accept posts the shown version once (path, body, CSRF) and publishes the confirmed status;
  - 409, 503 and 403 stay typed with exactly one POST each, never resent;
  - registration sends the rendered version only when rules are published;
  - a registration 409 re-reads the session without resubmitting and isn't marked as possibly created.
- `GuidelinesAcceptanceTest` (5): the minimal entry, malformed entries dropped, per-server and per-account coverage
  with version, replacement, and forget-server removal.
