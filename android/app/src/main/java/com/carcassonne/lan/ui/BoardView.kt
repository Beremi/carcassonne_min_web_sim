package com.carcassonne.lan.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.carcassonne.lan.model.MatchState
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val BASE_CELL_PX = 96f

@Composable
fun BoardView(
    modifier: Modifier,
    match: MatchState,
    preview: TilePreviewState?,
    lockedPlacement: LockedPlacementState?,
    onTapCell: (Int, Int) -> Unit,
    onLongPressCell: (Int, Int) -> Unit,
    onTapMeepleOption: (String) -> Unit,
    onConfirmPlacement: () -> Unit,
    onRevertPlacement: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val tileCache = remember(context) { TileBitmapCache(context) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .onSizeChanged { boardSize = it }
            .background(Color(0xFFECE6D8))
            .pointerInput(zoom, pan, match.id, preview, lockedPlacement) {
                detectTapGestures(
                    onTap = { pos ->
                        if (lockedPlacement != null) {
                            val hit = hitTestMeepleOption(
                                tap = pos,
                                placement = lockedPlacement,
                                boardWidth = size.width.toFloat(),
                                boardHeight = size.height.toFloat(),
                                zoom = zoom,
                                pan = pan,
                            )
                            if (hit != null) {
                                onTapMeepleOption(hit)
                            }
                            return@detectTapGestures
                        }

                        val cell = toCell(pos, size.width.toFloat(), size.height.toFloat(), zoom, pan)
                        onTapCell(cell.first, cell.second)
                    },
                    onLongPress = { pos ->
                        if (lockedPlacement != null) return@detectTapGestures
                        val cell = toCell(pos, size.width.toFloat(), size.height.toFloat(), zoom, pan)
                        onLongPressCell(cell.first, cell.second)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(0.45f, 2.8f)
                    pan += panChange
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellPx = BASE_CELL_PX * zoom
            val center = Offset(size.width / 2f + pan.x, size.height / 2f + pan.y)

            fun cellTopLeft(x: Int, y: Int): Offset {
                return Offset(
                    x = center.x + x * cellPx - cellPx / 2f,
                    y = center.y + y * cellPx - cellPx / 2f,
                )
            }

            val visibleX = ((size.width / cellPx).toInt() / 2) + 4
            val visibleY = ((size.height / cellPx).toInt() / 2) + 4
            for (gy in -visibleY..visibleY) {
                for (gx in -visibleX..visibleX) {
                    val tl = cellTopLeft(gx, gy)
                    drawRect(
                        color = Color(0x1A000000),
                        topLeft = tl,
                        size = Size(cellPx, cellPx),
                        style = Stroke(width = 1.2f),
                    )
                }
            }

            for ((cellKey, inst) in match.board) {
                val (x, y) = parseCell(cellKey)
                val topLeft = cellTopLeft(x, y)
                val image = tileCache.get(inst.tileId)

                withTransform({
                    translate(topLeft.x, topLeft.y)
                    rotate(inst.rotDeg.toFloat(), pivot = Offset(cellPx / 2f, cellPx / 2f))
                }) {
                    if (image != null) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(0, 0),
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(0, 0),
                            dstSize = IntSize(cellPx.roundToInt(), cellPx.roundToInt()),
                        )
                    } else {
                        drawRect(
                            color = Color(0xFFCFB989),
                            size = Size(cellPx, cellPx),
                        )
                    }
                }

                if (inst.meeples.isNotEmpty()) {
                    val badge = Offset(topLeft.x + cellPx * 0.78f, topLeft.y + cellPx * 0.22f)
                    drawCircle(
                        color = Color(0xFF203A63),
                        radius = cellPx * 0.1f,
                        center = badge,
                    )
                }
            }

            if (preview != null && lockedPlacement == null) {
                val topLeft = cellTopLeft(preview.x, preview.y)
                val image = tileCache.get(preview.tileId)
                withTransform({
                    translate(topLeft.x, topLeft.y)
                    rotate(preview.rotDeg.toFloat(), pivot = Offset(cellPx / 2f, cellPx / 2f))
                }) {
                    if (image != null) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(0, 0),
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(0, 0),
                            dstSize = IntSize(cellPx.roundToInt(), cellPx.roundToInt()),
                            alpha = if (preview.legal) 0.65f else 0.35f,
                        )
                    } else {
                        drawRect(
                            color = if (preview.legal) Color(0xAA80B45B) else Color(0xAAA7473D),
                            size = Size(cellPx, cellPx),
                        )
                    }
                }

                drawRect(
                    color = if (preview.legal) Color(0xFF2E7D32) else Color(0xFFC62828),
                    topLeft = topLeft,
                    size = Size(cellPx, cellPx),
                    style = Stroke(width = 4f),
                )

                if (!preview.legal) {
                    val cx = topLeft.x + cellPx / 2f
                    val cy = topLeft.y + cellPx / 2f
                    val r = cellPx * 0.22f
                    drawLine(
                        color = Color(0xFFC62828),
                        start = Offset(cx - r, cy - r),
                        end = Offset(cx + r, cy + r),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color(0xFFC62828),
                        start = Offset(cx + r, cy - r),
                        end = Offset(cx - r, cy + r),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            if (lockedPlacement != null) {
                val topLeft = cellTopLeft(lockedPlacement.x, lockedPlacement.y)
                val image = tileCache.get(lockedPlacement.tileId)

                withTransform({
                    translate(topLeft.x, topLeft.y)
                    rotate(lockedPlacement.rotDeg.toFloat(), pivot = Offset(cellPx / 2f, cellPx / 2f))
                }) {
                    if (image != null) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(0, 0),
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(0, 0),
                            dstSize = IntSize(cellPx.roundToInt(), cellPx.roundToInt()),
                            alpha = 0.92f,
                        )
                    }
                }

                drawRect(
                    color = Color(0xFF1565C0),
                    topLeft = topLeft,
                    size = Size(cellPx, cellPx),
                    style = Stroke(width = 4f),
                )

                val markerR = (cellPx * 0.09f).coerceAtLeast(8f)
                for (option in lockedPlacement.options) {
                    val pt = markerCenter(
                        placement = lockedPlacement,
                        option = option,
                        boardWidth = size.width,
                        boardHeight = size.height,
                        zoom = zoom,
                        pan = pan,
                    )
                    val selected = lockedPlacement.selectedMeepleFeatureId == option.featureId
                    drawCircle(
                        color = if (selected) Color(0xFFFFC107) else Color(0xFF1E88E5),
                        radius = markerR,
                        center = pt,
                    )
                    drawCircle(
                        color = Color(0xFF0D1A2B),
                        radius = markerR,
                        center = pt,
                        style = Stroke(width = 2.2f),
                    )
                }
            }
        }

        if (lockedPlacement != null && boardSize.width > 0 && boardSize.height > 0) {
            val buttonSizePx = with(density) { 46.dp.toPx() }
            val rowWidthPx = buttonSizePx * 2f + with(density) { 8.dp.toPx() } + with(density) { 12.dp.toPx() }
            val rowHeightPx = buttonSizePx + with(density) { 8.dp.toPx() }
            val marginPx = with(density) { 10.dp.toPx() }

            val cellPx = BASE_CELL_PX * zoom
            val centerX = boardSize.width / 2f + pan.x
            val centerY = boardSize.height / 2f + pan.y
            val tileLeft = centerX + lockedPlacement.x * cellPx - cellPx / 2f
            val tileTop = centerY + lockedPlacement.y * cellPx - cellPx / 2f

            val rowX = (tileLeft + cellPx / 2f - rowWidthPx / 2f)
                .coerceIn(4f, boardSize.width - rowWidthPx - 4f)
            val belowY = tileTop + cellPx + marginPx
            val aboveY = tileTop - rowHeightPx - marginPx
            val rowY = if (belowY + rowHeightPx <= boardSize.height - 4f) belowY else aboveY.coerceAtLeast(4f)

            Surface(
                modifier = Modifier
                    .offset { IntOffset(rowX.roundToInt(), rowY.roundToInt()) },
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 5.dp,
                color = Color(0xE9FFFFFF),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = onConfirmPlacement,
                        modifier = Modifier.size(46.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Confirm",
                            tint = Color(0xFF2E7D32),
                        )
                    }

                    IconButton(
                        onClick = onRevertPlacement,
                        modifier = Modifier.size(46.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Revert",
                            tint = Color(0xFF1565C0),
                        )
                    }
                }
            }
        }
    }
}

