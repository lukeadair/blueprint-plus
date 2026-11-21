package com.scaletape

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    // shortcut: saves synchronously on the main thread (small JSON, keeps writes ordered); move to a writer thread if jobs get huge.
    fun update(f: (Job) -> Job) {
        job = f(job).copy(updated = System.currentTimeMillis())
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
    // The first Set fixes `anchor`; the second makes a run from anchor to cursor.
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var anchor by remember { mutableStateOf<Offset?>(null) }
    var calibrating by remember { mutableStateOf(false) }
    var calibLine by remember { mutableStateOf<Run?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) } // name + scale dialog
    var selected by remember { mutableStateOf<Run?>(null) } // tapped run, offered for deletion

    // Registered after the screen-level handler, so while measuring or selecting, back cancels that instead of leaving.
    BackHandler(enabled = cursor != null || anchor != null || calibrating || selected != null) {
        cursor = null; anchor = null; calibrating = false; selected = null
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
        val (bmp, dpi) = withContext(pdfThread) {
            r.openPage(page).use { p ->
                // PDF units are points (1/72"), so render scale s gives 72*s pixels per paper inch.
                val s = minOf(4f, MAX_SIDE_PX / maxOf(p.width, p.height))
                val b = Bitmap.createBitmap((p.width * s).toInt(), (p.height * s).toInt(), Bitmap.Config.ARGB_8888)
                b.eraseColor(android.graphics.Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                b to 72f * s
            }
        }
        bitmap = bmp; ppi = dpi; cursor = null; anchor = null; selected = null
        if (viewSize.width > 0) { // fit page to view
            zoom = minOf(viewSize.width.toFloat() / bmp.width, viewSize.height.toFloat() / bmp.height)
            pan = Offset((viewSize.width - bmp.width * zoom) / 2, (viewSize.height - bmp.height * zoom) / 2)
        }
    }

    val ratio = parseScale(job.scale)
    val total = job.totalInches()

    fun setPoint() {
        val c = cursor ?: return
        val a = anchor
        if (a == null) { anchor = c; return } // magnifier stays open for the end point
        val run = Run(page, a, c, ppi)
        if (calibrating) { calibLine = run; calibrating = false } else update { it.copy(runs = it.runs + run) }
        anchor = null; cursor = null
    }

    val sheet = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    val localDensity = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) } // measured, so the peek shows exactly the header
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
                    val a = anchor
                    if (c != null || a != null) {
                        TextButton(onClick = { cursor = null; anchor = null }) { Text("Cancel") }
                        val live = if (a != null && c != null && ratio != null && !calibrating)
                            formatFeetInches(Run(page, a, c, ppi).inches(ratio)) else null
                        Text(
                            when {
                                live != null -> live
                                c == null -> "Tap the end point"
                                a == null -> "Drag to the start, tap ≡ to set"
                                else -> "Drag to the end, tap ≡ to set"
                            },
                            Modifier.weight(1f).padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(onClick = { setPoint() }, enabled = c != null) { Text(if (a == null) "Set start" else "Set end") }
                    } else if (selected != null) {
                        val sel = selected!!
                        TextButton(onClick = { selected = null }) { Text("Done") }
                        Text(
                            ratio?.let { formatFeetInches(sel.inches(it)) } ?: "Selected run",
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
            // Pull the sheet up to see every run; delete any of them here.
            if (job.runs.isEmpty()) {
                Text(
                    "No runs yet",
                    Modifier.padding(16.dp).padding(bottom = navBar),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else LazyColumn(contentPadding = PaddingValues(bottom = navBar)) {
                itemsIndexed(job.runs) { i, r ->
                    ListItem(
                        headlineContent = { Text(ratio?.let { formatFeetInches(r.inches(it)) } ?: "Scale not set") },
                        supportingContent = { Text("Run ${i + 1}" + if (pageCount > 1) " · page ${r.page + 1}" else "") },
                        trailingContent = {
                            IconButton(onClick = {
                                if (selected == r) selected = null
                                update { it.copy(runs = it.runs.filterIndexed { j, _ -> j != i }) }
                            }) { Icon(Icons.Default.Delete, "Delete run ${i + 1}") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
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
                            if (cursor == null && anchor == null && !calibrating) {
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
                        cursor = cursor, anchor = anchor, calibLine = calibLine, selected = selected,
                        pointColor = if (calibrating) CALIB_COLOR else RUN_COLOR,
                    )
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

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all runs?") },
        text = { Text("All ${job.runs.size} runs on every page will be removed.") },
        confirmButton = {
            TextButton(onClick = { update { it.copy(runs = emptyList()) }; confirmClear = false }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )

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
    anchor: Offset?,
    calibLine: Run?,
    selected: Run?,
    pointColor: Color,
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

    Canvas(Modifier.fillMaxSize()) {
        /** Draws the page and its marks in page-pixel space; [px] is one screen pixel in that space. */
        fun DrawScope.plan(px: Float) {
            drawImage(image)
            runs.forEach {
                if (it == selected) drawLine(SELECTED_COLOR, it.a, it.b, 6.dp.toPx() * px)
                else drawLine(RUN_COLOR, it.a, it.b, 3.dp.toPx() * px)
            }
            calibLine?.let { drawLine(CALIB_COLOR, it.a, it.b, 3.dp.toPx() * px) }
            anchor?.let { a ->
                drawCircle(pointColor, 4.dp.toPx() * px, a)
                cursor?.let { drawLine(pointColor, a, it, 2.dp.toPx() * px, pathEffect = dash) }
            }
        }

        fun DrawScope.crosshair(at: Offset, arm: Float, color: Color) {
            drawLine(color, at - Offset(arm, 0f), at + Offset(arm, 0f), 1.dp.toPx())
            drawLine(color, at - Offset(0f, arm), at + Offset(0f, arm), 1.dp.toPx())
        }

        withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset.Zero) }) { plan(1 / zoom) }

        if (ratio != null) runs.forEach { r ->
            val mid = (r.a + r.b) / 2f * zoom + pan
            drawContext.canvas.nativeCanvas.drawText(formatFeetInches(r.inches(ratio)), mid.x, mid.y - 4.dp.toPx(), labelPaint)
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
        val settingEnd = anchor != null
        drawCircle(if (settingEnd) END_GRABBER_COLOR else primary, geo.grabberR, g)
        drawCircle(outline, geo.grabberR, g, style = Stroke(2.dp.toPx()))
        val bar = 8.dp.toPx()
        for (dy in listOf(-6, 0, 6)) {
            val y = g.y + dy.dp.toPx()
            drawLine(if (settingEnd) Color.White else onPrimary, Offset(g.x - bar, y), Offset(g.x + bar, y), 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
