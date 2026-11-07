package io.github.thiesi.chitchat.feature.completion

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelStore
import io.github.thiesi.chitchat.core.mentions.*
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerCompletionUiTest {
    private val dispatcher=StandardTestDispatcher()
    private val api=Operations()
    private val store=ViewModelStore()
    private lateinit var model: CompletionViewModel
    private lateinit var pane: CompletionPane
    private lateinit var editor: CompletionEditor
    private var body=""
    private var writes=0
    private var typingInputs=0
    private var traversals=0
    private var allowEdit=true
    private var details: Any?=null
    @Before fun setup() {
        Dispatchers.setMain(dispatcher);model=CompletionViewModel(api) { 100 };store.put("completion",model)
        model.foreground();model.navigate("entry");pane=checkNotNull(model.bind(api.session.value,CompletionTarget.Room(42),"entry"))
        editor=CompletionEditor("")
    }
    @After fun cleanup() { store.clear();dispatcher.scheduler.runCurrent();Dispatchers.resetMain() }
    private fun bridge(): ComposerCompletion {
        val source=details
        return ComposerCompletion(editor,model,pane,model.state.value,{body},
            { fromDetails -> allowEdit && if (fromDetails) source!=null && details===source else details==null },{true},
            { body=it;writes++;true },{typingInputs++},{emptyList()},nextField={traversals++})
    }
    private fun original()=checkNotNull(model.state.value.selection)
    private suspend fun TestScope.offer(text: String="@An",caret: Int=text.length) {
        body=text;editor.value=TextFieldValue(text,TextRange(caret));model.input(pane,editor.snapshot())
        runCurrent();advanceTimeBy(150);runCurrent()
    }

    @Test fun aLateTextCallbackCannotRepopulateAnExternallyClearedDraft()=runTest(dispatcher) {
        offer();val old=bridge();body=""
        old.input(TextFieldValue("@Ann",TextRange(4)))
        assertEquals("",body);assertEquals(0,writes);assertEquals(0,typingInputs)
        assertNull(old.choice)
    }
    @Test fun caretOnlyInputDoesNotWriteOrPublishTypingAndStillRetiresThePreviousChoice()=runTest(dispatcher) {
        offer();val old=original();val controller=bridge()
        controller.input(TextFieldValue("@An",TextRange(2)))
        assertEquals(0,writes);assertEquals(0,typingInputs);assertEquals(2,editor.value.selection.start)
        assertNotSame(old,original());assertEquals("A",original().token.prefix)
    }
    @Test fun anIdenticalInputEchoCannotReplayAQueryOrInventATypingInput()=runTest(dispatcher) {
        offer();val before=api.requests.size;val choice=original()
        bridge().input(editor.value);advanceTimeBy(1000);runCurrent()
        assertEquals(before,api.requests.size);assertSame(choice,original());assertEquals(0,writes);assertEquals(0,typingInputs)
    }
    @Test fun activeImeCompositionSurvivesTheBridgeWithoutMentionRequestsUntilCommit()=runTest(dispatcher) {
        runCurrent();val value=TextFieldValue("@An",TextRange(3),TextRange(0,3));bridge().input(value)
        advanceTimeBy(500);runCurrent()
        assertEquals(value,editor.value);assertEquals("@An",body);assertEquals(1,writes);assertEquals(1,typingInputs)
        assertNull(model.state.value.selection);assertTrue(api.requests.none { it.limit==8 })
        assertFalse(bridge().tab());assertFalse(bridge().traverseTab());assertEquals(0,traversals)
        bridge().input(TextFieldValue("@An",TextRange(3)));advanceTimeBy(150);runCurrent()
        assertEquals("An",original().token.prefix);assertEquals(1,writes);assertEquals(1,typingInputs)
    }
    @Test fun aDetailsCallbackCannotEditOrDismissASuccessorDetailsWindow()=runTest(dispatcher) {
        offer();details=Any();val old=bridge();val choice=original();val candidate=model.state.value.candidates.first()
        details=Any()
        assertFalse(old.choose(choice,candidate,true));old.dismiss(choice,true)
        assertEquals("@An",body);assertEquals(0,writes);assertSame(choice,original())
    }
    @Test fun anAcceptedExplicitChoiceWritesTheWholeDraftAndCaretExactlyOnce()=runTest(dispatcher) {
        offer("Hi @AnTAIL\nline",6);val controller=bridge();val choice=original();val candidate=model.state.value.candidates.first()
        assertTrue(controller.choose(choice,candidate,false))
        assertEquals("Hi @Ann TAIL\nline",body);assertEquals(8,editor.value.selection.start)
        assertEquals(editor.value.selection.start,editor.value.selection.end);assertEquals(1,writes);assertEquals(1,typingInputs)
        assertNull(model.state.value.selection)
    }
    @Test fun ownerRetirementRejectsAnOldBridgeEvenWithIdenticalDraftCharacters()=runTest(dispatcher) {
        offer();val old=bridge();val choice=original();val candidate=model.state.value.candidates.first()
        api.session.value=api.session.value.copy(user=SessionUser(9,"Other",listOf("member"),1,false),generation=2)
        assertFalse(old.choose(choice,candidate,false));assertEquals("@An",body);assertEquals(0,writes)
        assertFalse(old.tab());assertFalse(old.traverseTab());assertEquals(0,traversals)
    }
    @Test fun unmatchedTabDefersTraversalUntilThePostImeHandlerWithoutEditingOrTyping()=runTest(dispatcher) {
        offer("NoMatch")
        assertFalse(bridge().tab());assertEquals(0,traversals)
        assertTrue(bridge().traverseTab());assertEquals("NoMatch",body)
        assertEquals(1,traversals);assertEquals(0,writes);assertEquals(0,typingInputs)
    }
    @Test fun matchingTabUsesTheScopedLocalSeedWithoutMovingFocus()=runTest(dispatcher) {
        offer("An")
        assertTrue(bridge().tab());assertEquals("Ann: ",body)
        assertEquals(0,traversals);assertEquals(1,writes);assertEquals(1,typingInputs)
    }
    private class Operations: RoomMentionOperations {
        override val session=MutableStateFlow(SessionState(server=(ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
            generation=1,phase=SessionPhase.AUTHENTICATED,user=SessionUser(7,"Viewer",listOf("member"),1,false)))
        val requests=mutableListOf<RoomMentionRequest>()
        override suspend fun suggestions(expected: SessionState,request: RoomMentionRequest): RoomMentionBatch {
            requests+=request;return RoomMentionBatch(request,request.prefix,listOf(RoomMentionUser(8,"Ann")))
        }
    }
}
