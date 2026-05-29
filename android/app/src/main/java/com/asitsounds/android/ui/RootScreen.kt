package com.asitsounds.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.asitsounds.android.R

private enum class Tab(val labelRes: Int) {
    Transcribe(R.string.tab_transcribe),
    Library(R.string.tab_library),
    Translit(R.string.tab_translit),
    Settings(R.string.tab_settings),
}

@Composable
fun RootScreen() {
    var current by remember { mutableStateOf(Tab.Transcribe) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = current == Tab.Transcribe,
                    onClick = { current = Tab.Transcribe },
                    icon = { Icon(Icons.Outlined.PlayArrow, null) },
                    label = { Text(stringResource(Tab.Transcribe.labelRes)) },
                )
                NavigationBarItem(
                    selected = current == Tab.Library,
                    onClick = { current = Tab.Library },
                    icon = { Icon(Icons.Outlined.List, null) },
                    label = { Text(stringResource(Tab.Library.labelRes)) },
                )
                NavigationBarItem(
                    selected = current == Tab.Translit,
                    onClick = { current = Tab.Translit },
                    icon = { Icon(Icons.Outlined.Edit, null) },
                    label = { Text(stringResource(Tab.Translit.labelRes)) },
                )
                NavigationBarItem(
                    selected = current == Tab.Settings,
                    onClick = { current = Tab.Settings },
                    icon = { Icon(Icons.Outlined.Settings, null) },
                    label = { Text(stringResource(Tab.Settings.labelRes)) },
                )
            }
        }
    ) { padding ->
        val mod = Modifier.padding(padding)
        when (current) {
            Tab.Transcribe -> PipelineScreen(mod)
            Tab.Library -> LibraryScreen(mod)
            Tab.Translit -> TranslitDemoScreen(mod)
            Tab.Settings -> SettingsScreen(mod)
        }
    }
}
