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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
    var renaming by remember { mutableStateOf(false) }

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
        bitmap = bmp; ppi = dpi; cursor = null; anchor = null
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
        if (a == null) { anchor = c; cursor = null; return }
        val run = Run(page, a, c, ppi)
        if (calibrating) { calibLine = run; calibrating = false } else update { it.copy(runs = it.runs + run) }
        anchor = null; cursor = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job.name, maxLines = 1) },
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
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename job") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
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
                            a == null -> "Drag the handle to the start"
                            else -> "Drag the handle to the end"
                        },
                        Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = { setPoint() }, enabled = c != null) { Text(if (a == null) "Set start" else "Set end") }
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
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = job.scale,
                onValueChange = { s -> update { it.copy(scale = s) } },
                label = { Text("Scale") },
                singleLine = true,
                isError = ratio == null,
                supportingText = if (ratio == null) ({ Text("e.g. 1/4\" = 1'") }) else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
                    .pointerInput(Unit) {
                        detectTapGestures { tap -> if (bitmap != null) cursor = (tap - pan) / zoom }
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
                        cursor = cursor, anchor = anchor, calibLine = calibLine,
                        pointColor = if (calibrating) CALIB_COLOR else RUN_COLOR,
                    )
                    cursor?.let { c ->
                        val g = Geometry(c * zoom + pan, viewSize, LocalDensity.current.density).grabber
                        val half = with(LocalDensity.current) { 28.dp.toPx() }
                        Box(
                            Modifier
                                .offset { IntOffset((g.x - half).roundToInt(), (g.y - half).roundToInt()) }
                                .size(56.dp)
                                .semantics { contentDescription = "Point handle" }
                                .pointerInput(Unit) {
                                    // Consume everything so the canvas doesn't pan or move the point on tap.
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        down.consume()
                                        drag(down.id) { change ->
                                            cursor = cursor?.plus(change.positionChange() / zoom)
                                            change.consume()
                                        }
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

    if (renaming) {
        var name by remember { mutableStateOf(job.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename job") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { update { it.copy(name = name.trim()) }; renaming = false }, enabled = name.isNotBlank()) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

/** Screen positions of the grabber and loupe for a point at screen position [p]. */
private class Geometry(p: Offset, view: IntSize, density: Float) {
    val loupeR = 64 * density
    private val gap = 72 * density
    private val grabberBelow = p.y + gap + 28 * density < view.height
    val grabber = if (grabberBelow) p + Offset(0f, gap) else p - Offset(0f, gap)
    // Loupe above the point; to the side when the grabber is above or there's no room.
    val loupe = if (grabberBelow && p.y - gap - 2 * loupeR > 0) p - Offset(0f, gap + loupeR)
    else Offset(
        if (p.x > view.width / 2f) p.x - gap - loupeR else p.x + gap + loupeR,
        p.y.coerceIn(loupeR, maxOf(loupeR, view.height - loupeR)),
    )
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
            runs.forEach { drawLine(RUN_COLOR, it.a, it.b, 3.dp.toPx() * px) }
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
        val p = c * zoom + pan
        val geo = Geometry(p, IntSize(size.width.toInt(), size.height.toInt()), density.density)
        crosshair(p, 12.dp.toPx(), pointColor)

        // Grabber: a handle offset from the point so the finger doesn't hide it.
        drawLine(outline, p, geo.grabber, 1.dp.toPx())
        drawCircle(primary, 20.dp.toPx(), geo.grabber)
        drawCircle(onPrimary, 20.dp.toPx(), geo.grabber, style = Stroke(2.dp.toPx()))

        // Loupe: the area around the point, magnified, with crosshairs.
        val l = geo.loupe
        val r = geo.loupeR
        val m = (zoom * LOUPE_MAG).coerceAtMost(maxOf(zoom, 12f))
        clipPath(Path().apply { addOval(Rect(l, r)) }) {
            drawRect(Color.White)
            withTransform({ translate(l.x - c.x * m, l.y - c.y * m); scale(m, m, Offset.Zero) }) { plan(1 / m) }
            crosshair(l, r, pointColor)
        }
        drawCircle(outline, r, l, style = Stroke(2.dp.toPx()))
    }
}
