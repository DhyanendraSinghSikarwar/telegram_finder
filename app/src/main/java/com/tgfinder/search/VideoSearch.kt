package com.tgfinder.search

import com.tgfinder.telegram.TdClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.drinkless.tdlib.TdApi

/** One concrete file on Telegram (a message in some chat). */
data class VideoVersion(
    val chatId: Long,
    val messageId: Long,
    val chatTitle: String,
    val fileId: Int,
    val uniqueId: String,
    val fileName: String,
    val mimeType: String,
    val caption: String,
    val sizeBytes: Long,
    val durationSec: Int,
    val width: Int,
    val height: Int,
    val thumbFileId: Int,
    val miniThumb: ByteArray?,
    val date: Int,
    val supportsStreaming: Boolean,
    val parsed: ParsedName,
) {
    val key: String get() = "$chatId:$messageId"

    /** Quality from the file name, else derived from the video height. */
    val qualityLabel: String
        get() = parsed.quality ?: when {
            height >= 2000 -> "4K"
            height >= 1000 -> "1080p"
            height >= 700 -> "720p"
            height >= 460 -> "480p"
            height > 0 -> "${height}p"
            else -> "Unknown"
        }

    val qualityRank: Int
        get() = when (qualityLabel) {
            "8K" -> 4320; "4K" -> 2160
            else -> qualityLabel.removeSuffix("p").toIntOrNull() ?: 0
        }

    override fun equals(other: Any?) = other is VideoVersion && other.key == key && other.fileId == fileId
    override fun hashCode() = key.hashCode()
}

/** The same title found in one or more chats/qualities. */
data class MediaGroup(
    val key: String,
    val parsed: ParsedName,
    val versions: List<VideoVersion>,
) {
    val displayTitle: String get() = parsed.title.ifBlank { versions.first().fileName }
    val bestThumb: VideoVersion? get() = versions.firstOrNull { it.thumbFileId != 0 } ?: versions.firstOrNull()
}

/**
 * A paginated keyword search over every chat the account has joined. Runs two TDLib searches in
 * parallel (videos, and documents whose MIME type is video/…), drops content that may not be saved,
 * and merges duplicates into [MediaGroup]s.
 */
class VideoSearchSession(private val td: TdClient, val query: String) {

    private var videoOffset: String? = ""
    private var documentOffset: String? = ""
    private val seenMessages = HashSet<String>()
    private val groups = LinkedHashMap<String, MutableList<VideoVersion>>()
    private val groupParsed = HashMap<String, ParsedName>()

    val hasMore: Boolean get() = videoOffset != null || documentOffset != null

    /** Loads the next page and returns all groups found so far. */
    suspend fun loadMore(): List<MediaGroup> {
        td.checkFloodWait()
        coroutineScope {
            val videos = videoOffset?.let { off -> async { page(TdApi.SearchMessagesFilterVideo(), off) } }
            val docs = documentOffset?.let { off -> async { page(TdApi.SearchMessagesFilterDocument(), off) } }
            videos?.await()?.let { (messages, next) -> videoOffset = next; messages.forEach { add(it) } }
            docs?.await()?.let { (messages, next) -> documentOffset = next; messages.forEach { add(it) } }
        }
        return snapshot()
    }

    fun snapshot(): List<MediaGroup> = groups.map { (key, versions) ->
        MediaGroup(key, groupParsed.getValue(key), versions.sortedByDescending { it.qualityRank })
    }

    private suspend fun page(filter: TdApi.SearchMessagesFilter, offset: String): Pair<List<TdApi.Message>, String?> {
        val found = td.send(
            TdApi.SearchMessages().apply {
                chatList = null // all chats, main list and archive
                this.query = this@VideoSearchSession.query
                this.offset = offset
                limit = PAGE_SIZE
                this.filter = filter
                chatTypeFilter = null
                minDate = 0
                maxDate = 0
            }
        )
        return found.messages.toList() to found.nextOffset.ifEmpty { null }
    }

    private suspend fun add(message: TdApi.Message) {
        // Respect content protection: never offer files that Telegram marks as not savable.
        if (!message.canBeSaved) return
        val chat = runCatching { td.chat(message.chatId) }.getOrNull() ?: return
        if (chat.hasProtectedContent) return
        if (!seenMessages.add("${message.chatId}:${message.id}")) return

        val version = toVersion(message, chat.title) ?: return
        val parsed = version.parsed
        val titleKey = FileNameParser.normalizedKey(parsed.title).ifBlank { version.uniqueId }
        val prefix = "$titleKey|${parsed.episodeLabel.orEmpty()}|"

        var groupKey = prefix + (parsed.year ?: "")
        if (groupKey !in groups) {
            val sameTitle = groups.keys.filter { it.startsWith(prefix) }
            if (parsed.year == null && sameTitle.isNotEmpty()) {
                groupKey = sameTitle.first()
            } else if (parsed.year != null) {
                // A group without a year can adopt this year.
                sameTitle.firstOrNull { it == prefix }?.let { old ->
                    val moved = groups.remove(old)!!
                    groups[groupKey] = moved
                    groupParsed[groupKey] = groupParsed.remove(old)!!.copy(year = parsed.year)
                }
            }
        }
        val list = groups.getOrPut(groupKey) { mutableListOf() }
        if (list.any { it.uniqueId == version.uniqueId }) return // same file forwarded to several chats
        list += version
        if (groupKey !in groupParsed) groupParsed[groupKey] = parsed
    }

    companion object {
        private const val PAGE_SIZE = 50

        fun toVersion(message: TdApi.Message, chatTitle: String): VideoVersion? {
            return when (val content = message.content) {
                is TdApi.MessageVideo -> {
                    val v = content.video
                    val caption = content.caption?.text.orEmpty()
                    VideoVersion(
                        chatId = message.chatId, messageId = message.id, chatTitle = chatTitle,
                        fileId = v.video.id, uniqueId = v.video.remote?.uniqueId.orEmpty(),
                        fileName = v.fileName.orEmpty(), mimeType = v.mimeType.ifBlank { "video/mp4" }, caption = caption,
                        sizeBytes = v.video.size.takeIf { it > 0 } ?: v.video.expectedSize,
                        durationSec = v.duration, width = v.width, height = v.height,
                        thumbFileId = v.thumbnail?.file?.id ?: 0, miniThumb = v.minithumbnail?.data,
                        date = message.date, supportsStreaming = v.supportsStreaming,
                        parsed = FileNameParser.parse(v.fileName.orEmpty(), caption),
                    )
                }
                is TdApi.MessageDocument -> {
                    val d = content.document
                    if (!d.mimeType.orEmpty().startsWith("video/", ignoreCase = true)) return null
                    val caption = content.caption?.text.orEmpty()
                    VideoVersion(
                        chatId = message.chatId, messageId = message.id, chatTitle = chatTitle,
                        fileId = d.document.id, uniqueId = d.document.remote?.uniqueId.orEmpty(),
                        fileName = d.fileName.orEmpty(), mimeType = d.mimeType, caption = caption,
                        sizeBytes = d.document.size.takeIf { it > 0 } ?: d.document.expectedSize,
                        durationSec = 0, width = 0, height = 0,
                        thumbFileId = d.thumbnail?.file?.id ?: 0, miniThumb = d.minithumbnail?.data,
                        date = message.date, supportsStreaming = true,
                        parsed = FileNameParser.parse(d.fileName.orEmpty(), caption),
                    )
                }
                else -> null
            }
        }
    }
}
