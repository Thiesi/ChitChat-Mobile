package io.github.thiesi.chitchat.core.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.session.RateLimitNotice
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.status.Wait
import io.github.thiesi.chitchat.feature.status.waitText
import kotlinx.coroutines.delay

/**
 * #323b: the monotonic clock rate waits are measured on. Production is `SystemClock.elapsedRealtime`, the same clock
 * SessionRepository's gate uses; tests and captures provide their own, never wall time.
 */
val LocalMonotonicClock = staticCompositionLocalOf<() -> Long> { { android.os.SystemClock.elapsedRealtime() } }

/**
 * Observation only, null in production: told which form a drawn composer line shows and its visible text. The line's
 * semantics are the full line whatever the form, so tests can't read the visible form from the tree.
 */
val LocalRateLineProbe = staticCompositionLocalOf<((short: Boolean, visible: String) -> Unit)?> { null }

/** The known wait's deadline for [notice], fenced to this generation and account, or null. */
private fun SessionState.deadline(notice: RateLimitNotice) =
    rateWaits[notice.budget]?.takeIf { it.generation == generation && it.userId == user?.id }?.untilElapsed

@Composable
private fun waitWords(remaining: Long) =
    if (remaining < 60_000) stringResource(R.string.rate_wait_under_minute) else waitText(Wait.of(remaining))

/** A current notice's wait words, null for an unknown wait; frozen per notice. Null result from [current] means over. */
private class Current(val words: String?)

/**
 * The notice's wait while it's current: a known wait until its deadline (ONE composition-bound one-shot ends it; it
 * never sends), an unknown one until the screen drops the notice. Null when there's nothing to show or say.
 */
@Composable
private fun current(session: SessionState, notice: RateLimitNotice?): Current? {
    notice ?: return null
    if (!notice.knownWait) return Current(null)
    val clock = LocalMonotonicClock.current
    val deadline = session.deadline(notice) ?: return null
    var over by remember(deadline) { mutableStateOf(clock() >= deadline) }
    if (!over) LaunchedEffect(deadline) { delay((deadline - clock()).coerceAtLeast(0)); over = true }
    if (over) return null
    val remaining = remember(notice, deadline) { deadline - clock() }
    return Current(waitWords(remaining))
}

@Composable
private fun fullText(now: Current, @StringRes known: Int, @StringRes unknown: Int) =
    now.words?.let { stringResource(known, it) } ?: stringResource(unknown)

/**
 * #323b (PL ruling): "You've … recently. Try again in {wait}." as a level-1 muted line, never error colour.
 * - **Known wait:** {wait} from the stored deadline at render, frozen until the notice changes (minutes and hours never
 *   tick; under a minute reads "less than a minute"); gone at the deadline. **Unknown:** "…Try again later." until the
 *   screen drops the notice (its next interaction).
 * - **[short]** (PL refinement): "Try again in {wait}." / "Try again later." where the full line doesn't fit.
 * - **Silent text** (PL/TL, after the 100 ms snapshot finding in rate-budgets.md): its only semantics are the full line as
 *   its description, the same in both forms, and no live region. Send carries every announcement ([rateWaitState]).
 * - [tag] is the composer line's test tag; the Details copy has none.
 */
