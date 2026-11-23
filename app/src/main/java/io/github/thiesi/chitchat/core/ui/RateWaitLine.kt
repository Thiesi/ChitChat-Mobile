package io.github.thiesi.chitchat.core.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
 * #323b (PL ruling): "You've … recently. Try again in {wait}." as a level-1 muted line, never error colour.
 * - **Known wait:** from the stored deadline at render, frozen until the notice changes (minutes and hours never tick;
 *   under a minute reads "less than a minute"). At the deadline ONE composition-bound one-shot clears the line. It never
 *   sends, and there's no other timer.
 * - **Unknown wait:** "…Try again later." until the screen drops the notice (its next interaction).
 * - Announced once through a polite live region.
 */
@Composable
fun RateWaitLine(session: SessionState, notice: RateLimitNotice, @StringRes known: Int, @StringRes unknown: Int,
    modifier: Modifier = Modifier) {
    val text = if (notice.knownWait) {
        val clock = LocalMonotonicClock.current
        val deadline = session.rateWaits[notice.budget]
            ?.takeIf { it.generation == session.generation && it.userId == session.user?.id }?.untilElapsed ?: return
        var over by remember(deadline) { mutableStateOf(clock() >= deadline) }
        if (!over) LaunchedEffect(deadline) { delay((deadline - clock()).coerceAtLeast(0)); over = true }
        if (over) return
        val remaining = remember(notice, deadline) { deadline - clock() }
        stringResource(known, if (remaining < 60_000) stringResource(R.string.rate_wait_under_minute) else waitText(Wait.of(remaining)))
    } else stringResource(unknown)
    Text(text, modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("rate_wait_line"),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}
