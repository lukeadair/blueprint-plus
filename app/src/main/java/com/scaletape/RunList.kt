package com.scaletape

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Runs grouped by room, each room with its total. Drag a run by its ≡ handle onto another room's
 * section to move it. Rooms are alphabetical (so they don't jump when a run moves), Unassigned ("") last.
 * [extraRooms] (added by the user) show even when empty, so runs can be dragged into them.
 */
@Composable
fun RunList(
    runs: List<Run>,
    ratio: Double?,
    showPages: Boolean,
    selected: Run?,
    bottomPadding: Dp,
    onTap: (index: Int) -> Unit,
    onDelete: (index: Int) -> Unit,
    onMove: (index: Int, room: String) -> Unit,
    extraRooms: List<String>,
    onAddRoom: () -> Unit,
    onDeleteRoom: (room: String) -> Unit,
    onRenameRoom: (room: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byRoom = runs.withIndex().groupBy { it.value.room.orEmpty() }
    val sections = (byRoom.keys + extraRooms).distinct()
        .sortedWith(roomOrder)
        .map { it to byRoom[it].orEmpty() }
    val listState = rememberLazyListState()
    val currentRuns by rememberUpdatedState(runs)
    val move by rememberUpdatedState(onMove)

    // The run being dragged, where the finger is (list viewport pixels), how far the row is shifted from its
    // place in the list (finger movement plus any auto-scroll), and the room under the finger.
    var dragging by remember { mutableStateOf<Int?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var hover by remember { mutableStateOf<String?>(null) }
    val hoverColor = MaterialTheme.colorScheme.tertiaryContainer
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() } // auto-scroll zone at the top and bottom of the list
    val maxStep = with(density) { 14.dp.toPx() } // fastest scroll, per frame, at the very edge

    /** The room of whatever list item sits at [y] (viewport pixels): a room header or a run row. */
    fun roomAt(y: Float): String? {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
        val key = item?.key as? String ?: return null
        return when {
            key.startsWith("room:") -> key.removePrefix("room:")
            key.startsWith("run:") -> currentRuns.getOrNull(key.removePrefix("run:").toInt())?.room.orEmpty()
            else -> null
        }
    }

    // While carrying a run near the top or bottom edge, scroll the list (faster nearer the edge). The row's
    // shift grows by what scrolled, so it stays under the finger.
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val height = listState.layoutInfo.viewportSize.height.toFloat()
            val step = when {
                fingerY < edge -> -maxStep * (edge - fingerY) / edge
                fingerY > height - edge -> maxStep * (fingerY - (height - edge)) / edge
                else -> 0f
            }.coerceIn(-maxStep, maxStep)
            if (step != 0f) {
                dragY += listState.scrollBy(step)
                roomAt(fingerY)?.let { hover = it }
            }
        }
    }

    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
        for ((room, items) in sections) {
            val dropBg = if (dragging != null && hover == room) hoverColor else null
            item(key = "room:$room") {
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // weight(fill = false): a long name wraps instead of squeezing the pencil out.
                            Text(room.ifEmpty { "Unassigned" }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
                            if (room.isNotEmpty()) IconButton(onClick = { onRenameRoom(room) }, Modifier.size(36.dp)) {
                                Icon(Icons.Default.Edit, "Rename room $room", Modifier.size(18.dp))
                            }
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ratio?.let { r -> Text("%.2f LF".format(items.sumOf { it.value.inches(r) } / 12), style = MaterialTheme.typography.titleMedium) }
                            if (room.isNotEmpty()) IconButton(onClick = { onDeleteRoom(room) }) {
                                Icon(Icons.Default.Close, "Delete room $room")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = dropBg ?: MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
            items(items, key = { "run:${it.index}" }) { (i, r) ->
                val isDragged = dragging == i
                // Keep the carried row alive when auto-scroll takes it off screen, or the drag would be cancelled.
                val pinnable = LocalPinnableContainer.current
                DisposableEffect(isDragged) {
                    val handle = if (isDragged) pinnable?.pin() else null
                    onDispose { handle?.release() }
                }
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Drag to move run ${i + 1} to another room",
                            // Near the screen's left edge: keep the system back gesture from taking the drag.
                            modifier = Modifier.systemGestureExclusion().pointerInput(i, room) {
                                // Consumes the drag, so the sheet and list don't move while a run is carried.
                                detectDragGestures(
                                    onDragStart = {
                                        val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "run:$i" }
                                        fingerY = row?.let { it.offset + it.size / 2f } ?: 0f
                                        dragging = i; dragY = 0f; hover = room
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragY += amount.y
                                        fingerY += amount.y
                                        roomAt(fingerY)?.let { hover = it }
                                    },
                                    onDragEnd = {
                                        hover?.let { to -> if (to != currentRuns.getOrNull(i)?.room.orEmpty()) move(i, to) }
                                        dragging = null; hover = null; dragY = 0f
                                    },
                                    onDragCancel = { dragging = null; hover = null; dragY = 0f },
                                )
                            }.padding(4.dp),
                        )
                    },
                    headlineContent = { Text(ratio?.let { formatFeetInches(r.inches(it)) } ?: "Scale not set") },
                    supportingContent = { Text("Run ${i + 1}" + if (showPages) " · page ${r.page + 1}" else "") },
                    trailingContent = {
                        IconButton(onClick = { onDelete(i) }) { Icon(Icons.Default.Delete, "Delete run ${i + 1}") }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = when {
                            isDragged -> MaterialTheme.colorScheme.surfaceContainerHighest
                            dropBg != null -> dropBg
                            r == selected -> MaterialTheme.colorScheme.secondaryContainer
                            else -> Color.Transparent
                        },
                    ),
                    modifier = Modifier
                        .then(if (isDragged) Modifier.zIndex(1f).graphicsLayer { translationY = dragY }.shadow(8.dp) else Modifier)
                        .clickable { onTap(i) },
                )
            }
        }
        item(key = "add-room") {
            ListItem(
                headlineContent = { Text("Add room") },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onAddRoom),
            )
        }
    }
}
