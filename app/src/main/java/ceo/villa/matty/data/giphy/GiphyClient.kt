package ceo.villa.matty.data.giphy

import ceo.villa.matty.data.api.GiphyGif
import ceo.villa.matty.data.api.GiphyResponse
import ceo.villa.matty.data.api.MattermostJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * The GIF picker, done the way Mattermost's web and desktop apps do it: the server hands out a
 * Giphy SDK key in its client config (ServiceSettings.GiphySdkKey, or Mattermost's built-in key
 * when the admin leaves it blank) and the client talks to Giphy directly. A chosen GIF is posted
 * as `![title](fixed_height url)`, so every Mattermost client renders it.
 */
class GiphyClient(private val http: OkHttpClient) {

    suspend fun trending(key: String, offset: Int = 0, rating: String = "g"): List<GiphyGif> =
        fetch("https://api.giphy.com/v1/gifs/trending?api_key=${enc(key)}&limit=30&offset=$offset&rating=$rating")

    suspend fun search(key: String, query: String, offset: Int = 0, rating: String = "g", lang: String = "en"): List<GiphyGif> =
        fetch("https://api.giphy.com/v1/gifs/search?api_key=${enc(key)}&q=${enc(query)}&limit=30&offset=$offset&rating=$rating&lang=$lang")

    private suspend fun fetch(url: String): List<GiphyGif> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Giphy returned ${r.code}")
            MattermostJson.decodeFromString(GiphyResponse.serializer(), r.body!!.string()).data
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        fun markdownFor(gif: GiphyGif): String {
            val title = gif.title.replace("[", "").replace("]", "").ifBlank { "GIF" }
            return "![$title](${gif.images.fixedHeight.url})"
        }
    }
}
