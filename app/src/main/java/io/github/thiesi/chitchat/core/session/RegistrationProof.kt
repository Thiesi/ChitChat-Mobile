package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.RegistrationChallenge
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Challenge material is ephemeral and deliberately has no generated toString().
internal class RegistrationProof(val nonce: String?, val solution: String?)

internal suspend fun solveRegistration(challenge: RegistrationChallenge?): RegistrationProof {
    if (challenge == null) return RegistrationProof(null, null)
    if (!challenge.nonce.matches(Regex("[0-9a-f]{32}")) || challenge.bits !in 0..22) {
        throw ApiFailure(FailureKind.INCOMPATIBLE)
    }
    try {
        return withContext(Dispatchers.Default) {
            withTimeout(60_000) {
                val digest = MessageDigest.getInstance("SHA-256")
                var counter = 0L
                var proof: RegistrationProof? = null
                while (proof == null) {
                    if (counter % 256 == 0L) currentCoroutineContext().ensureActive()
                    val answer = counter.toString()
                    val hash = digest.digest("${challenge.nonce}:$answer".encodeToByteArray())
                    if (hasLeadingZeroBits(hash, challenge.bits)) proof = RegistrationProof(challenge.nonce, answer)
                    counter++
                }
                proof
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw ApiFailure(FailureKind.HTTP, 408, "registration_challenge_timeout")
    }
}

internal fun hasLeadingZeroBits(hash: ByteArray, bits: Int): Boolean {
    require(bits in 0..hash.size * 8)
    val whole = bits / 8
    if ((0 until whole).any { hash[it] != 0.toByte() }) return false
    val remaining = bits % 8
    return remaining == 0 || (hash[whole].toInt() and 255) ushr (8 - remaining) == 0
}
