package com.asitsounds.android.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.asitsounds.android.whisper.PipelineSegment
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    audioUri: Uri,
    segments: List<PipelineSegment>,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(audioUri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Poll position ~10 Hz. ExoPlayer also has a Player.Listener for events
    // (state changes, errors, etc.), but for the segment highlight a tick
    // loop is the simplest reliable signal.
    var currentMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            currentMs = player.currentPosition
            delay(100)
        }
    }

    val listState = rememberLazyListState()
    val activeIndex = segments.indexOfFirst { currentMs in it.startMs until it.endMs }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
            items(segments, key = { it.idx }) { s ->
                val active = currentMs in s.startMs until s.endMs
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (active)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
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
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1_000
    val m = totalSec / 60
    val s = totalSec % 60
    val cs = (ms % 1_000) / 10
    return "%02d:%02d.%02d".format(m, s, cs)
}
