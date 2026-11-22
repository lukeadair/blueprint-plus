package com.scaletape

import android.annotation.SuppressLint
import android.content.Intent
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

// PdfRenderer isn't thread-safe: every render and close goes through this one thread.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val pdfThread = Dispatchers.IO.limitedParallelism(1)

private const val MAX_SIDE_PX = 4096f // caps bitmap memory (~64 MB) for big sheets
private const val LOUPE_MAG = 2.5f // loupe magnification relative to the current zoom
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
    // Borders the draft changed (it and the rooms it pushed aside), by index; applied to the job at commit, so
    // borders added meanwhile (seeding finishing mid-drag) aren't overwritten.
    var pushed by remember { mutableStateOf<Map<Int, RoomBoundary>>(emptyMap()) }
    LaunchedEffect(editIdx, draft == null) { pushed = emptyMap() } // a drag cut short by a reset mustn't leave it behind
    // shortcut: saves synchronously on the main thread (small JSON, keeps writes ordered); move to a writer thread if jobs get huge.
    // Snapshots of the job before each user edit; in memory only, so they're gone once the job is closed.
    val undoStack = remember { mutableStateListOf<Job>() }
    val redoStack = remember { mutableStateListOf<Job>() }
    fun update(undoable: Boolean = false, f: (Job) -> Job) {
        val next = f(job)
        if (undoable && (next.runs != job.runs || next.rooms != job.rooms || next.boundaries != job.boundaries)) {
            undoStack.add(job); if (undoStack.size > 50) undoStack.removeAt(0)
            redoStack.clear()
        }
        // Snapshots from another scale hold borders and room picks found at it, and restore keeps the scale.
        if (parseScale(next.scale) != parseScale(job.scale)) { undoStack.clear(); redoStack.clear() }
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
    var deletingBorder by remember { mutableStateOf<Int?>(null) } // index into job.boundaries
    var renamingRoom by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Run?>(null) } // tapped run, offered for deletion
    // While a node of `selected` is dragged: (the saved run, the run as dragged). Saved when the drag ends.
    var nodeDraft by remember { mutableStateOf<Pair<Run, Run>?>(null) }
    var nodeLoupe by remember { mutableStateOf<Offset?>(null) } // page point under the finger while a node is dragged
    val shownSelected = nodeDraft?.takeIf { it.first == selected }?.second ?: selected
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
        if (!job.scaleSet && !calibrating && calibLine == null) editing = true // new job: ask for the scale first
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
        val fromText = withContext(pdfThread) { (0 until r.pageCount).associateWith { r.roomLabels(it) } }
        labels = fromText.filterValues { it.isNotEmpty() }
        // Last resort: OCR pages with no text labels. A page stays out of `labels` until its OCR is done, so it
        // isn't seeded as roomless first. shortcut: re-runs on every open with no cache; persist in Job if slow.
        for (pg in fromText.filterValues { it.isEmpty() }.keys) labels = labels + (pg to r.ocrRoomLabels(pg))
    }

    /**
     * Picks rooms for runs the user hasn't placed by hand (all of them, or only new ones without a room):
     * the nearest label not behind a wall. Wall sizes depend on the scale, so this reruns when it changes.
     */
    suspend fun refreshRooms(onlyNew: Boolean, pages: Set<Int>? = null) {
        val r = renderer ?: return
        val todo = job.runs.filter {
            !it.roomManual && it.page !in job.seededPages && (!onlyNew || it.room == null) && (pages == null || it.page in pages)
        }
        if (todo.isEmpty() || labels.isEmpty()) return
        val pageLabels = labels
        val scaleRatio = parseScale(job.scale)
        val shown = bitmap
        val shownPage = page
        val shownPpi = ppi
        val picked: Map<Run, String?> = withContext(pdfThread) {
            todo.groupBy { it.page }.flatMap { (pg, runs) ->
                val l = pageLabels[pg] ?: return@flatMap emptyList() // not read yet (OCR pending): keep its rooms
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
    // What the last finished refresh saw: when only labels changed (an OCR page came in), re-pick just those pages.
    val lastRefresh = remember { arrayOf<Any?>(null, null) }
    LaunchedEffect(labels, job.scale) {
        @Suppress("UNCHECKED_CAST") val prev = lastRefresh[0] as Map<Int, List<RoomLabel>>?
        val pages = if (prev != null && lastRefresh[1] == job.scale) labels.keys.filter { labels[it] != prev[it] }.toSet() else null
        refreshRooms(onlyNew = false, pages)
        lastRefresh[0] = labels; lastRefresh[1] = job.scale // only once done: a cancelled refresh is redone next time
    }

    // First time a page is shown with a valid scale (and again after Redetect, or a scale change if not edited): draw a
    // starting border around each room label, found from the walls. Runs on this page then go by the borders.
    LaunchedEffect(labels, bitmap, bitmapPage, job.scale, job.seededPages, job.scaleSet) {
        if (!job.scaleSet) return@LaunchedEffect // the 1/4" default is often wrong: seeding at it draws bad borders
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
    val context = LocalContext.current
    // A point must sit a little away from the last one, so tapping Set twice can't add a duplicate node.
    val minMovePx = with(LocalDensity.current) { 8.dp.toPx() }
    fun movedEnough(c: Offset) = chain.lastOrNull()?.let { (c - it).getDistance() * zoom >= minMovePx } ?: true
    fun setPoint() {
        val c = cursor?.takeIf { movedEnough(it) } ?: return
        chain = chain + c // magnifier stays open for the next point
        if (calibrating && chain.size == 2) { calibLine = Run(page, chain[0], chain[1], ppi); calibrating = false; chain = emptyList(); cursor = null }
    }
    fun finishChain() {
        if (chain.size < 2) return
        val run = Run(page, chain[0], chain[1], ppi, extra = chain.drop(2))
        update(true) { it.copy(runs = it.runs + run).reassignRooms() }; scope.launch { refreshRooms(onlyNew = true) }
        chain = emptyList(); cursor = null
    }

    fun commitNode(base: Run) {
        val moved = nodeDraft?.takeIf { it.first == base }?.second
        nodeDraft = null
        if (moved == null || moved == base) return
        var saved = moved
        update(true) { j ->
            val i = j.runs.indexOf(base)
            if (i < 0) j else j.copy(runs = j.runs.mapIndexed { k, r -> if (k == i) moved else r }).reassignRooms().also { saved = it.runs[i] }
        }
        selected = saved
    }

    /** Swaps [old] for [new] and selects the stored run (reassignRooms may have changed its room, so it can differ from [new]). */
    fun replaceSelected(old: Run, new: Run) {
        val i = job.runs.indexOf(old)
        if (i < 0) return
        update(true) { j -> j.copy(runs = j.runs.mapIndexed { k, r -> if (k == i) new else r }).reassignRooms() }
        selected = job.runs[i]
    }

    /** Moves the edit data (not page, scale or name) from the top of [from] into the job, pushing the current job onto [to]. */
    fun restore(from: MutableList<Job>, to: MutableList<Job>) {
        val s = from.removeLastOrNull() ?: return
        to.add(job)
        selected = null; editIdx = null; draft = null; nodeDraft = null
        update { it.copy(runs = s.runs, rooms = s.rooms, boundaries = s.boundaries, editedPages = s.editedPages, seededPages = s.seededPages) }
    }

    val sheet = rememberBottomSheetScaffoldState()
    val localDensity = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) } // measured, so the peek shows exactly the header
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Pulled all the way up, the sheet stops below the title bar instead of sliding under the status bar.
    val screenHeight = with(localDensity) { LocalWindowInfo.current.containerSize.height.toDp() }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBar = statusBar + 64.dp
    val listMaxHeight = (screenHeight - topBar - headerHeight).coerceAtLeast(0.dp)
    // Flatten the top corners as the sheet meets the title bar, so the plan can't show in the gaps.
    // derivedStateOf: the offset changes every drag frame, but only a changed corner should recompose the screen.
    val corner by remember(localDensity, topBar) {
        derivedStateOf {
            val y = runCatching { sheet.bottomSheetState.requireOffset() }.getOrNull() // throws before first layout
            if (y == null) 28.dp else with(localDensity) { (y.toDp() - topBar).coerceIn(0.dp, 28.dp) }
        }
    }
    val sheetExpanded = sheet.bottomSheetState.currentValue == SheetValue.Expanded
    BackHandler(enabled = sheetExpanded) { scope.launch { sheet.bottomSheetState.partialExpand() } }

    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = headerHeight,
        sheetShape = RoundedCornerShape(topStart = corner, topEnd = corner),
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
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Settings, "Job config") }
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
                                else -> ""
                            },
                            Modifier.weight(1f).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { setPoint() }, enabled = c != null && movedEnough(c), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Set point") }
                        if (!calibrating) Button(onClick = { finishChain() }, Modifier.padding(start = 6.dp, end = 8.dp), enabled = chain.size >= 2, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Done") }
                    } else if (editRooms) {
                        val idx = editIdx
                        val name = draft?.name
                        if (idx == null) Button(
                            onClick = { redetecting = true }, Modifier.padding(start = 8.dp), enabled = bitmap != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                        ) { Text("Redetect") }
                        else Button(
                            onClick = { deletingBorder = idx },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Delete") }
                        Text(
                            name ?: "",
                            Modifier.weight(1f)
                                .then(if (name != null) Modifier.clickable { renamingRoom = name } else Modifier)
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = { if (idx != null) { editIdx = null; draft = null } else editRooms = false },
                            Modifier.padding(end = 8.dp),
                        ) { Text("Done") }
                    } else if (selected != null) {
                        val sel = selected!!
                        Button(
                            onClick = { update(true) { it.copy(runs = it.runs - sel) }; selected = null },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Delete") }
                        Text(
                            listOfNotNull(sel.room?.ifEmpty { null }, ratio?.let { formatFeetInches((shownSelected ?: sel).inches(it)) })
                                .joinToString(" · ").ifEmpty { "Selected run" },
                            Modifier.weight(1f).padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { selected = null }, Modifier.padding(end = 8.dp)) { Text("Done") }
                    } else {
                        // Equal-width sides keep the total centered.
                        Box(Modifier.width(104.dp).padding(start = 8.dp)) {
                            IconButton(onClick = {
                                editRooms = true; editIdx = null; draft = null
                                cursor = null; chain = emptyList(); calibrating = false; selected = null
                            }) { Icon(painterResource(R.drawable.ic_format_shapes), "Edit room borders") }
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
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
                        Row(Modifier.width(104.dp).padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                            if (calibrating) TextButton(onClick = { calibrating = false }) { Text("Cancel") }
                            else {
                                IconButton(onClick = { restore(undoStack, redoStack) }, enabled = undoStack.isNotEmpty()) {
                                    Icon(painterResource(R.drawable.ic_undo), "Undo")
                                }
                                IconButton(onClick = { restore(redoStack, undoStack) }, enabled = redoStack.isNotEmpty()) {
                                    Icon(painterResource(R.drawable.ic_redo), "Redo")
                                }
                            }
                        }
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
                    update(true) { it.copy(runs = it.runs.filterIndexed { j, _ -> j != i }) }
                },
                onMove = { i, room ->
                    val old = job.runs.getOrNull(i)
                    if (old != null && (old.room.orEmpty() != room || !old.roomManual)) {
                        val moved = old.copy(room = room, roomManual = true)
                        if (selected == old) selected = moved
                        // Keep the room it left in the list even if that was its last run (Unassigned excepted).
                        val from = old.room.orEmpty()
                        update(true) {
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
                                if (hit != null && hit.distanceTo(p) * zoom < 24.dp.toPx()) {
                                    if (hit == selected) { // tap on the selected run's edge adds a node there
                                        replaceSelected(hit, hit.withInserted(p, 8.dp.toPx() / zoom)) // same 8dp gap as chained points
                                    } else selected = hit
                                    return@detectTapGestures
                                }
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
                        runs = job.runs.filter { it.page == page }.map { if (it == selected) shownSelected!! else it },
                        ratio = ratio, zoom = zoom, pan = pan,
                        cursor = cursor, nodeLoupe = nodeLoupe, chain = chain, calibLine = calibLine, selected = shownSelected,
                        pointColor = if (calibrating) CALIB_COLOR else RUN_COLOR,
                        rooms = job.boundaries.mapIndexed { i, b -> if (i == editIdx && draft != null) draft!! else pushed[i] ?: b }.filter { it.page == page },
                        activeRoom = draft, editRooms = editRooms, pxPerPt = ppi / 72f,
                    )
                    if (editRooms) draft?.let { d ->
                        val handle = with(LocalDensity.current) { 22.dp.toPx() }
                        val snapPx = with(LocalDensity.current) { 12.dp.toPx() }
                        fun commit() {
                            val p = pushed.also { pushed = emptyMap() }
                            val i = editIdx ?: return
                            val b = draft ?: return
                            val changed = p + (i to b)
                            if (changed.all { (k, v) -> job.boundaries.getOrNull(k) == v }) return
                            update(true) { j -> j.copy(boundaries = j.boundaries.mapIndexed { k, old -> changed[k] ?: old }, editedPages = j.editedPages + b.page).reassignRooms() }
                            if (selected != null && selected !in job.runs) selected = null
                        }
                        val n = d.points.size
                        val pxPerPt = ppi / 72f
                        val pageRect = Rect(0f, 0f, (bitmap?.width ?: 0) / pxPerPt, (bitmap?.height ?: 0) / pxPerPt)
                        /** Takes [b] if it's a simple shape on the page, pushing overlapped rooms aside (from where they were before this drag). */
                        fun tryMove(b: RoomBoundary) {
                            pushAside(job.boundaries, editIdx ?: return, b, pageRect)?.let { out -> pushed = out.withIndex().filter { (k, o) -> o != job.boundaries[k] }.associate { (k, o) -> k to o }; draft = b }
                        }
                        for (i in 0 until n) { // drag the dot between two corners to move that wall; tap it to add a corner there
                            val m = (d.points[i] + d.points[(i + 1) % n]) / 2f * (ppi / 72f) * zoom + pan
                            Box(
                                Modifier.offset { IntOffset((m.x - handle).roundToInt(), (m.y - handle).roundToInt()) }.size(44.dp)
                                    .semantics { contentDescription = "Wall" }
                                    .pointerInput(i, n) {
                                        var total = Offset.Zero
                                        var start: RoomBoundary? = null
                                        detectDragGestures(
                                            onDragStart = { total = Offset.Zero; start = draft },
                                            onDragEnd = { commit() }, onDragCancel = { commit() },
                                        ) { change, drag ->
                                            change.consume()
                                            total += drag / zoom / (ppi / 72f)
                                            start?.let { tryMove(it.moveEdge(i, total)) }
                                        }
                                    }
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
                                            val p = snapCorner(cur.points, i, target.clampTo(pageRect), snapPx / zoom / (ppi / 72f))
                                            tryMove(cur.copy(points = cur.points.mapIndexed { k, q -> if (k == i) p else q }))
                                        }
                                    }
                                    .pointerInput(i, n) {
                                        detectTapGestures(onLongPress = { draft?.withoutVertex(i)?.let { tryMove(it); commit() } })
                                    },
                            )
                        }
                    }
                    val sel = selected
                    if (sel != null && sel.page == page && !editRooms && cursor == null && chain.isEmpty() && !calibrating) {
                        // Drag a node of the selected run to move it; the run is saved when the finger lifts.
                        val handle = with(LocalDensity.current) { 22.dp.toPx() }
                        val shown = shownSelected ?: sel
                        shown.points().forEachIndexed { i, pt ->
                            val v = pt * zoom + pan
                            Box(
                                Modifier.offset { IntOffset((v.x - handle).roundToInt(), (v.y - handle).roundToInt()) }.size(44.dp)
                                    .semantics { contentDescription = "Run point" }
                                    .pointerInput(sel, i) { // long-press removes the node (a run keeps 2)
                                        detectTapGestures(onLongPress = {
                                            val fewer = sel.withoutPoint(i)
                                            if (fewer != sel) replaceSelected(sel, fewer)
                                        })
                                    }
                                    .pointerInput(sel, i) {
                                        var target = Offset.Zero
                                        detectDragGestures(
                                            onDragStart = { target = (nodeDraft?.takeIf { it.first == sel }?.second ?: sel).points()[i] },
                                            onDragEnd = { nodeLoupe = null; commitNode(sel) }, onDragCancel = { nodeLoupe = null; commitNode(sel) },
                                        ) { change, drag ->
                                            change.consume()
                                            target = (target + drag / zoom).clampTo(Rect(0f, 0f, (bitmap?.width ?: 0).toFloat(), (bitmap?.height ?: 0).toFloat()))
                                            nodeDraft = sel to sel.withPoint(i, target); nodeLoupe = target
                                        }
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
                if (editRooms && editIdx == null && bitmap != null) FloatingActionButton(
                    onClick = { addingRoom = true },
                    Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    shape = CircleShape,
                ) { Icon(Icons.Default.Add, "Add room") }
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
                    update { it.copy(scale = calibratedScale(line.inches(1.0), inches!!), scaleSet = true) }
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
                update(true) { it.unseed(setOf(page)) } // clean slate: the seeding effect redraws the page
            }) { Text("Redetect") }
        },
        dismissButton = { TextButton(onClick = { redetecting = false }) { Text("Cancel") } },
    )

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all runs?") },
        text = { Text("All ${job.runs.size} runs on every page will be removed.") },
        confirmButton = {
            TextButton(onClick = { update(true) { it.copy(runs = emptyList()) }; confirmClear = false }) { Text("Clear") }
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
                        update(true) {
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
                        update(true) { it.renameRoom(old, to) }
                        renamingRoom = null
                    },
                    enabled = target.isNotEmpty() && !reserved && target != old,
                ) { Text(if (existing != null) "Merge" else "Rename") }
            },
            dismissButton = { TextButton(onClick = { renamingRoom = null }) { Text("Cancel") } },
        )
    }

    deletingBorder?.let { idx ->
        val border = job.boundaries.getOrNull(idx)
        if (border == null) { deletingBorder = null; return@let }
        AlertDialog(
            onDismissRequest = { deletingBorder = null },
            title = { Text("Delete ${border.name} border?") },
            text = { Text("Its runs go to the room around them, or Unassigned. No measurements are deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    update(true) { j -> j.copy(boundaries = j.boundaries.filterIndexed { k, _ -> k != idx }, editedPages = j.editedPages + border.page).reassignRooms() }
                    editIdx = null; draft = null
                    if (selected != null && selected !in job.runs) selected = null
                    deletingBorder = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingBorder = null }) { Text("Cancel") } },
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
                    update(true) { j ->
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
            title = { Text("Job Config") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!job.scaleSet) Text("Set the drawing scale, or calibrate from a known dimension.")
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                    OutlinedTextField(
                        value = scale, onValueChange = { scale = it }, singleLine = true, isError = !scaleValid,
                        label = { Text("Scale") },
                        suffix = if (job.foundScale?.let { parseScale(it) == parseScale(scale) } == true) {
                            { Text("(loaded from plan)") }
                        } else null,
                        supportingText = { Text("e.g. 1/4\" = 1', 1/8\" = 1', 3/4\" = 1'") },
                    )
                    OutlinedButton(
                        onClick = {
                            editing = false; editRooms = false; editIdx = null; draft = null
                            cursor = null; chain = emptyList(); selected = null; calibrating = true
                        },
                        Modifier.fillMaxWidth(),
                        enabled = bitmap != null,
                    ) { Text("Calibrate from a known length") }
                    OutlinedButton(
                        onClick = {
                            val r = ratio ?: return@OutlinedButton
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { store.exportQuote(job, r) } }
                                    .onSuccess { uri ->
                                        val send = Intent(Intent.ACTION_SEND).setType(XLSX_MIME)
                                            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, job.name)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(Intent.createChooser(send, "Export spreadsheet"))
                                    }
                                    .onFailure { Toast.makeText(context, "Couldn't export: ${it.message}", Toast.LENGTH_LONG).show() }
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = ratio != null && (job.runs.isNotEmpty() || job.rooms.isNotEmpty()),
                    ) { Text("Export spreadsheet") }
                    TextButton(
                        onClick = { editing = false; confirmClear = true },
                        Modifier.fillMaxWidth(),
                        enabled = job.runs.isNotEmpty(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Clear all runs") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { update { it.copy(name = name.trim(), scale = scale.trim(), scaleSet = true) }; editing = false },
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
    nodeLoupe: Offset?, // a run's node being dragged: the loupe floats above it
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
                it.points().zipWithNext().forEach { (p, q) -> drawLine(if (sel) SELECTED_COLOR else RUN_COLOR, p, q, (if (sel) 4 else 3).dp.toPx() * px) }
                if (sel) it.points().zipWithNext().forEach { (p, q) -> drawCircle(SELECTED_COLOR, 6.dp.toPx() * px, (p + q) / 2f) } // tap an edge to add a node
                if (sel) it.points().forEach { p -> // node handles, like a room border's corners
                    drawCircle(Color.White, 9.dp.toPx() * px, p)
                    drawCircle(SELECTED_COLOR, 9.dp.toPx() * px, p, style = Stroke(3.dp.toPx() * px))
                }
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
        if (editRooms) activeRoom?.let { r -> // corner handles; the small dots between them move a wall or add a corner
            val color = roomColor(r.name)
            r.points.forEachIndexed { i, v ->
                val next = r.points[(i + 1) % r.points.size]
                drawCircle(color, 4.dp.toPx(), (v + next) / 2f * pxPerPt * zoom + pan)
                (v * pxPerPt * zoom + pan).let {
                    drawCircle(Color.White, 9.dp.toPx(), it)
                    drawCircle(color, 9.dp.toPx(), it, style = Stroke(3.dp.toPx()))
                }
            }
        }

        val c = cursor ?: nodeLoupe ?: return@Canvas
        val p = c * zoom + pan // the point, on screen; the loupe is centered on it
        val geo = Geometry(p, density.density)
        val r = geo.loupeR
        // Dragging a node, the finger is on the point, so the loupe floats above it instead.
        val lc = if (cursor != null) p else Offset(p.x.coerceIn(r, size.width - r), (p.y - 2.2f * r).coerceAtLeast(r + 4.dp.toPx()))

        // Loupe: what's directly under it, magnified, with crosshairs on the point.
        val m = (zoom * LOUPE_MAG).coerceAtMost(maxOf(zoom, 12f))
        clipPath(Path().apply { addOval(Rect(lc, r)) }) {
            drawRect(Color.White)
            withTransform({ translate(lc.x - c.x * m, lc.y - c.y * m); scale(m, m, Offset.Zero) }) { plan(1 / m) }
            crosshair(lc, r, pointColor)
        }
        drawCircle(outline, r, lc, style = Stroke(2.dp.toPx()))
        if (cursor == null) return@Canvas

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

/**
 * Room labels on [page] read by on-device OCR; empty if it fails. The page is rendered in tiles at 3 px per point
 * (216 dpi): a whole page fits in 4096 px at about 1.6, too small to read 7pt text. Not for `pdfThread`.
 */
private suspend fun PdfRenderer.ocrRoomLabels(page: Int): List<RoomLabel> = runCatching {
    val s = 3f
    val tilePx = 2048
    val tilePt = tilePx / s
    // Overlap: a line touching an inner tile edge is skipped, so any line up to 300pt (a long label with its size,
    // or two labels ML Kit merged) must fit whole in the next tile.
    val step = tilePt - 300f
    val (w, h) = withContext(pdfThread) { openPage(page).use { it.width.toFloat() to it.height.toFloat() } }
    fun starts(len: Float) = (0..ceil(maxOf(0f, len - tilePt) / step).toInt()).map { it * step }
    val bmp = Bitmap.createBitmap(tilePx, tilePx, Bitmap.Config.ARGB_8888)
    val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val words = mutableListOf<OcrWord>()
    val seen = mutableListOf<Triple<String, Float, Float>>() // lines already read in an overlapping tile
    try {
        for (ty in starts(h)) for (tx in starts(w)) {
            withContext(pdfThread) {
                openPage(page).use { p ->
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    // With a transform the page draws at 1 px per point, then the transform applies.
                    val m = Matrix().apply { postScale(s, s); postTranslate(-tx * s, -ty * s) }
                    p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
            val text = suspendCancellableCoroutine { c ->
                client.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { c.resume(it) }
                    .addOnFailureListener { c.resumeWithException(it) }
            }
            for (l in text.textBlocks.flatMap { it.lines }) {
                val b = l.boundingBox ?: continue
                // A line cut by an inner tile edge is read whole in the next tile.
                if ((tx > 0 && b.left < 2) || (ty > 0 && b.top < 2) ||
                    (tx + tilePt < w && b.right > tilePx - 2) || (ty + tilePt < h && b.bottom > tilePx - 2)) continue
                val cx = tx + b.exactCenterX() / s; val cy = ty + b.exactCenterY() / s
                if (seen.any { it.first == l.text && abs(it.second - cx) < 5 && abs(it.third - cy) < 5 }) continue
                seen += Triple(l.text, cx, cy)
                for (e in l.elements) e.boundingBox?.let {
                    words += OcrWord(e.text, seen.size, tx + it.left / s, ty + it.top / s, tx + it.right / s, ty + it.bottom / s)
                }
            }
        }
    } finally {
        client.close() // bmp isn't recycled: a cancelled OCR may still be reading it
    }
    ocrLabels(words)
}.getOrDefault(emptyList())

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


/** Room labels on [page]; empty when the device can't read PDF text or the page has none (e.g. scans). */
@SuppressLint("NewApi") // guarded by canReadPdfText
private fun PdfRenderer.roomLabels(page: Int): List<RoomLabel> {
    if (!canReadPdfText) return emptyList()
    return runCatching {
        openPage(page).use { p ->
            findRoomLabels(p.textContents.joinToString("") { it.text }, search = { word ->
                p.searchText(word).filter { it.bounds.isNotEmpty() }.map { m ->
                    TextHit(
                        m.textStartIndex,
                        m.bounds.minOf { it.left }, m.bounds.minOf { it.top },
                        m.bounds.maxOf { it.right }, m.bounds.maxOf { it.bottom },
                    )
                }
            })
        }
    }.getOrDefault(emptyList())
}
