package io.github.thiesi.chitchat.core.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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
 * Observation only, null in production: told which form a drawn line shows and its visible text. The line's semantics
 * are the full line whatever the form (see [RateWaitLine]), so tests can't read the visible form from the tree.
 */
val LocalRateLineProbe = staticCompositionLocalOf<((short: Boolean, visible: String) -> Unit)?> { null }

/** The known wait's deadline for [notice], fenced to this generation and account, or null. */
private fun SessionState.deadline(notice: RateLimitNotice) =
    rateWaits[notice.budget]?.takeIf { it.generation == generation && it.userId == user?.id }?.untilElapsed

@Composable
private fun waitWords(remaining: Long) =
    if (remaining < 60_000) stringResource(R.string.rate_wait_under_minute) else waitText(Wait.of(remaining))

/**
 * #323b (PL ruling): "You've … recently. Try again in {wait}." as a level-1 muted line, never error colour.
 * - **Known wait:** from the stored deadline at render, frozen until the notice changes (minutes and hours never tick;
 *   under a minute reads "less than a minute"). At the deadline ONE composition-bound one-shot clears the line. It never
 *   sends, and there's no other timer.
 * - **Unknown wait:** "…Try again later." until the screen drops the notice (its next interaction).
 * - **[short]** (PL refinement): "Try again in {wait}." / "Try again later." where the full line doesn't fit; the screen
 *   puts the full line first in Details. Same style, {wait} rules and one-shot.
 * - **Spoken text is always the full line** (PL ruling): the node's only semantics are the full line as its description,
 *   a polite live region and one test tag, identical in both forms. It's announced once when the notice appears, and a
 *   full/short swap (the keyboard, a rotation) changes nothing a screen reader sees, so it stays silent. Static text, not
 *   a control, so visible and spoken text may differ, as with P-25.
 */
@Composable
fun RateWaitLine(session: SessionState, notice: RateLimitNotice, @StringRes known: Int, @StringRes unknown: Int,
    modifier: Modifier = Modifier, short: Boolean = false) {
    val (visible, spoken) = if (notice.knownWait) {
        val clock = LocalMonotonicClock.current
        val deadline = session.deadline(notice) ?: return
        var over by remember(deadline) { mutableStateOf(clock() >= deadline) }
        if (!over) LaunchedEffect(deadline) { delay((deadline - clock()).coerceAtLeast(0)); over = true }
        if (over) return
        val remaining = remember(notice, deadline) { deadline - clock() }
        val words = waitWords(remaining)
        stringResource(if (short) R.string.rate_wait_short else known, words) to stringResource(known, words)
    } else stringResource(if (short) R.string.rate_wait_short_unknown else unknown) to stringResource(unknown)
    LocalRateLineProbe.current?.let { probe -> SideEffect { probe(short, visible) } }
    Text(visible, modifier.clearAndSetSemantics {
        contentDescription = spoken; liveRegion = LiveRegionMode.Polite; testTag = "rate_wait_line"
    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/**
 * #323b refinement (PL, TL): the measurements behind the composer's full-or-short choice, held by the screen.
 * [short] says whether the short form is on screen; Details then shows the full line first.
 */
@Stable
class RateLineFit {
    internal var timeline by mutableStateOf<Pair<Any, Int>?>(null)
    internal var line by mutableStateOf<Pair<Any, Int>?>(null)
    var short by mutableStateOf(false)
        internal set
}

/** The measurement key: a measurement counts only for the notice and window it was taken in. */
fun rateFitKey(notice: RateLimitNotice?, window: Any): Any = listOf(notice, window)

/** On the screen's timeline (the column's only weighted block): its height in each layout pass, tagged with the key. */
fun Modifier.rateFitTimeline(fit: RateLineFit, notice: RateLimitNotice?, window: Any): Modifier {
    val key = rateFitKey(notice, window)
    return onGloballyPositioned { fit.timeline = key to it.size.height }
}

/**
 * #323b refinement (PL, TL): the composer's wait line, full or short. The composer comes first: the full line shows only
 * if the timeline keeps one 48dp row beside it. The timeline's height with the line absent is its measured height plus
 * the line's own measured slot ([spacing] included), both taken in the same layout pass. The timeline is the column's
 * only weighted block, so that height already excludes the composer and every other block, and the composer, which is
 * unweighted, keeps its normal arrangement while the timeline has room.
 *
 * - **Decided once per notice and [window]** (width, the height above the keyboard, font scale, and whether the reply
 *   chip and the attachment card or notices are present: layout, never draft content), so it can't flip while typing.
 *   A tall draft at decision time only makes it more conservative.
 * - **No frame wait:** it decides from measurements tagged with the current key, as soon as a layout pass provides them.
 *   While they're pending the previous form holds; with none yet, the short form shows. The line is never absent while
 *   [notice] exists (apart from its own deadline one-shot).
 */
@Composable
fun RateWaitSlot(fit: RateLineFit, session: SessionState, notice: RateLimitNotice?, @StringRes known: Int,
    @StringRes unknown: Int, window: Any, availableWidth: Dp, spacing: Dp, modifier: Modifier = Modifier) {
    val key = rateFitKey(notice, window)
    var last by remember(notice) { mutableStateOf<Boolean?>(null) }
    var decided by remember(key) { mutableStateOf<Boolean?>(null) }
    if (notice == null) {
        SideEffect { fit.short = false }
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium
    val fullText = if (notice.knownWait) {
        val clock = LocalMonotonicClock.current
        session.deadline(notice)?.let { deadline -> remember(notice, deadline) { deadline - clock() } }
            ?.takeIf { it > 0 }?.let { stringResource(known, waitWords(it)) }
    } else stringResource(unknown)
    val timeline = fit.timeline
    val line = fit.line
    val fresh = decided ?: if (fullText != null && timeline?.first == key && line?.first == key) {
        val widthPx = with(density) { availableWidth.roundToPx() }.coerceAtLeast(1)
        val spacingPx = with(density) { spacing.roundToPx() }
        val need = measurer.measure(AnnotatedString(fullText), style, constraints = Constraints(maxWidth = widthPx)).size.height +
            spacingPx + with(density) { 48.dp.roundToPx() }
        timeline.second + line.second + spacingPx >= need
    } else null
    if (decided == null && fresh != null) SideEffect { decided = fresh; last = fresh }
    val full = fresh ?: last ?: false
    SideEffect { fit.short = fullText != null && !full }
    RateWaitLine(session, notice, known, unknown, modifier.onGloballyPositioned { fit.line = key to it.size.height }, short = !full)
}
