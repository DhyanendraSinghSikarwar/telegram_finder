package com.tgfinder.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Copies a finished TDLib download into the public Movies/TGFinder folder. */
class MediaStoreSaver(private val context: Context) {

    /** Returns a URI (content:// on Android 10+, file:// before) that can be played later. */
    fun save(source: File, displayName: String, mimeType: String): String {
        val name = sanitize(displayName).ifBlank { source.name }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(source, name, mimeType) else saveLegacy(source, name)
    }

    private fun saveScoped(source: File, name: String, mimeType: String): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType.ifBlank { "video/mp4" })
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the file in Movies/$FOLDER")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 20) } }
                ?: throw IOException("Could not write to Movies/$FOLDER")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(source: File, name: String): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create Movies/$FOLDER")
        var target = File(dir, name)
        var n = 1
        while (target.exists()) {
            target = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "mp4")}")
            n++
        }
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 1 shl 20) } }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        return Uri.fromFile(target).toString()
    }

    /** Deletes a saved video. Returns false if the system refused (e.g. the file belongs to an earlier install). */
    fun delete(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return false)
                !f.exists() || f.delete()
            } else {
                context.contentResolver.delete(uri, null, null) >= 0
            }
        } catch (e: SecurityException) {
            false
        }
    }

    fun exists(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return if (uri.scheme == "file") {
            File(uri.path ?: return false).exists()
        } else {
            runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(150)

    companion object {
        const val FOLDER = "TGFinder"
    }
}
