package com.tgfinder.telegram

import com.tgfinder.data.SecurePrefs
import com.tgfinder.download.DownloadRepository
import org.drinkless.tdlib.TdApi

/**
 * Keeps TDLib's file cache (where streamed video lands) under the limit chosen in Settings.
 * Runs only when no download is in progress, so partial downloads are never trimmed.
 */
class StreamCache(
    private val td: TdClient,
    private val prefs: SecurePrefs,
    private val downloads: DownloadRepository,
) {
    private val videoTypes: Array<TdApi.FileType> get() = arrayOf(TdApi.FileTypeVideo(), TdApi.FileTypeDocument())

    suspend fun trimIfNeeded() {
        if (downloads.hasActiveWork()) return
        val limit = prefs.cacheLimitGb.value.toLong() * 1024 * 1024 * 1024
        runCatching {
            if (td.send(TdApi.GetStorageStatisticsFast()).filesSize <= limit) return
            td.send(optimize(size = limit, ttl = -1, immunityDelay = 0))
        }
    }

    /** Deletes all cached streaming data. Returns false if a download is running. */
    suspend fun clearAll(): Boolean {
        if (downloads.hasActiveWork()) return false
        runCatching { td.send(optimize(size = 0, ttl = 0, immunityDelay = 0)) }
        return true
    }

    suspend fun usageBytes(): Long =
        runCatching { td.send(TdApi.GetStorageStatisticsFast()).filesSize }.getOrDefault(0L)

    private fun optimize(size: Long, ttl: Int, immunityDelay: Int) = TdApi.OptimizeStorage().apply {
        this.size = size
        this.ttl = ttl
        count = -1
        this.immunityDelay = immunityDelay
        fileTypes = videoTypes
        chatIds = LongArray(0)
        excludeChatIds = LongArray(0)
        returnDeletedFileStatistics = false
        chatLimit = 0
    }
}
