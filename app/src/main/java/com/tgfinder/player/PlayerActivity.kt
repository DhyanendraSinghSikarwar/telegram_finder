package com.tgfinder.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.tgfinder.TgFinderApp
import com.tgfinder.data.db.PlaybackPositionEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Full-screen landscape player for either a Telegram file (streamed through [TelegramDataSource])
 * or a downloaded file (content:// or file path).
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    private val container by lazy { (application as TgFinderApp).container }
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var saveJob: Job? = null

    private val fileId by lazy { intent.getIntExtra(EXTRA_FILE_ID, 0) }
    private val localUri by lazy { intent.getStringExtra(EXTRA_LOCAL_URI) }
    private val resumeKey by lazy { intent.getStringExtra(EXTRA_RESUME_KEY) ?: "file:$fileId" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        playerView = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            keepScreenOn = true
        }
        setContentView(playerView)
        initPlayer()
    }

    private fun initPlayer() {
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val source = if (localUri != null) {
            ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this))
                .createMediaSource(MediaItem.fromUri(Uri.parse(localUri)))
        } else {
            ProgressiveMediaSource.Factory(TelegramDataSource.Factory(container.td))
                .createMediaSource(MediaItem.fromUri(TelegramDataSource.uriFor(fileId)))
        }

        val exo = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        exo.setMediaSource(source)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, describe(error), Toast.LENGTH_LONG).show()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    lifecycleScope.launch { container.db.playback().delete(resumeKey) }
                }
            }
        })
        playerView.player = exo
        player = exo

        lifecycleScope.launch {
            val saved = container.db.playback().get(resumeKey)
            if (saved != null && saved.positionMs > 10_000 &&
                (saved.durationMs <= 0 || saved.positionMs < saved.durationMs - 30_000)
            ) {
                exo.seekTo(saved.positionMs)
                Toast.makeText(this@PlayerActivity, "Resuming from ${formatTime(saved.positionMs)}", Toast.LENGTH_SHORT).show()
            }
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun describe(error: PlaybackException): String {
        val cause = generateSequence(error.cause) { it.cause }.mapNotNull { it.message }.firstOrNull()
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "This phone cannot decode this video's format. Try another version."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This file type or file is not playable."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No internet connection."
            else -> cause ?: "Playback failed (${error.errorCodeName})."
        }
    }

    override fun onStart() {
        super.onStart()
        saveJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                savePosition()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        saveJob?.cancel()
        savePosition()
        player?.pause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun savePosition() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (pos <= 0) return
        container.appScope.launch {
            container.db.playback().put(PlaybackPositionEntity(resumeKey, pos, dur, System.currentTimeMillis()))
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        if (localUri == null && fileId != 0) {
            val td = container.td
            val downloads = container.downloads
            container.appScope.launch {
                // Stop streaming unless the same file is being saved by the download manager.
                if (!downloads.isDownloadingFile(fileId)) {
                    runCatching { td.send(TdApi.CancelDownloadFile(fileId, false)) }
                }
                container.cache.trimIfNeeded()
            }
        }
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_LOCAL_URI = "local_uri"
        private const val EXTRA_RESUME_KEY = "resume_key"

        fun streamIntent(context: Context, fileId: Int, resumeKey: String) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_FILE_ID, fileId)
                .putExtra(EXTRA_RESUME_KEY, resumeKey)

        fun localIntent(context: Context, uri: String, resumeKey: String) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_LOCAL_URI, uri)
                .putExtra(EXTRA_RESUME_KEY, resumeKey)

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
