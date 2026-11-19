package ceo.villa.matty.data.net

import android.webkit.MimeTypeMap
import java.security.SecureRandom

/**
 * Upload names for photos and videos, the way MarkOut names what it saves: 128 random bits as
 * 32 lowercase hex characters, then the file's real extension. Camera names like
 * "PXL_20261110_221530123.jpg" give away when and on what a picture was taken; these give
 * away nothing. Documents keep their names — those are usually meaningful.
 */
object MediaNames {
    private val random = SecureRandom()

    fun isMedia(mime: String) = mime.startsWith("image/") || mime.startsWith("video/")

    fun random(mime: String, originalName: String): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "$hex.${extension(mime, originalName)}"
    }

    private fun extension(mime: String, originalName: String): String {
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()
        val fromName = originalName.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        return when {
            fromMime == "jpeg" -> "jpg"
            !fromMime.isNullOrBlank() -> fromMime
            fromName != null -> fromName
            mime.startsWith("video/") -> "mp4"
            else -> "jpg"
        }
    }
}
