package com.tgfinder.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.tgfinder.telegram.ErrorMessages
import com.tgfinder.telegram.TdClient
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Streams a Telegram file to ExoPlayer without waiting for the whole download.
 *
 * On open (and therefore on every seek) it asks TDLib to download from the requested offset with
 * high priority. Reads block until TDLib has written the needed bytes, then read them straight from
 * TDLib's partially-downloaded file.
 *
 * URI format: `tg://file/<fileId>`.
 */
@UnstableApi
class TelegramDataSource(private val td: TdClient) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(private val td: TdClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource(td)
    }

    private var uri: Uri? = null
    private var fileId = 0
    private var fileSize = 0L
    private var position = 0L
    private var bytesRemaining = 0L
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null
    private var opened = false
    private var requestedOffset = -1L
    /** Exclusive end of the contiguous range known to be on disk from the last prefix query. */
    private var availableEnd = -1L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        transferInitializing(dataSpec)

        val file = td.sendBlocking(TdApi.GetFile(fileId))
        if (!file.local.canBeDownloaded && !file.local.isDownloadingCompleted) {
            throw IOException(ErrorMessages.FILE_UNAVAILABLE)
        }
        fileSize = if (file.size > 0) file.size else file.expectedSize
        position = dataSpec.position
        if (fileSize > 0 && position > fileSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        if (fileSize <= 0) throw IOException(ErrorMessages.FILE_UNAVAILABLE)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else fileSize - position
        availableEnd = -1L

        requestDownload(position)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /** Tells TDLib to fetch from [offset] onward with top priority (replacing any earlier range). */
    private fun requestDownload(offset: Long) {
        val current = td.cachedFile(fileId)
        if (current?.local?.isDownloadingCompleted == true) return
        td.sendBlocking(TdApi.DownloadFile(fileId, STREAM_PRIORITY, offset, 0, false))
        requestedOffset = offset
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        var available = prefixAvailable(position)
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (available <= 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            if (System.currentTimeMillis() > deadline) throw IOException("Telegram is not sending data. Check your connection.")
            val f = td.cachedFile(fileId)
            // If TDLib stopped (or is downloading a different range), point it back at our position.
            if (f == null || !f.local.isDownloadingActive || f.local.downloadOffset > position ||
                requestedOffset < 0 || position < requestedOffset
            ) {
                if (f?.local?.canBeDownloaded == false && f.local.isDownloadingCompleted.not()) {
                    throw IOException(ErrorMessages.FILE_UNAVAILABLE)
                }
                requestDownload(position)
            }
            td.awaitFileUpdate(WAIT_SLICE_MS)
            available = prefixAvailable(position)
        }

        val path = localPath() ?: throw IOException("Telegram has not created the file yet")
        val file = openFile(path)
        file.seek(position)
        val toRead = minOf(length.toLong(), available, bytesRemaining).toInt()
        val read = file.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    private fun prefixAvailable(at: Long): Long {
        if (at < availableEnd) return availableEnd - at
        val size = td.sendBlocking(TdApi.GetFileDownloadedPrefixSize(fileId, at)).size
        availableEnd = if (size > 0) at + size else -1L
        return size
    }

    private fun localPath(): String? {
        val cached = td.cachedFile(fileId)?.local?.path
        if (!cached.isNullOrEmpty()) return cached
        return td.sendBlocking(TdApi.GetFile(fileId)).local.path.takeIf { it.isNotEmpty() }
    }

    private fun openFile(path: String): RandomAccessFile {
        val existing = raf
        if (existing != null && rafPath == path) return existing
        existing?.close()
        return RandomAccessFile(path, "r").also { raf = it; rafPath = path }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } finally {
            raf = null
            rafPath = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        /** Highest TDLib download priority. */
        const val STREAM_PRIORITY = 32
        private const val READ_TIMEOUT_MS = 60_000L
        private const val WAIT_SLICE_MS = 250L

        fun uriFor(fileId: Int): Uri = Uri.parse("tg://file/$fileId")
    }
}
