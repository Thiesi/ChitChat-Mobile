package io.github.thiesi.chitchat.core.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.conversation.serverInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Shared room/DM rendering: account date/clock preference, device locale/timezone otherwise. */
@Composable
fun messageTime(value: String, preferences: DisplayPreferences?): String {
    val instant = serverInstant(value) ?: return stringResource(R.string.conversation_time_unknown)
    val locale = preferences?.dateLocale?.let(Locale::forLanguageTag) ?: LocalConfiguration.current.locales[0]
    val twentyFour = when (preferences?.hourCycle) { "h23" -> true; "h12" -> false; else -> DateFormat.is24HourFormat(LocalContext.current) }
    val date = instant.atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(date) + " " +
        DateTimeFormatter.ofPattern(if (twentyFour) "HH:mm" else "h:mm a", locale).format(date)
}
