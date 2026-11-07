package io.github.thiesi.chitchat.feature.profile

import androidx.lifecycle.ViewModelStore
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.profile.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Operations()
    private val store = ViewModelStore()
    private lateinit var model: ProfileViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher); model = ProfileViewModel(api) { 100 }; store.put("profile",model)
        model.foreground()
    }
    @After fun cleanup() {
        model.background(); api.gates.values.forEach { it.complete(Unit) }; dispatcher.scheduler.runCurrent()
        store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain()
    }
    private fun open(user: Long = 8, room: Long? = 42, source: String = "room-entry") =
        checkNotNull(model.open(api.session.value,user,room,source))

    @Test fun originalSessionAndFrozenIdsReachTheSingleGet() = runTest(dispatcher) {
        val original = api.session.value; val choice = open(); runCurrent()
        assertSame(original,api.calls.single().first); assertSame(choice.request,api.calls.single().second)
        assertEquals(8L,model.state.value.snapshot!!.profile.id); assertEquals(42L,model.state.value.snapshot!!.roomId)
        assertFalse(model.state.value.loading); assertNull(model.state.value.error)
    }
    @Test fun refreshUsesNewReferenceAndOldCloseOrRefreshCannotAffectIt() = runTest(dispatcher) {
        val old=open(); runCurrent(); val fresh=checkNotNull(model.refresh(old)); runCurrent()
        assertNotSame(old,fresh); assertNotSame(old.request,fresh.request)
        model.close(old); assertNull(model.refresh(old)); runCurrent()
        assertSame(fresh,model.state.value.selection); assertEquals(2,api.calls.size)
    }
    @Test fun refreshImmediatelyRemovesOldFactsAndDoesNotDuplicateAnInFlightGet() = runTest(dispatcher) {
        val old=open(); runCurrent(); api.gates[8]=CompletableDeferred()
        val fresh=checkNotNull(model.refresh(old)); assertNull(model.state.value.snapshot); runCurrent()
        assertTrue(model.state.value.loading); assertNull(model.refresh(fresh)); model.foreground(); runCurrent()
        assertEquals(2,api.calls.size); api.gates.getValue(8).complete(Unit); runCurrent()
        assertFalse(model.state.value.loading); assertNotNull(model.state.value.snapshot)
    }
    @Test fun nonCooperativeOldSuccessCannotPublishIntoSuccessorOrClearItsLoading() = runTest(dispatcher) {
        api.ignoreCancellation=true; api.gates[8]=CompletableDeferred(); open(); runCurrent()
        api.gates[9]=CompletableDeferred(); val next=open(9); runCurrent()
        api.gates.getValue(8).complete(Unit); runCurrent()
        assertSame(next,model.state.value.selection); assertTrue(model.state.value.loading); assertNull(model.state.value.snapshot)
        api.gates.getValue(9).complete(Unit); runCurrent(); assertEquals(9L,model.state.value.snapshot!!.profile.id)
    }
    @Test fun nonCooperativeOldFailureAndFinallyCannotReplaceSuccessor() = runTest(dispatcher) {
        api.ignoreCancellation=true; api.gates[8]=CompletableDeferred(); api.failures[8]=ApiFailure(FailureKind.NETWORK)
        open(); runCurrent(); val next=open(9); runCurrent(); api.gates.getValue(8).complete(Unit); runCurrent()
        assertSame(next,model.state.value.selection); assertEquals(9L,model.state.value.snapshot!!.profile.id)
        assertNull(model.state.value.error)
    }
    @Test fun genuineBackgroundClearsPrivateFactsAndForegroundNeverReplaysGet() = runTest(dispatcher) {
        val old=open(); runCurrent(); model.background(); assertNull(model.state.value.selection); assertNull(model.state.value.snapshot)
        assertNull(model.refresh(old)); model.foreground(); runCurrent(); assertEquals(1,api.calls.size)
        assertNull(model.state.value.selection)
    }
    @Test fun repeatedForegroundForConfigurationRetainsOriginalInFlightGet() = runTest(dispatcher) {
        api.gates[8]=CompletableDeferred(); val choice=open(); runCurrent(); model.foreground(); model.foreground(); runCurrent()
        assertEquals(1,api.calls.size); assertSame(choice,model.state.value.selection)
        api.gates.getValue(8).complete(Unit); runCurrent(); assertNotNull(model.state.value.snapshot)
    }
    @Test fun logoutFencesButtonsBeforeTheSessionCollectorCanClearFacts() = runTest(dispatcher) {
        val choice=open(); runCurrent(); val original=api.session.value
        api.session.value=original.copy(phase=SessionPhase.SIGNED_OUT,user=null)
        assertFalse(choice.matches(api.session.value)); assertNull(model.refresh(choice))
        assertNull(model.open(original,9,null,"room-entry")); runCurrent(); assertNull(model.state.value.snapshot)
    }
    @Test fun serverAccountGenerationAndVersionChangesEachRetireTheSelection() = runTest(dispatcher) {
        val original=api.session.value
        val replacements=listOf(original.copy(generation=2),original.copy(user=original.user!!.copy(sessionVersion=2)),
            original.copy(user=original.user!!.copy(id=9)),original.copy(server=(ServerAddress.parse("other.example.test") as AddressResult.Valid).address))
        for (next in replacements) {
            api.session.value=original; runCurrent(); open(); runCurrent(); api.session.value=next; runCurrent()
            assertNull(model.state.value.selection); assertNull(model.state.value.snapshot)
        }
    }
    @Test fun ownerReplacementRetiresANonCooperativeInFlightGet() = runTest(dispatcher) {
        api.ignoreCancellation=true; api.gates[8]=CompletableDeferred(); open(); runCurrent()
        api.session.value=api.session.value.copy(generation=2); runCurrent(); api.gates.getValue(8).complete(Unit); runCurrent()
        assertNull(model.state.value.selection); assertNull(model.state.value.snapshot)
    }
    @Test fun missingProfileClearsFactsAndRequiresExplicitFreshChoice() = runTest(dispatcher) {
        val old=open(); runCurrent(); api.failures[8]=ApiFailure(FailureKind.HTTP,404,"user_not_found")
        val failed=checkNotNull(model.refresh(old)); runCurrent(); assertNull(model.state.value.snapshot)
        assertEquals(404,model.state.value.error!!.status); model.foreground()
        api.session.value=api.session.value.copy(shareTyping=true); runCurrent(); assertEquals(2,api.calls.size)
        api.failures.clear(); checkNotNull(model.refresh(failed)); runCurrent(); assertNotNull(model.state.value.snapshot)
    }
    @Test fun mismatchedAccountOrRoomFromAConsumerBoundaryCannotBePublished() = runTest(dispatcher) {
        api.wrongUser=true; open(); runCurrent(); assertNull(model.state.value.snapshot)
        assertEquals(FailureKind.INCOMPATIBLE,model.state.value.error!!.kind)
        api.wrongUser=false; api.wrongRoom=true; open(); runCurrent(); assertNull(model.state.value.snapshot)
        assertEquals(FailureKind.INCOMPATIBLE,model.state.value.error!!.kind)
    }
    @Test fun invalidTargetsGuestOrStaleOwnerCannotReplaceValidSelection() = runTest(dispatcher) {
        val choice=open(); runCurrent(); val original=api.session.value
        assertNull(model.open(original,0,null,"entry")); assertNull(model.open(original,8,0,"entry"))
        assertNull(model.open(original,8,null," ")); assertNull(model.open(original.copy(generation=2),8,null,"entry"))
        assertNull(model.open(original.copy(phase=SessionPhase.GUEST),8,null,"entry"))
        assertSame(choice,model.state.value.selection); assertEquals(1,api.calls.size)
    }
    @Test fun sessionBusyRefreshWarningOrCooldownCannotStartAGet() = runTest(dispatcher) {
        val original=api.session.value
        for (blocked in listOf(original.copy(busy=true),original.copy(refreshRequired=true),original.copy(storageWarning=true),original.copy(retryAtMillis=101))) {
            api.session.value=blocked; runCurrent(); assertNull(model.open(blocked,8,null,"entry"))
        }
        assertTrue(api.calls.isEmpty())
    }
    @Test fun closedOrClearedViewModelDoesNotRegainPrivateContentFromLateWork() = runTest(dispatcher) {
        api.ignoreCancellation=true; api.gates[8]=CompletableDeferred(); val choice=open(); runCurrent()
        store.clear(); api.gates.getValue(8).complete(Unit); runCurrent()
        assertNull(model.state.value.selection); assertNull(model.state.value.snapshot); assertNull(model.refresh(choice))
    }
    @Test fun privateNamesAreAbsentFromStateStringsAndInitialPreservesUnicode() = runTest(dispatcher) {
        open(); runCurrent(); assertFalse(model.state.value.toString().contains("Private profile"))
        assertFalse(model.state.value.selection.toString().contains("Viewer"))
        assertEquals("\uD83D\uDE42",profileInitial("  \uD83D\uDE42 Visitor")); assertEquals("?",profileInitial(" "))
    }

    private class Operations : ProfileOperations {
        override val session=MutableStateFlow(SessionState(server=(ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
            generation=1,phase=SessionPhase.AUTHENTICATED,user=SessionUser(7,"Viewer",listOf("member"),1,false)))
        val calls=mutableListOf<Pair<SessionState,ProfileRequest>>()
        val gates=mutableMapOf<Long,CompletableDeferred<Unit>>()
        val failures=mutableMapOf<Long,ApiFailure>()
        var ignoreCancellation=false; var wrongUser=false; var wrongRoom=false
        override suspend fun load(expected: SessionState, request: ProfileRequest): ProfileSnapshot {
            calls+=expected to request; val failure=failures[request.userId]
            gates[request.userId]?.let { if (ignoreCancellation) withContext(NonCancellable) { it.await() } else it.await() }
            failure?.let { throw it }
            return ProfileSnapshot(BasicProfile(if (wrongUser) 999 else request.userId,"Private profile","2026-01-01T00:00:00Z",
                "Member",false,true,0,false),if (wrongRoom) 99 else request.roomId,true)
        }
    }
}
