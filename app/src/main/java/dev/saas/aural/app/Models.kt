package dev.saas.aural.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StreamFormat(
    val id: String,
    val ext: String,
    val vcodec: String,
    val acodec: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val abr: Double,
    val tbr: Double,
    val asr: Int,
    val bytes: Long,
    val note: String
) {
    val hasVideo get() = vcodec.isNotBlank() && vcodec != "none"
    val hasAudio get() = acodec.isNotBlank() && acodec != "none"
    val audioOnly get() = hasAudio && !hasVideo
    val videoOnly get() = hasVideo && !hasAudio

    fun description(): String = buildList {
        if (hasVideo) add(if (height > 0) height.toString() + "p" else "Vídeo")
        if (hasVideo && fps > 0) add(fps.toInt().toString() + " fps")
        if (hasVideo) add(vcodec)
        if (hasAudio) add(acodec)
        if (hasAudio && abr > 0) add(abr.toInt().toString() + " kb/s")
        if (hasAudio && asr > 0) add((asr / 1000).toString() + " kHz")
        add(ext.uppercase())
        if (bytes > 0) add(String.format("%.1f MB", bytes / 1_000_000.0))
    }.joinToString(" · ")

    companion object {
        fun fromJson(json: JSONObject): StreamFormat? {
            val id = json.optString("format_id")
            if (id.isBlank()) return null
            return StreamFormat(
                id, json.optString("ext", "?"),
                json.optString("vcodec", "none"), json.optString("acodec", "none"),
                json.optInt("width"), json.optInt("height"), json.optDouble("fps", 0.0),
                json.optDouble("abr", 0.0), json.optDouble("tbr", 0.0),
                json.optInt("asr"), json.optLong("filesize").takeIf { it > 0 }
                    ?: json.optLong("filesize_approx"),
                json.optString("format_note")
            )
        }
    }
}

data class Track(val index: Int, val title: String, val url: String?)

sealed interface Inspection {
    data class Media(
        val url: String,
        val title: String,
        val creator: String,
        val duration: Int,
        val formats: List<StreamFormat>
    ) : Inspection

    data class Collection(
        val url: String,
        val title: String,
        val tracks: List<Track>
    ) : Inspection

    data class Imported(val urls: List<String>) : Inspection
}

data class DownloadSpec(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val audio: Boolean,
    val formatSelector: String,
    val mkv: Boolean = false,
    val collection: String? = null,
    val trackIndices: List<Int> = emptyList()
) {
    fun toJson() = JSONObject()
        .put("id", id).put("url", url).put("title", title)
        .put("audio", audio).put("format", formatSelector).put("mkv", mkv)
        .put("collection", collection)
        .put("indices", JSONArray(trackIndices))

    companion object {
        fun fromJson(value: JSONObject): DownloadSpec = DownloadSpec(
            id = value.getString("id"), url = value.getString("url"),
            title = value.optString("title"), audio = value.getBoolean("audio"),
            formatSelector = value.getString("format"),
            mkv = value.optBoolean("mkv"),
            collection = value.optString("collection").takeIf { it.isNotBlank() },
            trackIndices = value.optJSONArray("indices")?.let { array ->
                (0 until array.length()).map { array.getInt(it) }
            } ?: emptyList()
        )
    }
}

data class DownloadRecord(
    val id: String,
    val title: String,
    val audio: Boolean,
    val state: String,
    val percent: Int = 0,
    val detail: String = "",
    val files: Int = 0,
    val time: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().put("id", id).put("title", title)
        .put("audio", audio).put("state", state).put("percent", percent)
        .put("detail", detail).put("files", files).put("time", time)

    companion object {
        fun fromJson(value: JSONObject): DownloadRecord = DownloadRecord(
            value.getString("id"), value.optString("title"), value.optBoolean("audio"),
            value.optString("state"), value.optInt("percent"), value.optString("detail"),
            value.optInt("files"), value.optLong("time")
        )
    }
}
