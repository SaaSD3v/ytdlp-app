package dev.saas.aural.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val Background = Color(0xFF0C0B16)
private val Panel = Color(0xFF1A1929)
private val Violet = Color(0xFFB9A2FF)
private val Aqua = Color(0xFF71E6D3)
private val Muted = Color(0xFFAAA9BE)

class MainActivity : ComponentActivity() {
    private var input by mutableStateOf("")
    private var inspection by mutableStateOf<Inspection?>(null)
    private var analyzing by mutableStateOf(false)
    private var problem by mutableStateOf<String?>(null)

    private val openJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try { acceptText(readSharedFile(uri)) }
            catch (error: Exception) { problem = error.message ?: "Não foi possível ler o JSON." }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadStore.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        setContent {
            val records by DownloadStore.records.collectAsState()
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Violet, secondary = Aqua, background = Background,
                    surface = Panel, onSurface = Color.White, onBackground = Color.White
                )
            ) {
                Home(
                    input, { input = it }, analyzing, problem, inspection, records,
                    onAnalyze = { acceptText(input) },
                    onImport = { openJson.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")) },
                    onPaste = {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                        if (!value.isNullOrBlank()) { input = value; acceptText(value) }
                    },
                    onClose = { inspection = null },
                    onDownload = { jobs ->
                        try {
                            DownloadService.enqueue(this, jobs)
                            inspection = null
                            problem = null
                        } catch (error: Exception) {
                            problem = error.message ?: "Não foi possível iniciar o download."
                        }
                    },
                    onInspectTrack = { acceptText(it) },
                    onCancel = { id ->
                        startService(Intent(this, DownloadService::class.java).apply {
                            action = DownloadService.ACTION_CANCEL
                            putExtra(DownloadService.EXTRA_ID, id)
                        })
                    }
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { acceptText(it) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val stream = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (!text.isNullOrBlank()) acceptText(text)
                else if (stream != null) lifecycleScope.launch {
                    try { acceptText(readSharedFile(stream)) }
                    catch (error: Exception) { problem = error.message ?: "Não foi possível abrir o arquivo." }
                }
            }
        }
    }

    private suspend fun readSharedFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { source ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2_000_000) error("JSON maior que 2 MB.")
                output.write(buffer, 0, count)
            }
        } ?: error("Arquivo indisponível.")
        output.toString(Charsets.UTF_8.name())
    }

    private fun acceptText(value: String) {
        input = value
        problem = null
        val links = ImportParser.links(value)
        when {
            links.isEmpty() -> problem = "Cole um link HTTP(S) ou importe um JSON com URLs."
            links.size > 1 -> inspection = Inspection.Imported(links)
            else -> {
                inspection = null
                analyzing = true
                lifecycleScope.launch {
                    try { inspection = YtDlpEngine.inspect(this@MainActivity, links.first()) }
                    catch (error: Exception) {
                        problem = error.message?.take(300) ?: "Não foi possível analisar o link."
                    } finally { analyzing = false }
                }
            }
        }
    }
}

@Composable
private fun Home(
    input: String,
    onInput: (String) -> Unit,
    analyzing: Boolean,
    problem: String?,
    inspection: Inspection?,
    records: List<DownloadRecord>,
    onAnalyze: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onClose: () -> Unit,
    onDownload: (List<DownloadSpec>) -> Unit,
    onInspectTrack: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    Scaffold(containerColor = Background) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).background(Violet, RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Background)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("AURAL", fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("powered by yt-dlp", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Panel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.background(
                            Brush.linearGradient(
                                listOf(Color(0xFF32265A), Color(0xFF1B2536), Panel)
                            )
                        ).padding(24.dp)
                    ) {
                        Text("Sua mídia. Sua escolha.", fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
                        Spacer(Modifier.height(9.dp))
                        Text(
                            "Escolha o stream, veja o codec e baixe a qualidade disponível na fonte.",
                            color = Color(0xFFD5D0E7), lineHeight = 21.sp
                        )
                        Spacer(Modifier.height(22.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tag("Áudio original", Aqua)
                            Tag("Vídeo sem recodificar", Violet)
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Adicionar link", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = input, onValueChange = onInput,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2, maxLines = 5,
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("URL, lista de URLs ou JSON") },
                        leadingIcon = { Icon(Icons.Rounded.AddLink, null) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(onClick = onAnalyze, enabled = !analyzing && input.isNotBlank(), modifier = Modifier.weight(1f)) {
                            if (analyzing) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(if (analyzing) "Analisando" else "Analisar")
                        }
                        OutlinedButton(onClick = onPaste) { Icon(Icons.Rounded.ContentPaste, "Colar", Modifier.size(19.dp)) }
                    }
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.FileOpen, null, Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Importar arquivo JSON")
                    }
                    if (problem != null) Text(problem, color = Color(0xFFFFB4AB), fontSize = 13.sp)
                }
            }
            item {
                HorizontalDivider(color = Color.White.copy(alpha = .11f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(String.valueOf(records.size), color = Muted)
                }
            }
            if (records.isEmpty()) item {
                Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.LibraryMusic, null, tint = Violet, modifier = Modifier.size(37.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Pronto para o primeiro download", fontWeight = FontWeight.SemiBold)
                        Text("Links compartilhados com Aural também aparecem aqui.", color = Muted, fontSize = 13.sp)
                    }
                }
            }
            items(records, key = { it.id }) { record -> DownloadCard(record, onCancel) }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
    when (inspection) {
        is Inspection.Media -> MediaSheet(inspection, onClose, onDownload)
        is Inspection.Collection -> CollectionSheet(
            inspection.title, inspection.tracks, false, onClose,
            { selected, audio, cap, mkv ->
                onDownload(listOf(DownloadSpec(
                    url = inspection.url, title = inspection.title, audio = audio,
                    formatSelector = if (audio) "bestaudio/best" else videoSelector(cap),
                    mkv = mkv, collection = inspection.title, trackIndices = selected
                )))
            }, onInspectTrack
        )
        is Inspection.Imported -> CollectionSheet(
            "Links importados", inspection.urls.mapIndexed { index, link ->
                Track(index + 1, link, link)
            }, true, onClose,
            { selected, audio, cap, mkv ->
                onDownload(selected.map { position ->
                    val link = inspection.urls[position - 1]
                    DownloadSpec(
                        url = link, title = link, audio = audio,
                        formatSelector = if (audio) "bestaudio/best" else videoSelector(cap),
                        mkv = mkv
                    )
                })
            }, onInspectTrack
        )
        null -> Unit
    }
}

