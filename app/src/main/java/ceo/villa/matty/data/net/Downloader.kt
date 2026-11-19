package ceo.villa.matty.data.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import ceo.villa.matty.data.api.MattermostClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Saves Mattermost file attachments to Downloads/Matty.
 *
 * It goes through Matty's own hardened HTTP client rather than the system DownloadManager:
 * DownloadManager replays custom headers (the session token) on every redirect hop and keeps
 * them in its own database. Here the token is sent only for a real file id on the signed-in
 * server's exact origin, and OkHttp drops it on any cross-origin redirect.
 */
class Downloader(private val context: Context) {

    private val fileId = Regex("^[a-z0-9]{26}$")

    /** Returns a content URI for the saved file. */
    suspend fun saveAttachment(client: MattermostClient, id: String, rawName: String, mime: String): Uri = withContext(Dispatchers.IO) {
        require(fileId.matches(id)) { "Not a Mattermost file" }
        val url = client.fileUrl(id) + "?download=1"
        check(client.isSameOrigin(url) && TransportPolicy.isAllowed(url)) { NetErrors.HTTPS_REQUIRED }
        val req = Request.Builder().url(url)
            .apply { TransportPolicy.mediaHeaders(url, client.origin, client.token).forEach { (name, value) -> header(name, value) } }
            .build()
        val name = safeName(rawName)
        client.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("The server answered ${resp.code}")
            val body = resp.body ?: throw IOException("Empty download")
            val type = mime.ifBlank { body.contentType()?.toString() ?: "application/octet-stream" }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Matty")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Couldn't create the file")
            try {
                resolver.openOutputStream(uri)?.use { out -> body.byteStream().copyTo(out) } ?: throw IOException("Couldn't write the file")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        }
    }

    companion object {
        /**
         * The characters that change which way text runs or hide in it: the direction marks,
         * embeddings, overrides and isolates, and the invisible zero-width ones. With them a
         * member can make "invoice\u202Efdp.apk" read as "invoicekpa.pdf". The zero-width
         * joiner and non-joiner stay: they only join letters and emoji (Persian needs them),
         * and can't reorder a name.
         */
        private fun hidden(ch: Char): Boolean =
            ch == '\u061C' || ch == '\u180E' || ch == '\u200B' || ch == '\u200E' || ch == '\u200F' ||
                ch in '\u202A'..'\u202E' || ch in '\u2060'..'\u2069' || ch == '\uFEFF'

        /** A bare, harmless file name: no paths, no control or direction characters, no leading dots. */
        fun safeName(raw: String): String {
            val bare = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = bare.filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" && !hidden(it) }.trim().trimStart('.')
            return cleaned.take(120).ifBlank { "file" }
        }
    }
}
