package com.asitsounds.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
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
import com.asitsounds.android.db.ProjectEntity
import com.asitsounds.android.db.SegmentEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val scope = rememberCoroutineScope()

    val projects by db.projectDao().observeAll().collectAsState(initial = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("New project") }
    var selectedProject by remember { mutableStateOf<ProjectEntity?>(null) }

    if (selectedProject != null) {
        ProjectDetail(selectedProject!!) { selectedProject = null }
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Library") }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    newName = "New project"
                    showCreate = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, null); Text("  New project")
            }

            if (projects.isEmpty()) {
                Text("No projects yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { p ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                                .format(java.util.Date(p.updatedAt)),
                                style = MaterialTheme.typography.bodySmall)
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(onClick = { selectedProject = p }) { Text("Open") }
                                IconButton(onClick = {
                                    scope.launch { db.projectDao().delete(p) }
                                }) {
                                    Icon(Icons.Outlined.Delete, "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreate) {
            AlertDialog(
                onDismissRequest = { showCreate = false },
                title = { Text("New project") },
                text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                val now = System.currentTimeMillis()
                                db.projectDao().insert(
                                    ProjectEntity(
                                        id = UUID.randomUUID().toString(),
                                        name = name, createdAt = now, updatedAt = now,
                                    )
                                )
                            }
                        }
                        showCreate = false
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDetail(project: ProjectEntity, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val files by db.mediaFileDao().observeByProject(project.id).collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project.name) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files.isEmpty()) {
                Text(
                    "No files yet. Transcribe one from the Transcribe tab — pick this project to persist segments here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.id }) { f ->
                    var segs by remember { mutableStateOf<List<SegmentEntity>>(emptyList()) }
                    androidx.compose.runtime.LaunchedEffect(f.id) {
                        segs = db.segmentDao().listByFile(f.id)
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(f.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${f.status} · ${segs.size} segments",
                                 style = MaterialTheme.typography.bodySmall)
                            segs.take(5).forEach { s ->
                                Text("• ${s.textEn}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (segs.size > 5) Text("…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
