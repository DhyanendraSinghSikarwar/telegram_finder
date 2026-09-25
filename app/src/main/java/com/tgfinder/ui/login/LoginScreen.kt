package com.tgfinder.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgfinder.AppContainer
import com.tgfinder.telegram.AuthState
import com.tgfinder.telegram.ConnectionStatus
import com.tgfinder.telegram.ErrorMessages
import com.tgfinder.ui.common.Banner
import com.tgfinder.ui.common.BannerKind
import com.tgfinder.ui.common.containerViewModel
import com.tgfinder.ui.theme.ImdbYellow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LoginViewModel(private val c: AppContainer) : ViewModel() {
    val auth: StateFlow<AuthState> = c.td.auth
    val connection: StateFlow<ConnectionStatus> = c.td.connection

    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                block()
            } catch (e: Exception) {
                error.value = ErrorMessages.forTelegram(e)
            } finally {
                busy.value = false
            }
        }
    }

    fun submitPhone(phone: String) {
        val cleaned = phone.filter { it.isDigit() || it == '+' }
        if (cleaned.count { it.isDigit() } < 7) {
            error.value = "Enter your full phone number with the country code, e.g. +91 98765 43210."
            return
        }
        run { c.td.setPhone(cleaned) }
    }

    fun submitCode(code: String) = run { c.td.checkCode(code.trim()) }
    fun resendCode() = run { c.td.resendCode() }
    fun submitPassword(password: String) = run { c.td.checkPassword(password) }
    fun submitEmail(email: String) = run { c.td.setEmail(email.trim()) }
    fun submitEmailCode(code: String) = run { c.td.checkEmailCode(code.trim()) }
    fun startOver() = run { c.td.logOut() }
    fun clearError() { error.value = null }
}

@Composable
fun LoginScreen(onOpenSettings: () -> Unit) {
    val vm = containerViewModel { LoginViewModel(it) }
    val auth by vm.auth.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val connection by vm.connection.collectAsState()

    LaunchedEffect(auth::class) { vm.clearError() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("TG Finder", style = MaterialTheme.typography.headlineLarge, color = ImdbYellow, fontWeight = FontWeight.Black)
        Text(
            "Log in with your Telegram account to search videos in the chats you have joined.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (connection == ConnectionStatus.WAITING_FOR_NETWORK) Banner(ErrorMessages.NO_INTERNET, BannerKind.ERROR)

        when (val s = auth) {
            AuthState.Loading, AuthState.LoggingOut -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text("Connecting to Telegram…")
            }
            AuthState.NeedsConfig -> {
                Banner("Enter your Telegram API ID and API hash in Settings first.", BannerKind.WARNING)
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }
            is AuthState.ConfigError -> {
                Banner(s.message, BannerKind.ERROR)
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }
            AuthState.WaitPhone -> SingleFieldStep(
                label = "Phone number",
                placeholder = "+91 98765 43210",
                help = "Telegram will send a login code to your Telegram app or by SMS.",
                keyboard = KeyboardType.Phone,
                action = "Send code",
                busy = busy,
                onSubmit = vm::submitPhone,
            )
            is AuthState.WaitCode -> {
                SingleFieldStep(
                    label = "Login code",
                    placeholder = "12345",
                    help = "We sent a code ${s.via} to ${s.phone}.",
                    keyboard = KeyboardType.NumberPassword,
                    action = "Verify",
                    busy = busy,
                    onSubmit = vm::submitCode,
                )
                Row {
                    if (s.canResend) TextButton(onClick = vm::resendCode, enabled = !busy) { Text("Resend code") }
                    TextButton(onClick = vm::startOver, enabled = !busy) { Text("Change number") }
                }
            }
            is AuthState.WaitPassword -> SingleFieldStep(
                label = "Two-step verification password",
                placeholder = "",
                help = if (s.hint.isNotBlank()) "Password hint: ${s.hint}" else "Your account has two-step verification enabled.",
                keyboard = KeyboardType.Password,
                password = true,
                action = "Unlock",
                busy = busy,
                onSubmit = vm::submitPassword,
            )
            AuthState.WaitEmail -> SingleFieldStep(
                label = "Email address",
                placeholder = "you@example.com",
                help = "Telegram requires a login email for this account.",
                keyboard = KeyboardType.Email,
                action = "Continue",
                busy = busy,
                onSubmit = vm::submitEmail,
            )
            is AuthState.WaitEmailCode -> SingleFieldStep(
                label = "Email code",
                placeholder = "",
                help = "Enter the code sent to ${s.pattern.ifBlank { "your email" }}.",
                keyboard = KeyboardType.NumberPassword,
                action = "Verify",
                busy = busy,
                onSubmit = vm::submitEmailCode,
            )
            is AuthState.Unsupported -> {
                Banner(s.message, BannerKind.WARNING)
                Button(onClick = vm::startOver, enabled = !busy) { Text("Start over") }
            }
            AuthState.Ready -> Text("Logged in.")
        }

        error?.let { Banner(it, BannerKind.ERROR) }
        TextButton(onClick = onOpenSettings) { Text("Settings") }
    }
}

@Composable
private fun SingleFieldStep(
    label: String,
    placeholder: String,
    help: String,
    keyboard: KeyboardType,
    action: String,
    busy: Boolean,
    password: Boolean = false,
    onSubmit: (String) -> Unit,
) {
    var value by rememberSaveable(label) { mutableStateOf("") }
    val submit = remember(onSubmit) { { v: String -> if (v.isNotBlank()) onSubmit(v) } }
    Text(help, style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit(value) }),
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
    )
    Button(onClick = { submit(value) }, enabled = !busy && value.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(action)
    }
}
