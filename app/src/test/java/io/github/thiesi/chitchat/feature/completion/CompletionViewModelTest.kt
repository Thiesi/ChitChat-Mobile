package io.github.thiesi.chitchat.feature.completion

import androidx.lifecycle.ViewModelStore
import io.github.thiesi.chitchat.core.mentions.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CompletionViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val api=Operations()
    private val store=ViewModelStore()
    private lateinit var model: CompletionViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher); model=CompletionViewModel(api) { 100 }; store.put("completion",model)
        model.foreground(); model.navigate("entry")
    }
    @After fun cleanup() {
        model.background(); api.gates.values.forEach { it.complete(Unit) }; dispatcher.scheduler.runCurrent()
        store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain()
    }
    private fun room(id: Long=42)=checkNotNull(model.bind(api.session.value,CompletionTarget.Room(id),"entry"))
    private fun direct(name: String="Ann")=checkNotNull(model.bind(api.session.value,CompletionTarget.Direct(8,name),"entry"))
    private fun edit(text: String)=ComposerEdit(text,text.length)
    private fun choice()=checkNotNull(model.state.value.selection)
    private fun requestKey(room: Long=42,prefix: String="",limit: Int=8)="$room:$limit:$prefix"
    private suspend fun TestScope.suggest(pane: CompletionPane,body: String="@An"): CompletionSelection {
        model.input(pane,edit(body)); runCurrent(); advanceTimeBy(150); runCurrent(); return choice()
    }
    private fun candidates()=model.state.value.candidates.map { it.username }
    private fun queryCalls()=api.calls.filter { it.second.limit==8 }

    @Test fun oneOriginalSessionSeedUses25AndRepeatedBindOrForegroundNeverRepeatsIt()=runTest(dispatcher) {
        val original=api.session.value;val pane=room();runCurrent()
        assertSame(original,api.calls.single().first);assertEquals(25,api.calls.single().second.limit)
        assertEquals("",api.calls.single().second.prefix)
        repeat(3) { model.foreground();assertSame(pane,room());runCurrent() }
        assertEquals(1,api.calls.size);assertNull(model.state.value.selection)
    }
    @Test fun debounceCoalescesActualEditorChangesWithoutARequestForTheRetiredPrefix()=runTest(dispatcher) {
        val pane=room();runCurrent();model.input(pane,edit("@A"));runCurrent();advanceTimeBy(100)
        model.input(pane,edit("@An"));runCurrent();advanceTimeBy(149);runCurrent();assertTrue(queryCalls().isEmpty())
        advanceTimeBy(1);runCurrent();assertEquals(listOf("An"),queryCalls().map { it.second.prefix })
        assertSame(pane.original,queryCalls().single().first);assertEquals(listOf("Ann","Andy"),candidates())
    }
    @Test fun broadcastsHaveNoUserIdAndTheCombinedOrderedListNeverExceeds8()=runTest(dispatcher) {
        api.rows[requestKey()]=(8L..15L).map { RoomMentionUser(it,"Member$it") }
        val pane=room();suggest(pane,"@");val people=model.state.value.candidates
        assertEquals(8,people.size);assertTrue(people[0] is CompletionCandidate.Broadcast);assertEquals("room",people[0].username)
        assertTrue(people[1] is CompletionCandidate.Broadcast);assertEquals("here",people[1].username)
        assertEquals((8L..13L).toList(),people.filterIsInstance<CompletionCandidate.Person>().map { it.id })
    }
    @Test fun directHintsAndTabUseOnlyTheCurrentPeerWithNoRoomOrGlobalGet()=runTest(dispatcher) {
        val pane=direct();suggest(pane,"@a");assertEquals(listOf("Ann"),candidates());assertTrue(api.calls.isEmpty())
        var output: ComposerEdit?=null
        assertTrue(model.tab(pane,edit("An"),listOf("Another","room")) { output=it;true })
        assertEquals("Ann: ",output!!.text);assertTrue(api.calls.isEmpty())
        suggest(pane,"@room");assertTrue(candidates().isEmpty())
    }
    @Test fun roomReservedNamesFromAccountHintsStayExplicitBroadcastsAndAreNotDuplicated()=runTest(dispatcher) {
        api.rows[requestKey(prefix="ro")]=listOf(RoomMentionUser(8,"ROOM"));val pane=room();suggest(pane,"@ro")
        assertEquals(listOf("room"),candidates());assertTrue(model.state.value.candidates.single() is CompletionCandidate.Broadcast)
        api.rows[requestKey(prefix="roo_")]=listOf(RoomMentionUser(8,"Room"));suggest(pane,"@roo_")
        assertTrue(model.state.value.candidates.single() is CompletionCandidate.Broadcast)
        val peer=direct("room");suggest(peer,"@ro")
        assertTrue(model.state.value.candidates.single() is CompletionCandidate.Person)
    }
    @Test fun aNonCooperativeOldQueryCannotPublishOrClearANewerLoadingChoice()=runTest(dispatcher) {
        api.ignoreCancellation=true;api.gates[requestKey(prefix="An")]=CompletableDeferred()
        api.gates[requestKey(prefix="Bo")]=CompletableDeferred();val pane=room();suggest(pane)
        val fresh=suggest(pane,"@Bo");api.gates.getValue(requestKey(prefix="An")).complete(Unit);runCurrent()
        assertSame(fresh,choice());assertTrue(model.state.value.loading);assertTrue(candidates().isEmpty())
        api.gates.getValue(requestKey(prefix="Bo")).complete(Unit);runCurrent()
        assertEquals(listOf("Bob"),candidates());assertFalse(model.state.value.loading)
    }
    @Test fun oldFailureAndFinallyCannotOverwriteANewerSuccessfulQuery()=runTest(dispatcher) {
        api.ignoreCancellation=true;api.gates[requestKey(prefix="An")]=CompletableDeferred()
        api.failures[requestKey(prefix="An")]=ApiFailure(FailureKind.NETWORK)
        val pane=room();suggest(pane);val fresh=suggest(pane,"@Bo")
        api.gates.getValue(requestKey(prefix="An")).complete(Unit);runCurrent()
        assertSame(fresh,choice());assertEquals(listOf("Bob"),candidates());assertFalse(model.state.value.failed)
    }
    @Test fun queryFailureIsRetainedWithoutAutomaticReplayAndExplicitRetryGetsANewReference()=runTest(dispatcher) {
        api.failures[requestKey(prefix="An")]=ApiFailure(FailureKind.NETWORK)
        val pane=room();val old=suggest(pane);assertTrue(model.state.value.failed)
        model.foreground();room();advanceTimeBy(1000);runCurrent();assertEquals(1,queryCalls().size)
        api.failures.clear();model.retry(old);val fresh=choice();assertNotSame(old,fresh)
        advanceTimeBy(150);runCurrent();assertEquals(2,queryCalls().size);assertFalse(model.state.value.failed)
        model.retry(old);runCurrent();assertEquals(2,queryCalls().size);assertSame(fresh,choice())
    }
    @Test fun aFailedSeedHasNoRetryLoopAndLocalSpeakersStillComplete()=runTest(dispatcher) {
        api.failures[requestKey(limit=25)]=ApiFailure(FailureKind.NETWORK);val pane=room();runCurrent()
        repeat(3) { room();model.foreground();runCurrent() };assertEquals(1,api.calls.size)
        var output: ComposerEdit?=null
        assertTrue(model.tab(pane,edit("Al"),listOf("Alice")) { output=it;true });assertEquals("Alice: ",output!!.text)
    }
    @Test fun staleChoiceAndCandidateObjectsCannotSelectEvenAfterTheSameTextReturns()=runTest(dispatcher) {
        val pane=room();val old=suggest(pane);val oldCandidate=model.state.value.candidates.first()
        suggest(pane,"@Bo");val fresh=suggest(pane);var writes=0
        assertFalse(model.choose(old,oldCandidate,edit("@An")) { writes++;true })
        assertFalse(model.choose(fresh,oldCandidate,edit("@An")) { writes++;true })
        model.dismiss(old);assertSame(fresh,choice());assertEquals(0,writes)
    }
    @Test fun aChangedCaretSelectionCompositionOrCompleteDraftRejectsTheOldChoice()=runTest(dispatcher) {
        val pane=room();val original=suggest(pane);val candidate=model.state.value.candidates.first();var writes=0
        for (value in listOf(ComposerEdit("@An",2),ComposerEdit("@An",1,3),ComposerEdit("@An",3,3,true),edit("prefix @An")))
            assertFalse(model.choose(original,candidate,value) { writes++;true })
        assertEquals(0,writes);assertSame(original,choice())
    }
    @Test fun aRejectedConsumerKeepsItsChoiceAndDoesNotClaimAnEdit()=runTest(dispatcher) {
        val pane=room();val original=suggest(pane);val candidate=model.state.value.candidates.first()
        assertFalse(model.choose(original,candidate,edit("@An")) { false });assertSame(original,choice())
        var result: ComposerEdit?=null
        assertTrue(model.choose(original,candidate,edit("@An")) { result=it;true })
        assertEquals("@Ann ",result!!.text);assertNull(model.state.value.selection)
    }
    @Test fun aReentrantAcceptedConsumerCannotHaveItsNewPaneClearedByTheOldCompletion()=runTest(dispatcher) {
        val pane=room();val original=suggest(pane);val candidate=model.state.value.candidates.first()
        assertTrue(model.choose(original,candidate,edit("@An")) { direct("Bob");true })
        assertTrue(model.state.value.pane!!.target is CompletionTarget.Direct)
        assertEquals("Bob",(model.state.value.pane!!.target as CompletionTarget.Direct).username)
    }
    @Test fun logoutAndVersionChangesFenceButtonsBeforeTheCollectorRuns()=runTest(dispatcher) {
        val pane=room();val original=suggest(pane);val candidate=model.state.value.candidates.first();val before=api.session.value
        api.session.value=before.copy(phase=SessionPhase.SIGNED_OUT,user=null)
        assertFalse(model.choose(original,candidate,edit("@An")) { fail("stale edit");true })
        assertNull(model.bind(before,CompletionTarget.Room(42),"entry"));runCurrent();assertNull(model.state.value.pane)
        api.session.value=before;runCurrent();val next=room();suggest(next)
        api.session.value=before.copy(user=SessionUser(7,"Viewer",listOf("member"),2,false))
        assertFalse(next.matches(api.session.value));assertNull(model.bind(before,CompletionTarget.Room(42),"entry"))
    }
    @Test fun backgroundDropsPrivateStateAndForegroundDoesNotRestartQueries()=runTest(dispatcher) {
        api.ignoreCancellation=true;api.gates[requestKey(prefix="An")]=CompletableDeferred()
        val pane=room();val old=suggest(pane);model.background();val count=api.calls.size;model.foreground()
        api.gates.getValue(requestKey(prefix="An")).complete(Unit);runCurrent()
        assertNull(model.state.value.pane);assertNull(model.state.value.selection);assertEquals(count,api.calls.size)
        model.retry(old);runCurrent();assertEquals(count,api.calls.size)
    }
    @Test fun configurationForegroundAndBindingRetainOriginalInFlightWork()=runTest(dispatcher) {
        api.gates[requestKey(prefix="An")]=CompletableDeferred();val pane=room();val original=suggest(pane)
        repeat(3) { model.foreground();assertSame(pane,room());runCurrent() }
        assertSame(original,choice());assertEquals(1,queryCalls().size)
        api.gates.getValue(requestKey(prefix="An")).complete(Unit);runCurrent();assertEquals(listOf("Ann","Andy"),candidates())
    }
    @Test fun navigationRetiresTheOriginalEntryAndOldCleanupCannotCloseItsSuccessor()=runTest(dispatcher) {
        val pane=room();val original=suggest(pane);model.navigate("next")
        val next=checkNotNull(model.bind(api.session.value,CompletionTarget.Direct(8,"Ann"),"next"));suggest(next)
        val fresh=choice();model.quiet(pane);model.dismiss(original);model.editorChanged(pane,edit("changed"))
        assertSame(fresh,choice());assertNull(model.bind(api.session.value,CompletionTarget.Room(42),"entry"))
    }
    @Test fun oldRoomMemberResponseCannotPopulateAnotherRoomAfterNavigation()=runTest(dispatcher) {
        api.ignoreCancellation=true;api.gates[requestKey(limit=25)]=CompletableDeferred();room();runCurrent()
        model.navigate("next");val next=checkNotNull(model.bind(api.session.value,CompletionTarget.Room(43),"next"))
        api.rows[requestKey(room=43,limit=25)]=listOf(RoomMentionUser(11,"Zed"));runCurrent()
        api.gates.getValue(requestKey(limit=25)).complete(Unit);runCurrent()
        assertFalse(model.tab(next,edit("An"),emptyList()) { fail("old members");true })
        var output: ComposerEdit?=null
        assertTrue(model.tab(next,edit("Ze"),emptyList()) { output=it;true });assertEquals("Zed: ",output!!.text)
    }
    @Test fun externalEditorChangeClosesOnlyTheOriginalChoiceAndNeverQueries()=runTest(dispatcher) {
        val pane=room();suggest(pane);val count=api.calls.size
        model.editorChanged(pane,edit("elsewhere"));assertNull(model.state.value.selection)
        runCurrent();assertEquals(count,api.calls.size)
    }
    @Test fun ircCyclesFromRecentThenPresentThenSeedNamesWithoutSortingOrDuplicateNames()=runTest(dispatcher) {
        val pane=room();runCurrent();var output=edit("An")
        repeat(3) { assertTrue(model.tab(pane,output,listOf("Andy","ANDY")) { output=it;true })
            assertEquals(listOf("Andy: ","Ann: ","Andy: ")[it],output.text) }
        assertTrue(queryCalls().isEmpty())
    }
    @Test fun arrowChoiceWrapsAndAnOldArrowCallbackDoesNotChangeANewSelection()=runTest(dispatcher) {
        val pane=room();val old=suggest(pane);assertTrue(model.move(old,-1));assertEquals(1,model.state.value.highlighted)
        assertTrue(model.move(old,1));assertEquals(0,model.state.value.highlighted)
        val fresh=suggest(pane,"@Bo");assertFalse(model.move(old,1));assertSame(fresh,choice());assertEquals(0,model.state.value.highlighted)
    }
    @Test fun invalidResponseIdentityOrRowsCannotBecomeCandidates()=runTest(dispatcher) {
        val pane=room();runCurrent()
        for (mode in listOf("request","prefix","self","duplicate","oversize")) {
            api.malformed=mode;suggest(pane);assertTrue(mode,model.state.value.failed);assertTrue(candidates().isEmpty())
        }
    }
    @Test fun deniedGuestInvalidTargetAndBusySessionCannotStartOrReplaceAPane()=runTest(dispatcher) {
        val original=api.session.value
        for (blocked in listOf(original.copy(phase=SessionPhase.GUEST),original.copy(busy=true),original.copy(refreshRequired=true),
            original.copy(storageWarning=true),original.copy(retryAtMillis=101))) {
            api.session.value=blocked;runCurrent();assertNull(model.bind(blocked,CompletionTarget.Room(42),"entry"))
        }
        api.session.value=original;runCurrent()
        assertNull(model.bind(original,CompletionTarget.Room(0),"entry"));assertNull(model.bind(original,CompletionTarget.Direct(7,"Viewer"),"entry"))
        assertNull(model.bind(original.copy(generation=2),CompletionTarget.Room(42),"entry"));assertTrue(api.calls.isEmpty())
    }
    @Test fun changingPeerNameOrTargetNamespaceRetiresOldCandidates()=runTest(dispatcher) {
        val pane=direct();val old=suggest(pane);val next=direct("AnnRenamed");assertNotSame(pane,next)
        assertNull(model.state.value.selection);model.retry(old);assertNull(model.state.value.selection)
        val room=room(8);assertTrue(room.target is CompletionTarget.Room);assertNotSame(next,room)
    }
    @Test fun clearingTheViewModelFencesLateSuccessAndPrivateSnapshotsHaveNoBodyStrings()=runTest(dispatcher) {
        api.ignoreCancellation=true;api.gates[requestKey(prefix="An")]=CompletableDeferred();val pane=room();suggest(pane)
        assertFalse(model.state.value.toString().contains("@An"));assertFalse(choice().toString().contains("@An"))
        store.clear();api.gates.getValue(requestKey(prefix="An")).complete(Unit);runCurrent()
        assertNull(model.state.value.pane);assertNull(model.state.value.selection)
    }

    private class Operations: RoomMentionOperations {
        override val session=MutableStateFlow(SessionState(server=(ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
            generation=1,phase=SessionPhase.AUTHENTICATED,user=SessionUser(7,"Viewer",listOf("member"),1,false)))
        val calls=mutableListOf<Pair<SessionState,RoomMentionRequest>>()
        val gates=mutableMapOf<String,CompletableDeferred<Unit>>();val failures=mutableMapOf<String,ApiFailure>()
        val rows=mutableMapOf<String,List<RoomMentionUser>>()
        var ignoreCancellation=false;var malformed=""
        override suspend fun suggestions(expected: SessionState,request: RoomMentionRequest): RoomMentionBatch {
            calls+=expected to request;val key="${request.roomId}:${request.limit}:${request.prefix}"
            val failure=failures[key];val bad=malformed
            val result=rows[key] ?: listOf(RoomMentionUser(8,"Ann"),RoomMentionUser(9,"Andy"),RoomMentionUser(10,"Bob"))
                .filter { it.username.startsWith(request.prefix,ignoreCase=true) }
            gates[key]?.let { if (ignoreCancellation) withContext(NonCancellable) { it.await() } else it.await() }
            failure?.let { throw it }
            val users=when(bad) { "self"->listOf(RoomMentionUser(7,"Viewer"));"duplicate"->listOf(RoomMentionUser(8,"Ann"),RoomMentionUser(8,"Ann"))
                "oversize"->(8L..16L).map { RoomMentionUser(it,"Ann$it") };else->result }
            return RoomMentionBatch(if (bad=="request") RoomMentionRequest(request.roomId,request.prefix) else request,
                if (bad=="prefix") "wrong" else request.prefix,users)
        }
    }
}
