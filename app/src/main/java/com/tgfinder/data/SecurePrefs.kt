package com.tgfinder.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tgfinder.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import android.util.Base64

/**
 * Credentials (Telegram API ID/hash, TMDB key) and app settings, stored with
 * EncryptedSharedPreferences. Values are never logged.
 */
@Suppress("DEPRECATION") // security-crypto is deprecated but remains the documented way to encrypt prefs on API 24+.
class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences = open(context)

    data class Config(val apiId: Int, val apiHash: String, val tmdbKey: String) {
        val hasTelegram: Boolean get() = apiId > 0 && apiHash.isNotBlank()
        val hasTmdb: Boolean get() = tmdbKey.isNotBlank()
    }

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.DARK)
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _cacheLimitGb = MutableStateFlow(prefs.getInt(KEY_CACHE_GB, 2))
    val cacheLimitGb: StateFlow<Int> = _cacheLimitGb.asStateFlow()

    fun saveConfig(apiId: Int, apiHash: String, tmdbKey: String) {
        prefs.edit {
            putInt(KEY_API_ID, apiId)
            putString(KEY_API_HASH, apiHash.trim())
            putString(KEY_TMDB, tmdbKey.trim())
        }
        _config.value = readConfig()
    }

    fun setTheme(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _theme.value = mode
    }

    fun setCacheLimitGb(gb: Int) {
        prefs.edit { putInt(KEY_CACHE_GB, gb) }
        _cacheLimitGb.value = gb
    }

    /** Random key used to encrypt the TDLib database, created once per install. */
    fun databaseKey(): ByteArray {
        prefs.getString(KEY_DB_KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit { putString(KEY_DB_KEY, Base64.encodeToString(key, Base64.NO_WRAP)) }
        return key
    }

    private fun readConfig() = Config(
        apiId = prefs.getInt(KEY_API_ID, 0),
        apiHash = prefs.getString(KEY_API_HASH, "") ?: "",
        tmdbKey = prefs.getString(KEY_TMDB, "") ?: "",
    )

    companion object {
        private const val FILE = "tgfinder_secure"
        private const val KEY_API_ID = "api_id"
        private const val KEY_API_HASH = "api_hash"
        private const val KEY_TMDB = "tmdb_key"
        private const val KEY_THEME = "theme"
        private const val KEY_CACHE_GB = "cache_gb"
        private const val KEY_DB_KEY = "td_db_key"

        private fun open(context: Context): SharedPreferences {
            fun create(): SharedPreferences {
                val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    context, FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                create()
            } catch (e: Exception) {
                // The keystore key can be lost (e.g. after a device restore). Start over rather than crash.
                context.deleteSharedPreferences(FILE)
                create()
            }
        }
    }
}
