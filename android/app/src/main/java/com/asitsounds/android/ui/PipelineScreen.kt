package com.asitsounds.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.asitsounds.android.db.AppDatabase
import com.asitsounds.android.db.FileStatus
import com.asitsounds.android.db.MediaFileEntity
import com.asitsounds.android.db.ProjectEntity
import com.asitsounds.android.whisper.AudioDecoder
import com.asitsounds.android.whisper.ModelManager
import com.asitsounds.android.whisper.ModelStatus
import com.asitsounds.android.whisper.Pipeline
import com.asitsounds.android.whisper.PipelineEvent
import com.asitsounds.android.whisper.PipelineSegment
import com.asitsounds.android.whisper.SubtitleExport
import com.asitsounds.android.whisper.SubtitleFormat
import com.asitsounds.android.whisper.TranslatorChoice
import com.asitsounds.android.whisper.WhisperModel
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private class PipelineUiState {
    val modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val pickedUri = MutableStateFlow<Uri?>(null)
    val event = MutableStateFlow<PipelineEvent?>(null)
    val segments = MutableStateFlow<List<PipelineSegment>>(emptyList())
    val translator = MutableStateFlow(TranslatorChoice.MYMEMORY)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PipelineScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelManager = remember { ModelManager(ctx) }
    val state = remember { PipelineUiState() }

    // Hydrate cached model on first composition.
    LaunchedEffect(Unit) {
        modelManager.localPathIfExists(WhisperModel.BASE)?.let {
            state.modelStatus.value = ModelStatus.Ready(it)
        }
    }

    val modelStatus by state.modelStatus.collectAsState()
    val pickedUri by state.pickedUri.collectAsState()
    val event by state.event.collectAsState()
    val segments by state.segments.collectAsState()
    val translator by state.translator.collectAsState()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            ctx.contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            state.pickedUri.value = it
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Transcribe") }) },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            modelSection(modelStatus) {
                scope.launch {
                    modelManager.download(WhisperModel.BASE).collect { s -> state.modelStatus.value = s }
                }
            }
            HorizontalDivider()
            translatorSection(translator) { state.translator.value = it }
            HorizontalDivider()
            fileSection(pickedUri) { picker.launch(arrayOf("audio/*", "video/*")) }
            HorizontalDivider()
            runSection(modelStatus, pickedUri, event) {
                val path = (modelStatus as? ModelStatus.Ready)?.path ?: return@runSection
                val uri = pickedUri ?: return@runSection
                state.segments.value = emptyList()
                val tx = translator.make()
                scope.launch {
                    val db = AppDatabase.get(ctx)
                    val now = System.currentTimeMillis()
                    val project = ProjectEntity(
                        id = UUID.randomUUID().toString(),
                        name = "Session ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(now))}",
                        createdAt = now, updatedAt = now,
                    )
                    db.projectDao().insert(project)
                    val fileId = UUID.randomUUID().toString()
                    db.mediaFileDao().insert(
                        MediaFileEntity(
                            id = fileId, projectId = project.id,
                            path = uri.toString(),
                            displayName = uri.lastPathSegment ?: "(file)",
                            mime = null, durationMs = null,
                            status = FileStatus.processing, error = null,
                            createdAt = now,
                        )
                    )
                    Pipeline.run(ctx, path, uri, translator = tx, persistFileId = fileId).collect { e ->
                        state.event.value = e
                        if (e is PipelineEvent.Done) {
                            state.segments.value = e.segments
                            db.mediaFileDao().update(
                                db.mediaFileDao().get(fileId)!!.copy(status = FileStatus.done)
                            )
                        }
                        if (e is PipelineEvent.Failed) {
                            db.mediaFileDao().update(
                                db.mediaFileDao().get(fileId)!!.copy(status = FileStatus.error, error = e.message)
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            if (segments.isNotEmpty() && pickedUri != null) {
                var showPlayer by remember { mutableStateOf(false) }
                if (showPlayer) {
                    PlayerScreen(pickedUri!!, segments, modifier = Modifier.fillMaxSize())
                    return@Column
                }
                androidx.compose.material3.Button(
                    onClick = { showPlayer = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open player with highlight") }

                exportSection(ctx, segments)
            }
            segmentsList(segments)
        }
    }
}

@Composable
private fun modelSection(status: ModelStatus, onDownload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Whisper model", style = MaterialTheme.typography.titleSmall)
        when (status) {
            ModelStatus.NotDownloaded -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Download ggml-base.bin (~142 MB)")
            }
            is ModelStatus.Downloading -> {
                val total = status.total.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { (status.received.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${status.received / (1024 * 1024)} / ${total / (1024 * 1024)} MB",
                     style = MaterialTheme.typography.bodySmall)
            }
            is ModelStatus.Ready -> Text("✓ ${status.path.substringAfterLast('/')}",
                                          style = MaterialTheme.typography.bodySmall)
            is ModelStatus.Failed -> Text("✗ ${status.message}",
                                           color = MaterialTheme.colorScheme.error,
                                           style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun translatorSection(current: TranslatorChoice, onSelect: (TranslatorChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Translator", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TranslatorChoice.values().forEach { c ->
                androidx.compose.material3.FilterChip(
                    selected = current == c,
                    onClick = { onSelect(c) },
                    label = { Text(c.label) },
                )
            }
        }
    }
}

@Composable
private fun fileSection(uri: Uri?, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Audio / Video", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text(uri?.lastPathSegment ?: "Pick a file…")
        }
    }
}

@Composable
private fun runSection(
    modelStatus: ModelStatus,
    pickedUri: Uri?,
    event: PipelineEvent?,
    onRun: () -> Unit,
) {
    val canRun = modelStatus is ModelStatus.Ready && pickedUri != null &&
        (event == null || event is PipelineEvent.Done || event is PipelineEvent.Failed)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onRun, enabled = canRun, modifier = Modifier.fillMaxWidth()) {
            Text("Transcribe")
        }
        when (event) {
            PipelineEvent.Decoding,
            PipelineEvent.Transcribing,
            PipelineEvent.Transliterating -> {
                CircularProgressIndicator()
                Text(event.javaClass.simpleName + "…",
                     style = MaterialTheme.typography.bodySmall)
            }
            is PipelineEvent.Translating -> {
                CircularProgressIndicator()
                Text("Translating ${event.current}/${event.total}…",
                     style = MaterialTheme.typography.bodySmall)
            }
            is PipelineEvent.Done -> Text("Done — ${event.segments.size} segments",
                                          style = MaterialTheme.typography.bodySmall)
            is PipelineEvent.Failed -> Text("Failed — ${event.message}",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall)
            null -> Unit
        }
    }
}

@Composable
private fun exportSection(ctx: android.content.Context, segments: List<PipelineSegment>) {
    var format by remember { mutableStateOf(SubtitleFormat.SRT) }
    val createDoc = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument(format.mime),
    ) { uri ->
        uri?.let { SubtitleExport.writeTo(ctx, it, format, segments) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Export", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleFormat.values().forEach { f ->
                androidx.compose.material3.FilterChip(
                    selected = format == f,
                    onClick = { format = f },
                    label = { Text(f.name) },
                )
            }
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { createDoc.launch("transcript.${format.ext}") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save as .${format.ext}") }
    }
}

@Composable
private fun segmentsList(segments: List<PipelineSegment>) {
    if (segments.isEmpty()) return
    Text("Segments (${segments.size})", style = MaterialTheme.typography.titleSmall)
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(segments, key = { it.idx }) { s ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${formatMs(s.startMs)} — ${formatMs(s.endMs)}",
                         style = MaterialTheme.typography.labelSmall)
                    Text("EN  ${s.textEn}", style = MaterialTheme.typography.bodyMedium)
                    Text("한  ${s.textHangul}",
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.primary)
                    if (s.textKo.isNotEmpty()) {
                        Text("KO  ${s.textKo}",
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1_000
    val m = totalSec / 60
    val s = totalSec % 60
    val cs = (ms % 1_000) / 10
    return "%02d:%02d.%02d".format(m, s, cs)
}
