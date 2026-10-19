package io.github.thiesi.chitchat.feature.auth

import java.util.Locale

internal enum class RegistrationProblem { USERNAME, PASSWORD, CONFIRMATION }

/** Local hints mirror v3.2.1; the server remains authoritative for validation/policy. */
internal fun registrationProblem(username: String, password: String, confirmation: String): RegistrationProblem? {
    val name = username.trim()
    if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,31}"))) return RegistrationProblem.USERNAME
    if (password.codePointCount(0, password.length) !in 12..4096 ||
        password.any { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 } ||
        password.lowercase(Locale.ROOT).contains(name.lowercase(Locale.ROOT))) return RegistrationProblem.PASSWORD
    return if (password != confirmation) RegistrationProblem.CONFIRMATION else null
}
