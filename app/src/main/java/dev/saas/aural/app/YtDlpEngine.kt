package dev.saas.aural.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object YtDlpEngine {
    @Volatile private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (!ready) {
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            ready = true
        }
    }

    suspend fun inspect(context: Context, url: String): Inspection = withContext(Dispatchers.IO) {
        init(context)
        val request = YoutubeDLRequest(url)
            .addOption("--dump-single-json")
            .addOption("--flat-playlist")
            .addOption("--skip-download")
            .addOption("--no-warnings")
        val root = JSONObject(YoutubeDL.getInstance().execute(request).out.trim())
        val entries = root.optJSONArray("entries")
        if (entries != null) {
            val tracks = buildList {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val title = entry.optString("title").ifBlank { "Faixa " + (i + 1) }
                    val direct = entry.optString("webpage_url").takeIf { it.startsWith("http") }
                        ?: entry.optString("url").takeIf { it.startsWith("http") }
                    val youtubeId = entry.optString("id")
                    val itemUrl = direct ?: if (
                        url.contains("youtube.com") && youtubeId.matches(Regex("[A-Za-z0-9_-]{11}"))
                    ) "https://www.youtube.com/watch?v=" + youtubeId else null
                    add(Track(i + 1, title, itemUrl))
                }
            }
            if (tracks.isEmpty()) error("Esta coleção não trouxe faixas disponíveis.")
            Inspection.Collection(url, root.optString("title", "Coleção"), tracks)
        } else {
            val formats = parseFormats(root)
            // Some flat extractors omit formats. Fetch the individual item in that case.
            val full = if (formats.isEmpty()) {
                val detail = YoutubeDLRequest(url)
                    .addOption("--dump-single-json").addOption("--no-playlist")
                    .addOption("--skip-download").addOption("--no-warnings")
                JSONObject(YoutubeDL.getInstance().execute(detail).out.trim())
            } else root
            Inspection.Media(
                url, full.optString("title", "Mídia"), full.optString("uploader"),
                full.optInt("duration"), parseFormats(full)
            )
        }
    }

    private fun parseFormats(root: JSONObject): List<StreamFormat> {
        val array = root.optJSONArray("formats") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { StreamFormat.fromJson(it) }?.let { add(it) }
            }
        }.distinctBy { it.id }
    }
}
