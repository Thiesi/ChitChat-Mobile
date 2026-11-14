package io.github.thiesi.chitchat.feature.rooms

import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.account.withStepUp
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomOwnershipOperations
import io.github.thiesi.chitchat.core.rooms.RoomTransferMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Who can take over: still loading, loaded, a network/server failure (Try again) or hidden after a 403. */
enum class TransferLoad { LOADING, LOADED, FAILED, HIDDEN }

/** P-217's outcome lines. UNKNOWN is a transfer that may have committed: only Check room resolves it. */
enum class TransferOutcome { SUCCESS, ALREADY_OWNER, NOT_ELIGIBLE, NOT_MEMBER, CHANGED, FORBIDDEN, UNKNOWN }

/**
 * Room ownership transfer state for one room's detail dialog (P-217). Memory only; usernames never reach a string
 * representation. [ownerActing] is decided from the room's own `member_role`, never guessed.
 */
data class RoomTransferUi(
    val roomId: Long, val roomName: String, val ownerActing: Boolean,
    val load: TransferLoad = TransferLoad.LOADING,
    val owner: RoomTransferMember? = null, val candidates: List<RoomTransferMember> = emptyList(),
    val pickerOpen: Boolean = false, val filter: String = "",
    val chosen: RoomTransferMember? = null, val confirming: Boolean = false, val posting: Boolean = false,
    val methods: VerificationMethods? = null, val verificationBusy: Boolean = false,
    /** A definite failure that changed nothing (wrong password, rate limit, an unlisted refusal): the choice is kept. */
    val error: ApiFailure? = null,
    val outcome: TransferOutcome? = null, val outcomeName: String? = null, val checking: Boolean = false,
) { override fun toString() = "RoomTransferUi" }

/** Global room-management roles (P-217): they and the room's owner may load the snapshot. Never inferred. */
internal val ROOM_MANAGER_ROLES = setOf("super_admin", "admin", "chat_admin")

/**
 * One room's transfer flow, owned by [RoomsViewModel]. Every request is bound to the identity and attempt that
 * started it: a retired flow's late result never reaches a successor. The POST is never resent; a transfer whose
 * reply was lost is UNKNOWN until an explicit re-read (it commits before the response).
 */
