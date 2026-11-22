package com.scaletape

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// PdfRenderer isn't thread-safe: every render and close goes through this one thread.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val pdfThread = Dispatchers.IO.limitedParallelism(1)

private const val MAX_SIDE_PX = 4096f // caps bitmap memory (~64 MB) for big sheets
private const val LOUPE_MAG = 4f // loupe magnification relative to the current zoom
private val RUN_COLOR = Color(0xFFD32F2F) // fixed, not themed: drawn over white paper
private val CALIB_COLOR = Color(0xFF1565C0)
private val SELECTED_COLOR = Color(0xFFFF6F00)
private val END_GRABBER_COLOR = Color(0xFF2E7D32) // grabber turns green while placing the end point

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobScreen(store: JobStore, initial: Job, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var job by remember { mutableStateOf(initial) }
    // Room-border editing: tap a room to edit its border; `draft` is that border while its handles are dragged.
    var editIdx by remember { mutableStateOf<Int?>(null) } // index in job.boundaries
    var draft by remember { mutableStateOf<RoomBoundary?>(null) }
    // shortcut: saves synchronously on the main thread (small JSON, keeps writes ordered); move to a writer thread if jobs get huge.
    fun update(f: (Job) -> Job) {
        val next = f(job)
        val reseeded = next.reseedIfScaleChanged(job)
        if (reseeded !== next) { editIdx = null; draft = null } // borders were wiped, so editIdx may point at another one
        job = reseeded.copy(updated = System.currentTimeMillis())
        store.save(job)
    }

    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var ppi by remember { mutableFloatStateOf(72f) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Measuring: tap places `cursor` (shown in the loupe), drag the grabber to fine-tune, then Set.
    // Each Set appends `cursor` to `chain`; Done turns a chain of 2+ points into one run (calibrating finishes at 2).
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var chain by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var calibrating by remember { mutableStateOf(false) }
    var calibLine by remember { mutableStateOf<Run?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) } // name + scale dialog
    var addingRoom by remember { mutableStateOf(false) }
    var deletingRoom by remember { mutableStateOf<String?>(null) }
    var renamingRoom by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Run?>(null) } // tapped run, offered for deletion
    var editRooms by remember { mutableStateOf(false) }
    var redetecting by remember { mutableStateOf(false) } // confirm before replacing this page's borders
    var bitmapPage by remember { mutableIntStateOf(-1) } // the page `bitmap` shows (it lags `page` while rendering)

    // Registered after the screen-level handler, so while measuring or selecting, back cancels that instead of leaving.
    BackHandler(enabled = cursor != null || chain.isNotEmpty() || calibrating || selected != null || editRooms) {
        cursor = null; chain = emptyList(); calibrating = false; selected = null
        if (editIdx != null) { editIdx = null; draft = null } else editRooms = false
    }

    DisposableEffect(Unit) {
        val r = runCatching {
            PdfRenderer(ParcelFileDescriptor.open(store.pdf(job.id), ParcelFileDescriptor.MODE_READ_ONLY))
        }.onFailure { message = "Couldn't open PDF: ${it.message}" }.getOrNull()
        renderer = r
        onDispose { r?.let { CoroutineScope(pdfThread).launch { it.close() } } }
    }

    val pageCount = renderer?.pageCount ?: 0
    val page = job.page.coerceIn(0, maxOf(0, pageCount - 1))

    LaunchedEffect(renderer, page) {
        val r = renderer ?: return@LaunchedEffect
        val (bmp, dpi) = withContext(pdfThread) { r.renderPage(page) }
        bitmap = bmp; ppi = dpi; bitmapPage = page; cursor = null; chain = emptyList(); editIdx = null; draft = null
        if (selected?.page != page) selected = null // kept when a list tap switched to its page
        if (viewSize.width > 0) { // fit page to view
            zoom = minOf(viewSize.width.toFloat() / bmp.width, viewSize.height.toFloat() / bmp.height)
            pan = Offset((viewSize.width - bmp.width * zoom) / 2, (viewSize.height - bmp.height * zoom) / 2)
        }
    }

    // Room labels per page, read from the PDF's text.
    var labels by remember { mutableStateOf<Map<Int, List<RoomLabel>>>(emptyMap()) }
    LaunchedEffect(renderer) { // after the render effect above, so the page shows first
        val r = renderer ?: return@LaunchedEffect
        // shortcut: reads every page up front; fine for plan sets of a few dozen sheets, make it per-page if big sets lag.
        labels = withContext(pdfThread) { (0 until r.pageCount).associateWith { r.roomLabels(it) } }
    }

    /**
     * Picks rooms for runs the user hasn't placed by hand (all of them, or only new ones without a room):
     * the nearest label not behind a wall. Wall sizes depend on the scale, so this reruns when it changes.
     */
    suspend fun refreshRooms(onlyNew: Boolean) {
        val r = renderer ?: return
        val todo = job.runs.filter { !it.roomManual && it.page !in job.seededPages && (!onlyNew || it.room == null) }
        if (todo.isEmpty() || labels.isEmpty()) return
        val pageLabels = labels
        val scaleRatio = parseScale(job.scale)
        val shown = bitmap
        val shownPage = page
        val shownPpi = ppi
        val picked: Map<Run, String?> = withContext(pdfThread) {
            todo.groupBy { it.page }.flatMap { (pg, runs) ->
                val l = pageLabels[pg].orEmpty()
                if (l.isEmpty()) return@flatMap runs.map { it to null }
                val (bmp, bppi) = if (pg == shownPage && shown != null) shown to shownPpi else r.renderPage(pg)
                val walls = scaleRatio?.let { ratio -> WallFinder((bppi * ratio).toFloat()) { x, y -> bmp.isDark(x, y) } }
                val result = runs.map { run -> run.midPoints().let { m -> run to pickRoom(l, m.x, m.y, bppi / 72f, walls) } }
                if (bmp !== shown) bmp.recycle()
                result
            }.toMap()
        }
        // Apply to the runs as they are now (some may have been added or deleted meanwhile).
        val updated = job.runs.map { run -> if (run in picked && run.page !in job.seededPages && picked[run] != run.room) run.copy(room = picked[run]) else run }
        if (updated != job.runs) {
            if (selected in picked) selected = null
            update { it.copy(runs = updated) }
        }
    }
    LaunchedEffect(labels, job.scale) { refreshRooms(onlyNew = false) }

    // First time a page is shown with a valid scale (and again after Redetect, or a scale change if not edited): draw a
    // starting border around each room label, found from the walls. Runs on this page then go by the borders.
    LaunchedEffect(labels, bitmap, bitmapPage, job.scale, job.seededPages) {
        val bmp = bitmap ?: return@LaunchedEffect
        val ratio = parseScale(job.scale) ?: return@LaunchedEffect
        val pg = bitmapPage
        val pageLabels = labels[pg] ?: return@LaunchedEffect // labels not read yet
        // Re-seeded: pages never seeded, and pages with borders from the old star-shaped seeder (> MAX_CORNERS corners).
        val stale = job.boundaries.any { it.page == pg && it.points.size > MAX_CORNERS }
        if (pg != page || (pg in job.seededPages && !stale)) return@LaunchedEffect
        val pxPerPt = ppi / 72f
        val pxPerInch = (ppi * ratio).toFloat()
        // Borders the user added before the page was seeded stay; seeds are cut back off them.
        val kept = job.boundaries.filter { it.page == pg && it.points.size <= MAX_CORNERS }
        val seeded = withContext(Dispatchers.Default) {
            val walls = WallFinder(pxPerInch) { x, y -> bmp.isDark(x, y) }
            val pageRect = Rect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            val rooms = numberRepeats(
                pageLabels.filterNot { inTable(it.x, it.y, pxPerPt) { x, y -> bmp.isDark(x, y) } }, // schedules list rooms too
                taken = (job.boundaries.filter { it.page != pg } + kept).map { it.name }.toSet(),
            )
            val rects = rooms.map { l ->
                ensureActive()
                val c = Offset(l.x, l.y) * pxPerPt
                c to seedRect(c, maxLen = 360 * pxPerInch, inset = 4 * pxPerInch, spread = 24 * pxPerInch, page = pageRect) { from, to ->
                    walls.crosses(from.x, from.y, to.x, to.y)
                }
            }
            val keptPx = kept.map { b -> b.bounds().let { Rect(it.topLeft * pxPerPt, it.bottomRight * pxPerPt) } }
            separate(rects, minApart = 48 * pxPerInch).mapIndexedNotNull { i, r ->
                // a label inside a kept border is that room: clipAway drops it
                keptPx.fold(r) { acc, k -> acc?.let { clipAway(it, k, rects[i].first) } }
                    ?.let { RoomBoundary(rooms[i].name, pg, it.corners().map { c -> c / pxPerPt }) }
            }
        }
        if (stale) { editIdx = null; draft = null } // dropping old borders shifts the indices
        update { j ->
            val keep = j.boundaries.filter { it.page != pg || it.points.size <= MAX_CORNERS } // drops old star-shaped seeds
            j.copy(boundaries = keep + seeded, seededPages = j.seededPages + pg).reassignRooms()
        }
        if (selected != null && selected !in job.runs) selected = null
    }

    val ratio = parseScale(job.scale)
    val total = job.totalInches()

    val scope = rememberCoroutineScope()
    fun setPoint() {
        val c = cursor ?: return
        chain = chain + c // magnifier stays open for the next point
        if (calibrating && chain.size == 2) { calibLine = Run(page, chain[0], chain[1], ppi); calibrating = false; chain = emptyList(); cursor = null }
    }
    fun finishChain() {
        if (chain.size < 2) return
        val run = Run(page, chain[0], chain[1], ppi, extra = chain.drop(2))
        update { it.copy(runs = it.runs + run).reassignRooms() }; scope.launch { refreshRooms(onlyNew = true) }
        chain = emptyList(); cursor = null
    }

    val sheet = rememberBottomSheetScaffoldState()
    val localDensity = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) } // measured, so the peek shows exactly the header
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Pulled all the way up, the sheet stops below the title bar instead of sliding under the status bar.
    val screenHeight = with(localDensity) { LocalWindowInfo.current.containerSize.height.toDp() }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listMaxHeight = (screenHeight - statusBar - 64.dp - headerHeight).coerceAtLeast(0.dp)
    val sheetExpanded = sheet.bottomSheetState.currentValue == SheetValue.Expanded
    BackHandler(enabled = sheetExpanded) { scope.launch { sheet.bottomSheetState.partialExpand() } }

    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = headerHeight,
        sheetDragHandle = null, // drawn inside the measured header instead
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(job.name, maxLines = 1)
                        Text(
                            "Scale: ${job.scale}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ratio == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (pageCount > 1) {
                        IconButton(onClick = { update { it.copy(page = page - 1) } }, enabled = page > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page")
                        }
                        Text("${page + 1}/$pageCount", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { update { it.copy(page = page + 1) } }, enabled = page < pageCount - 1) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page")
                        }
                    }
                    IconToggleButton(
                        checked = editRooms,
                        onCheckedChange = {
                            editRooms = it; editIdx = null; draft = null
                            if (it) { cursor = null; chain = emptyList(); calibrating = false; selected = null }
                        },
                        colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) { Icon(Icons.Default.Place, "Edit room borders") }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit name and scale") }
                },
            )
        },
        sheetContent = {
            // Header includes the gesture-bar inset so, collapsed, the run list stays hidden below it.
            Column(Modifier.onSizeChanged { headerHeight = with(localDensity) { it.height.toDp() } }.padding(bottom = navBar)) {
                // Same pill as BottomSheetDefaults.DragHandle, minus its 22dp padding above and below.
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 2.dp)
                        .size(32.dp, 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
                )
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    val c = cursor
                    if (c != null || chain.isNotEmpty()) {
                        TextButton(onClick = { cursor = null; chain = emptyList() }) { Text("Cancel") }
                        val live = if (chain.isNotEmpty() && c != null && ratio != null && !calibrating)
                            (chain + c).let { formatFeetInches(Run(page, it[0], it[1], ppi, extra = it.drop(2)).inches(ratio)) } else null
                        Text(
                            when {
                                live != null -> live
                                c == null -> "Tap the plan to continue"
                                chain.isEmpty() -> "Tap ≡ to set start"
                                else -> "Tap ≡ to add a point"
                            },
                            Modifier.weight(1f).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Button(onClick = { setPoint() }, enabled = c != null, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Set point") }
                        if (!calibrating) Button(onClick = { finishChain() }, Modifier.padding(start = 6.dp, end = 8.dp), enabled = chain.size >= 2, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Done") }
                    } else if (editRooms) {
                        val idx = editIdx
                        TextButton(onClick = { if (idx != null) { editIdx = null; draft = null } else editRooms = false }) { Text("Done") }
                        Text(
                            draft?.name ?: "Tap a room",
                            Modifier.weight(1f).padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (idx == null) {
                            TextButton(onClick = { redetecting = true }, enabled = bitmap != null) { Text("Redetect") }
                            TextButton(onClick = { addingRoom = true }, enabled = bitmap != null) { Text("Add room") }
                        }
                        else Button(
                            onClick = {
                                update { j -> j.copy(boundaries = j.boundaries.filterIndexed { k, _ -> k != idx }, editedPages = j.editedPages + page).reassignRooms() }
                                editIdx = null; draft = null
                                if (selected != null && selected !in job.runs) selected = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text("Delete") }
                    } else if (selected != null) {
                        val sel = selected!!
                        TextButton(onClick = { selected = null }) { Text("Done") }
                        Text(
                            listOfNotNull(sel.room?.ifEmpty { null }, ratio?.let { formatFeetInches(sel.inches(it)) })
                                .joinToString(" · ").ifEmpty { "Selected run" },
                            Modifier.weight(1f).padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(
                            onClick = { update { it.copy(runs = it.runs - sel) }; selected = null },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text("Delete") }
                    } else {
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(
                                if (total == null) "Fix the scale" else "%.2f LF".format(total / 12),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                if (calibrating) "Tap the start of a known dimension"
                                else "${job.runs.size} runs" + (total?.let { " · ${formatFeetInches(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            onClick = { update { it.copy(runs = it.runs.dropLast(1)) } },
                            enabled = job.runs.isNotEmpty(),
                        ) { Text("Undo") }
                        TextButton(onClick = { confirmClear = true }, enabled = job.runs.isNotEmpty()) { Text("Clear") }
                        FilterChip(
                            selected = calibrating,
                            onClick = { calibrating = !calibrating },
                            label = { Text("Calibrate") },
                            enabled = bitmap != null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            HorizontalDivider()
            // Pull the sheet up to see every run, grouped by room; delete or drag them between rooms here.
            RunList(
                runs = job.runs, ratio = ratio, showPages = pageCount > 1, selected = selected, bottomPadding = navBar,
                modifier = Modifier.heightIn(max = listMaxHeight),
                extraRooms = (job.rooms + job.boundaries.map { it.name }).distinct(),
                onAddRoom = { addingRoom = true },
                onDeleteRoom = { deletingRoom = it },
                onRenameRoom = { renamingRoom = it },
                onTap = { i ->
                    // Highlight it on the plan (switching page if needed) and drop the sheet to show it.
                    val r = job.runs[i]
                    cursor = null; chain = emptyList(); calibrating = false
                    selected = r
                    if (r.page != page) update { it.copy(page = r.page) }
                    else if (viewSize.width > 0) { // same page: center it, zooming in until it spans ~40% of the view
                        val len = (r.b - r.a).getDistance()
                        if (len > 0f) zoom = maxOf(zoom, minOf(20f, viewSize.width * 0.4f / len))
                        val mid = (r.a + r.b) / 2f
                        pan = Offset(viewSize.width / 2f, viewSize.height / 2f) - mid * zoom
                    }
                    scope.launch { sheet.bottomSheetState.partialExpand() }
                },
                onDelete = { i ->
                    if (selected == job.runs[i]) selected = null
                    update { it.copy(runs = it.runs.filterIndexed { j, _ -> j != i }) }
                },
                onMove = { i, room ->
                    val old = job.runs.getOrNull(i)
                    if (old != null && (old.room.orEmpty() != room || !old.roomManual)) {
                        val moved = old.copy(room = room, roomManual = true)
                        if (selected == old) selected = moved
                        // Keep the room it left in the list even if that was its last run (Unassigned excepted).
                        val from = old.room.orEmpty()
                        update {
                            it.copy(
                                runs = it.runs.mapIndexed { j, r -> if (j == i) moved else r },
                                rooms = if (from.isEmpty() || from in it.rooms) it.rooms else it.rooms + from,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) { // padding already keeps the plan above the peeking sheet
            message?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }

            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, panBy, zoomBy, _ ->
                            val newZoom = (zoom * zoomBy).coerceIn(0.05f, 20f)
                            pan = centroid - (centroid - pan) * (newZoom / zoom) + panBy
                            zoom = newZoom
                        }
                    }
                    .pointerInput(page) {
                        detectTapGestures { tap ->
                            if (bitmap == null) return@detectTapGestures
                            val p = (tap - pan) / zoom
                            if (editRooms) { // taps pick a room to edit instead of measuring
                                val pt = p / (ppi / 72f)
                                val i = job.boundaries.indices.filter { job.boundaries[it].page == page && job.boundaries[it].contains(pt) }
                                    .minByOrNull { job.boundaries[it].area() }
                                editIdx = i; draft = i?.let { job.boundaries[it] }
                                return@detectTapGestures
                            }
                            if (cursor == null && chain.isEmpty() && !calibrating) {
                                // Not measuring: a tap near a run selects it; a tap elsewhere clears a selection first.
                                val hit = job.runs.filter { it.page == page }.minByOrNull { it.distanceTo(p) }
                                if (hit != null && hit.distanceTo(p) * zoom < 24.dp.toPx()) { selected = hit; return@detectTapGestures }
                                if (selected != null) { selected = null; return@detectTapGestures }
                            }
                            val c = cursor
                            if (c != null && (tap - (c * zoom + pan)).getDistance() > Geometry(Offset.Zero, density).loupeR) {
                                cursor = null // tap outside the magnifier closes it
                                return@detectTapGestures
                            }
                            cursor = p
                        }
                    },
            ) {
                val bmp = bitmap
                if (bmp == null) {
                    if (message == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    PlanCanvas(
                        image = remember(bmp) { bmp.asImageBitmap() },
                        runs = job.runs.filter { it.page == page },
                        ratio = ratio, zoom = zoom, pan = pan,
                        cursor = cursor, chain = chain, calibLine = calibLine, selected = selected,
                        pointColor = if (calibrating) CALIB_COLOR else RUN_COLOR,
                        rooms = job.boundaries.mapIndexed { i, b -> if (i == editIdx && draft != null) draft!! else b }.filter { it.page == page },
                        activeRoom = draft, editRooms = editRooms, pxPerPt = ppi / 72f,
                    )
                    if (editRooms) draft?.let { d ->
                        val handle = with(LocalDensity.current) { 22.dp.toPx() }
                        fun commit() {
                            val i = editIdx ?: return
                            val b = draft ?: return
                            if (job.boundaries.getOrNull(i) == b) return
                            update { j -> j.copy(boundaries = j.boundaries.mapIndexed { k, old -> if (k == i) b else old }, editedPages = j.editedPages + b.page).reassignRooms() }
                            if (selected != null && selected !in job.runs) selected = null
                        }
                        val n = d.points.size
                        val pxPerPt = ppi / 72f
                        val pageRect = Rect(0f, 0f, (bitmap?.width ?: 0) / pxPerPt, (bitmap?.height ?: 0) / pxPerPt)
                        /** Stays a simple shape, off the other rooms on this page. */
                        fun fits(b: RoomBoundary) = !b.selfCrosses() &&
                            job.boundaries.withIndex().none { (k, o) -> k != editIdx && o.page == b.page && b.overlaps(o) }
                        if (n < MAX_CORNERS) for (i in 0 until n) { // tap the dot between two corners to add a corner there
                            val m = (d.points[i] + d.points[(i + 1) % n]) / 2f * (ppi / 72f) * zoom + pan
                            Box(
                                Modifier.offset { IntOffset((m.x - handle).roundToInt(), (m.y - handle).roundToInt()) }.size(44.dp)
                                    .semantics { contentDescription = "Add corner" }
                                    .pointerInput(i, n) { detectTapGestures { draft = draft?.splitEdge(i); commit() } },
                            )
                        }
                        for (i in 0 until n) { // drag a corner to move it; long-press to remove it
                            val v = d.points[i] * (ppi / 72f) * zoom + pan
                            Box(
                                Modifier.offset { IntOffset((v.x - handle).roundToInt(), (v.y - handle).roundToInt()) }.size(44.dp)
                                    .semantics { contentDescription = "Corner" }
                                    .pointerInput(i, n) {
                                        // Follows the finger; a spot that would overlap a room or leave the page is skipped,
                                        // so the corner waits at the last good spot.
                                        var target = Offset.Zero
                                        detectDragGestures(
                                            onDragStart = { target = draft?.points?.getOrNull(i) ?: Offset.Zero },
                                            onDragEnd = { commit() }, onDragCancel = { commit() },
                                        ) { change, drag ->
                                            change.consume()
                                            target += drag / zoom / (ppi / 72f)
                                            val cur = draft ?: return@detectDragGestures
                                            val moved = cur.copy(points = cur.points.mapIndexed { k, p -> if (k == i) target.clampTo(pageRect) else p })
                                            if (fits(moved)) draft = moved
                                        }
                                    }
                                    .pointerInput(i, n) {
                                        detectTapGestures(onLongPress = { draft?.withoutVertex(i)?.takeIf { fits(it) }?.let { draft = it; commit() } })
                                    },
                            )
                        }
                    }
                    cursor?.let { c ->
                        val g = Geometry(c * zoom + pan, LocalDensity.current.density).grabber
                        val half = with(LocalDensity.current) { 28.dp.toPx() }
                        Box(
                            Modifier
                                .offset { IntOffset((g.x - half).roundToInt(), (g.y - half).roundToInt()) }
                                .size(56.dp)
                                .semantics { contentDescription = "Point handle" }
                                .pointerInput(page) {
                                    // Drag moves the point; a tap sets it. Consume everything so the canvas ignores it.
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        down.consume()
                                        val start = cursor
                                        var moved = 0f
                                        drag(down.id) { change ->
                                            moved += change.positionChange().getDistance()
                                            cursor = cursor?.plus(change.positionChange() / zoom)
                                            change.consume()
                                        }
                                        if (moved < viewConfiguration.touchSlop) { cursor = start; setPoint() }
                                    }
                                },
                        )
                    }
                }
            }
        }
    }

    calibLine?.let { line ->
        var known by remember { mutableStateOf("") }
        val inches = parseLength(known, 12.0)?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { calibLine = null },
            title = { Text("Known length") },
            text = {
                OutlinedTextField(
                    value = known, onValueChange = { known = it }, singleLine = true,
                    label = { Text("Real length of the blue line") }, placeholder = { Text("e.g. 10' 6\"") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    update { it.copy(scale = calibratedScale(line.inches(1.0), inches!!)) }
                    calibLine = null
                }, enabled = inches != null) { Text("Set scale") }
            },
            dismissButton = { TextButton(onClick = { calibLine = null }) { Text("Cancel") } },
        )
    }

    if (redetecting) AlertDialog(
        onDismissRequest = { redetecting = false },
        title = { Text("Redetect rooms?") },
        text = { Text("Replaces every room border on this page, including ones you edited or added, with new ones found from the plan.") },
        confirmButton = {
            TextButton(onClick = {
                editIdx = null; draft = null; redetecting = false
                update { it.unseed(setOf(page)) } // clean slate: the seeding effect redraws the page
            }) { Text("Redetect") }
        },
        dismissButton = { TextButton(onClick = { redetecting = false }) { Text("Cancel") } },
    )

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all runs?") },
        text = { Text("All ${job.runs.size} runs on every page will be removed.") },
        confirmButton = {
            TextButton(onClick = { update { it.copy(runs = emptyList()) }; confirmClear = false }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )

    if (addingRoom) {
        var name by remember { mutableStateOf("") }
        // A new room's border: a 10' square in the middle of the view, on the page, cut back off the rooms there.
        val newRect = remember {
            val bmp = bitmap ?: return@remember null
            val pxPerPt = ppi / 72f
            val mid = (Offset(viewSize.width / 2f, viewSize.height / 2f) - pan) / zoom / pxPerPt
            val pageRect = Rect(0f, 0f, bmp.width / pxPerPt, bmp.height / pxPerPt)
            if (!pageRect.contains(mid)) return@remember null
            val half = (60 * (ratio ?: (1.0 / 48)) * 72).toFloat()
            job.boundaries.filter { it.page == page }.fold(Rect(mid, half).intersect(pageRect) as Rect?) { r, b ->
                r?.let { clipAway(it, b.bounds(), mid) }
            }
        }
        val taken = (job.rooms + job.boundaries.map { it.name } + job.runs.mapNotNull { it.room } + "Unassigned").any { it.equals(name.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { addingRoom = false },
            title = { Text("Add room") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                    isError = taken,
                    supportingText = when {
                        taken -> ({ Text("That room already exists") })
                        newRect == null && bitmap != null -> ({ Text("No border: move an empty spot to the middle of the screen") })
                        else -> null
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val b = newRect?.let { RoomBoundary(name.trim(), page, it.corners()) }
                        update {
                            it.copy(
                                rooms = it.rooms + name.trim(), boundaries = it.boundaries + listOfNotNull(b),
                                editedPages = if (b != null) it.editedPages + page else it.editedPages,
                            ).reassignRooms()
                        }
                        if (b != null) { editRooms = true; editIdx = job.boundaries.lastIndex; draft = b }
                        addingRoom = false
                    },
                    enabled = name.isNotBlank() && !taken,
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingRoom = false }) { Text("Cancel") } },
        )
    }

    renamingRoom?.let { old ->
        var name by remember(old) { mutableStateOf(old) }
        val target = name.trim()
        // Renaming onto another room merges them; use that room's exact spelling.
        val existing = (job.rooms + job.boundaries.map { it.name } + job.runs.mapNotNull { it.room?.ifEmpty { null } }).distinct()
            .firstOrNull { it != old && it.equals(target, ignoreCase = true) }
        val reserved = target.equals("Unassigned", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { renamingRoom = null },
            title = { Text("Rename room") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                    isError = reserved,
                    supportingText = when {
                        reserved -> ({ Text("That name is taken by Unassigned") })
                        existing != null -> ({ Text("Its runs will join $existing") })
                        else -> null
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val to = existing ?: target
                        if (selected?.room == old) selected = null
                        editIdx = null; draft = null
                        update { it.renameRoom(old, to) }
                        renamingRoom = null
                    },
                    enabled = target.isNotEmpty() && !reserved && target != old,
                ) { Text(if (existing != null) "Merge" else "Rename") }
            },
            dismissButton = { TextButton(onClick = { renamingRoom = null }) { Text("Cancel") } },
        )
    }

    deletingRoom?.let { room ->
        val count = job.runs.count { it.room == room }
        AlertDialog(
            onDismissRequest = { deletingRoom = null },
            title = { Text("Delete $room?") },
            text = {
                Text(
                    if (count == 0) "The room is empty."
                    else "Its $count run${if (count == 1) "" else "s"} will move to Unassigned. No measurements are deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Runs stay, marked manual so re-picking doesn't put them straight back.
                    if (selected?.room == room) selected = null
                    editIdx = null; draft = null
                    update { j ->
                        j.copy(
                            rooms = j.rooms - room,
                            boundaries = j.boundaries.filter { it.name != room },
                            editedPages = j.editedPages + j.boundaries.filter { it.name == room }.map { it.page },
                            runs = j.runs.map { if (it.room == room) it.copy(room = "", roomManual = true) else it },
                        )
                    }
                    deletingRoom = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingRoom = null }) { Text("Cancel") } },
        )
    }

    if (editing) {
        var name by remember { mutableStateOf(job.name) }
        var scale by remember { mutableStateOf(job.scale) }
        val scaleValid = parseScale(scale) != null
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit job") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                    OutlinedTextField(
                        value = scale, onValueChange = { scale = it }, singleLine = true, isError = !scaleValid,
                        label = { Text("Scale") },
                        supportingText = { Text("e.g. 1/4\" = 1', 1/8\" = 1', 3/4\" = 1'") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { update { it.copy(name = name.trim(), scale = scale.trim()) }; editing = false },
                    enabled = name.isNotBlank() && scaleValid,
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

private val ROOM_COLORS = listOf(0xFF1E88E5, 0xFF43A047, 0xFF8E24AA, 0xFFF4511E, 0xFF00897B, 0xFFC0CA33, 0xFFD81B60, 0xFF6D4C41)

/** A room's color, fixed per name (drawn over white paper, so not themed). */
private fun roomColor(name: String) = Color(ROOM_COLORS[(name.hashCode() and Int.MAX_VALUE) % ROOM_COLORS.size])

/** The loupe sits directly over the point; the grabber hangs off its bottom rim. */
private class Geometry(p: Offset, density: Float) {
    val loupeR = 64 * density
    val grabberR = 22 * density
    val grabber = p + Offset(0f, loupeR + grabberR - 8 * density) // overlaps the rim so they read as one piece
}

@Composable
private fun PlanCanvas(
    image: ImageBitmap,
    runs: List<Run>,
    ratio: Double?,
    zoom: Float,
    pan: Offset,
    cursor: Offset?,
    chain: List<Offset>,
    calibLine: Run?,
    selected: Run?,
    pointColor: Color,
    rooms: List<RoomBoundary>,
    activeRoom: RoomBoundary?,
    editRooms: Boolean,
    pxPerPt: Float,
) {
    val density = LocalDensity.current
    val labelPaint = remember(density) {
        Paint().apply {
            color = RUN_COLOR.toArgb(); isAntiAlias = true; isFakeBoldText = true
            textSize = with(density) { 14.sp.toPx() }
        }
    }
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(20f, 12f)) }
    val roomPaint = remember(density) {
        Paint().apply {
            color = 0xFF263238.toInt(); isAntiAlias = true; isFakeBoldText = true; textAlign = Paint.Align.CENTER
            textSize = with(density) { 12.sp.toPx() }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        /** Draws the page and its marks in page-pixel space; [px] is one screen pixel in that space. */
        fun DrawScope.plan(px: Float) {
            drawImage(image)
            rooms.forEach { r ->
                val path = Path().apply {
                    r.points.forEachIndexed { i, pt -> (pt * pxPerPt).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                    close()
                }
                val c = roomColor(r.name)
                val active = r === activeRoom
                drawPath(path, c.copy(alpha = if (active) 0.25f else if (editRooms) 0.16f else 0.07f))
                drawPath(path, c, style = Stroke((if (active) 3.dp else 1.5.dp).toPx() * px))
            }
            runs.forEach {
                val sel = it == selected
                it.points().zipWithNext().forEach { (p, q) -> drawLine(if (sel) SELECTED_COLOR else RUN_COLOR, p, q, (if (sel) 6 else 3).dp.toPx() * px) }
            }
            calibLine?.let { drawLine(CALIB_COLOR, it.a, it.b, 3.dp.toPx() * px) }
            chain.zipWithNext().forEach { (p, q) -> drawLine(pointColor, p, q, 2.dp.toPx() * px) }
            chain.forEach { drawCircle(pointColor, 4.dp.toPx() * px, it) }
            chain.lastOrNull()?.let { a -> cursor?.let { drawLine(pointColor, a, it, 2.dp.toPx() * px, pathEffect = dash) } }
        }

        fun DrawScope.crosshair(at: Offset, arm: Float, color: Color) {
            drawLine(color, at - Offset(arm, 0f), at + Offset(arm, 0f), 1.dp.toPx())
            drawLine(color, at - Offset(0f, arm), at + Offset(0f, arm), 1.dp.toPx())
        }

        withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset.Zero) }) { plan(1 / zoom) }

        if (ratio != null) runs.forEach { r ->
            val mid = r.midpoint() * zoom + pan
            drawContext.canvas.nativeCanvas.drawText(formatFeetInches(r.inches(ratio)), mid.x, mid.y - 4.dp.toPx(), labelPaint)
        }

        rooms.forEach { r -> // names sit at the middle of each border, over the plan
            val mid = r.points.reduce { a, b -> a + b } / r.points.size.toFloat() * pxPerPt * zoom + pan
            drawContext.canvas.nativeCanvas.drawText(r.name, mid.x, mid.y, roomPaint)
        }
        if (editRooms) activeRoom?.let { r -> // corner handles; the small dots between them add a corner
            val color = roomColor(r.name)
            r.points.forEachIndexed { i, v ->
                val next = r.points[(i + 1) % r.points.size]
                if (r.points.size < MAX_CORNERS) drawCircle(color, 4.dp.toPx(), (v + next) / 2f * pxPerPt * zoom + pan)
                (v * pxPerPt * zoom + pan).let {
                    drawCircle(Color.White, 9.dp.toPx(), it)
                    drawCircle(color, 9.dp.toPx(), it, style = Stroke(3.dp.toPx()))
                }
            }
        }

        val c = cursor ?: return@Canvas
        val p = c * zoom + pan // the point, on screen; the loupe is centered on it
        val geo = Geometry(p, density.density)
        val r = geo.loupeR

        // Loupe: what's directly under it, magnified, with crosshairs on the point.
        val m = (zoom * LOUPE_MAG).coerceAtMost(maxOf(zoom, 12f))
        clipPath(Path().apply { addOval(Rect(p, r)) }) {
            drawRect(Color.White)
            withTransform({ translate(p.x - c.x * m, p.y - c.y * m); scale(m, m, Offset.Zero) }) { plan(1 / m) }
            crosshair(p, r, pointColor)
        }
        drawCircle(outline, r, p, style = Stroke(2.dp.toPx()))

        // Grabber: a drag handle (three bars) attached to the loupe, so the finger never covers the point.
        val g = geo.grabber
        val settingEnd = chain.isNotEmpty()
        drawCircle(if (settingEnd) END_GRABBER_COLOR else primary, geo.grabberR, g)
        drawCircle(outline, geo.grabberR, g, style = Stroke(2.dp.toPx()))
        val bar = 8.dp.toPx()
        for (dy in listOf(-6, 0, 6)) {
            val y = g.y + dy.dp.toPx()
            drawLine(if (settingEnd) Color.White else onPrimary, Offset(g.x - bar, y), Offset(g.x + bar, y), 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** Renders [page] to a white-backed bitmap; returns it and its pixels per paper inch. Call on `pdfThread`. */
private fun PdfRenderer.renderPage(page: Int): Pair<Bitmap, Float> = openPage(page).use { p ->
    // PDF units are points (1/72"), so render scale s gives 72*s pixels per paper inch.
    val s = minOf(4f, MAX_SIDE_PX / maxOf(p.width, p.height))
    val b = Bitmap.createBitmap((p.width * s).toInt(), (p.height * s).toInt(), Bitmap.Config.ARGB_8888)
    b.eraseColor(android.graphics.Color.WHITE)
    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    b to 72f * s
}

/** Plan ink vs paper, for wall finding. Off-page counts as paper. */
private fun Bitmap.isDark(x: Int, y: Int): Boolean {
    if (x !in 0 until width || y !in 0 until height) return false
    val p = getPixel(x, y)
    return ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10 < 160
}

// PdfRenderer text search arrived in Android 15 (API 35), and on 11+ through SDK extension 13.
private val canReadPdfText = Build.VERSION.SDK_INT >= 35 ||
    (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)

/** Room labels on [page]; empty when the device can't read PDF text or the page has none (e.g. scans). */
@SuppressLint("NewApi") // guarded by canReadPdfText
private fun PdfRenderer.roomLabels(page: Int): List<RoomLabel> {
    if (!canReadPdfText) return emptyList()
    return runCatching {
        openPage(page).use { p ->
            findRoomLabels(p.textContents.joinToString("") { it.text }) { word ->
                p.searchText(word).filter { it.bounds.isNotEmpty() }.map { m ->
                    TextHit(
                        m.textStartIndex,
                        m.bounds.minOf { it.left }, m.bounds.minOf { it.top },
                        m.bounds.maxOf { it.right }, m.bounds.maxOf { it.bottom },
                    )
                }
            }
        }
    }.getOrDefault(emptyList())
}
