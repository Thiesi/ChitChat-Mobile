# Proposed Android architecture

Status: recommended in discovery, not implemented. [Product decisions](discovery.md#consequential-decisions-for-the-maintainer) remain open. Backend source baseline and compatibility are recorded in [the contract](backend-compatibility.md).

## Structure and dependencies

Use Kotlin, Compose Material 3, a single activity, AndroidX navigation, ViewModels, coroutines, and Flow. Begin with one `:app` module and packages grouped by concrete responsibility:

```text
app/                 Application container, activity, lifecycle, navigation
core/network/        API DTOs, Retrofit/OkHttp, errors, URL policy
core/session/        Cookies, CSRF, secure storage, session state and generation
core/storage/        Room cache, encryption, DataStore settings
core/realtime/       SSE adapter, cursor transactions, reducer and reconciliation
core/notifications/  FCM service, device registrar, channels, routing
feature/server/      Address validation and compatibility
feature/auth/        Sign-in, registration, MFA, restoration, step-up
feature/chat/        Rooms, DMs, history, composer, media and user cards
feature/search/      Server-side search and result navigation
feature/account/     Privacy, preferences, account lifecycle, identities
feature/moderation/  Reports, in-place tools, queue
feature/admin/       Users, rooms, audit, settings, status and inspections
```

Dependencies should have a specific role: Retrofit/OkHttp for HTTP, OkHttp SSE for framed event delivery with explicit reconnect control, Kotlin serialization for DTOs, Room for transactional cache/indexes, DataStore for non-secret preferences, Coil with the authorized image client for previews/avatars, WorkManager for finite deferred jobs, Credential Manager for approved passkey flows, AndroidX Browser for Custom Tabs, and Firebase Messaging only for push. MockWebServer, coroutine tests, JUnit, and Compose UI testing cover their respective boundaries. Use a small constructor-injected application container initially; introduce a DI framework only if wiring warrants it. Do not add a domain layer full of pass-through use cases.

Pin stable compatible versions and Gradle wrapper checksum in M1 after validating current official compatibility tables. Proposed minimum is API 26; passkey UI must check API/provider support (Android guidance requires API 28+). Target/compile SDK follow the current supported release and distribution requirements at implementation time. A credential-free CI build must work without Firebase config; real push builds use explicitly supplied configuration and fail clearly if it is missing.

This follows Android's [architecture recommendations](https://developer.android.com/topic/architecture/recommendations): UI state flows from repositories through ViewModels, and user actions flow back through explicit methods. [Offline read guidance](https://developer.android.com/topic/architecture/data-layer/offline-first) supports a local source of truth without requiring unsafe automatic offline writes.

## UI, navigation, and accessibility

Phone navigation has Rooms, Messages, and Notifications destinations, plus account/settings. Search is available from the conversation list and conversation toolbar. A conversation has a native top app bar, an anchored message list, composer, reply/edit banner, attachment chip, and explicit send button. Expanded windows show list/detail panes. Room details provide membership, members, room policy and permitted management actions. User cards and message long-press menus expose authorized actions; every long-press action also has an accessible alternative. Administrative tools are native permission-aware screens.

Use stable message IDs for list keys; retain first-visible ID and pixel offset when prepending older pages. Auto-scroll only when already at the live edge or after the user's own acknowledged send. Otherwise show a new-message count/jump control. Unread markers derive from server read state; acknowledge reading only when foregrounded and the relevant content is visible. Do not confuse notification read state with conversation read state. DM read marks the conversation rather than an arbitrary visible message ID: document and match that existing behavior.

Multiline input uses explicit send by default; respect IME insets, focus restoration, rotation, hardware keyboards, selection, clipboard and TalkBack. Drafts and scroll state are account/conversation scoped. Color and typography use original ChitChat identity adapted to Material 3, with System/Light/Dark and the six existing color choices, including contrast. Honor account date locale/hour-cycle while following device timezone. Provide touch targets of at least 48dp, meaningful semantics, readable timestamps/reactions/attachment labels, large-font reflow, reduced motion, and no color-only status cues.

Each screen specifies loading, empty, content, stale/offline, retryable error, and authorization-lost states. Guest expiry, muted/read-only rooms, generic DM unavailability, lockdown, and rate limiting need understandable explanations. Preserve typed drafts on transient failure. Never make a toast the only record of a failed send or an account operation.

## Network and session security

Canonicalize user-entered HTTPS URLs with an explicit origin and deployment base-path policy. The current web deployment uses root `/api/v1/*.php`; do not advertise arbitrary subpath hosting until tested. Reject URL user-info, fragments and arbitrary API-path input; confirm any host-changing redirect before credentials are sent. Use normal platform TLS verification, no trust-all code, and no globally enabled cleartext. Debug-only local HTTP may be added narrowly for tests; a self-signed server must be configured with a trusted certificate rather than a bypass.

One session-scoped OkHttp client owns the cookie jar and CSRF interceptor. Preserve actual Set-Cookie names and attributes; do not hardcode `PHPSESSID`. Send secrets only to the selected origin and intended paths, including for image/download requests. Disable sensitive HTTP body logging and HTTP disk caching of private responses (`Cache-Control: no-store` is the server default). Treat exported/shared files as explicit user actions through SAF/FileProvider with temporary URI grants; never route a privileged API URL into an external downloader.

Keep passwords/recovery codes only for the active request/UI and clear them afterward. Encrypt saved authenticated cookies with an Android Keystore AES-GCM key in app-private no-backup storage; encrypted local session policy never extends server validity. Rebootstrap on cold start before revealing cached private content. Proposed bounded client retention: expire saved authenticated credentials after seven days of inactivity or 30 days since full login, whichever comes first; the server may invalidate earlier. Anonymous and guest credentials remain transient. This bounded persistence is an Android policy for the server's lifetime-zero cookie, not a new server refresh mechanism.

Model `NoServer`, `Bootstrapping`, `Anonymous`, `PendingMfa`, `Authenticated`, `Guest`, `Reauthenticating`, and `Invalidated` explicitly. CSRF and account identity changes are atomic. `202 mfa_required` is pending authentication; never publish an authenticated state early. On invalidation, cancel every scoped request/job/SSE before clearing cookies, CSRF, keys/content cache, drafts, cursors, notifications and back stack. Increment a local generation and discard late results from older generations. A resource 403 removes unauthorized resource state; it does not always mean global logout. An authenticated 401 or `forced_logout` invalidates the session. Parse error code as well as status; respect `step_up_required`, `passkey_step_up_required`, `muted`, `maintenance_lockdown`, and 429.

No guessed refresh-token, bearer-auth, native-provider or push endpoint. [Backend proposals](backend-dependencies.md) stay behind explicit unsupported-capability states until a compatible server ships them.

## Cache and message consistency

Use a separate local namespace/database per canonical server/account and a generation-scoped encryption key. Store sensitive body/caption/draft/file metadata as encrypted fields; keep keys in no-backup storage. Room indexes can hold IDs/timestamps needed for ordering, not plaintext full-text message content. No local message search or analytics pipeline; search remains the server's authorization-aware POST. Use an encrypted, bounded cache (proposed seven-day/500-message-per-conversation maximum); purge on logout, invalidation, account switch, server deletion and cache expiry. Guest content stays memory-only. Disk image caching for authenticated media must be disabled or equally encrypted and purged.

Local cache is a view of server-authorized data, not proof of current authorization. Cold starts require revalidation before revealing it. Existing foreground data can remain visible during a temporary connection outage with a clear stale state. Refresh visible pages/mutations and clear inaccessible messages/media after retention or permission changes. Do not infer deletion from absence on just the newest page; walk to a known boundary or invalidate/refetch that cache segment. Never join unrelated ranges into an apparently continuous transcript.

Server IDs define order within each message kind; room and DM IDs are separate namespaces. Distinguish optimistic local pending rows from acknowledged server rows. A send response and its SSE event must converge to one row. There is no idempotency key: on an ambiguous timeout mark the attempt `unknown`, reconcile history, and let the user explicitly decide to resend with duplicate-risk context. Do not retry a message/upload automatically or match two identical texts as proof of one send.

## Realtime and lifecycle

One connection per active authenticated app session, not one per screen. Process lifecycle starts/stops it; Compose screen lifecycle alone must not own it. Closing the app stops stream, presence and typing. No unrestricted permanent service or frequent background worker. On background transition send a best-effort presence heartbeat with `room_id:null`, then let the lease expire if the request cannot finish; never block backgrounding on it.

On foreground entry: revalidate the session, refresh room/conversation lists and notification counts, start/resume the stream, and reconcile active history, mutation state, attachments, read/relationship state and presence. Notifications, DM reads on other devices and block changes have no verified SSE event: also refresh on destination entry/focus and at a bounded foreground interval (proposed once per minute, with debouncing and backoff). Stop that interval in background/offline/invalidated states. Subscribe/buffer events while refreshing so the HTTP/SSE boundary cannot overwrite newer data. Serialize reductions and reconciliation per conversation; invalidate and refetch if response ordering cannot be proven. Persist event application and cursor in one cache transaction. A failed reduction must not advance the cursor. Unknown event types trigger safe invalidation and a bounded resync; malformed known events require explicit recovery, not silent acknowledgment.

Deduplicate persistent events by server/account/event ID. Handle nonpersistent `forced_logout` without ID or envelope. `Last-Event-ID` resumes ordered events but gaps are legal (other recipients, retention); do not expect contiguous IDs. Reconnect normal 25-second endings at the suggested interval; use bounded exponential jitter for failures and honor server retry/rate-limit guidance. Cancel retries when offline/backgrounded/invalidated. Replaying reactions uses current viewer IDs, not the actor's shared `reacted_by_me` flag. Presence events invalidate the authoritative list. Ignore expired typing signals; clear typing on arrival of the sender's message, room change, or backgrounding.

Foreground presence uses a fresh connection UUID per active app lifetime, ordinary renewals with `interacted:false`, deliberate user activity with `true`. Keep the server's warning/expiry behavior. Returning from background alone must not fake human activity. Membership survives inactivity; reenter explicitly when expired.

## Push and Android integration

`PushDeviceRegistrar` is a narrow integration seam: unsupported/unconfigured, pending registration, registered, revoked, or failed. It consumes current session identity and token rotation, and must not claim success until the backend acknowledges. `FirebaseMessagingService` receives tokens/data, validates routing, deduplicates notification IDs, and schedules only short finite follow-up work. Registration/revocation jobs carry account generation and cannot replay under a different user. Enable Firebase auto-init only after an explicit push choice; no Analytics dependency or Firebase Authentication.

Prefer content-free data payloads with opaque registration/account binding and server-issued notification identifiers. Server payloads are hints; taps reauthorize on the server. Use explicit immutable PendingIntents, distinct request identities, native back stacks and allowlisted routes; reject unknown hosts/accounts and arbitrary browser links. Create channels for participation and security/policy events; Android user settings can always silence them even if server security categories have no per-account opt-out. Default lock-screen public version is generic; no message contents or moderation reasons. Foreground delivery refreshes current state without a duplicate alert for the visible conversation.

On logout revoke registration while authenticated when possible, cancel OS notifications, clear local bindings, and delete/rotate the FCM token on this single-account installation. Offline logout clears locally immediately; server-side bounded registration expiry and session-version binding are required to stop stale delivery. No private payload can be displayed after local logout. Devices without compatible Play services retain ordinary foreground chat with a visible push-unavailable state; no permanent background connection is substituted. See [Firebase setup](firebase-setup.md).
