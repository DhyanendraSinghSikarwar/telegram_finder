package com.tgfinder.download

import android.content.Context
import com.tgfinder.data.db.DownloadDao
import com.tgfinder.data.db.DownloadEntity
import com.tgfinder.search.VideoSearchSession
import com.tgfinder.search.VideoVersion
import com.tgfinder.telegram.ErrorMessages
import com.tgfinder.telegram.TdClient
import com.tgfinder.telegram.TdException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private class DownloadFailure(override val message: String) : Exception(message)

/**
 * Downloads Telegram videos through TDLib, then copies them to Movies/TGFinder. Work runs in the
 * app scope; [DownloadService] keeps the process in the foreground while anything is active.
 */
class DownloadRepository(
    private val context: Context,
    private val td: TdClient,
    private val dao: DownloadDao,
    private val saver: MediaStoreSaver,
    private val scope: CoroutineScope,
) {
    val all: Flow<List<DownloadEntity>> = dao.observeAll()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val activeFileIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val parallel = Semaphore(MAX_PARALLEL)

    /** Downloads left "active" by a previous process cannot continue on their own; mark them paused. */
    suspend fun onAppStart() = dao.pauseAllActive()

    fun isDownloadingFile(fileId: Int) = fileId in activeFileIds
    fun hasActiveWork() = jobs.values.any { it.isActive }

    suspend fun enqueue(version: VideoVersion, title: String, subtitle: String?, posterPath: String?, thumbPath: String?) {
        val existing = dao.get(version.key)
        if (existing != null && existing.status == DownloadEntity.COMPLETED &&
            existing.contentUri != null && saver.exists(existing.contentUri)
        ) return
        if (existing != null && existing.status in DownloadEntity.ACTIVE && jobs[version.key]?.isActive == true) return

        dao.put(
            DownloadEntity(
                key = version.key, chatId = version.chatId, messageId = version.messageId, fileId = version.fileId,
                fileName = version.fileName.ifBlank { fallbackName(title, version.mimeType) },
                mimeType = version.mimeType, title = title, subtitle = subtitle, posterPath = posterPath,
                thumbPath = thumbPath, quality = version.qualityLabel, chatTitle = version.chatTitle,
                totalBytes = version.sizeBytes, downloadedBytes = existing?.downloadedBytes ?: 0,
                status = DownloadEntity.QUEUED, error = null, contentUri = null,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
        )
        launch(version.key)
    }

    suspend fun resume(key: String) {
        dao.setStatus(key, DownloadEntity.QUEUED)
        launch(key)
    }

    suspend fun pause(key: String) {
        jobs.remove(key)?.cancel()
        val e = dao.get(key) ?: return
        runCatching { td.send(TdApi.CancelDownloadFile(e.fileId, false)) } // keeps the partial data for resume
        activeFileIds -= e.fileId
        dao.setStatus(key, DownloadEntity.PAUSED)
    }

    suspend fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        val e = dao.get(key) ?: return
        runCatching { td.send(TdApi.CancelDownloadFile(e.fileId, false)) }
        runCatching { td.send(TdApi.DeleteFile(e.fileId)) }
        activeFileIds -= e.fileId
        dao.delete(key)
    }

    /** Deletes a finished download from storage. Returns false if Android refused to delete the file. */
    suspend fun deleteCompleted(key: String): Boolean {
        val e = dao.get(key) ?: return true
        val ok = e.contentUri == null || withContext(Dispatchers.IO) { saver.delete(e.contentUri) }
        if (ok) dao.delete(key)
        return ok
    }

    /** Removes the entry from the list without touching the file. */
    suspend fun forget(key: String) = dao.delete(key)

    private fun launch(key: String) {
        DownloadService.start(context)
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch { parallel.withPermit { run(key) } }
    }

    private suspend fun run(key: String) {
        val entity = dao.get(key) ?: return
        var fileId = entity.fileId
        try {
            // File ids are not stable across TDLib restarts; refresh from the message every time.
            val message = td.send(TdApi.GetMessage(entity.chatId, entity.messageId))
            if (!message.canBeSaved || td.chat(entity.chatId).hasProtectedContent) {
                throw DownloadFailure("Saving content from this chat is restricted.")
            }
            val version = VideoSearchSession.toVersion(message, entity.chatTitle)
                ?: throw DownloadFailure(ErrorMessages.FILE_UNAVAILABLE)
            fileId = version.fileId
            dao.setFileId(key, fileId)
            activeFileIds += fileId
            dao.setStatus(key, DownloadEntity.DOWNLOADING)

            var file = td.send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0, 0, false))
            var restarts = 0
            var lastReported = -1L
            while (!file.local.isDownloadingCompleted) {
                val total = if (file.size > 0) file.size else file.expectedSize
                if (file.local.downloadedSize != lastReported) {
                    lastReported = file.local.downloadedSize
                    dao.setProgress(key, lastReported, total)
                    restarts = 0
                }
                if (!file.local.isDownloadingActive) {
                    if (!file.local.canBeDownloaded) throw DownloadFailure(ErrorMessages.FILE_UNAVAILABLE)
                    if (++restarts > MAX_RESTARTS) throw DownloadFailure("The download keeps stopping. Check your connection and resume.")
                    file = td.send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0, 0, false))
                }
                delay(1_000)
                file = td.send(TdApi.GetFile(fileId))
            }

            dao.setProgress(key, file.size, file.size)
            dao.setStatus(key, DownloadEntity.SAVING)
            val current = dao.get(key) ?: return
            val uri = withContext(Dispatchers.IO) { saver.save(File(file.local.path), current.fileName, current.mimeType) }
            dao.setCompleted(key, uri)
            // The copy in Movies/TGFinder is the one we keep; free TDLib's internal copy.
            runCatching { td.send(TdApi.DeleteFile(fileId)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is DownloadFailure -> e.message
                is TdException -> ErrorMessages.forTelegram(e)
                else -> ErrorMessages.forNetwork(e)
            }
            dao.setStatus(key, DownloadEntity.FAILED, message)
        } finally {
            activeFileIds -= fileId
            jobs.remove(key)
        }
    }

    private fun fallbackName(title: String, mime: String): String {
        val ext = when {
            mime.contains("matroska") -> "mkv"
            mime.contains("webm") -> "webm"
            mime.contains("quicktime") -> "mov"
            else -> "mp4"
        }
        return "${title.ifBlank { "video" }}.$ext"
    }

    companion object {
        private const val DOWNLOAD_PRIORITY = 16
        private const val MAX_PARALLEL = 2
        private const val MAX_RESTARTS = 30
    }
}
