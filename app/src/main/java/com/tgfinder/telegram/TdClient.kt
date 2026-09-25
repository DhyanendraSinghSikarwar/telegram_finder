package com.tgfinder.telegram

import android.content.Context
import android.os.Build
import com.tgfinder.BuildConfig
import com.tgfinder.data.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class TdException(val code: Int, message: String) : Exception(message)

/** Telegram asked us to slow down (error 429 "retry after N"). */
class FloodWaitException(val seconds: Int) : TdException(429, "Telegram asked to wait $seconds seconds before searching again.")

sealed interface AuthState {
    data object Loading : AuthState
    /** API ID / hash not entered yet. */
    data object NeedsConfig : AuthState
    data class ConfigError(val message: String) : AuthState
    data object WaitPhone : AuthState
    data class WaitCode(val phone: String, val via: String, val canResend: Boolean) : AuthState
    data class WaitPassword(val hint: String) : AuthState
    data object WaitEmail : AuthState
    data class WaitEmailCode(val pattern: String) : AuthState
    data class Unsupported(val message: String) : AuthState
    data object Ready : AuthState
    data object LoggingOut : AuthState
}

enum class ConnectionStatus { WAITING_FOR_NETWORK, CONNECTING, UPDATING, READY }

data class ChatInfo(val id: Long, val title: String, val hasProtectedContent: Boolean)

/**
 * Thin coroutine wrapper around the TDLib Java [Client]. One instance per process.
 */
