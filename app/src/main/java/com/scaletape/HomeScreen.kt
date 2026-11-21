package com.scaletape

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: JobStore, onOpen: (String) -> Unit) {
    var jobs by remember { mutableStateOf<List<Job>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Job?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { jobs = withContext(Dispatchers.IO) { store.list() } }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        creating = true
        scope.launch {
            try {
                onOpen(withContext(Dispatchers.IO) { store.create(uri) }.id)
            } catch (e: Exception) { // corrupt or password-protected PDFs throw
                snackbar.showSnackbar("Couldn't open PDF: ${e.message}")
            } finally {
                creating = false
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Jobs") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!creating) pickPdf.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New job") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = jobs
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                list == null || creating -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    "No jobs yet. Tap New job and pick a plan PDF.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) { // clear the FAB
                    items(list, key = { it.id }) { job ->
                        ListItem(
                            headlineContent = { Text(job.name) },
                            supportingContent = { Text(jobSummary(job)) },
                            trailingContent = {
                                IconButton(onClick = { toDelete = job }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete ${job.name}")
                                }
                            },
                            modifier = Modifier.clickable { onOpen(job.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    toDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete ${job.name}?") },
            text = { Text("Its measurements and its copy of the PDF will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(job.id)
                    jobs = jobs?.minus(job)
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun jobSummary(job: Job): String {
    val total = job.totalInches()?.let { "%.2f LF".format(it / 12) } ?: "scale not set"
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(job.updated))
    return "${job.runs.size} runs · $total · $date"
}
