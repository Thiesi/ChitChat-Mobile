package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal class MfaStatusReply(val mfa: MfaStatus)

@Serializable
internal class MfaStatus(val enabled: Boolean, @SerialName("recovery_codes_remaining") val remaining: Int)

@Serializable
internal class IdentityMethodsReply(@SerialName("has_password") val hasPassword: Boolean)

@Serializable
internal class StepUpPasswordRequest(val password: String)

@Serializable
internal class StepUpStatus(
    val active: Boolean,
    val method: String?,
    @SerialName("verified_at") val verifiedAt: String?,
    @SerialName("expires_at") val expiresAt: String?,
    @SerialName("max_age_seconds") val maxAgeSeconds: Int,
)

@Serializable
internal class PasswordStepUpReply(@SerialName("privileged_step_up") val status: StepUpStatus)

@Serializable
internal class StepUpSecurity(@SerialName("privileged_step_up") val status: StepUpStatus)

@Serializable
internal class RecoveryStepUpReply(val security: StepUpSecurity, @SerialName("recovery_codes_remaining") val remaining: Int)

@Serializable
internal class ExportReply(val filename: String, val export: JsonObject)