internal class RoomTransferFlow(
    private val scope: CoroutineScope,
    private val ownership: RoomOwnershipOperations,
    private val account: AccountOperations,
    private val identity: () -> Any?,
    /** Re-read the room detail and the directory; returns the fresh room, or null when it's gone. */
    private val reread: suspend (Long) -> RoomInfo?,
    /** The room answered 404 room_not_found: the existing room-unavailable path. */
    private val unavailable: (Long, ApiFailure) -> Unit,
    /** Show the room the transfer returned. */
    private val adopt: (RoomInfo) -> Unit,
) {
    private val mutable = MutableStateFlow<RoomTransferUi?>(null)
    val state = mutable.asStateFlow()
    private var sequence = 0L
    private var job: Job? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var access: AccountAccess? = null
    private var verification: Job? = null
    /**
     * Rooms whose transfer may have committed without an answer, for this identity. It outlives [retire] (leaving
     * mid-POST, background), so the next open forces Check room before any new choice.
     */
    private val unknownRooms = mutableSetOf<Pair<Any, Long>>()

    /** Opens the flow for a room whose viewer may manage it; ordinary members never trigger the snapshot GET. */
    fun open(room: RoomInfo, roles: List<String>) {
        val current = mutable.value
        if (current != null && current.roomId == room.id) return
        retire()
        if (room.memberRole != "owner" && roles.none { it in ROOM_MANAGER_ROLES }) return
        val who = identity()
        unknownRooms.removeAll { it.first != who } // Another account's markers never apply.
        val unknown = who != null && (who to room.id) in unknownRooms
        mutable.value = RoomTransferUi(room.id, room.name, ownerActing = room.memberRole == "owner",
            outcome = if (unknown) TransferOutcome.UNKNOWN else null)
        load()
    }

    /** Dialog closed, navigation, background or another account: drop everything and fence late results. */
    fun retire() {
        sequence++; job?.cancel(); job = null
        verification?.cancel(); verification = null; gate?.cancel(); gate = null; access = null
        mutable.value = null
    }

    fun retry() { if (mutable.value?.load == TransferLoad.FAILED) load() }

    private fun load(keepPicker: Boolean = false) {
        val start = mutable.value ?: return
        val attempt = ++sequence
        val expected = identity()
        mutable.value = start.copy(load = if (keepPicker) start.load else TransferLoad.LOADING, checking = keepPicker)
        job = scope.launch {
            try {
                val snapshot = ownership.transferSnapshot(start.roomId)
                if (!current(attempt, expected)) return@launch
                mutable.value = mutable.value?.copy(load = TransferLoad.LOADED, owner = snapshot.owner,
                    candidates = snapshot.candidates, checking = false,
                    // A picker on a list with nobody left closes back to the dialog's "No one…" line.
                    pickerOpen = keepPicker && mutable.value?.pickerOpen == true && snapshot.candidates.isNotEmpty())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (!current(attempt, expected)) return@launch
                when {
                    failure.status == 404 && failure.code == "room_not_found" -> { val id = start.roomId; retire(); unavailable(id, failure) }
                    failure.status == 403 -> {
                        // No longer manages the room (handed on meanwhile, or a stale role): no row, one room re-read.
                        mutable.value = mutable.value?.copy(load = TransferLoad.HIDDEN, pickerOpen = false, checking = false)
                        reread(start.roomId)
                    }
                    else -> mutable.value = mutable.value?.copy(load = TransferLoad.FAILED, pickerOpen = false, checking = false)
                }
            }
        }
    }

    fun openPicker() {
        val value = mutable.value ?: return
        // A possible commit must be checked first: no new choice while the outcome is unknown.
        if (value.load == TransferLoad.LOADED && value.candidates.isNotEmpty() && !value.posting &&
            value.outcome != TransferOutcome.UNKNOWN)
            mutable.value = value.copy(pickerOpen = true, filter = "", outcome = null, outcomeName = null)
    }

    /** Back or Cancel in the picker returns to the dialog; never while a transfer may be in flight. */
    fun closePicker() {
        val value = mutable.value ?: return
        if (value.posting || value.outcome == TransferOutcome.UNKNOWN) return
        mutable.value = value.copy(pickerOpen = false, chosen = null, confirming = false, outcome = null, outcomeName = null)
    }

    fun filter(text: String) { mutable.value?.let { if (it.pickerOpen) mutable.value = it.copy(filter = text) } }

    fun choose(member: RoomTransferMember) {
        val value = mutable.value ?: return
        if (!value.pickerOpen || value.posting || value.outcome == TransferOutcome.UNKNOWN || value.candidates.none { it === member }) return
        mutable.value = value.copy(chosen = member, confirming = true)
    }

    fun cancelConfirm() { mutable.value?.let { if (!it.posting) mutable.value = it.copy(confirming = false) } }

    /** Make owner: one POST through withStepUp (one retry only after a confirmed step-up), never resent. */
    fun confirm() {
        val value = mutable.value ?: return
        val target = value.chosen ?: return
        if (!value.confirming || value.posting) return
        val attempt = ++sequence
        val expected = identity()
        mutable.value = value.copy(confirming = false, posting = true, outcome = null, outcomeName = null, error = null)
        job = scope.launch {
            // True only while a POST's result is open: a definite refusal (step-up included) committed nothing.
            var uncertain = false
            try {
                val room = withStepUp({
                    uncertain = true
                    try { ownership.transferOwnership(value.roomId, target.id) }
                    catch (failure: ApiFailure) { if (!ambiguous(failure)) uncertain = false; throw failure }
                }, { awaitVerification(attempt, expected) })
                if (!current(attempt, expected)) return@launch
                val acted = mutable.value ?: return@launch
                mutable.value = acted.copy(posting = false, pickerOpen = false, chosen = null, methods = null,
                    outcome = TransferOutcome.SUCCESS, outcomeName = target.username)
                adopt(room)
                // Never assume the result: re-read the room and directory, then who can take over now.
                val fresh = reread(value.roomId)
                if (!current(attempt, expected)) return@launch
                if (fresh != null && (fresh.memberRole == "owner" || !acted.ownerActing)) load()
                else mutable.value = mutable.value?.copy(load = TransferLoad.HIDDEN)
            } catch (cancelled: CancellationException) {
                // Leaving mid-POST can't undo a committed transfer: remember it, so the next open asks for Check room.
                if (uncertain && expected != null) unknownRooms += expected to value.roomId
                throw cancelled
            } catch (failure: ApiFailure) {
                if (!current(attempt, expected)) return@launch
                refused(failure, target, uncertain)
            }
        }
    }

    private suspend fun refused(failure: ApiFailure, target: RoomTransferMember, uncertain: Boolean) {
        val value = mutable.value ?: return
        val outcome = when {
            failure.status == 409 && failure.code == "already_owner" -> TransferOutcome.ALREADY_OWNER
            failure.status == 409 && failure.code == "transfer_target_not_eligible" -> TransferOutcome.NOT_ELIGIBLE
            failure.status == 404 && failure.code == "membership_not_found" -> TransferOutcome.NOT_MEMBER
            failure.status == 409 && failure.code == "ownership_changed" -> TransferOutcome.CHANGED
            failure.status == 403 && failure.code == "forbidden" -> TransferOutcome.FORBIDDEN
            failure.status == 404 && failure.code == "room_not_found" -> { val id = value.roomId; retire(); unavailable(id, failure); return }
            // A POST without a definite answer (or an unusable reply) may have committed: unknown, never a blind retry.
            uncertain -> TransferOutcome.UNKNOWN
            // Verification failed, or a definite refusal: nothing happened, the choice is kept.
            else -> null
        }
        mutable.value = value.copy(posting = false, methods = null, verificationBusy = false,
            error = if (outcome == null) failure else null, outcome = outcome, outcomeName = target.username)
        if (outcome == TransferOutcome.UNKNOWN) identity()?.let { unknownRooms += it to value.roomId }
        when (outcome) {
            TransferOutcome.ALREADY_OWNER, TransferOutcome.FORBIDDEN -> { reread(value.roomId); load(keepPicker = true) }
            TransferOutcome.NOT_MEMBER -> load(keepPicker = true)
            else -> Unit
        }
    }

    /** Check room after ownership_changed or an unknown outcome: one room GET, then the fresh list. */
    fun checkRoom() {
        val value = mutable.value ?: return
        if (value.outcome !in setOf(TransferOutcome.CHANGED, TransferOutcome.UNKNOWN) || value.checking) return
        val attempt = ++sequence
        val expected = identity()
        val unknown = value.outcome == TransferOutcome.UNKNOWN
        mutable.value = value.copy(checking = true)
        job = scope.launch {
            try {
                val fresh = reread(value.roomId)
                if (!current(attempt, expected)) return@launch
                if (unknown && expected != null) unknownRooms -= expected to value.roomId
                // After an unknown, the dialog shows the room's actual owner state; after a change, the picker reopens.
                mutable.value = mutable.value?.copy(checking = false, outcome = null, outcomeName = null, chosen = null,
                    pickerOpen = !unknown)
                if (fresh == null) return@launch
                if (fresh.memberRole == "owner" || !value.ownerActing) load(keepPicker = !unknown)
                else mutable.value = mutable.value?.copy(load = TransferLoad.HIDDEN, pickerOpen = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ApiFailure) {
                if (current(attempt, expected)) mutable.value = mutable.value?.copy(checking = false)
            }
        }
    }

    private suspend fun awaitVerification(attempt: Long, expected: Any?) {
        val current = account.open()
        val methods = current.verificationMethods()
        current.ensureCurrent()
        if (!current(attempt, expected)) throw CancellationException("Transfer retired")
        val waiting = CompletableDeferred<Unit>()
        gate = waiting; access = current
        mutable.value = mutable.value?.copy(methods = methods, error = null)
        waiting.await()
        current.ensureCurrent()
        if (!current(attempt, expected)) throw CancellationException("Transfer retired")
    }

    /** The shared step-up prompt's answer; credentials stay in the dialog and this call. */
    fun verify(secret: String, recovery: Boolean) {
        val value = mutable.value ?: return
        val current = access ?: return
        val waiting = gate ?: return
        val methods = value.methods ?: return
        if (value.verificationBusy || secret.isEmpty() ||
            (recovery && (!methods.mfaEnabled || methods.recoveryCodesRemaining == 0)) ||
            (!recovery && (methods.mfaEnabled || !methods.hasPassword))) return
        val attempt = sequence
        val expected = identity()
        mutable.value = value.copy(verificationBusy = true, error = null)
        verification = scope.launch {
            try {
                if (recovery) current.verifyRecovery(secret) else current.verifyPassword(secret)
                current.ensureCurrent()
                if (!current(attempt, expected)) return@launch
                mutable.value = mutable.value?.copy(methods = null, verificationBusy = false)
                waiting.complete(Unit)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (!current(attempt, expected)) return@launch
                if (failure.code == "step_up_invalid_credentials" || failure.code == "invalid_recovery_code")
                    mutable.value = mutable.value?.copy(error = failure, verificationBusy = false)
                else waiting.completeExceptionally(failure)
            }
        }
    }

    /** Cancel the step-up prompt: nothing was sent, so the picker keeps the choice. */
    fun cancelVerification() {
        val value = mutable.value ?: return
        if (value.methods == null || value.verificationBusy) return
        val attempt = ++sequence
        job?.cancel(); verification?.cancel(); gate?.cancel(); gate = null; access = null
        if (attempt == sequence) mutable.value = value.copy(posting = false, methods = null, error = null)
    }

    private fun current(attempt: Long, expected: Any?) = attempt == sequence && expected != null && identity() == expected

    private fun ambiguous(failure: ApiFailure) = failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT,
        FailureKind.TLS, FailureKind.REDIRECT, FailureKind.INCOMPATIBLE, FailureKind.TOO_LARGE) || (failure.status ?: 0) >= 500
}
