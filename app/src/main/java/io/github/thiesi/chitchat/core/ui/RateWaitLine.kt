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
import androidx.compose.ui.Modifier
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
 * #323b (PL, TL): Send's spoken wait. While a notice is current, Send keeps its label ("Send") and its state description
 * is the full line; a polite live region makes Send the one carrier of every announcement, because Compose 1.11.4 diffs
 * semantics snapshots every 100 ms and a node that appears (the line) isn't reliably announced, while Send always exists.
 * - **First appearance:** the state description appears on an existing node: one announcement.
 * - **Each refused tap** flips [parity]: one trailing space, present or absent, on this state description only (never
 *   the visible line or Details), so each refusal is a real snapshot difference with no timer: one announcement.
 * - **Silent otherwise:** no live region while [sending] (so "Sending" isn't spoken), and clearing drops the state and
 *   the live region in the same change, so the cleared Send isn't announced. Keyboard, rotation and form swaps don't
 *   touch Send.
 */
fun Modifier.rateWaitState(spoken: String?, parity: Boolean, sending: Boolean): Modifier =
    if (spoken == null) this else semantics {
        stateDescription = if (parity) "$spoken " else spoken
        if (!sending) liveRegion = LiveRegionMode.Polite
    }

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
 * line absent (its measured height plus the drawn line's own slot, [spacing] included, from the same layout pass):
 * - **FULL** only if the timeline keeps one 48dp row beside the full line;
 * - **SHORT** if the short form still leaves the composer its natural height (the timeline absorbs it);
 * - **HIDDEN** otherwise (compact with the keyboard up): no line above the composer; the screen lights the Details dot and
 *   puts the full line first there. A drawn line with the timeline at 0 may be hiding a squeeze, so that reads HIDDEN.
 *
 * Decided once per notice and [window] (width, the height above the keyboard, font scale, and whether the reply chip and
 * the attachment card or notices are present: layout, never draft content), so it can't flip while typing; a re-decision
 * (the keyboard hiding, a reply started) can change the form. No frame wait: measurements are tagged with the window, so
 * a notice arriving in a laid-out window decides at once. While a measurement for the window is pending the previous
 * form holds; with none yet, SHORT. The line is silent text (see [RateWaitLine]).
 */
@Composable
fun RateWaitSlot(fit: RateLineFit, session: SessionState, notice: RateLimitNotice?, @StringRes known: Int,
    @StringRes unknown: Int, window: Any, availableWidth: Dp, spacing: Dp, modifier: Modifier = Modifier) {
    var last by remember(notice) { mutableStateOf<RateLineForm?>(null) }
    var decided by remember(notice, window) { mutableStateOf<RateLineForm?>(null) }
    val now = current(session, notice)
    if (notice == null || now == null) {
        SideEffect { fit.form = null; fit.settled = false; fit.lineSlot = 0 }
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium
    val full = fullText(now, known, unknown)
    val short = now.words?.let { stringResource(R.string.rate_wait_short, it) } ?: stringResource(R.string.rate_wait_short_unknown)
    val timeline = fit.timeline
    val fresh = decided ?: if (timeline?.first == window) {
        val drawn = fit.lineSlot
        if (drawn > 0 && timeline.second == 0) RateLineForm.HIDDEN
        else with(density) {
            val widthPx = availableWidth.roundToPx().coerceAtLeast(1)
            fun height(text: String) = measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = widthPx)).size.height
            val absent = timeline.second + drawn
            val spacingPx = spacing.roundToPx()
            when {
                absent >= height(full) + spacingPx + 48.dp.roundToPx() -> RateLineForm.FULL
                absent >= height(short) + spacingPx -> RateLineForm.SHORT
                else -> RateLineForm.HIDDEN
            }
        }
    } else null
    if (decided == null && fresh != null) SideEffect { decided = fresh; last = fresh }
    val form = fresh ?: last ?: RateLineForm.SHORT
    val settled = fresh != null
    SideEffect { fit.form = form; fit.settled = settled }
    if (form == RateLineForm.HIDDEN) {
        SideEffect { fit.lineSlot = 0 }
        return
    }
    val spacingPx = with(density) { spacing.roundToPx() }
    RateWaitLine(session, notice, known, unknown, modifier.onGloballyPositioned { fit.lineSlot = it.size.height + spacingPx },
        short = form == RateLineForm.SHORT, tag = "rate_wait_line")
}
