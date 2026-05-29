package com.asitsounds.android.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.asitsounds.android.AppSettings
import com.asitsounds.android.ColorSchemePref
import com.asitsounds.android.R
import com.asitsounds.android.whisper.TranslatorChoice
import com.asitsounds.android.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings.get(ctx) }
    val model by settings.model.collectAsState()
    val translator by settings.translator.collectAsState()
    val scheme by settings.colorScheme.collectAsState()
    val scope = rememberCoroutineScope()

    var diskBytes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) { diskBytes = withContext(Dispatchers.IO) { computeDiskUsage(ctx) } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.settings_model), style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WhisperModel.values().forEach { m ->
                    FilterChip(
                        selected = model == m,
                        onClick = { settings.setModel(m) },
                        label = { Text(m.id) },
                    )
                }
            }

            HorizontalDivider()

            Text(stringResource(R.string.settings_translator), style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslatorChoice.values().forEach { c ->
                    FilterChip(
                        selected = translator == c,
                        onClick = { settings.setTranslator(c) },
                        label = { Text(c.label) },
                    )
                }
            }

            HorizontalDivider()

            Text(stringResource(R.string.settings_dark_mode), style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val labels = mapOf(
                    ColorSchemePref.SYSTEM to R.string.settings_dark_mode_auto,
                    ColorSchemePref.DARK to R.string.settings_dark_mode_on,
                    ColorSchemePref.LIGHT to R.string.settings_dark_mode_off,
                )
                ColorSchemePref.values().forEach { p ->
                    FilterChip(
                        selected = scheme == p,
                        onClick = { settings.setColorScheme(p) },
                        label = { Text(stringResource(labels[p]!!)) },
                    )
                }
            }

            HorizontalDivider()

            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val version = remember {
                        try {
                            val pm = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                            "${pm.versionName} (${pm.longVersionCode})"
                        } catch (t: Throwable) { "?" }
                    }
                    Text("${stringResource(R.string.settings_version)}: $version",
                         style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${stringResource(R.string.settings_data_usage)}: ${
                            if (diskBytes < 0) "—" else Formatter.formatShortFileSize(ctx, diskBytes)
                        }",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(licenseText, style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            File(ctx.filesDir, "models").deleteRecursively()
                        }
                        diskBytes = withContext(Dispatchers.IO) { computeDiskUsage(ctx) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text(stringResource(R.string.settings_data_clear)) }
        }
    }
}

private fun computeDiskUsage(ctx: android.content.Context): Long {
    val root = ctx.filesDir
    fun sizeOf(f: File): Long = if (f.isDirectory)
        (f.listFiles()?.sumOf { sizeOf(it) } ?: 0) else f.length()
    return sizeOf(root)
}

private val licenseText = """
    As It Sounds — MIT

    Bundled: whisper.cpp (MIT), ggml (MIT), UniFFI (MPL-2.0)
    Translation: MyMemory · LibreTranslate (AGPLv3 server) · DeepL
""".trimIndent()