@Composable
fun RateWaitLine(session: SessionState, notice: RateLimitNotice, @StringRes known: Int, @StringRes unknown: Int,
    modifier: Modifier = Modifier, short: Boolean = false, tag: String? = null) {
    val now = current(session, notice) ?: return
    val spoken = fullText(now, known, unknown)
    val visible = if (!short) spoken else now.words?.let { stringResource(R.string.rate_wait_short, it) }
        ?: stringResource(R.string.rate_wait_short_unknown)
    if (tag != null) LocalRateLineProbe.current?.let { probe -> SideEffect { probe(short, visible) } }
    Text(visible, modifier.clearAndSetSemantics {
        contentDescription = spoken
        if (tag != null) testTag = tag
    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/**
 * #323b2 (PL ruling): the shared message-mutation wait in the edit/delete dialog (on its refusal line) and the reaction
 * picker (above the emoji rows). The same persistent carrier as the composer's: always composed while the dialog shows, a
 * polite live region whose description is the full line while a notice is current (one trailing space flipped per refused
 * tap: [parity]) and nothing otherwise, a zero-footprint 1dp strip when there's nothing to say. So the line appearing and
 * each refused tap are one change on an existing node (one announcement each), and an empty carrier is no swipe stop.
 */
@Composable
fun mutationNotice(session: SessionState, notice: RateLimitNotice?, budget: io.github.thiesi.chitchat.core.session.RateBudget): RateLimitNotice? {
    // A wait already known (another edit, delete or reaction started it) shows as soon as the dialog opens (PL); opening
    // sends nothing. The refusal's own notice wins, so an unknown wait still reads "Try again later.".
    val clock = LocalMonotonicClock.current
    return notice ?: RateLimitNotice(budget, true).takeIf {
        session.rateWaits[budget]?.let { wait -> wait.generation == session.generation && wait.userId == session.user?.id &&
            wait.untilElapsed > clock() } == true
    }
}

@Composable
fun RateWaitDialogLine(session: SessionState, notice: RateLimitNotice?, @StringRes known: Int, @StringRes unknown: Int,
    tag: String, modifier: Modifier = Modifier, parity: Boolean = false,
    drawnPadding: androidx.compose.foundation.layout.PaddingValues? = null, onShown: ((initial: Boolean) -> Unit)? = null) {
    val now = current(session, notice)
    val full = now?.let { fullText(it, known, unknown) }
    val spoken = full?.let { if (parity) "$it " else it }
    // [onShown] (TL, PL): each time the line appears (initial: it's there as the dialog opens) and on each refused tap
    // (the parity flips), so the dialog can bring it into view. Nothing else: no focus or caret change.
    if (onShown != null) {
        val opening = remember { booleanArrayOf(true) }
        val shown = full != null
        LaunchedEffect(shown, parity) {
            if (shown) onShown(opening[0])
            opening[0] = false
        }
    }
    // [drawnPadding] applies only while the line is drawn: an empty carrier adds nothing to its container.
    val drawn = if (drawnPadding != null) Modifier.padding(drawnPadding) else Modifier
    Text(full.orEmpty(), modifier.then(if (full != null) drawn else ZeroFootprintStrip).clearAndSetSemantics {
        if (spoken != null) contentDescription = spoken
        liveRegion = LiveRegionMode.Polite
        testTag = tag
    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/**
 * #323b (PL, TL): Send's readable wait. While a notice is current, Send keeps its label ("Send") and its state description
 * is the full line, so focusing Send reads "Send, You've sent…". No live region: a button changes several properties
 * at once (label, enabled, progress), and on a live node each change is an announcement (run 45222016407: three per
 * appearance). The composer's wait line carries the announcement ([RateWaitSlot]).
 */
fun Modifier.rateWaitState(spoken: String?): Modifier = if (spoken == null) this else semantics { stateDescription = spoken }

/** Send's spoken wait for [notice], or null when no notice is current (known wait over, none at all). */
@Composable
fun rateWaitSpoken(session: SessionState, notice: RateLimitNotice?, @StringRes known: Int, @StringRes unknown: Int): String? =
    current(session, notice)?.let { fullText(it, known, unknown) }

/** The composer line's three forms (PL refinement and the TL's amended condition 3). */
enum class RateLineForm { FULL, SHORT, HIDDEN }

/**
 * #323b refinement (PL, TL): the measurements behind the composer's form, held by the screen. [hidden] lights the Details
 * attention dot; [detailsFirst] puts the full line first in Details (short or hidden).
 */
@Stable
class RateLineFit {
    internal var timeline by mutableStateOf<Pair<Any, Int>?>(null)
    internal var lineSlot by mutableIntStateOf(0)
    var form by mutableStateOf<RateLineForm?>(null)
        internal set
    /** True when [form] was decided for the current window (not a held or fallback form): a decision after layout. */
    var settled by mutableStateOf(false)
        internal set
    val hidden get() = form == RateLineForm.HIDDEN
    val detailsFirst get() = form == RateLineForm.SHORT || form == RateLineForm.HIDDEN
}

/** On the screen's timeline (the column's only weighted block): its height in each layout pass, tagged with the window. */
fun Modifier.rateFitTimeline(fit: RateLineFit, window: Any): Modifier =
    onGloballyPositioned { fit.timeline = window to it.size.height }

/**
 * #323b refinement (PL, TL): the composer's wait line. The composer comes first. With the timeline's height with the
 * line absent (its measured height plus the drawn line's own slot, its top [spacing] included, from the same layout
 * pass):
 * - **FULL** only if the timeline keeps one 48dp row beside the full line;
 * - **SHORT** if the short form still leaves the composer its natural height (the timeline absorbs it);
 * - **HIDDEN** only where [hiddenAllowed] (compact with the keyboard up, TL's amended condition 3; #364: the PL's J found
 *   200% portrait with the keyboard down hidden, because this branch had no such gate) and even the short form doesn't
 *   fit: no text drawn; the screen lights the Details dot and puts the
 *   full line first in Details. A drawn line with the timeline at 0 may be hiding a squeeze, so that reads HIDDEN. The
 *   node stays the carrier as a 1dp full-width strip at its place, directly above the composer block (TL's branch 2: a
 *   zero-height node announced nothing on run 45233308933); the one accepted extra swipe stop, in this rare state.
 *
 * Decided once per notice and [window] (width, the height above the keyboard, font scale, and whether the reply chip and
 * the attachment card or notices are present: layout, never draft content), so it can't flip while typing. No frame
 * wait: measurements are tagged with the window. While one is pending the previous form holds; with none yet, SHORT.
 *
 * **The announcement carrier (PL and TL, after run 45222016407):** this line is ONE persistent node, always composed,
 * never smaller than 1dp: a 1dp strip with no notice and while hidden (TL, after run 45299066691: a zero-height node is
 * outside the semantics snapshot, so a notice giving it size and text in one change arrived as a new node and didn't
 * reliably announce). With no notice the strip says nothing and offers nothing, so it's no swipe stop.
 *
 * **The strip has zero layout footprint (TL and PL, #348, option A2):** it reports 0 height to its parent and places its
 * 1dp node at its slot's bottom edge, overhanging into the 6dp gap and the composer's top padding below, where no
 * semantics node sits. So the semantics node always has a real 1dp size, even when a packed window allots the timeline's
 * block 0 (200% portrait, a reply chip and the keyboard up), while nothing moves: neither the timeline nor the composer
 * gives anything up, and the composer is measured exactly as before. The strip draws nothing (no background, divider or
 * ripple) and takes no input (no pointer input, no click semantics). The drawn line keeps its real height. Its only semantics: a permanent polite live region, a description that
 * is the full line while a notice is current (with or without one trailing space, flipped per refused tap: [parity])
 * and nothing otherwise, and its test tag. So the notice appearing and each refused tap are one change on an existing
 * node (one announcement), clearing empties it (silent), and full/short/hidden swaps change only its size and visible
 * text, never its semantics. It's the one swipe stop on the wait, where the text is. The caller places it where the
 * strip adds no gap (inside the timeline's block, not as a spaced child): the timeline gives up that 1dp.
 */
/**
 * The deliberate overhang (A2): measured at 1dp whatever the incoming height (even 0), reported to the parent as 0 tall,
 * placed at the slot's top-left so it extends 1dp below the slot's bottom edge.
 */
private val ZeroFootprintStrip = Modifier.layout { measurable, constraints ->
    val strip = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    layout(strip.width, 0) { strip.place(0, 0) }
}.height(1.dp)

@Composable
fun RateWaitSlot(fit: RateLineFit, session: SessionState, notice: RateLimitNotice?, @StringRes known: Int,
    @StringRes unknown: Int, window: Any, availableWidth: Dp, spacing: Dp, modifier: Modifier = Modifier,
    parity: Boolean = false, @StringRes shortKnown: Int = R.string.rate_wait_short,
    @StringRes shortUnknown: Int = R.string.rate_wait_short_unknown, hiddenAllowed: Boolean = false) {
    var last by remember(notice) { mutableStateOf<RateLineForm?>(null) }
    var decided by remember(notice, window) { mutableStateOf<RateLineForm?>(null) }
    val now = current(session, notice)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium
    val full = now?.let { fullText(it, known, unknown) }
    val short = now?.let { it.words?.let { w -> stringResource(shortKnown, w) } ?: stringResource(shortUnknown) }
    val timeline = fit.timeline
    val fresh = if (full == null || short == null) null else decided ?: if (timeline?.first == window) {
        val drawn = fit.lineSlot
        if (drawn > 0 && timeline.second == 0) (if (hiddenAllowed) RateLineForm.HIDDEN else RateLineForm.SHORT)
        else with(density) {
            val widthPx = availableWidth.roundToPx().coerceAtLeast(1)
            fun height(text: String) = measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = widthPx)).size.height
            val absent = timeline.second + drawn
            val spacingPx = spacing.roundToPx()
            when {
                absent >= height(full) + spacingPx + 48.dp.roundToPx() -> RateLineForm.FULL
                // Outside compact with the keyboard up the short form always shows (the timeline absorbs it).
                !hiddenAllowed || absent >= height(short) + spacingPx -> RateLineForm.SHORT
                else -> RateLineForm.HIDDEN
            }
        }
    } else null
    if (fresh != null && decided == null) SideEffect { decided = fresh; last = fresh }
    val form = if (full == null) null else fresh ?: last ?: RateLineForm.SHORT
    val settled = fresh != null
    SideEffect { fit.form = form; fit.settled = settled }
    val shown = when (form) { RateLineForm.FULL -> full; RateLineForm.SHORT -> short; else -> null }
    if (shown != null) LocalRateLineProbe.current?.let { probe -> SideEffect { probe(form == RateLineForm.SHORT, shown) } }
    val spoken = full?.let { if (parity) "$it " else it }
    // Drawn: the text with its top spacing. Otherwise (no notice, or hidden) the 1dp strip with zero footprint (A2): the
    // node always has a real size, and the layout is exactly as with no node at all.
    Text(shown.orEmpty(), modifier.then(if (shown != null) Modifier.padding(top = spacing) else ZeroFootprintStrip)
        .onGloballyPositioned { fit.lineSlot = if (shown != null) it.size.height else 0 }
        .clearAndSetSemantics {
            if (spoken != null) contentDescription = spoken
            liveRegion = LiveRegionMode.Polite
            testTag = "rate_wait_line"
        }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}
