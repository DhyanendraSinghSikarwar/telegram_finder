package com.tgfinder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgfinder.AppContainer
import com.tgfinder.BuildConfig
import com.tgfinder.download.DownloadService
import com.tgfinder.telegram.AuthState
import com.tgfinder.telegram.ErrorMessages
import com.tgfinder.ui.common.Banner
import com.tgfinder.ui.common.BannerKind
import com.tgfinder.ui.common.containerViewModel
import com.tgfinder.ui.theme.ImdbYellow
import com.tgfinder.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val config = c.prefs.config
    val theme = c.prefs.theme
    val cacheLimitGb = c.prefs.cacheLimitGb
    val auth = c.td.auth
    val cacheUsage = MutableStateFlow<Long?>(null)
    val message = MutableStateFlow<String?>(null)

    fun refreshUsage() = viewModelScope.launch {
        if (c.td.auth.value == AuthState.Ready) cacheUsage.value = c.cache.usageBytes()
    }

    /** Returns an error message, or null when saved. */
    fun save(apiIdText: String, apiHash: String, tmdbKey: String): String? {
        val id = apiIdText.trim().toIntOrNull()
        if (id == null || id <= 0) return "API ID must be a number (from my.telegram.org)."
        if (!apiHash.trim().matches(Regex("[0-9a-fA-F]{32}"))) return "API hash should be 32 letters/digits (from my.telegram.org)."
        val oldConfig = c.prefs.config.value
        c.prefs.saveConfig(id, apiHash, tmdbKey)
        c.td.onConfigChanged()
        message.value = if (c.td.auth.value == AuthState.Ready && (oldConfig.apiId != id || oldConfig.apiHash != apiHash.trim())) {
            "Saved. A new API ID/hash is used the next time you log in."
        } else {
            "Saved."
        }
        return null
    }

    fun setTheme(mode: ThemeMode) = c.prefs.setTheme(mode)

    fun setCacheLimit(gb: Int) {
        c.prefs.setCacheLimitGb(gb)
        viewModelScope.launch { c.cache.trimIfNeeded(); refreshUsage() }
    }

    fun clearCache() = viewModelScope.launch {
        message.value = if (c.cache.clearAll()) "Streaming cache cleared." else "Finish or pause downloads before clearing the cache."
        refreshUsage()
    }

    fun logOut() = viewModelScope.launch {
        try {
            c.td.logOut()
        } catch (e: Exception) {
            message.value = ErrorMessages.forTelegram(e)
        }
    }
}

@Composable
fun SettingsScreen(setupMode: Boolean, onBack: (() -> Unit)? = null) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val config by vm.config.collectAsState()
    val theme by vm.theme.collectAsState()
    val cacheGb by vm.cacheLimitGb.collectAsState()
    val usage by vm.cacheUsage.collectAsState()
    val auth by vm.auth.collectAsState()
    val message by vm.message.collectAsState()

    var apiId by rememberSaveable { mutableStateOf(if (config.apiId > 0) config.apiId.toString() else "") }
    var apiHash by rememberSaveable { mutableStateOf(config.apiHash) }
    var tmdbKey by rememberSaveable { mutableStateOf(config.tmdbKey) }
    var showHash by remember { mutableStateOf(false) }
    var showTmdb by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(auth) { vm.refreshUsage() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (setupMode) {
            Spacer(Modifier.height(24.dp))
            Text("Welcome to TG Finder", style = MaterialTheme.typography.headlineMedium, color = ImdbYellow, fontWeight = FontWeight.Black)
            Text(
                "TG Finder is a personal Telegram client. It needs your own Telegram API credentials " +
                    "and, optionally, a free TMDB key for posters and ratings. They are stored encrypted on this phone only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row {
                if (onBack != null) TextButton(onClick = onBack) { Text("← Back") }
            }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SectionTitle("Telegram API")
        Text(
            "Get these at my.telegram.org → API development tools.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiId, onValueChange = { apiId = it.filter(Char::isDigit) }, label = { Text("API ID") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        SecretField("API hash", apiHash, { apiHash = it.trim() }, showHash) { showHash = !showHash }

        SectionTitle("TMDB (posters, ratings, cast)")
        Text(
            "Free at themoviedb.org → Settings → API. The API key (v3) or the read access token both work.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecretField("TMDB API key", tmdbKey, { tmdbKey = it.trim() }, showTmdb) { showTmdb = !showTmdb }
        if (!setupMode && !config.hasTmdb) Banner(ErrorMessages.TMDB_KEY_MISSING, BannerKind.WARNING)

        formError?.let { Banner(it, BannerKind.ERROR) }
        message?.let { Banner(it) }
        Button(
            onClick = { formError = vm.save(apiId, apiHash, tmdbKey) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (setupMode) "Save and continue" else "Save") }

        if (!setupMode) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Streaming cache")
            Text(
                "Streamed video is kept temporarily and trimmed automatically to this size." +
                    (usage?.let { " Currently using ${DownloadService.formatBytes(it)}." } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 5, 10).forEach { gb ->
                    FilterChip(selected = cacheGb == gb, onClick = { vm.setCacheLimit(gb) }, label = { Text("$gb GB") })
                }
            }
            OutlinedButton(onClick = { vm.clearCache() }) { Text("Clear cache now") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = theme == mode,
                        onClick = { vm.setTheme(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            if (auth == AuthState.Ready) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Account")
                Button(
                    onClick = { confirmLogout = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Log out") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "TG Finder ${BuildConfig.VERSION_NAME} · Uses TDLib, TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You will need a new login code to use TG Finder again. Downloaded videos stay in Movies/TGFinder.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logOut() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ImdbYellow)
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit, visible: Boolean, onToggle: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (visible) "Hide" else "Show")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