@Composable
private fun Tag(label: String, color: Color) {
    Surface(color = color.copy(alpha = .15f), shape = CircleShape) {
        Text(label, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@Composable
private fun DownloadCard(record: DownloadRecord, onCancel: (String) -> Unit) {
    val busy = record.state in listOf("Na fila", "Baixando", "Salvando")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (record.audio) Icons.Rounded.AudioFile else Icons.Rounded.VideoFile,
                    null, tint = if (record.audio) Aqua else Violet,
                    modifier = Modifier.size(27.dp)
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(record.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(record.state + if (record.detail.isNotBlank()) " · " + record.detail else "",
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (record.state == "Erro") Color(0xFFFFB4AB) else Muted, fontSize = 12.sp)
                }
                if (busy) IconButton(onClick = { onCancel(record.id) }) {
                    Icon(Icons.Rounded.Cancel, "Cancelar", tint = Muted)
                } else Icon(
                    if (record.state == "Concluído") Icons.Rounded.CheckCircle else Icons.Rounded.Close,
                    null, tint = if (record.state == "Concluído") Aqua else Muted
                )
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { record.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Aqua, trackColor = Color.White.copy(alpha = .1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaSheet(media: Inspection.Media, onClose: () -> Unit, onDownload: (List<DownloadSpec>) -> Unit) {
    val videoFormats = remember(media) {
        media.formats.filter { it.hasVideo }.sortedWith(
            compareByDescending<StreamFormat> { it.height }.thenByDescending { it.fps }.thenByDescending { it.tbr }
        )
    }
    val audioFormats = remember(media) {
        media.formats.filter { it.audioOnly }.sortedWith(
            compareByDescending<StreamFormat> { it.abr }.thenByDescending { it.tbr }
        )
    }
    var audio by remember(media.url) { mutableStateOf(videoFormats.isEmpty()) }
    var selectedAudio by remember(media.url) { mutableStateOf("__best__") }
    var selectedVideo by remember(media.url) { mutableStateOf("__best__") }
    var companion by remember(media.url) { mutableStateOf("__best__") }
    var mkv by remember(media.url) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 25.dp)) {
            Text(media.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            if (media.creator.isNotBlank()) Text(media.creator, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FilterChip(
                    selected = audio, onClick = { audio = true },
                    label = { Text("Só áudio") },
                    leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(17.dp)) }
                )
                if (videoFormats.isNotEmpty()) FilterChip(
                    selected = !audio, onClick = { audio = false },
                    label = { Text("Vídeo + áudio") },
                    leadingIcon = { Icon(Icons.Rounded.VideoFile, null, Modifier.size(17.dp)) }
                )
            }
            Text(
                if (audio) "STREAM DE ÁUDIO" else "QUALIDADE DO VÍDEO",
                color = Aqua, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FormatChoice(
                        "Melhor disponível", "O yt-dlp escolhe a melhor qualidade oferecida",
                        if (audio) selectedAudio == "__best__" else selectedVideo == "__best__"
                    ) { if (audio) selectedAudio = "__best__" else selectedVideo = "__best__" }
                }
                items(if (audio) audioFormats else videoFormats, key = { it.id }) { format ->
                    FormatChoice(
                        format.description(),
                        "ID " + format.id + if (format.note.isNotBlank()) " · " + format.note else "",
                        if (audio) selectedAudio == format.id else selectedVideo == format.id
                    ) {
                        if (audio) selectedAudio = format.id else selectedVideo = format.id
                    }
                }
            }
            if (!audio) {
                Spacer(Modifier.height(12.dp))
                Text("ÁUDIO PARA JUNTAR", color = Aqua, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { companion = "__best__" },
                        label = { Text(if (companion == "__best__") "✓ Melhor áudio" else "Melhor áudio") })
                    audioFormats.forEach { format ->
                        AssistChip(onClick = { companion = format.id }, label = {
                            Text((if (companion == format.id) "✓ " else "") +
                                format.acodec + " " + format.abr.toInt() + "k")
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mkv, onCheckedChange = { mkv = it })
                    Text("Usar MKV ao juntar streams", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(9.dp))
            Text("Sem limite artificial de resolução ou bitrate; a fonte define os formatos.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val selector = if (audio) {
                        if (selectedAudio == "__best__") "bestaudio/best" else selectedAudio
                    } else if (selectedVideo == "__best__") {
                        "bv+ba/best"
                    } else {
                        val video = videoFormats.firstOrNull { it.id == selectedVideo }
                        if (video?.hasAudio == true) selectedVideo
                        else selectedVideo + "+" + if (companion == "__best__") "bestaudio" else companion
                    }
                    onDownload(listOf(DownloadSpec(
                        url = media.url, title = media.title, audio = audio,
                        formatSelector = selector, mkv = mkv
                    )))
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(9.dp))
                Text(if (audio) "Baixar áudio original" else "Baixar vídeo")
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun FormatChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Violet.copy(alpha = .17f) else Color(0xFF242337),
        shape = RoundedCornerShape(16.dp),
        border = if (selected) BorderStroke(1.dp, Violet) else null
    ) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 11.sp, color = Muted, maxLines = 2)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Violet, modifier = Modifier.size(20.dp))
        }
    }
}

