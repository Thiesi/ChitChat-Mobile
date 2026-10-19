package io.github.thiesi.chitchat.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.session.RegistrationPreparation
import io.github.thiesi.chitchat.core.session.SessionState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegistrationForm(state: SessionState, model: AuthViewModel, enabled: Boolean) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf<LocalDate?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        password = ""; confirmation = ""; birthDate = null; pickerOpen = false
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val problem = registrationProblem(username, password, confirmation)
    val ready = enabled && state.registration == RegistrationPreparation.READY
    val submit = {
        attempted = true
        if (ready && problem == null) {
            val entered = password
            val date = birthDate?.toString()
            password = ""; confirmation = ""; attempted = false
            focus.clearFocus(); keyboard?.hide()
            model.register(username, entered, date)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.create_account_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.registration_policy))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().semantics { contentType = ContentType.NewUsername },
            label = { Text(stringResource(R.string.username)) }, enabled = enabled, singleLine = true,
            isError = attempted && problem == RegistrationProblem.USERNAME,
            supportingText = { Text(stringResource(R.string.username_policy)) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
            label = { Text(stringResource(R.string.password)) }, enabled = enabled, singleLine = true,
            isError = attempted && problem == RegistrationProblem.PASSWORD,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next))
        OutlinedTextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.confirm_password)) }, enabled = enabled, singleLine = true,
            isError = attempted && problem == RegistrationProblem.CONFIRMATION,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }))
        if (attempted && problem != null) InlineError(stringResource(when (problem) {
            RegistrationProblem.USERNAME -> R.string.username_policy
            RegistrationProblem.PASSWORD -> R.string.password_policy
            RegistrationProblem.CONFIRMATION -> R.string.password_mismatch
        }))
        Text(stringResource(R.string.birth_date_explanation))
        OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); pickerOpen = true }, enabled = enabled) {
            Text(birthDate?.let { stringResource(R.string.selected_birth_date, it.toString()) } ?: stringResource(R.string.add_birth_date))
        }
        if (birthDate != null) TextButton(onClick = { birthDate = null }, enabled = enabled) { Text(stringResource(R.string.remove_birth_date)) }
        when (state.registration) {
            RegistrationPreparation.PREPARING -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.preparing_registration))
            }
            RegistrationPreparation.READY -> Text(stringResource(R.string.registration_ready))
            RegistrationPreparation.IDLE, RegistrationPreparation.FAILED -> TextButton(onClick = model::prepareRegistration, enabled = enabled) {
                Text(stringResource(R.string.refresh_registration_check))
            }
        }
        Button(onClick = submit, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.create_account)) }
    }
    if (pickerOpen) {
        val today = remember { LocalDate.now(ZoneOffset.UTC) }
        val latest = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Birth date, including the picker's internal selection, stays out of saved-instance state.
        val picker = remember { DatePickerState(
            locale = Locale.getDefault(),
            initialSelectedDateMillis = birthDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            yearRange = (today.year - 120)..today.year,
            initialDisplayMode = DisplayMode.Input,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
            },
        ) }
        DatePickerDialog(onDismissRequest = { pickerOpen = false }, confirmButton = {
            TextButton(onClick = {
                birthDate = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                pickerOpen = false
            }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.use_birth_date)) }
        }, dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text(stringResource(android.R.string.cancel)) } }) {
            // Date-input children can be saveable internally even when the outer state is not.
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) { DatePicker(picker) }
        }
    }
}
