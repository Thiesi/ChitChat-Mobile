package io.github.thiesi.chitchat.core.session

/**
 * #323: one server rate-limit policy that a call site opts into with `authorized(access, budget = …)`. Its own
 * `429 rate_limited` then gates only that policy, locally and in memory, for the server's wait; the session and every
 * other action carry on. Each value is exactly one server policy (ChitChat v3.10.0, b2791a0), never broader: a missed
 * local gate only costs one more server 429, while over-gating would refuse what the server allows.
 *
 * Every limiter refusal comes from `src/Http/RateLimiter.php:108–114` (`429 rate_limited`, `retry_after_seconds` >= 1).
 * Each endpoint below consumes its policy before any write work, so a write's `rate_limited` 429 is definite.
 */
enum class RateBudget {
    /** `message_report`: `public/api/v1/reports/message.php:24`, before ReportService. */
    MESSAGE_REPORT,

    /** `user_report`: `public/api/v1/reports/user.php:24`, before UserReportService. */
    USER_REPORT,

    /**
     * `push_subscription_management`: `public/api/v1/push/update-preferences.php:23–24` and
     * `public/api/v1/push/revoke-device.php:23–24`, each before the service. Not `push/subscribe.php`, which can refuse
     * under `push_subscription_refresh` (`subscribe.php:23–24`) or this policy (`PushSubscriptionService.php:48/79`), so it
     * stays unmapped.
     */
    PUSH_SUBSCRIPTION_MANAGEMENT,

    /** `personal_data_export`: `public/api/v1/account/export.php:24–25`, before PersonalDataExportService. */
    PERSONAL_DATA_EXPORT,

    /** `attachment_upload`: `public/api/v1/attachments/upload.php:25–26`, before AttachmentService (room uploads). */
    ATTACHMENT_UPLOAD,

    /**
     * `direct_message_attachment_upload`: `public/api/v1/direct-messages/attachments/upload.php:25–26`, before
     * DirectMessageAttachmentService.
     */
    DIRECT_MESSAGE_ATTACHMENT_UPLOAD,

    /**
     * `privileged_step_up`: `public/api/v1/step-up.php:24` via `src/Auth/PrivilegedStepUpService.php:37`, after only an MFA
     * read and before the credential check.
     */
    PRIVILEGED_STEP_UP,

    /**
     * `mfa_recovery`: `public/api/v1/mfa/step-up-recovery.php:22` via `src/Auth/MfaService.php:263`, before its
     * transaction (:269).
     */
    MFA_RECOVERY,

    /**
     * `recovery_key_management`: `public/api/v1/account/recovery-key.php:22` via `src/Auth/RecoveryKeyService.php:27`,
     * before its transaction (:28).
     */
    RECOVERY_KEY_MANAGEMENT,
}

/** A policy limiter's own refusal from the server: exactly `429 rate_limited`, never a local gate or a proxy's 429. */
internal fun io.github.thiesi.chitchat.core.network.ApiFailure.isServerRateBudget() =
    status == 429 && code == "rate_limited" && !localGate
