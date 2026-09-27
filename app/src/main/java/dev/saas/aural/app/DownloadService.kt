package dev.saas.aural.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val waiting = ConcurrentLinkedQueue<DownloadSpec>()
    private val canceled = ConcurrentHashMap.newKeySet<String>()
    private val processing = AtomicBoolean(false)
    @Volatile private var runningId: String? = null
    @Volatile private var lastStartId = 0
    private var lastProgressAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadStore.init(this)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads Aural", NotificationManager.IMPORTANCE_LOW)
        )
        val foreground = notification("Preparando downloads", 0, null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, foreground, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, foreground)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                canceled.add(id)
                if (runningId == id) YoutubeDL.getInstance().destroyProcessById(id)
                update(id, "Cancelado", detail = "Interrompido pelo usuário")
                if (!processing.get() && waiting.isEmpty()) stopSelfResult(startId)
            }
            ACTION_ADD -> {
                val payload = intent.getStringExtra(EXTRA_JOBS) ?: return START_NOT_STICKY
                val jobs = JSONArray(payload)
                for (i in 0 until jobs.length()) {
                    val spec = DownloadSpec.fromJson(jobs.getJSONObject(i))
                    DownloadStore.put(this, DownloadRecord(spec.id, spec.title, spec.audio, "Na fila"))
                    waiting.add(spec)
                }
                pump()
            }
        }
        return START_NOT_STICKY
    }

    private fun pump() {
        if (!processing.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val spec = waiting.poll() ?: break
                    if (canceled.remove(spec.id)) continue
                    runningId = spec.id
                    runJob(spec)
                    runningId = null
                }
            } finally {
                runningId = null
                val finishedThrough = lastStartId
                processing.set(false)
                if (waiting.isNotEmpty()) pump()
                else {
                    // A new start can arrive while the old queue is being drained.
                    // stopSelfResult leaves that newer start alive.
                    stopSelfResult(finishedThrough)
                }
            }
        }
    }

    private fun runJob(spec: DownloadSpec) {
        val staging = File(getExternalFilesDir(null) ?: filesDir, "staging/" + spec.id)
        try {
            if (!staging.mkdirs()) error("Não foi possível preparar o armazenamento.")
            YtDlpEngine.init(this)
            update(spec.id, "Baixando", 0, "Iniciando yt-dlp")
            val request = YoutubeDLRequest(spec.url)
                .addOption("-f", spec.formatSelector)
                .addOption("-o", File(staging, outputTemplate(spec)).absolutePath)
                .addOption("--no-mtime")
                .addOption("--newline")
                .addOption("--progress")
                .addOption("--no-simulate")
                .addOption("--continue")
                .addOption("--no-overwrites")
                .addOption("--print", "after_move:" + FILE_MARKER + "%(filepath)s")
            if (spec.audio) {
                // "best" keeps the source audio codec; it does not request a lossy transcode.
                request.addOption("--extract-audio").addOption("--audio-format", "best")
            } else if (spec.mkv) request.addOption("--merge-output-format", "mkv")
            if (spec.collection != null) {
                request.addOption("--yes-playlist")
                    .addOption("--playlist-items", spec.trackIndices.joinToString(","))
                    .addOption("--ignore-errors")
            } else request.addOption("--no-playlist")

            val printed = LinkedHashSet<File>()
            val response = YoutubeDL.getInstance().execute(request, spec.id, false) { percent, eta, line ->
                if (line.startsWith(FILE_MARKER)) printed.add(File(line.removePrefix(FILE_MARKER).trim()))
                val now = System.currentTimeMillis()
                if (now - lastProgressAt >= 650 && percent >= 0 && !canceled.contains(spec.id)) {
                    lastProgressAt = now
                    val detail = if (eta >= 0) "Restam cerca de " + eta + " s" else "Recebendo mídia"
                    update(spec.id, "Baixando", percent.toInt().coerceIn(0, 99), detail)
                }
            }
            response.out.lineSequence().filter { it.startsWith(FILE_MARKER) }
                .forEach { printed.add(File(it.removePrefix(FILE_MARKER).trim())) }
            if (canceled.contains(spec.id)) {
                update(spec.id, "Cancelado", detail = "Interrompido pelo usuário")
                return
            }
            val files = printed.filter { it.isFile && it.canonicalFile.toPath().startsWith(staging.canonicalFile.toPath()) }
            if (files.isEmpty()) error("yt-dlp terminou sem produzir um arquivo.")
            var published = 0
            files.forEach { file ->
                publish(file, spec)
                published++
                update(spec.id, "Salvando", 99, published.toString() + "/" + files.size + " arquivos")
            }
            update(spec.id, "Concluído", 100, destinationLabel(spec) + " · " + published + " arquivo(s)", published)
        } catch (error: Exception) {
            if (canceled.contains(spec.id)) {
                update(spec.id, "Cancelado", detail = "Interrompido pelo usuário")
            } else {
                val message = error.message?.lineSequence()?.lastOrNull { it.isNotBlank() }
                    ?.take(220) ?: error.javaClass.simpleName
                update(spec.id, "Erro", detail = message)
            }
        } finally {
            canceled.remove(spec.id)
            staging.deleteRecursively()
        }
    }

    private fun outputTemplate(spec: DownloadSpec): String =
        if (spec.collection != null) "%(playlist_index|00)s - %(title).180B [%(id)s].%(ext)s"
        else "%(title).180B [%(id)s].%(ext)s"

    private fun destinationLabel(spec: DownloadSpec) =
        if (spec.audio) "Música/Aural" else "Vídeos/Aural"

    private fun publish(source: File, spec: DownloadSpec): Uri {
        val ext = source.extension.lowercase()
        val mime = when (ext) {
            "m4a", "mp4" -> if (spec.audio) "audio/mp4" else "video/mp4"
            "webm" -> if (spec.audio) "audio/webm" else "video/webm"
            "opus", "ogg" -> "audio/ogg"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (spec.audio) "audio/*" else "video/*"
        }
        val collection = if (spec.audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val base = if (spec.audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val folder = spec.collection?.replace(Regex("""[\\/:*?"<>|]"""), "_")
            ?.take(80)?.takeIf { it.isNotBlank() }
        val path = base + "/Aural/" + (if (folder != null) folder + "/" else "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values)
            ?: error("Não foi possível criar o arquivo na biblioteca do Android.")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 1024 * 1024) }
            }
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun update(id: String, state: String, percent: Int? = null, detail: String? = null, files: Int? = null) {
        val old = DownloadStore.find(id) ?: return
        val next = old.copy(
            state = state, percent = percent ?: old.percent,
            detail = detail ?: old.detail, files = files ?: old.files
        )
        DownloadStore.put(this, next)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(next.title, next.percent, if (state == "Baixando") id else null))
    }

    private fun notification(title: String, percent: Int, id: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(dev.saas.aural.R.drawable.ic_app)
            .setContentTitle("Aural · " + title)
            .setContentText(if (id == null) "Preparando" else percent.toString() + "%")
            .setContentIntent(open).setOngoing(id != null)
            .setOnlyAlertOnce(true)
        if (id != null) {
            val cancelIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_ID, id)
            }
            builder.setProgress(100, percent, false)
                .addAction(0, "Cancelar", PendingIntent.getService(
                    this, id.hashCode(), cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
        }
        return builder.build()
    }

    override fun onDestroy() {
        runningId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ADD = "dev.saas.aural.ADD"
        const val ACTION_CANCEL = "dev.saas.aural.CANCEL"
        const val EXTRA_JOBS = "jobs"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "aural-downloads"
        private const val NOTIFICATION_ID = 731
        private const val FILE_MARKER = "__AURAL_FILE__"

        fun enqueue(context: android.content.Context, specs: List<DownloadSpec>) {
            if (specs.isEmpty()) return
            val payload = JSONArray().apply { specs.forEach { put(it.toJson()) } }
            context.startForegroundService(Intent(context, DownloadService::class.java).apply {
                action = ACTION_ADD
                putExtra(EXTRA_JOBS, payload.toString())
            })
        }
    }
}
