package io.github.thiesi.chitchat.feature.notifications

import androidx.lifecycle.ViewModelStore
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.notifications.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCenterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Operations()
    private val store = ViewModelStore()
    private lateinit var model: NotificationCenterViewModel
    private var now = 1000L
    @Before fun start() { Dispatchers.setMain(dispatcher); model = NotificationCenterViewModel(api) { now }; store.put("notices", model) }
    @After fun stop() {
        model.background(); api.getGate?.complete(Unit); api.postGate?.complete(Unit)
        dispatcher.scheduler.runCurrent(); store.clear(); Dispatchers.resetMain()
    }
    private fun TestScope.open() { model.foreground(); runCurrent(); assertTrue(model.state.value.loaded) }
    private fun choice(): ReadAllChoice { model.requestAll(api.session.value); return checkNotNull(model.state.value.allChoice) }

    @Test fun initialGetKeepsOriginalScopeAndIndependentServerUnreadWithoutReadingAnything() = runTest(dispatcher) {
        open(); assertEquals(9000, model.state.value.unreadCount); assertEquals(25, model.state.value.notifications.size)
        assertEquals(626L, model.state.value.olderCursor); assertTrue(model.state.value.mayHaveOlder)
        assertSame(api.session.value, api.pages.single().original); assertNull(api.pages.single().before)
        assertEquals(0, api.ones); assertEquals(0, api.alls)
    }
    @Test fun olderWindowsStayBoundedAfterAllTheirRowsDisappearAndLatestRemainsExplicit() = runTest(dispatcher) {
        open(); repeat(20) { model.older(api.session.value); runCurrent() }
        assertEquals(500, model.state.value.notifications.size); assertFalse(model.state.value.latestWindow)
        val bound = checkNotNull(model.state.value.fixedUpper); val count = api.pages.size
        api.rows = listOf(notice(9000)); model.invalidate(); runCurrent()
        assertTrue(model.state.value.notifications.isEmpty()); assertEquals(bound, model.state.value.fixedUpper)
        model.background(); model.foreground(); runCurrent(); model.refresh(api.session.value); runCurrent()
        assertTrue(api.pages.drop(count).all { it.before == bound + 1 })
        assertEquals(0, api.ones); assertEquals(0, api.alls)
        model.latest(api.session.value); runCurrent()
        assertEquals(9000L, model.state.value.notifications.single().id)
        assertTrue(model.state.value.latestWindow); assertNull(model.state.value.fixedUpper)
    }
    @Test fun lostLatestOverlapKeepsTheOldWindowStaleRatherThanStitchingUnrelatedRows() = runTest(dispatcher) {
        open(); val ids = model.state.value.notifications.map { it.id }
        api.rows = (1001L..2000).reversed().map { notice(it) }; model.invalidate(); runCurrent()
        assertTrue(model.state.value.incompleteRange); assertTrue(model.state.value.stale)
        assertEquals(ids, model.state.value.notifications.map { it.id })
        assertEquals(0, api.alls); assertEquals(0, api.ones)
        model.latest(api.session.value); runCurrent(); assertFalse(model.state.value.incompleteRange)
        assertEquals(2000L, model.state.value.notifications.first().id)
    }
    @Test fun activeReadPostQueuesAndCoalescesHintsWithoutCancellationOrReplay() = runTest(dispatcher) {
        open(); api.postGate = CompletableDeferred(); model.markRead(api.session.value, 650); runCurrent()
        assertTrue(model.state.value.writing); repeat(100) { model.invalidate() }
        model.refresh(api.session.value); runCurrent(); assertEquals(1, api.pages.size)
        assertEquals(1, api.ones); assertEquals(0, api.cancelledPosts)
        api.postGate!!.complete(Unit); runCurrent()
        assertEquals(1, api.ones); assertEquals(0, api.alls); assertEquals(3, api.pages.size)
        assertFalse(model.state.value.busy); assertFalse(model.state.value.unknownRead)
    }
    @Test fun allIsExplicitAndIncludesUnloadedAndNewlyArrivedNoticesAtOperationTime() = runTest(dispatcher) {
        open(); val request = choice(); assertEquals(0, api.alls)
        api.rows = listOf(notice(800)) + api.rows
        model.confirmAll(request); runCurrent()
        assertEquals(1, api.alls); assertTrue(api.rows.all { it.read }); assertEquals(0, model.state.value.unreadCount)
        assertTrue(model.state.value.notifications.all { model.state.value.isRead(it) })
        model.confirmAll(request); runCurrent(); assertEquals(1, api.alls)
    }
    @Test fun replacedOrBackgroundedAllDialogCallbacksCannotConsumeASuccessor() = runTest(dispatcher) {
        open(); val old = choice(); val replacement = choice()
        model.cancelAll(old); model.confirmAll(old); runCurrent()
        assertSame(replacement, model.state.value.allChoice); assertEquals(0, api.alls)
        model.background(); model.confirmAll(replacement); model.foreground(); runCurrent()
        assertNull(model.state.value.allChoice); assertEquals(0, api.alls)
    }
    @Test fun unconfirmedAllNeverReplaysAndPassiveGetDoesNotSupplyExplicitReconciliation() = runTest(dispatcher) {
        open(); api.postFailure = ApiFailure(FailureKind.TIMEOUT)
        model.confirmAll(choice()); runCurrent(); assertTrue(model.state.value.unknownRead)
        assertEquals(1, api.alls); api.postFailure = null; model.invalidate(); runCurrent()
        assertTrue(model.state.value.unknownRead); model.requestAll(api.session.value)
        assertNull(model.state.value.allChoice); assertEquals(1, api.alls)
        model.refresh(api.session.value); runCurrent(); assertFalse(model.state.value.unknownRead)
        assertNotNull(choice()); assertEquals(1, api.alls)
    }
    @Test fun confirmedPostSurvivesFailedGetWithLocalReadOverlayAndNoFabricatedTimestamp() = runTest(dispatcher) {
        open(); api.failGetAfterPost = true; model.markRead(api.session.value, 650); runCurrent()
        val state = model.state.value; val item = state.notifications.first()
        assertTrue(state.isRead(item)); assertFalse(item.read); assertNull(item.readAt)
        assertTrue(state.stale); assertFalse(state.unknownRead); assertEquals(17, state.unreadCount)
        api.failGetAfterPost = false; model.refresh(api.session.value); runCurrent()
        assertTrue(model.state.value.notifications.first().read); assertTrue(model.state.value.confirmedReadIds.isEmpty())
        assertEquals(1, api.ones)
    }
    @Test fun unconfirmedReadCanBrowseOlderAfterPassiveRefreshWithoutGrantingAnotherReadChoice() = runTest(dispatcher) {
        open(); api.postFailure = ApiFailure(FailureKind.TIMEOUT)
        model.markRead(api.session.value, 650); runCurrent(); assertTrue(model.state.value.unknownRead)
        api.postFailure = null; model.invalidate(); runCurrent()
        assertFalse(model.state.value.stale); assertTrue(model.state.value.unknownRead)
        val before = api.pages.size; model.older(api.session.value); runCurrent()
        assertEquals(before + 1, api.pages.size); assertEquals(626L, api.pages.last().before)
        assertEquals(50, model.state.value.notifications.size); assertTrue(model.state.value.unknownRead)
        model.markRead(api.session.value, 649); model.requestAll(api.session.value); runCurrent()
        assertEquals(1, api.ones); assertEquals(0, api.alls); assertNull(model.state.value.allChoice)
    }
    @Test fun backgroundCancellationKeepsAttemptedPostUnknownAndNeverRetriesItOnResume() = runTest(dispatcher) {
        open(); api.postGate = CompletableDeferred(); model.markRead(api.session.value, 650); runCurrent()
        model.background(); runCurrent(); assertTrue(model.state.value.unknownRead)
        assertEquals(1, api.cancelledPosts); api.postGate!!.complete(Unit)
        model.foreground(); runCurrent(); assertTrue(model.state.value.unknownRead); assertEquals(1, api.ones)
    }
    @Test fun oldRenderedOneAllAndRefreshCannotActOnAReplacementAccountBeforeTheCollectorRuns() = runTest(dispatcher) {
        open(); val original = api.session.value; val selected = choice()
        api.session.value = original.copy(generation = 2)
        model.markRead(original, 650); model.confirmAll(selected); model.refresh(original); model.open(original)
        assertEquals(0, api.ones); assertEquals(0, api.alls); assertEquals(1, api.pages.size)
        runCurrent(); assertFalse(model.belongsTo(original)); assertNull(model.state.value.allChoice)
        assertTrue(model.state.value.confirmedReadIds.isEmpty())
    }
    @Test fun lateUncooperativeGetCannotRepopulateTheNewAccountsPrivateFeed() = runTest(dispatcher) {
        open(); api.ignoreCancellation = true; api.getGate = CompletableDeferred()
        model.invalidate(); runCurrent()
        api.rows = listOf(notice(9001)); val gate = api.getGate; api.getGate = null
        api.session.value = api.session.value.copy(generation = 2, user = SessionUser(9, "New viewer", listOf("member"), 1, false))
        runCurrent(); assertEquals(9001L, model.state.value.notifications.single().id)
        gate!!.complete(Unit); runCurrent(); assertEquals(9001L, model.state.value.notifications.single().id)
    }
    @Test fun permissionLossClearsPrivateContentButCsrfAndNetworkFailuresDoNotPretendRevocation() = runTest(dispatcher) {
        open(); api.getFailure = ApiFailure(FailureKind.HTTP, 403, "csrf_failed")
        model.refresh(api.session.value); runCurrent(); assertTrue(model.state.value.notifications.isNotEmpty())
        api.getFailure = ApiFailure(FailureKind.NETWORK); model.refresh(api.session.value); runCurrent()
        assertTrue(model.state.value.notifications.isNotEmpty())
        api.getFailure = ApiFailure(FailureKind.HTTP, 403, "forbidden"); model.refresh(api.session.value); runCurrent()
        assertTrue(model.state.value.notifications.isEmpty()); assertNull(model.state.value.unreadCount)
        assertFalse(model.state.value.loaded)
    }
    @Test fun rateLimitAndGuestScopesCannotStartWritesOrSilentlyRetryThem() = runTest(dispatcher) {
        open(); api.session.value = api.session.value.copy(retryAtMillis = 2000)
        model.markRead(api.session.value, 650); model.requestAll(api.session.value); runCurrent()
        assertEquals(0, api.ones); assertEquals(0, api.alls)
        now = 3000; model.refresh(api.session.value); runCurrent(); assertEquals(0, api.ones)
        api.session.value = api.session.value.copy(phase = SessionPhase.GUEST)
        model.markRead(api.session.value, 650); runCurrent()
        assertFalse(model.belongsTo(api.session.value)); assertTrue(model.state.value.notifications.isEmpty())
    }
    @Test fun generatedPresentationStringsNeverContainPrivateNoticeText() = runTest(dispatcher) {
        open(); assertFalse(model.state.value.toString().contains("Private fixture message"))
    }
    @Test fun clearingTheOwnerDropsPrivateStateAndCannotBeReactivatedByStaleCallbacks() = runTest(dispatcher) {
        open(); val original = api.session.value; val selected = choice(); store.clear(); runCurrent()
        assertTrue(model.state.value.notifications.isEmpty()); assertNull(model.state.value.unreadCount)
        assertFalse(model.belongsTo(original)); model.foreground(); model.open(original); model.refresh(original)
        model.markRead(original, 650); model.confirmAll(selected); model.requestAll(original); runCurrent()
        assertEquals(1, api.pages.size); assertEquals(0, api.ones); assertEquals(0, api.alls)
        assertFalse(model.state.value.busy); assertNull(model.state.value.allChoice)
    }
    @Test fun clearingDuringAnUncooperativePostCannotPublishItsLateResultOrStartFollowupGets() = runTest(dispatcher) {
        open(); val original = api.session.value; api.postGate = CompletableDeferred(); api.ignorePostCancellation = true
        model.markRead(original, 650); runCurrent(); assertTrue(model.state.value.writing)
        store.clear(); api.postGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.notifications.isEmpty()); assertNull(model.state.value.unreadCount)
        assertEquals(1, api.ones); assertEquals(1, api.pages.size); assertFalse(model.belongsTo(original))
        model.foreground(); model.markRead(original, 650); runCurrent(); assertEquals(1, api.ones)
    }

    private class Operations : NotificationOperations {
        override val session = MutableStateFlow(SessionState(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
            generation = 1, phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        data class PageCall(val original: SessionState, val before: Long?, val limit: Int)
        val pages = mutableListOf<PageCall>()
        var rows = (1L..650).reversed().map { notice(it) }
        var unread = 9000; var ones = 0; var alls = 0; var cancelledPosts = 0
        var getGate: CompletableDeferred<Unit>? = null; var postGate: CompletableDeferred<Unit>? = null
        var getFailure: ApiFailure? = null; var postFailure: ApiFailure? = null
        var ignoreCancellation = false; var ignorePostCancellation = false; var failGetAfterPost = false
        override suspend fun page(expected: SessionState, beforeId: Long?, limit: Int): NotificationPage {
            pages += PageCall(expected, beforeId, limit)
            val selected = rows.filter { beforeId == null || it.id < beforeId }.take(limit)
            getGate?.let { if (ignoreCancellation) withContext(NonCancellable) { it.await() } else it.await() }
            getFailure?.let { throw it }
            if (failGetAfterPost && ones + alls > 0) throw ApiFailure(FailureKind.NETWORK)
            return NotificationPage(selected, unread, selected.lastOrNull()?.id, selected.size == limit)
        }
        override suspend fun markRead(expected: SessionState, ids: List<Long>): NotificationReadResult {
            ones++; awaitPost()
            rows = rows.map { if (it.id in ids) notice(it.id, read = true) else it }; unread = 17
            return NotificationReadResult(0, unread) // Already-confirmed/other-device outcomes may update zero rows.
        }
        override suspend fun markAllRead(expected: SessionState): NotificationReadResult {
            alls++; awaitPost(); rows = rows.map { notice(it.id, read = true) }; unread = 0
            return NotificationReadResult(rows.size, unread)
        }
        private suspend fun awaitPost() {
            try {
                postGate?.let { if (ignorePostCancellation) withContext(NonCancellable) { it.await() } else it.await() }
                postFailure?.let { throw it }
            }
            catch (cancelled: CancellationException) { cancelledPosts++; throw cancelled }
        }
    }
    companion object {
        private const val stamp = "2026-10-21T00:00:00Z"
        private fun notice(id: Long, read: Boolean = false) = AccountNotification(id, "security", "Fixture title",
            "Private fixture message", emptyList(), null, read, stamp.takeIf { read }, stamp)
    }
}
