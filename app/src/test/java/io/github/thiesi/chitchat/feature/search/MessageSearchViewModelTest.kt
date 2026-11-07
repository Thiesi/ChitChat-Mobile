package io.github.thiesi.chitchat.feature.search

import androidx.lifecycle.ViewModelStore
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.search.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.core.targets.TargetRequest
import io.github.thiesi.chitchat.core.direct.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = Operations()
    private val store = ViewModelStore()
    private lateinit var model: MessageSearchViewModel
    @Before fun start() { Dispatchers.setMain(dispatcher); model = MessageSearchViewModel(operations) { 1000L }; store.put("search", model) }
    @After fun stop() {
        model.state.value.entry?.let(model::background)
        operations.gates.values.forEach { it.complete(Unit) }; dispatcher.scheduler.runCurrent()
        store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain()
    }
    private fun open(id: String = "entry") = checkNotNull(model.open(operations.session.value, id))
    private fun search(entry: MessageSearchEntry, query: String = "original query"): MessageSearchAttempt {
        assertTrue(model.query(entry, query)); return checkNotNull(model.search(entry))
    }
    private fun results() = checkNotNull(model.state.value.results)
    private fun selection(entry: MessageSearchEntry): MessageSearchSelection {
        val page = results()
        return checkNotNull(model.select(entry, page, page.page.results.first()))
    }

    @Test fun openingEditingForegroundAndSessionRefreshNeverAutomaticallyPostAQuery() = runTest(dispatcher) {
        val entry = open(); model.query(entry, "private draft"); model.scope(entry, MessageSearchScope.DIRECT); runCurrent()
        assertTrue(operations.calls.isEmpty())
        operations.session.value = operations.session.value.copy(busy = true); runCurrent()
        operations.session.value = operations.session.value.copy(busy = false); runCurrent()
        assertSame(entry, open()); assertTrue(operations.calls.isEmpty())
        assertEquals("private draft", model.state.value.query)
    }
    @Test fun explicitSearchFreezesTheOriginalSessionQueryAndScopeAcrossDraftEdits() = runTest(dispatcher) {
        val original = operations.session.value; val entry = open()
        operations.gates[0] = CompletableDeferred()
        model.scope(entry, MessageSearchScope.DIRECT); val attempt = search(entry); runCurrent()
        model.query(entry, "new unsent query"); model.scope(entry, MessageSearchScope.ROOMS)
        operations.gates.getValue(0).complete(Unit); runCurrent()
        assertSame(original, operations.calls.single().first)
        assertSame(attempt, results().attempt)
        assertEquals("original query", results().attempt.request.query)
        assertEquals(MessageSearchScope.DIRECT, results().attempt.request.scope)
        assertEquals("new unsent query", model.state.value.query); assertEquals(MessageSearchScope.ROOMS, model.state.value.scope)
    }
    @Test fun localQueryMemoryGuardNeverTruncatesOrPostsAnOversizedInput() = runTest(dispatcher) {
        val entry = open(); model.query(entry, "keep")
        assertFalse(model.query(entry, "x".repeat(MessageSearchViewModel.MAX_QUERY_UNITS + 1)))
        assertEquals("keep", model.state.value.query); assertTrue(model.state.value.inputTooLong); assertNull(model.search(entry))
        assertTrue(model.query(entry, "😀".repeat(MessageSearchViewModel.MAX_QUERY_UNITS / 2)))
        assertFalse(model.state.value.inputTooLong)
        model.query(entry, "  "); assertNull(model.search(entry)); runCurrent(); assertTrue(operations.calls.isEmpty())
    }
    @Test fun nextAndPreviousUseTheCompletedQueryScopeAndExactOffsetsRatherThanEditedInput() = runTest(dispatcher) {
        val entry = open(); search(entry); runCurrent(); val first = results()
        model.query(entry, "different draft"); model.scope(entry, MessageSearchScope.DIRECT)
        val next = checkNotNull(model.next(entry, first)); runCurrent()
        assertEquals("original query", next.request.query); assertEquals(MessageSearchScope.ALL, next.request.scope)
        assertEquals(25, next.request.offset); assertEquals(25, results().attempt.request.offset)
        assertNull(model.next(entry, first)); assertNull(model.previous(entry, first))
        val previous = checkNotNull(model.previous(entry, results())); runCurrent()
        assertEquals(0, previous.request.offset); assertNull(model.previous(entry, results()))
        assertEquals("different draft", model.state.value.query)
    }
    @Test fun pageOrientedBrowsingKeepsOnePageAndReachesTheRealServerOffsetCeiling() = runTest(dispatcher) {
        val entry = open(); search(entry); runCurrent()
        repeat(200) { checkNotNull(model.next(entry, results())); runCurrent(); assertEquals(25, results().page.results.size) }
        assertEquals(5000, results().attempt.request.offset)
        assertTrue(results().page.hasMore); assertEquals(5025, results().page.nextOffset)
        assertFalse(results().page.canLoadMore); assertNull(model.next(entry, results()))
        assertEquals(201, operations.calls.size); assertNotNull(model.previous(entry, results()))
    }
    @Test fun unavailableNextIsNotGuessedFromAShortOrEmptyPage() = runTest(dispatcher) {
        operations.more = false; operations.empty = true
        val entry = open(); search(entry); runCurrent()
        assertTrue(results().page.results.isEmpty()); assertNull(model.next(entry, results()))
        assertEquals(1, operations.calls.size)
    }
    @Test fun busySessionCooldownStorageAndRevalidationRejectRequestsWithoutQueuingThem() = runTest(dispatcher) {
        val entry = open(); model.query(entry, "private")
        val original = operations.session.value
        for (blocked in listOf(original.copy(busy = true), original.copy(refreshRequired = true),
            original.copy(storageWarning = true), original.copy(retryAtMillis = 1001))) {
            operations.session.value = blocked; assertNull(model.search(entry)); runCurrent()
            operations.session.value = original; runCurrent(); assertTrue(operations.calls.isEmpty())
        }
        assertNotNull(model.search(entry)); runCurrent(); assertEquals(1, operations.calls.size)
    }
    @Test fun busyRequestHasNoImplicitSecondSearchAndOldCancelCannotRetireItsSuccessor() = runTest(dispatcher) {
        operations.gates[0] = CompletableDeferred(); val entry = open(); val old = search(entry); runCurrent()
        assertNull(model.search(entry)); model.cancel(old)
        val next = search(entry, "successor"); runCurrent()
        model.cancel(old); operations.gates.getValue(0).complete(Unit); runCurrent()
        assertSame(next, results().attempt); assertFalse(model.state.value.busy); assertEquals(2, operations.calls.size)
    }
    @Test fun lateNonCooperativeCancelledResponseCannotOverwriteTheSuccessorOrItsBusyFlag() = runTest(dispatcher) {
        operations.ignoreCancellation = true; operations.gates[0] = CompletableDeferred(); operations.gates[1] = CompletableDeferred()
        val entry = open(); val old = search(entry); runCurrent(); model.cancel(old)
        val next = search(entry, "successor"); runCurrent()
        operations.gates.getValue(0).complete(Unit); runCurrent()
        assertSame(next, model.state.value.pending); assertNull(model.state.value.results)
        operations.gates.getValue(1).complete(Unit); runCurrent(); assertSame(next, results().attempt)
    }
    @Test fun configurationRebindingKeepsTheEntryQueryAndPendingRequestWithoutAnotherPost() = runTest(dispatcher) {
        val entry = open(); operations.gates[0] = CompletableDeferred(); val attempt = search(entry); runCurrent()
        assertSame(entry, open()); assertSame(attempt, model.state.value.pending)
        operations.gates.getValue(0).complete(Unit); runCurrent()
        assertSame(attempt, results().attempt); assertEquals(1, operations.calls.size)
    }
    @Test fun genuineBackgroundClearsAllPrivateSearchDataAndResumeDoesNotReplay() = runTest(dispatcher) {
        val entry = open(); search(entry); runCurrent(); selection(entry)
        model.background(entry); runCurrent()
        assertEquals("", model.state.value.query); assertNull(model.state.value.results); assertNull(model.state.value.selection)
        assertNull(model.state.value.pending); assertNull(model.search(entry))
        assertSame(entry, open()); runCurrent(); assertEquals(1, operations.calls.size)
        assertNull(model.state.value.results); assertEquals("", model.state.value.query)
    }
    @Test fun backgroundRetiresEvenANonCooperativePendingResponse() = runTest(dispatcher) {
        operations.ignoreCancellation = true; operations.gates[0] = CompletableDeferred()
        val entry = open(); search(entry); runCurrent(); model.background(entry)
        operations.gates.getValue(0).complete(Unit); runCurrent(); open(); runCurrent()
        assertNull(model.state.value.results); assertFalse(model.state.value.busy); assertEquals(1, operations.calls.size)
    }
    @Test fun accountGenerationVersionServerGuestAndSignedOutRetirePrivateStateAndOldCallbacks() = runTest(dispatcher) {
        val original = operations.session.value
        val originalUser = checkNotNull(original.user)
        val otherServer = (ServerAddress.parse("https://other.example.test") as AddressResult.Valid).address
        val replacements = listOf(original.copy(generation = 2), original.copy(user = originalUser.copy(sessionVersion = 2)),
            original.copy(user = originalUser.copy(id = 9)), original.copy(server = otherServer),
            original.copy(phase = SessionPhase.GUEST, user = originalUser.copy(guest = true)),
            original.copy(phase = SessionPhase.SIGNED_OUT, user = null), original.copy(server = null))
        for ((index, replacement) in replacements.withIndex()) {
            operations.session.value = original; runCurrent(); val entry = open("entry$index")
            val attempt = search(entry); runCurrent(); val oldResults = results(); val choice = selection(entry)
            operations.session.value = replacement
            model.query(entry, "stale"); model.scope(entry, MessageSearchScope.DIRECT)
            assertNull(model.search(entry)); assertNull(model.next(entry, oldResults)); assertNull(model.retry(attempt))
            assertFalse(model.adopt(choice) { fail("Stale adoption"); true }); runCurrent()
            assertNull(model.state.value.entry); assertEquals("", model.state.value.query)
            assertNull(model.state.value.results); assertNull(model.state.value.selection)
            assertNull(model.open(original, "stale"))
        }
    }
    @Test fun oldEntryCloseBackgroundEditAndSelectionCallbacksCannotTouchASuccessor() = runTest(dispatcher) {
        val old = open("old"); search(old); runCurrent(); val oldResults = results(); val oldChoice = selection(old)
        val next = open("next"); search(next, "successor"); runCurrent(); val nextResults = results(); val nextChoice = selection(next)
        model.close(old); model.background(old); model.query(old, "stale"); model.cancelSelection(oldChoice)
        assertNull(model.next(old, oldResults)); assertNull(model.select(old, oldResults, oldResults.page.results.first()))
        assertFalse(model.adopt(oldChoice) { fail("Stale adoption"); true })
        assertSame(next, model.state.value.entry); assertSame(nextResults, results()); assertSame(nextChoice, model.state.value.selection)
        assertEquals("successor", model.state.value.query)
    }
    @Test fun explicitRetryIsReferenceBoundAndFailureNeverLoopsOnForegroundOrSessionEmission() = runTest(dispatcher) {
        operations.failure = ApiFailure(FailureKind.NETWORK); val entry = open(); val failed = search(entry); runCurrent()
        assertSame(failed, model.state.value.failedAttempt); assertNotNull(model.state.value.error); assertFalse(model.state.value.busy)
        assertSame(entry, open()); operations.session.value = operations.session.value.copy(busy = true); runCurrent()
        operations.session.value = operations.session.value.copy(busy = false); runCurrent(); assertEquals(1, operations.calls.size)
        operations.failure = null; val retried = checkNotNull(model.retry(failed)); assertNull(model.retry(failed)); runCurrent()
        assertSame(retried, results().attempt); assertEquals(2, operations.calls.size)
    }
    @Test fun editingAfterFailureRetiresTheOldRetryChoice() = runTest(dispatcher) {
        operations.failure = ApiFailure(FailureKind.HTTP, 400, "validation_error")
        val entry = open(); val failed = search(entry); runCurrent()
        model.query(entry, "new"); assertNull(model.retry(failed)); assertNull(model.state.value.error)
        assertEquals(1, operations.calls.size)
    }
    @Test fun nextFailureKeepsTheOriginalPageAndExplicitRetryUsesTheSameOriginalQueryOffset() = runTest(dispatcher) {
        val entry = open(); search(entry); runCurrent(); val first = results()
        operations.failure = ApiFailure(FailureKind.TIMEOUT); val failed = checkNotNull(model.next(entry, first)); runCurrent()
        assertSame(first, results()); assertSame(failed, model.state.value.failedAttempt)
        operations.failure = null; val retry = checkNotNull(model.retry(failed)); runCurrent()
        assertEquals(25, retry.request.offset); assertEquals("original query", retry.request.query)
        assertEquals(25, results().attempt.request.offset)
    }
    @Test fun scopedDenialAndIncompatibleResponsesClearOldPrivateResultsWhileValidationDoesNot() = runTest(dispatcher) {
        val entry = open()
        for (failure in listOf(ApiFailure(FailureKind.HTTP, 403, "forbidden"), ApiFailure(FailureKind.INCOMPATIBLE),
            ApiFailure(FailureKind.EXPIRED), ApiFailure(FailureKind.HTTP, 404, "not_found"))) {
            operations.failure = null; search(entry); runCurrent(); assertNotNull(model.state.value.results)
            operations.failure = failure; search(entry, "new"); runCurrent(); assertNull(model.state.value.results)
        }
        operations.failure = null; search(entry); runCurrent(); val old = results()
        operations.failure = ApiFailure(FailureKind.HTTP, 400, "validation_error"); search(entry, "-"); runCurrent()
        assertSame(old, results())
    }
    @Test fun cancelledSessionOperationIsNotSuccessOrAnAutomaticRetry() = runTest(dispatcher) {
        operations.cancel = true; val entry = open(); val attempt = search(entry); runCurrent()
        assertNull(model.state.value.results); assertSame(attempt, model.state.value.failedAttempt)
        assertEquals(FailureKind.EXPIRED, model.state.value.error?.kind); assertFalse(model.state.value.busy)
        open(); runCurrent(); assertEquals(1, operations.calls.size)
    }
    @Test fun resultAndRowReferencesRejectStaleOrInventedSelections() = runTest(dispatcher) {
        val entry = open(); search(entry); runCurrent(); val old = results()
        assertNull(model.select(entry, old, operations.row(999)))
        assertNull(model.select(entry, old, operations.row(1))) // Equal numeric ID is not the rendered row reference.
        search(entry, "replacement"); runCurrent()
        assertNull(model.select(entry, old, old.page.results.first()))
    }
    @Test fun rejectedConsumerKeepsItsSelectionAndAcceptedConsumerConsumesOnlyOnce() = runTest(dispatcher) {
        val entry = open(); search(entry); runCurrent(); val choice = selection(entry)
        assertFalse(model.adopt(choice) { false }); assertSame(choice, model.state.value.selection)
        assertTrue(model.adopt(choice) { true }); assertNull(model.state.value.selection)
        assertFalse(model.adopt(choice) { fail("Already consumed"); true }); assertEquals(1, operations.calls.size)
    }
    @Test fun acceptedConsumerCannotClearANewerSelectionItCreatedDuringHandoff() = runTest(dispatcher) {
        val entry = open(); search(entry); runCurrent(); val old = selection(entry)
        var next: MessageSearchSelection? = null
        assertTrue(model.adopt(old) { next = selection(entry); true })
        model.cancelSelection(old); assertSame(next, model.state.value.selection)
    }
    @Test fun selectionsExposeOnlyTypedExactTargetsAndNoLookupReadOrSendAction() = runTest(dispatcher) {
        val entry = open(); search(entry); runCurrent()
        val choice = selection(entry)
        assertEquals(MessageTarget.RoomMessage(42, 1), choice.target)
        model.scope(entry, MessageSearchScope.DIRECT); search(entry); runCurrent()
        assertEquals(MessageTarget.DirectMessage(9, 1), selection(entry).target)
        assertEquals(2, operations.calls.size)
    }
    @Test fun clearingTheViewModelRetiresPrivateStateAndNonCooperativeWork() = runTest(dispatcher) {
        operations.ignoreCancellation = true; operations.gates[0] = CompletableDeferred()
        val entry = open(); search(entry); runCurrent(); store.clear()
        operations.gates.getValue(0).complete(Unit); runCurrent()
        assertNull(model.state.value.entry); assertNull(model.state.value.results); assertEquals("", model.state.value.query)
        assertNull(model.open(operations.session.value, "again"))
    }
    @Test fun editsDuringPendingFailureRetireTheOldQueryAndScopeRetryChoice() = runTest(dispatcher) {
        for (changeScope in listOf(false, true)) {
            operations.failure = ApiFailure(FailureKind.NETWORK)
            val index = operations.calls.size
            operations.gates[index] = CompletableDeferred()
            val entry = open("failure$index"); val attempt = search(entry); runCurrent()
            if (changeScope) model.scope(entry, MessageSearchScope.DIRECT) else model.query(entry, "new draft")
            operations.gates.getValue(index).complete(Unit); runCurrent()
            assertNull(model.state.value.failedAttempt); assertNull(model.retry(attempt)); assertFalse(model.state.value.busy)
            assertEquals(index + 1, operations.calls.size)
        }
    }
    @Test fun pendingCancellationOrRejectedOversizeCannotRecreateAnOldRetryChoice() = runTest(dispatcher) {
        val entry = open()
        operations.cancel = true; operations.gates[0] = CompletableDeferred()
        val cancelled = search(entry); runCurrent(); model.query(entry, "changed")
        operations.gates.getValue(0).complete(Unit); runCurrent()
        assertNull(model.state.value.failedAttempt); assertNull(model.retry(cancelled))
        operations.cancel = false; operations.failure = ApiFailure(FailureKind.NETWORK); operations.gates[1] = CompletableDeferred()
        val rejected = search(entry); runCurrent()
        assertFalse(model.query(entry, "x".repeat(MessageSearchViewModel.MAX_QUERY_UNITS + 1)))
        operations.gates.getValue(1).complete(Unit); runCurrent()
        assertNull(model.state.value.failedAttempt); assertNull(model.retry(rejected)); assertNull(model.search(entry))
        assertEquals(2, operations.calls.size)
    }
    @Test fun stateAndReferenceDebugStringsNeverPrintPrivateQueriesExcerptsOrNames() = runTest(dispatcher) {
        val entry = open(); val attempt = search(entry, "SensitiveQuery"); runCurrent()
        val page = results(); val choice = selection(entry)
        for (value in listOf(model.state.value, entry, attempt, page, choice))
            for (privateText in listOf("SensitiveQuery", "Private excerpt", "PrivateUser"))
                assertFalse(value.toString(), value.toString().contains(privateText))
    }
    private fun roomChoice(entry: MessageSearchEntry): MessageSearchSelection {
        model.scope(entry, MessageSearchScope.ROOMS); search(entry); dispatcher.scheduler.runCurrent()
        return selection(entry)
    }
    private fun bind(choice: MessageSearchSelection, released: MutableList<TargetRequest>): MessageSearchTargetBinding {
        val request = TargetRequest(choice.results.attempt.entry.original, choice.target)
        return checkNotNull(model.bindTarget(choice, request) { released += it })
    }
    @Test fun targetBindingRequiresTheOriginalSessionObjectExactKindAndIdsAndCurrentChoice() = runTest(dispatcher) {
        val entry = open(); val choice = roomChoice(entry); val released = mutableListOf<TargetRequest>()
        for (request in listOf(TargetRequest(entry.original.copy(), choice.target),
            TargetRequest(entry.original, MessageTarget.RoomMessage(43, 1)),
            TargetRequest(entry.original, MessageTarget.RoomMessage(42, 2)),
            TargetRequest(entry.original, MessageTarget.DirectMessage(9, 1)),
            TargetRequest(entry.original, MessageTarget.RoomPing(42, 1))))
            assertNull(model.bindTarget(choice, request) { fail("An unbound request must not be released") })
        val binding = bind(choice, released)
        assertSame(binding, model.bindTarget(choice, binding.request) { fail("Original release must stay bound") })
        assertFalse(model.adoptTarget(binding, TargetRequest(entry.original, choice.target)) { fail("Equal fields are not the request"); true })
        assertTrue(released.isEmpty()); assertSame(binding, model.state.value.targetBinding)
    }
    @Test fun busyOrRejectingTargetConsumerRetainsTheOriginalBindingUntilAtomicAcceptance() = runTest(dispatcher) {
        val entry = open(); val choice = roomChoice(entry); val released = mutableListOf<TargetRequest>()
        val binding = bind(choice, released)
        assertFalse(model.adoptTarget(binding, binding.request) { false })
        operations.session.value = operations.session.value.copy(busy = true)
        assertFalse(model.adoptTarget(binding, binding.request) { fail("Busy must not navigate"); true })
        operations.session.value = operations.session.value.copy(busy = false)
        assertSame(choice, model.state.value.selection); assertSame(binding, model.state.value.targetBinding)
        assertTrue(model.adoptTarget(binding, binding.request) { true })
        assertEquals(listOf(binding.request), released); assertNull(model.state.value.targetBinding)
        assertNull(model.state.value.selection); assertEquals(1, operations.calls.size)
        assertFalse(model.adoptTarget(binding, binding.request) { fail("Already consumed"); true })
    }
    @Test fun sameIdSuccessorCannotBeCancelledOrAdoptedByAnOldBinding() = runTest(dispatcher) {
        val entry = open(); val released = mutableListOf<TargetRequest>(); val old = bind(roomChoice(entry), released)
        val nextChoice = selection(entry); val next = bind(nextChoice, released)
        assertEquals(old.request.target, next.request.target); assertNotSame(old.request, next.request)
        assertEquals(listOf(old.request), released)
        model.cancelTarget(old); model.cancelSelection(old.selection)
        assertFalse(model.adoptTarget(old, old.request) { fail("Stale request"); true })
        assertSame(next, model.state.value.targetBinding)
        model.cancelTarget(next); model.cancelTarget(next)
        assertEquals(listOf(old.request, next.request), released)
    }
    @Test fun eachPrivateIntentRetirementReleasesOnlyItsOwnedGetOnce() = runTest(dispatcher) {
        val actions: List<(MessageSearchEntry) -> Unit> = listOf(
            { model.query(it, "changed") }, { model.query(it, "x".repeat(MessageSearchViewModel.MAX_QUERY_UNITS + 1)) },
            { model.scope(it, MessageSearchScope.DIRECT) }, { model.search(it) },
            { model.next(it, results()) }, { model.background(it) }, { model.close(it) })
        for ((index, action) in actions.withIndex()) {
            val entry = open("retire$index"); val released = mutableListOf<TargetRequest>()
            val binding = bind(roomChoice(entry), released)
            action(entry); model.cancelTarget(binding); runCurrent()
            assertEquals(listOf(binding.request), released); assertNull(model.state.value.targetBinding)
        }
    }
    @Test fun recreationRetainsBindingButReplacementEntryOwnerAndClearRetireIt() = runTest(dispatcher) {
        val released = mutableListOf<TargetRequest>(); val entry = open(); val old = bind(roomChoice(entry), released)
        assertSame(entry, open()); assertSame(old, model.state.value.targetBinding); assertTrue(released.isEmpty())
        val replacement = open("replacement"); assertEquals(listOf(old.request), released)
        val second = bind(roomChoice(replacement), released)
        operations.session.value = operations.session.value.copy(generation = 2)
        assertFalse(model.adoptTarget(second, second.request) { fail("Replaced owner"); true }); runCurrent()
        assertEquals(listOf(old.request, second.request), released)
        val third = bind(roomChoice(open("third")), released); store.clear(); runCurrent()
        assertEquals(listOf(old.request, second.request, third.request), released); assertNull(model.state.value.targetBinding)
    }
    @Test fun acceptanceThatCreatesANewBindingCannotRetireThatSuccessor() = runTest(dispatcher) {
        val entry = open(); val released = mutableListOf<TargetRequest>(); val old = bind(roomChoice(entry), released)
        var next: MessageSearchTargetBinding? = null
        assertTrue(model.adoptTarget(old, old.request) { next = bind(selection(entry), released); true })
        assertSame(next, model.state.value.targetBinding); assertEquals(listOf(old.request), released)
        model.cancelTarget(old); assertSame(next, model.state.value.targetBinding)
    }
    @Test fun allScopeTargetBindingAndAdoptionRecheckCurrentDmDisclosures() = runTest(dispatcher) {
        val entry = open(); search(entry); runCurrent(); val choice = selection(entry)
        val request = TargetRequest(entry.original, choice.target); val released = mutableListOf<TargetRequest>()
        assertNull(model.bindTarget(choice, request) { released += it })
        val valid = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "super_admin", true, true, false))
        operations.session.value = operations.session.value.copy(privacy = valid); runCurrent()
        val binding = checkNotNull(model.bindTarget(choice, request) { released += it })
        operations.session.value = operations.session.value.copy(privacy = null)
        assertFalse(model.adoptTarget(binding, request) { fail("Unknown privacy"); true })
        assertTrue(released.isEmpty()); assertSame(binding, model.state.value.targetBinding)
        operations.session.value = operations.session.value.copy(privacy = valid)
        assertTrue(model.adoptTarget(binding, request) { true }); assertEquals(listOf(request), released)
    }
    @Test fun resourceDenialRemovesOnlyItsExcerptsAndKeepsTheRawPageBoundaryAndOtherRows() = runTest(dispatcher) {
        operations.otherRoom = true
        val entry = open(); val released = mutableListOf<TargetRequest>(); val binding = bind(roomChoice(entry), released)
        val old = results(); val unrelated = old.page.results.last()
        for (failure in listOf(ApiFailure(FailureKind.HTTP, 403, "csrf_failed"),
            ApiFailure(FailureKind.NETWORK), ApiFailure(FailureKind.HTTP, 429))) {
            model.targetDenied(binding, binding.request, failure)
            assertSame(old, results()); assertTrue(released.isEmpty())
        }
        val denial = ApiFailure(FailureKind.HTTP, 403, "forbidden")
        model.targetDenied(binding, binding.request, denial)
        assertNotSame(old, results()); assertEquals(listOf(unrelated), results().page.results)
        assertTrue(results().accessFiltered); assertTrue(results().page.hasMore); assertEquals(25, results().page.nextOffset)
        assertEquals("original query", model.state.value.query); assertEquals(listOf(binding.request), released)
        assertNull(model.state.value.targetBinding); assertNull(model.select(entry, old, unrelated))
        val next = checkNotNull(model.select(entry, results(), unrelated)); val current = bind(next, released)
        model.targetDenied(binding, binding.request, denial)
        assertSame(current, model.state.value.targetBinding); assertEquals(listOf(unrelated), results().page.results)
    }
    @Test fun directResourceDenialRetiresItsPrivatePageWithoutFabricatingExhaustionOrRetryConsent() = runTest(dispatcher) {
        val valid = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "super_admin", true, true, false))
        operations.session.value = operations.session.value.copy(privacy = valid)
        val entry = open(); model.scope(entry, MessageSearchScope.DIRECT); search(entry); runCurrent()
        val released = mutableListOf<TargetRequest>(); val binding = bind(selection(entry), released)
        model.targetDenied(binding, binding.request, ApiFailure(FailureKind.HTTP, 404, "not_found"))
        assertTrue(results().page.results.isEmpty()); assertTrue(results().page.canLoadMore)
        assertEquals(25, results().page.nextOffset); assertNull(model.state.value.failedAttempt)
        assertNull(model.state.value.selection); assertEquals(1, operations.calls.size)
        assertNotNull(model.next(entry, results())); runCurrent()
        assertFalse(results().accessFiltered); assertEquals(2, operations.calls.size)
    }
    @Test fun targetBindingDebugStringDoesNotExposeQueryOrResultContents() = runTest(dispatcher) {
        val binding = bind(roomChoice(open()), mutableListOf())
        for (text in listOf("original query", "Private excerpt", "PrivateUser", "Private room"))
            assertFalse(binding.toString().contains(text))
        assertEquals("MessageSearchState", model.state.value.toString())
    }
    private class Operations : MessageSearchOperations {
        override val session = MutableStateFlow(SessionState(
            server = (ServerAddress.parse("https://fixture.example.test") as AddressResult.Valid).address,
            phase = SessionPhase.AUTHENTICATED, generation = 1,
            user = SessionUser(7, "PrivateUser", listOf("member"), 1, false)))
        val calls = mutableListOf<Pair<SessionState, MessageSearchRequest>>()
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var ignoreCancellation = false
        var failure: ApiFailure? = null
        var cancel = false
        var more = true
        var empty = false
        var otherRoom = false
        fun row(id: Long, direct: Boolean = false): MessageSearchHit = if (direct)
            MessageSearchHit.Direct(id, "Private excerpt", SearchParticipant(7, "PrivateUser"),
                SearchParticipant(9, "Peer"), true, "2026-11-06T12:00:00Z", null)
            else MessageSearchHit.Room(id, "Private excerpt", null, SearchRoom(42, "private", "Private room"),
                "2026-11-06T12:00:00Z", null)
        override suspend fun search(expected: SessionState, request: MessageSearchRequest): MessageSearchPage {
            val index = calls.size; calls.add(expected to request)
            gates[index]?.let { gate -> if (ignoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await() }
            if (cancel) throw CancellationException("Fixture cancellation")
            failure?.let { throw it }
            val rows = if (empty) emptyList() else (1L..request.limit.toLong()).map {
                if (otherRoom && it == request.limit.toLong()) MessageSearchHit.Room(it, "Other excerpt", null,
                    SearchRoom(99, "other", "Other room"), "2026-11-06T12:00:00Z", null)
                else row(it, request.scope == MessageSearchScope.DIRECT)
            }
            return MessageSearchPage(rows, more && !empty, if (more && !empty) request.offset + rows.size else null)
        }
    }
}