private fun toCell(
    pos: Offset,
    width: Float,
    height: Float,
    zoom: Float,
    pan: Offset,
): Pair<Int, Int> {
    val cellPx = BASE_CELL_PX * zoom
    val centerX = width / 2f + pan.x
    val centerY = height / 2f + pan.y
    val x = ((pos.x - centerX) / cellPx).roundToInt()
    val y = ((pos.y - centerY) / cellPx).roundToInt()
    return Pair(x, y)
}

private fun parseCell(cellKey: String): Pair<Int, Int> {
    val parts = cellKey.split(",", limit = 2)
    return Pair(parts[0].toInt(), parts[1].toInt())
}

private fun hitTestMeepleOption(
    tap: Offset,
    placement: LockedPlacementState,
    boardWidth: Float,
    boardHeight: Float,
    zoom: Float,
    pan: Offset,
): String? {
    val radius = (BASE_CELL_PX * zoom * 0.12f).coerceAtLeast(16f)
    for (option in placement.options) {
        val pt = markerCenter(placement, option, boardWidth, boardHeight, zoom, pan)
        val d = hypot((tap.x - pt.x).toDouble(), (tap.y - pt.y).toDouble())
        if (d <= radius) return option.featureId
    }
    return null
}

private fun markerCenter(
    placement: LockedPlacementState,
    option: MeepleOptionState,
    boardWidth: Float,
    boardHeight: Float,
    zoom: Float,
    pan: Offset,
): Offset {
    val cellPx = BASE_CELL_PX * zoom
    val centerX = boardWidth / 2f + pan.x
    val centerY = boardHeight / 2f + pan.y
    val tileLeft = centerX + placement.x * cellPx - cellPx / 2f
    val tileTop = centerY + placement.y * cellPx - cellPx / 2f

    val rotated = rotateNormalized(option.x, option.y, placement.rotDeg)
    return Offset(
        x = tileLeft + rotated.first * cellPx,
        y = tileTop + rotated.second * cellPx,
    )
}

private fun rotateNormalized(x: Float, y: Float, rotDeg: Int): Pair<Float, Float> {
    val rot = ((rotDeg % 360) + 360) % 360
    return when (rot) {
        90 -> Pair(1f - y, x)
        180 -> Pair(1f - x, 1f - y)
        270 -> Pair(y, 1f - x)
        else -> Pair(x, y)
    }
}

private class TileBitmapCache(private val context: Context) {
    private val cache = mutableMapOf<String, ImageBitmap?>()

    fun get(tileId: String): ImageBitmap? {
        return cache.getOrPut(tileId) {
            runCatching {
                context.assets.open("images/tile_${tileId}.png").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
}
