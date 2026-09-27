package dev.saas.aural.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

object DownloadStore {
    private val mutable = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = mutable
    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val value = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
            .getString("records", "[]") ?: "[]"
        mutable.value = try {
            val array = JSONArray(value)
            (0 until array.length()).map { DownloadRecord.fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
        initialized = true
    }

    @Synchronized
    fun put(context: Context, record: DownloadRecord) {
        init(context)
        mutable.value = (listOf(record) + mutable.value.filterNot { it.id == record.id }).take(100)
        val array = JSONArray()
        mutable.value.forEach { array.put(it.toJson()) }
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
            .edit().putString("records", array.toString()).apply()
    }

    fun find(id: String): DownloadRecord? = mutable.value.firstOrNull { it.id == id }
}
