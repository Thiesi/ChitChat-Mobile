package io.github.thiesi.chitchat.feature.auth

import org.junit.Assert.*
import org.junit.Test

class RegistrationValidationTest {
    private val password = "a sufficiently long phrase"
    @Test fun usernamesFollowAsciiLengthAndFirstCharacterRules() {
        for (name in listOf("ab", "_Alice", "Älice", "a".repeat(33)))
            assertEquals(RegistrationProblem.USERNAME, registrationProblem(name, password, password))
        assertNull(registrationProblem(" Alice-42.x ", password, password))
    }
    @Test fun confirmationMustMatchWithoutTrimmingPassword() {
        assertEquals(RegistrationProblem.CONFIRMATION, registrationProblem("Alice", password, password + " "))
        assertNull(registrationProblem("Alice", "  $password  ", "  $password  "))
    }
    @Test fun passwordPolicyRejectsControlCharactersUsernameAndInvalidLengths() {
        for (value in listOf("too short", "a".repeat(4097), "a sufficiently long ALICE phrase", password + '\u0000'))
            assertEquals(RegistrationProblem.PASSWORD, registrationProblem("Alice", value, value))
    }
    @Test fun passwordLengthCountsUnicodeCodePointsNotUtf16Units() {
        assertNull(registrationProblem("Alice", "😀".repeat(12), "😀".repeat(12)))
        assertEquals(RegistrationProblem.PASSWORD, registrationProblem("Alice", "😀".repeat(6), "😀".repeat(6)))
    }
}
