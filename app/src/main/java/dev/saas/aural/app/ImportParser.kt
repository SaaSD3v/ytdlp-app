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
                if (found.isNotEmpty()) return found.toList()
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
                for (key in urlKeys) if (value.has(key)) visit(value.opt(key), found, depth + 1)
                for (key in groupKeys) if (value.has(key)) visit(value.opt(key), found, depth + 1)
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