class TdClient(private val context: Context, private val prefs: SecurePrefs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var client: Client? = null

    private val _auth = MutableStateFlow<AuthState>(AuthState.Loading)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionStatus.CONNECTING)
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    /** Wall-clock millis until which searches must not be sent. */
    private val _floodWaitUntil = MutableStateFlow(0L)
    val floodWaitUntil: StateFlow<Long> = _floodWaitUntil.asStateFlow()

    private val _fileUpdates = MutableSharedFlow<TdApi.File>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val fileUpdates: SharedFlow<TdApi.File> = _fileUpdates.asSharedFlow()

    private val files = ConcurrentHashMap<Int, TdApi.File>()
    private val fileLock = ReentrantLock()
    private val fileChanged = fileLock.newCondition()

    private val chats = ConcurrentHashMap<Long, ChatInfo>()

    val databaseDir: File get() = File(context.filesDir, "tdlib")
    val filesDir: File get() = File(context.filesDir, "tdlib_files")

    fun start() {
        synchronized(this) {
            if (client != null) return
            runCatching { Client.execute(TdApi.SetLogVerbosityLevel(if (BuildConfig.DEBUG) 2 else 0)) }
            client = Client.create({ onUpdate(it) }, null, null)
        }
    }

    /** Call after the user saved API ID/hash while TDLib waits for parameters. */
    fun onConfigChanged() {
        when (_auth.value) {
            is AuthState.NeedsConfig, is AuthState.ConfigError -> scope.launch { sendParameters() }
            // Mid-login with possibly wrong credentials: restart TDLib so the new API ID/hash is used.
            is AuthState.Ready, is AuthState.LoggingOut, is AuthState.Loading -> Unit
            else -> scope.launch { runCatching { send(TdApi.Close()) } }
        }
    }

    // ---- requests ---------------------------------------------------------------------------

    suspend fun <R : TdApi.Object> send(function: TdApi.Function<R>): R = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) {
            cont.resumeWithException(TdException(-1, "Telegram client is not running"))
            return@suspendCancellableCoroutine
        }
        c.send(function) { result ->
            if (result is TdApi.Error) {
                cont.resumeWithException(toException(result))
            } else {
                @Suppress("UNCHECKED_CAST")
                cont.resume(result as R)
            }
        }
    }

    /** Blocking variant for ExoPlayer loader threads. Throws [IOException] on failure. */
    fun <R : TdApi.Object> sendBlocking(function: TdApi.Function<R>, timeoutMs: Long = 30_000): R {
        val c = client ?: throw IOException("Telegram client is not running")
        val latch = CountDownLatch(1)
        val ref = AtomicReference<TdApi.Object>()
        c.send(function) { ref.set(it); latch.countDown() }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("Telegram did not respond in time")
        val result = ref.get()
        if (result is TdApi.Error) throw IOException(toException(result).message)
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    /** Throws [FloodWaitException] without contacting Telegram while a flood wait is active. */
    fun checkFloodWait() {
        val remaining = _floodWaitUntil.value - System.currentTimeMillis()
        if (remaining > 0) throw FloodWaitException(((remaining + 999) / 1000).toInt())
    }

    private fun toException(error: TdApi.Error): TdException {
        if (error.code == 429) {
            val seconds = Regex("""retry after (\d+)""", RegexOption.IGNORE_CASE).find(error.message)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""FLOOD_WAIT_(\d+)""").find(error.message)?.groupValues?.get(1)?.toIntOrNull()
                ?: 30
            _floodWaitUntil.value = System.currentTimeMillis() + seconds * 1000L
            return FloodWaitException(seconds)
        }
        return TdException(error.code, error.message)
    }

    // ---- auth -------------------------------------------------------------------------------

    suspend fun setPhone(phone: String) = send(
        TdApi.SetAuthenticationPhoneNumber().apply {
            phoneNumber = phone
            settings = TdApi.PhoneNumberAuthenticationSettings()
        }
    )

    suspend fun checkCode(code: String) = send(TdApi.CheckAuthenticationCode().apply { this.code = code })
    suspend fun resendCode() = send(TdApi.ResendAuthenticationCode())
    suspend fun checkPassword(password: String) = send(TdApi.CheckAuthenticationPassword().apply { this.password = password })
    suspend fun setEmail(email: String) = send(TdApi.SetAuthenticationEmailAddress().apply { emailAddress = email })
    suspend fun checkEmailCode(code: String) = send(
        TdApi.CheckAuthenticationEmailCode().apply { this.code = TdApi.EmailAddressAuthenticationCode().apply { this.code = code } }
    )

    /** Logs out; also used to go back to the phone-number step during login. */
    suspend fun logOut() {
        send(TdApi.LogOut())
    }

    private suspend fun sendParameters() {
        val cfg = prefs.config.value
        if (!cfg.hasTelegram) {
            _auth.value = AuthState.NeedsConfig
            return
        }
        _auth.value = AuthState.Loading
        try {
            send(
                TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    databaseDirectory = databaseDir.absolutePath
                    filesDirectory = filesDir.absolutePath
                    databaseEncryptionKey = prefs.databaseKey()
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = cfg.apiId
                    apiHash = cfg.apiHash
                    systemLanguageCode = Locale.getDefault().language.ifBlank { "en" }
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                    systemVersion = "Android ${Build.VERSION.RELEASE}"
                    applicationVersion = BuildConfig.VERSION_NAME
                }
            )
        } catch (e: TdException) {
            if (e.message.orEmpty().contains("encryption key", ignoreCase = true)) {
                // The stored key was lost (e.g. app data partially restored): start with a fresh session.
                databaseDir.deleteRecursively()
                send(TdApi.Close())
            } else {
                _auth.value = AuthState.ConfigError(ErrorMessages.forTelegram(e))
            }
        }
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        _auth.value = when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                scope.launch { sendParameters() }
                AuthState.Loading
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> AuthState.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> AuthState.WaitCode(
                phone = state.codeInfo.phoneNumber,
                via = describeCodeType(state.codeInfo.type),
                canResend = state.codeInfo.nextType != null,
            )
            is TdApi.AuthorizationStateWaitPassword -> AuthState.WaitPassword(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateWaitEmailAddress -> AuthState.WaitEmail
            is TdApi.AuthorizationStateWaitEmailCode -> AuthState.WaitEmailCode(state.codeInfo?.emailAddressPattern.orEmpty())
            is TdApi.AuthorizationStateWaitRegistration -> AuthState.Unsupported(
                "There is no Telegram account for this phone number. Sign up in the official Telegram app first."
            )
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> AuthState.Unsupported(
                "Telegram asked to confirm this login on another device. Try logging in again with your phone number."
            )
            is TdApi.AuthorizationStateWaitPremiumPurchase -> AuthState.Unsupported(
                "Telegram requires a Premium subscription to log in to this account from a new app."
            )
            is TdApi.AuthorizationStateReady -> AuthState.Ready
            is TdApi.AuthorizationStateLoggingOut -> AuthState.LoggingOut
            is TdApi.AuthorizationStateClosing -> AuthState.Loading
            is TdApi.AuthorizationStateClosed -> {
                // After logOut/close the client cannot be reused: start a fresh one.
                synchronized(this) { client = null }
                chats.clear()
                files.clear()
                start()
                AuthState.Loading
            }
            else -> _auth.value
        }
    }

    private fun describeCodeType(type: TdApi.AuthenticationCodeType?): String = when (type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> "in your Telegram app"
        is TdApi.AuthenticationCodeTypeSms, is TdApi.AuthenticationCodeTypeSmsWord, is TdApi.AuthenticationCodeTypeSmsPhrase -> "by SMS"
        is TdApi.AuthenticationCodeTypeCall -> "by phone call"
        is TdApi.AuthenticationCodeTypeFragment -> "on fragment.com"
        else -> "by Telegram"
    }

    // ---- updates ----------------------------------------------------------------------------

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuthState(obj.authorizationState)
            is TdApi.UpdateNewChat -> chats[obj.chat.id] = ChatInfo(obj.chat.id, obj.chat.title, obj.chat.hasProtectedContent)
            is TdApi.UpdateChatTitle -> chats.computeIfPresent(obj.chatId) { _, c -> c.copy(title = obj.title) }
            is TdApi.UpdateChatHasProtectedContent ->
                chats.computeIfPresent(obj.chatId) { _, c -> c.copy(hasProtectedContent = obj.hasProtectedContent) }
            is TdApi.UpdateFile -> {
                files[obj.file.id] = obj.file
                _fileUpdates.tryEmit(obj.file)
                fileLock.withLock { fileChanged.signalAll() }
            }
            is TdApi.UpdateConnectionState -> _connection.value = when (obj.state) {
                is TdApi.ConnectionStateWaitingForNetwork -> ConnectionStatus.WAITING_FOR_NETWORK
                is TdApi.ConnectionStateReady -> ConnectionStatus.READY
                is TdApi.ConnectionStateUpdating -> ConnectionStatus.UPDATING
                else -> ConnectionStatus.CONNECTING
            }
            else -> Unit
        }
    }

    // ---- chats & files ----------------------------------------------------------------------

    suspend fun chat(chatId: Long): ChatInfo {
        chats[chatId]?.let { return it }
        val chat = send(TdApi.GetChat(chatId))
        return ChatInfo(chat.id, chat.title, chat.hasProtectedContent).also { chats[chat.id] = it }
    }

    /** Latest known state of a file (from updates), if any. */
    fun cachedFile(fileId: Int): TdApi.File? = files[fileId]

    /** Blocks the calling (loader) thread until any file update arrives or [timeoutMs] passes. */
    fun awaitFileUpdate(timeoutMs: Long) {
        fileLock.withLock { fileChanged.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** Downloads a small file (thumbnail) synchronously and returns its local path. */
    suspend fun downloadSmallFile(fileId: Int): String? {
        files[fileId]?.local?.let { if (it.isDownloadingCompleted && it.path.isNotEmpty()) return it.path }
        val file = send(TdApi.DownloadFile(fileId, 1, 0, 0, true))
        return file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotEmpty() }
    }
}
