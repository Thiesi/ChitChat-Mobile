package io.github.thiesi.chitchat.feature.completion

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.mentions.ComposerEdit
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.Lantern

internal class CompletionEditor(body: String) {
    var value by mutableStateOf(TextFieldValue(body,TextRange(body.length)))
    fun snapshot()=ComposerEdit(value.text,value.selection.start,value.selection.end,value.composition!=null)
}

/** Presentation bridge; the feature-owned draft callback remains the only writer. */
internal class ComposerCompletion internal constructor(private val editor: CompletionEditor,
    private val model: CompletionViewModel?, private val pane: CompletionPane?, private val rendered: CompletionState?,
    private val currentBody: () -> String?, private val canEdit: (Boolean) -> Boolean, private val canOffer: () -> Boolean,
    private val write: (String) -> Boolean, private val onTextChanged: (String) -> Unit, private val localNames: () -> List<String>,
    private val inlineChoices: Boolean=true, private val nextField: () -> Unit = {}) {
    val value get()=editor.value
    val choice: CompletionSelection? get()=rendered?.selection?.takeIf {
        it.pane===pane && it.edit.same(editor.snapshot()) && currentBody()==value.text && canOffer()
    }
    private fun accept(replacement: ComposerEdit, original: ComposerEdit, fromDetails: Boolean): Boolean {
        if (!canEdit(fromDetails) || !canOffer() || currentBody()!=original.text || !editor.snapshot().same(original)) return false
        if (!write(replacement.text)) return false
        editor.value=TextFieldValue(replacement.text,TextRange(replacement.start,replacement.end))
        onTextChanged(replacement.text)
        return true
    }
    fun input(next: TextFieldValue) {
        if (next==value || !canEdit(false) || currentBody()!=value.text) return
        val changed=next.text!=value.text
        if (changed && !write(next.text)) return
        editor.value=next
        if (changed) onTextChanged(next.text)
        if (model!=null && pane!=null) {
            if (canOffer()) model.input(pane,editor.snapshot()) else model.quiet(pane)
        }
    }
    fun choose(original: CompletionSelection, candidate: CompletionCandidate, fromDetails: Boolean): Boolean {
        if (model==null || original!==choice) return false
        val edit=editor.snapshot()
        return model.choose(original,candidate,edit) { accept(it,edit,fromDetails) }
    }
    fun retry(original: CompletionSelection, fromDetails: Boolean) {
        if (original===choice && canEdit(fromDetails) && canOffer()) model?.retry(original)
    }
    fun dismiss(original: CompletionSelection, fromDetails: Boolean) {
        if (original===choice && canEdit(fromDetails)) model?.dismiss(original)
    }
    fun key(event: KeyEvent): Boolean {
        if (event.type!=KeyEventType.KeyDown || event.isShiftPressed ||
            event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        if (event.key==Key.Tab) return tab()
        if (model==null || pane==null || !canEdit(false) || !canOffer() ||
            value.composition!=null || currentBody()!=value.text) return false
        val pending=choice
        val original=pending?.takeIf { inlineChoices }
        return when(event.key) {
            Key.Enter -> if (original!=null && rendered!!.candidates.isNotEmpty())
                choose(original,rendered.candidates[rendered.highlighted],false) else false
            Key.DirectionDown -> original?.let { model.move(it,1) } ?: false
            Key.DirectionUp -> original?.let { model.move(it,-1) } ?: false
            Key.Escape -> if (original!=null) { model.dismiss(original);true } else false
            else -> false
        }
    }
    fun navigationKey(event: KeyEvent): Boolean {
        if (event.type!=KeyEventType.KeyDown || event.key!=Key.Tab || event.isShiftPressed ||
            event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        return traverseTab()
    }
    private fun canTab(): Boolean {
        if (!canEdit(false) || value.composition!=null || currentBody()!=value.text) return false
        return model==null || pane!=null && model.state.value.pane===pane && pane.matches(model.session.value)
    }
    internal fun tab(): Boolean {
        if (!canTab()) return false
        if (model!=null && pane!=null && canOffer()) {
            if (!inlineChoices && choice!=null) model.quiet(pane)
            val edit=editor.snapshot()
            return model.tab(pane,edit,localNames()) { accept(it,edit,false) }
        }
        return false
    }
    internal fun traverseTab(): Boolean {
        if (!canTab()) return false
        // Post-IME only: Android has performed its normal touch-to-keyboard transition.
        nextField()
        return true
    }
    internal val candidates get()=rendered?.candidates.orEmpty()
    internal val loading get()=rendered?.loading==true
    internal val failed get()=rendered?.failed==true
    internal val highlighted get()=rendered?.highlighted ?: 0
}

@Composable
internal fun rememberComposerCompletion(model: CompletionViewModel?, expected: SessionState, target: CompletionTarget,
    entryId: String, body: String, currentBody: () -> String?, canEdit: (Boolean) -> Boolean, canOffer: () -> Boolean,
    write: (String) -> Boolean, onTextChanged: (String) -> Unit, localNames: () -> List<String> = { emptyList() },
    inlineChoices: Boolean=true): ComposerCompletion {
    val editor=remember(expected.server,expected.generation,expected.user?.id,expected.user?.sessionVersion,
        entryId,target is CompletionTarget.Direct,target.id,(target as? CompletionTarget.Direct)?.username) { CompletionEditor(body) }
    val focus=LocalFocusManager.current
    val state=model?.state?.collectAsStateWithLifecycle()?.value
    val pane=state?.pane?.takeIf { it.matches(expected) && it.entryId==entryId && when {
        it.target is CompletionTarget.Room && target is CompletionTarget.Room -> it.target.id==target.id
        it.target is CompletionTarget.Direct && target is CompletionTarget.Direct ->
            it.target.id==target.id && it.target.username==target.username
        else -> false
    } }
    val offer=canOffer()
    LaunchedEffect(model,expected.server,expected.generation,expected.user?.id,expected.user?.sessionVersion,
        entryId,state?.entryId,target is CompletionTarget.Direct,target.id,(target as? CompletionTarget.Direct)?.username,offer) {
        if (offer) model?.bind(expected,target,entryId)
    }
    SideEffect {
        val canonical=currentBody()
        if (canonical!=null && canonical!=editor.value.text) editor.value=TextFieldValue(canonical,TextRange(canonical.length))
        if (model!=null && pane!=null) {
            model.editorChanged(pane,editor.snapshot())
            if (!offer) model.quiet(pane)
        }
    }
    return ComposerCompletion(editor,model,pane,state,currentBody,canEdit,canOffer,write,onTextChanged,localNames,inlineChoices,
        nextField={ focus.moveFocus(FocusDirection.Next) })
}

@Composable
internal fun CompletionSuggestions(composer: ComposerCompletion, fromDetails: Boolean=false, onAccepted: () -> Unit = {}) {
    val original=composer.choice ?: return
    val palette=if (MaterialTheme.colorScheme.background.luminance()<0.5f) Lantern.dark else Lantern.light
    val identitySize=Lantern.badge*LocalDensity.current.fontScale.coerceAtLeast(1f)
    key(original) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val limit=maxWidth.coerceAtLeast(Lantern.target)
    Row(Modifier.heightIn(min=Lantern.target).horizontalScroll(rememberScrollState()).testTag("completion_choices"),
        horizontalArrangement=Arrangement.spacedBy(Lantern.gap), verticalAlignment=Alignment.CenterVertically) {
        composer.candidates.forEachIndexed { index,candidate ->
            key(candidate) {
            val label=stringResource(if (candidate is CompletionCandidate.Broadcast) R.string.completion_broadcast else R.string.completion_person,
                candidate.username)
            val selected=index==composer.highlighted
            val bring=remember { BringIntoViewRequester() }
            var placed by remember { mutableStateOf(false) }
            LaunchedEffect(selected,placed) { if (selected && placed) bring.bringIntoView() }
            TextButton(onClick={ if (composer.choose(original,candidate,fromDetails)) onAccepted() },
                modifier=Modifier.heightIn(min=Lantern.target).widthIn(min=Lantern.target,max=limit).bringIntoViewRequester(bring).onPlaced { placed=true }.testTag("completion_candidate:$index")
                    .semantics { this.selected=selected;contentDescription=label },
                shape=Lantern.pill, border=BorderStroke(if (selected) Lantern.selectedEdge else Lantern.quietEdge,palette.boundary),
                colors=ButtonDefaults.textButtonColors(containerColor=palette.raised,contentColor=palette.text),
                contentPadding=PaddingValues(horizontal=Lantern.inset,vertical=Lantern.gap)) {
                val person=candidate as? CompletionCandidate.Person
                Box(Modifier.size(identitySize).background(Lantern.identity(person?.id ?: original.pane.target.id),
                    if (person!=null) Lantern.person else Lantern.room).clearAndSetSemantics {},contentAlignment=Alignment.Center) {
                    Text(if (person==null) "@" else candidate.username.take(candidate.username.offsetByCodePoints(0,1)),color=Lantern.ink,style=MaterialTheme.typography.labelSmall)
                }
                Text(label,modifier=Modifier.weight(1f,fill=false).padding(start=Lantern.gap).clearAndSetSemantics {},maxLines=1,overflow=TextOverflow.Ellipsis,
                    fontWeight=if (selected) FontWeight.Bold else FontWeight.Normal)
            }
            }
        }
        if (composer.loading) Text(stringResource(R.string.completion_loading),Modifier.widthIn(max=limit),color=palette.muted)
        else if (composer.failed) {
            Text(stringResource(R.string.completion_failed),Modifier.widthIn(max=limit),color=palette.muted)
            TextButton(onClick={ composer.retry(original,fromDetails) },modifier=Modifier.heightIn(min=Lantern.target).widthIn(max=limit),
                colors=ButtonDefaults.textButtonColors(contentColor=palette.actionText)) {
                Text(stringResource(R.string.completion_retry))
            }
        } else if (composer.candidates.isEmpty()) Text(stringResource(R.string.completion_empty),Modifier.widthIn(max=limit),color=palette.muted)
        TextButton(onClick={ composer.dismiss(original,fromDetails) },modifier=Modifier.heightIn(min=Lantern.target).widthIn(max=limit).testTag("completion_dismiss"),
            colors=ButtonDefaults.textButtonColors(contentColor=palette.actionText)) {
            Text(stringResource(R.string.completion_dismiss))
        }
    }
    }
    }
}
