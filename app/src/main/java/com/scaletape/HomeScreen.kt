package com.scaletape

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: JobStore, onStore: (JobStore) -> Unit, onOpen: (String) -> Unit) {
    var jobs by remember { mutableStateOf<List<Job>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Job?>(null) }
    var openId by remember { mutableStateOf<String?>(null) } // the row swiped open to show Delete
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    // The chosen order survives restarts.
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    var sort by remember { mutableStateOf(JobSort.entries.firstOrNull { it.name == prefs.getString("sort", null) } ?: JobSort.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Also on every return to the app: the sync tool may have brought jobs in meanwhile.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { jobs = withContext(Dispatchers.IO) { store.list() } } }

    /** Runs a sync-folder switch off the main thread, then shows the new store's jobs. */
    fun switchStore(failure: String, f: () -> JobStore) {
        creating = true
        scope.launch {
            try {
                val (s, list) = withContext(Dispatchers.IO) { f().let { it to it.list() } }
                onStore(s); jobs = list
            } catch (e: Exception) {
                snackbar.showSnackbar("$failure: ${e.message}")
            } finally {
                creating = false
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) switchStore("Couldn't use that folder") { startSync(context, tree) }
    }
    // An open row closes on scroll and once the delete dialog is dismissed.
    LaunchedEffect(listState) { snapshotFlow { listState.isScrollInProgress }.collect { if (it) openId = null } }
    LaunchedEffect(toDelete) { if (toDelete == null) openId = null }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        creating = true
        scope.launch {
            try {
                onOpen(withContext(Dispatchers.IO) { store.create(context, uri) }.id)
            } catch (e: Exception) { // corrupt or password-protected PDFs throw
                snackbar.showSnackbar("Couldn't open PDF: ${e.message}")
            } finally {
                creating = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jobs") },
                actions = {
                    Box {
                        TextButton(onClick = { sortMenu = true }) {
                            Text(sort.label)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Sort jobs")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            JobSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        sort = option
                                        prefs.edit().putString("sort", option.name).apply()
                                        sortMenu = false
                                    },
                                    trailingIcon = if (option == sort) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            if (store.folder is SafFolder) DropdownMenuItem(
                                text = { Text("Stop syncing") },
                                onClick = { moreMenu = false; switchStore("Couldn't copy the jobs back") { stopSync(context, store) } },
                            ) else DropdownMenuItem(
                                text = { Text("Sync folder") },
                                onClick = { moreMenu = false; if (!creating) pickFolder.launch(null) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!creating) pickPdf.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New job") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = jobs?.let { sort.order(it) }
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                list == null || creating -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    "No jobs yet. Tap New job and pick a plan PDF.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 88.dp)) { // clear the FAB
                    itemsIndexed(list, key = { _, job -> job.id }) { i, job ->
                        val surface = MaterialTheme.colorScheme.surface
                        val rowColor = if (i % 2 == 0) surface
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f).compositeOver(surface)
                        SwipeRow(
                            label = job.name,
                            open = openId == job.id,
                            onOpenChange = { open -> if (open) openId = job.id else if (openId == job.id) openId = null },
                            onTouch = { if (openId != job.id) openId = null },
                            onDelete = { toDelete = job },
                            onClick = { onOpen(job.id) },
                        ) { modifier ->
                            ListItem(
                                headlineContent = { Text(job.name) },
                                supportingContent = { Text(jobSummary(job, sort)) },
                                colors = ListItemDefaults.colors(containerColor = rowColor),
                                modifier = modifier,
                            )
                        }
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

private enum class Swipe { Closed, Open }

/** A row that swipes left to show a full-height Delete button behind it. Tapping an open row closes it. */
@Composable
private fun SwipeRow(
    label: String,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onTouch: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val width = 96.dp
    val widthPx = with(LocalDensity.current) { width.toPx() }
    val state = remember(widthPx) {
        AnchoredDraggableState(Swipe.Closed, DraggableAnchors { Swipe.Closed at 0f; Swipe.Open at -widthPx })
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { snapshotFlow { state.settledValue }.collect { onOpenChange(it == Swipe.Open) } }
    LaunchedEffect(open) { if (!open && state.targetValue == Swipe.Open) state.animateTo(Swipe.Closed) }
    Box(
        Modifier.height(IntrinsicSize.Min)
            // See every touch first (Initial pass, consumed or not) so touching this row closes any other open one.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); onTouch() } }
            .semantics {
            customActions = listOf(CustomAccessibilityAction("Delete $label") { onDelete(); true })
        },
    ) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight()
                .background(MaterialTheme.colorScheme.error)
                .clickable(onClick = onDelete),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val onError = MaterialTheme.colorScheme.onError
            Icon(Icons.Default.Delete, contentDescription = null, tint = onError)
            Text("Delete", color = onError, style = MaterialTheme.typography.labelLarge)
        }
        content(
            Modifier
                .offset { IntOffset(state.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(state, Orientation.Horizontal)
                .clickable {
                    if (state.currentValue == Swipe.Open) scope.launch { state.animateTo(Swipe.Closed) } else onClick()
                },
        )
    }
}

/** Home list orders. Dates sort newest first. */
enum class JobSort(val label: String, val order: (List<Job>) -> List<Job>) {
    RECENT("Recent activity", { jobs -> jobs.sortedByDescending { it.updated } }),
    NAME("Name", { jobs -> jobs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }),
    CREATED("Date created", { jobs -> jobs.sortedByDescending { it.created } }),
}

/** Shows the date the list is sorted by: created when sorting by it, otherwise last activity. */
private fun jobSummary(job: Job, sort: JobSort): String {
    val total = job.totalInches()?.let { "%.2f LF".format(it / 12) } ?: "scale not set"
    val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val date = if (sort == JobSort.CREATED) "created " + format.format(Date(job.created)) else format.format(Date(job.updated))
    return "${job.runs.size} runs · $total · $date"
}
