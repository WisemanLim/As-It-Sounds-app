package com.asitsounds.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.asitsounds.android.whisper.WhisperNative
import com.asitsounds.core.transliterate
import com.asitsounds.core.version

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TranslitDemoScreen(modifier: Modifier = Modifier) {
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    var input by remember { mutableStateOf("As it sounds, it works.") }
    var output by remember { mutableStateOf(transliterate(input)) }
    val coreVersion = remember { version() }
    val whisperInfo = remember {
        runCatching { WhisperNative.nativeVersion() }.getOrElse { "whisper load failed: ${it.message}" }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("As It Sounds") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "English / French / Japanese / …",
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            )

            Button(
                onClick = { output = transliterate(input) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Transliterate")
            }

            HorizontalDivider()

            Text(
                "한글 음차 (Hangul)",
                style = MaterialTheme.typography.labelLarge,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = output.ifEmpty { "—" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            HorizontalDivider()

            Text(
                text = "Core v$coreVersion",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "Whisper backend",
                style = MaterialTheme.typography.labelLarge,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = whisperInfo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