private fun videoSelector(height: Int): String =
    if (height == 0) "bv+ba/best"
    else "bv[height<=" + height + "]+ba/best[height<=" + height + "]"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionSheet(
    title: String,
    tracks: List<Track>,
    imported: Boolean,
    onClose: () -> Unit,
    onDownload: (List<Int>, Boolean, Int, Boolean) -> Unit,
    onInspectTrack: (String) -> Unit
) {
    var selected by remember(title) { mutableStateOf(tracks.map { it.index }.toSet()) }
    var audio by remember(title) { mutableStateOf(true) }
    var maxHeight by remember(title) { mutableStateOf(0) }
    var mkv by remember(title) { mutableStateOf(false) }
    var query by remember(title) { mutableStateOf("") }
    val visible = remember(query, tracks) { tracks.filter { it.title.contains(query, ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlaylistPlay, null, tint = Violet, modifier = Modifier.size(29.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(
                        String.valueOf(tracks.size) + if (imported) " links" else " faixas",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = audio, onClick = { audio = true }, label = { Text("Áudio original") })
                FilterChip(selected = !audio, onClick = { audio = false }, label = { Text("Vídeo + áudio") })
            }
            if (!audio) {
                Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 2160, 1440, 1080, 720, 480).forEach { height ->
                        FilterChip(
                            selected = maxHeight == height, onClick = { maxHeight = height },
                            label = { Text(if (height == 0) "Sem limite" else "Até " + height + "p") }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mkv, onCheckedChange = { mkv = it })
                    Text("Usar MKV ao juntar streams", fontSize = 13.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    selected = if (selected.size == tracks.size) emptySet() else tracks.map { it.index }.toSet()
                }) { Text(if (selected.size == tracks.size) "Desmarcar tudo" else "Selecionar tudo") }
                Spacer(Modifier.weight(1f))
                Text(String.valueOf(selected.size) + " selecionados", color = Muted, fontSize = 12.sp)
            }
            if (tracks.size > 8) OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Buscar faixa") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }
            )
            Spacer(Modifier.height(7.dp))
            LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(visible, key = { it.index }) { track ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (track.index in selected) selected - track.index else selected + track.index
                        }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = track.index in selected,
                            onCheckedChange = {
                                selected = if (it) selected + track.index else selected - track.index
                            }
                        )
                        Text(track.title, Modifier.weight(1f), maxLines = 2, fontSize = 13.sp)
                        if (track.url != null) IconButton(onClick = { onInspectTrack(track.url) }) {
                            Icon(Icons.Rounded.HighQuality, "Ver codecs desta faixa", tint = Aqua)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (imported) "Cada link será baixado separadamente."
                else "As faixas selecionadas serão salvas em uma pasta com o nome da coleção.",
                color = Muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onDownload(selected.sorted(), audio, maxHeight, mkv) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(9.dp))
                Text("Baixar " + selected.size + (if (selected.size == 1) " item" else " itens"))
            }
        }
    }
}
