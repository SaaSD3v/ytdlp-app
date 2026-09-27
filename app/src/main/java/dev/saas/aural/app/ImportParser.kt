package dev.saas.aural.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object ImportParser {
    private val link = Regex("""https?://[^\s<>"'\\\[\]{}]+""", RegexOption.IGNORE_CASE)
    private const val MAX_LINKS = 500
    private val urlKeys = listOf("webpage_url", "original_url", "url", "link")
    private val groupKeys = listOf("entries", "urls", "items", "videos", "results", "data")

    fun links(text: String): List<String> {
        val found = LinkedHashSet<String>()
        val trimmed = text.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{") || trimmed.startsWith("\"")) {
            try {
                visit(JSONTokener(trimmed).nextValue(), found)
                return found.toList()
            } catch (_: Exception) {
                // Shared text can contain a URL followed by non-JSON commentary.
            }
        }
        link.findAll(text).forEach { add(it.value, found) }
        return found.toList()
    }

    private fun visit(value: Any?, found: MutableSet<String>, depth: Int = 0) {
        if (depth > 12 || found.size >= MAX_LINKS) return
        when (value) {
            is String -> add(value, found)
            is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i), found, depth + 1)
            is JSONObject -> {
                // In yt-dlp JSON, "url" can be a short-lived CDN stream while
                // "webpage_url" is the original page. Never import both.
                if (value.has("entries")) {
                    visit(value.opt("entries"), found, depth + 1)
                    return
                }
                if (value.has("urls")) {
                    visit(value.opt("urls"), found, depth + 1)
                    return
                }
                val canonical = urlKeys.firstOrNull { value.optString(it).startsWith("http") }
                if (canonical != null) visit(value.opt(canonical), found, depth + 1)
                val extractor = value.optString("ie_key", value.optString("extractor_key"))
                val id = value.optString("id")
                if (canonical == null && extractor.startsWith("Youtube", ignoreCase = true) &&
                    id.matches(Regex("[A-Za-z0-9_-]{11}"))
                ) add("https://www.youtube.com/watch?v=" + id, found)
                for (key in groupKeys) if (key != "entries" && key != "urls" && value.has(key)) {
                    visit(value.opt(key), found, depth + 1)
                }
            }
        }
    }

    private fun add(raw: String, found: MutableSet<String>) {
        if (found.size >= MAX_LINKS) return
        val candidate = raw.trim().trimEnd('.', ',', ';', ')', ']', '}', '!')
        val parsed = Uri.parse(candidate)
        if (parsed.scheme in listOf("https", "http") && !parsed.host.isNullOrBlank()) {
            found.add(candidate)
        }
    }
}
