package com.carcassonne.lan.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.carcassonne.lan.model.MatchState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val BASE_CELL_PX = 96f

@Composable
fun BoardView(
    modifier: Modifier,
    match: MatchState,
    viewerPlayer: Int?,
    activePlayer: Int?,
    simplifiedView: Boolean,
    tileVisuals: Map<String, TileVisualState>,
    boardMeeples: List<BoardMeepleState>,
    selectedScoreHighlights: List<ScoreHighlightAreaState>,
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
    val tileLabelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#1C1C1C")
            textAlign = Paint.Align.CENTER
            textSize = 28f
        }
    }

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
            val activeColor = playerColor(activePlayer)
            val remoteIntent = match.turnState.intent?.takeIf { intent ->
                viewerPlayer != null &&
                    intent.player != viewerPlayer &&
                    intent.tileId.isNotBlank()
            }

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
                    if (!simplifiedView && image != null) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(0, 0),
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(0, 0),
                            dstSize = IntSize(cellPx.roundToInt(), cellPx.roundToInt()),
                        )
                    } else {
                        drawSimplifiedTile(
                            sizePx = cellPx,
                            tile = tileVisuals[inst.tileId],
                        )
                    }
                }

                if (simplifiedView) {
                    tileLabelPaint.textSize = (cellPx * 0.22f).coerceAtLeast(12f)
                    drawContext.canvas.nativeCanvas.drawText(
                        inst.tileId,
                        topLeft.x + cellPx / 2f,
                        topLeft.y + cellPx / 2f + tileLabelPaint.textSize * 0.35f,
                        tileLabelPaint,
                    )
                }
            }

            for (hl in selectedScoreHighlights) {
                val (x, y) = parseCell(hl.cellKey)
                val topLeft = cellTopLeft(x, y)
                val tone = highlightToneColor(hl.tone)

                for (poly in hl.polygons) {
                    if (poly.size < 3) continue
                    val path = Path()
                    path.moveTo(topLeft.x + poly[0].x * cellPx, topLeft.y + poly[0].y * cellPx)
                    for (i in 1 until poly.size) {
                        path.lineTo(topLeft.x + poly[i].x * cellPx, topLeft.y + poly[i].y * cellPx)
                    }
                    path.close()
                    drawPath(path = path, color = tone.copy(alpha = 0.30f))
                    drawPath(path = path, color = tone.copy(alpha = 0.92f), style = Stroke(width = 1.8f))
                }

                if (hl.polygons.isEmpty() && hl.fallbackPoint != null) {
                    drawCircle(
                        color = tone.copy(alpha = 0.80f),
                        radius = (cellPx * 0.08f).coerceAtLeast(7f),
                        center = Offset(
                            topLeft.x + hl.fallbackPoint.x * cellPx,
                            topLeft.y + hl.fallbackPoint.y * cellPx,
                        ),
                    )
                }
            }

            for (meeple in boardMeeples) {
                val (x, y) = parseCell(meeple.cellKey)
                val topLeft = cellTopLeft(x, y)
                val centerPt = Offset(
                    x = topLeft.x + meeple.x * cellPx,
                    y = topLeft.y + meeple.y * cellPx,
                )
                val baseR = (cellPx * 0.090f).coerceAtLeast(6f)
                val radius = if (meeple.isField) baseR * 1.2f else baseR
                val color = if (meeple.player == 2) Color(0xFFD53E3E) else Color(0xFF2B6BE1)
                drawCircle(color = color, radius = radius, center = centerPt)
                drawCircle(
                    color = Color(0xCC0F1220),
                    radius = radius,
                    center = centerPt,
                    style = Stroke(width = (radius * 0.17f).coerceAtLeast(1.5f)),
                )
                drawCircle(
                    color = Color(0xFFF4F3EE),
                    radius = radius * 0.20f,
                    center = centerPt,
                )
            }

            if (remoteIntent != null && preview == null && lockedPlacement == null) {
                val topLeft = cellTopLeft(remoteIntent.x, remoteIntent.y)
                val image = tileCache.get(remoteIntent.tileId)
                val intentColor = playerColor(remoteIntent.player)
                withTransform({
                    translate(topLeft.x, topLeft.y)
                    rotate(remoteIntent.rotDeg.toFloat(), pivot = Offset(cellPx / 2f, cellPx / 2f))
                }) {
                    if (!simplifiedView && image != null) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(0, 0),
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(0, 0),
                            dstSize = IntSize(cellPx.roundToInt(), cellPx.roundToInt()),
                            alpha = if (remoteIntent.locked) 0.90f else 0.68f,
                        )
                    } else {
                        drawSimplifiedTile(
                            sizePx = cellPx,
                            tile = tileVisuals[remoteIntent.tileId],
                            alpha = if (remoteIntent.locked) 0.90f else 0.70f,
                        )
                    }
                }

                drawRect(
                    color = intentColor.copy(alpha = if (remoteIntent.locked) 0.96f else 0.82f),
                    topLeft = topLeft,
                    size = Size(cellPx, cellPx),
                    style = Stroke(width = if (remoteIntent.locked) 4f else 3f),
                )

                val selectedFeatureId = remoteIntent.meepleFeatureId
                if (!selectedFeatureId.isNullOrBlank()) {
                    val feature = tileVisuals[remoteIntent.tileId]
                        ?.features
                        ?.firstOrNull { it.id == selectedFeatureId }
                    if (feature != null) {
                        val pt = rotateNormPoint(feature.x, feature.y, remoteIntent.rotDeg)
                        val centerPt = Offset(
                            x = topLeft.x + pt.x * cellPx,
                            y = topLeft.y + pt.y * cellPx,
                        )
                        val markerR = (cellPx * 0.09f).coerceAtLeast(8f)
                        drawCircle(
                            color = Color(0xFFFFC107).copy(alpha = 0.94f),
                            radius = markerR,
                            center = centerPt,
                        )
                        drawCircle(
                            color = Color(0xFF0D1A2B).copy(alpha = 0.95f),
                            radius = markerR,
                            center = centerPt,
                            style = Stroke(width = 2.2f),
                        )
                    }
                }
            }

            if (preview != null && lockedPlacement == null) {
                val topLeft = cellTopLeft(preview.x, preview.y)
                val image = tileCache.get(preview.tileId)
                withTransform({
                    translate(topLeft.x, topLeft.y)
                    rotate(preview.rotDeg.toFloat(), pivot = Offset(cellPx / 2f, cellPx / 2f))
                }) {
                    if (!simplifiedView && image != null) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(0, 0),
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(0, 0),
                            dstSize = IntSize(cellPx.roundToInt(), cellPx.roundToInt()),
                            alpha = if (preview.legal) 0.70f else 0.35f,
                        )
                    } else {
                        drawSimplifiedTile(
                            sizePx = cellPx,
                            tile = tileVisuals[preview.tileId],
                            alpha = if (preview.legal) 0.72f else 0.35f,
                        )
                    }
                }

                drawRect(
                    color = activeColor,
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
                    if (!simplifiedView && image != null) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(0, 0),
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(0, 0),
                            dstSize = IntSize(cellPx.roundToInt(), cellPx.roundToInt()),
                            alpha = 0.92f,
                        )
                    } else {
                        drawSimplifiedTile(
                            sizePx = cellPx,
                            tile = tileVisuals[lockedPlacement.tileId],
                            alpha = 0.92f,
                        )
                    }
                }

                drawRect(
                    color = activeColor,
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
                        color = if (selected) Color(0xFFFFC107) else activeColor,
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

private data class SimplePoint(
    val x: Float,
    val y: Float,
)

private data class RoadDotSpec(
    val center: SimplePoint,
    val radius: Float,
)

private data class RoadDrawSpec(
    val paths: List<Path>,
    val dots: List<RoadDotSpec>,
)

private data class RoadRenderContext(
    val singleToJunction: Map<String, SimplePoint>,
    val junction: SimplePoint? = null,
)

private data class RoadDeadEndTarget(
    val point: SimplePoint,
    val drawDot: Boolean,
)

private val SIMPLE_EDGE_ORDER = listOf("N", "E", "S", "W")
private val SIMPLE_EDGE_ANCHOR = mapOf(
    "N" to SimplePoint(50f, 0f),
    "E" to SimplePoint(100f, 50f),
    "S" to SimplePoint(50f, 100f),
    "W" to SimplePoint(0f, 50f),
)
private val EDGE_TO_FIELD_HALVES = mapOf(
    "N" to listOf("Nw", "Ne"),
    "E" to listOf("En", "Es"),
    "S" to listOf("Sw", "Se"),
    "W" to listOf("Wn", "Ws"),
)
private val HALF_FIELD_PORTS = setOf("Nw", "Ne", "En", "Es", "Se", "Sw", "Ws", "Wn")

private fun DrawScope.drawSimplifiedTile(
    sizePx: Float,
    tile: TileVisualState?,
    alpha: Float = 1f,
) {
    val a = alpha.coerceIn(0f, 1f)
    val fieldColor = Color(0xFF7DAC45).copy(alpha = a)
    val cityColor = Color(0xFFD2B059).copy(alpha = a)
    val outlineColor = Color(0xFF111111).copy(alpha = a)
    val roadColor = Color(0xFFFFFFFF).copy(alpha = a)
    val cloisterColor = Color(0xFFD53636).copy(alpha = a)

    drawRect(color = fieldColor, size = Size(sizePx, sizePx))

    val features = tile?.features.orEmpty()
    val roads = features.filter { it.type.equals("road", ignoreCase = true) }
    val cities = features.filter { it.type.equals("city", ignoreCase = true) }
    val cloisters = features.filter { it.type.equals("cloister", ignoreCase = true) }
    val roadContext = buildRoadRenderContext(roads)

    for (road in roads) {
        val spec = roadFeatureDrawData(
            feature = road,
            features = features,
            context = roadContext,
            sizePx = sizePx,
        )
        for (path in spec.paths) {
            drawPath(
                path = path,
                color = outlineColor,
                style = Stroke(
                    width = max(sizePx * 0.10f, 3f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            drawPath(
                path = path,
                color = roadColor,
                style = Stroke(
                    width = max(sizePx * 0.062f, 2f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
        for (dot in spec.dots) {
            drawCircle(
                color = outlineColor,
                radius = dot.radius * sizePx / 100f,
                center = dot.center.toOffset(sizePx),
            )
        }
    }
    if (roadContext.junction != null) {
        drawCircle(
            color = outlineColor,
            radius = 3.3f * sizePx / 100f,
            center = roadContext.junction.toOffset(sizePx),
        )
    }

    for (city in cities) {
        val cityPath = cityPathFromPorts(city.ports, sizePx) ?: continue
        drawPath(path = cityPath, color = cityColor)
        drawPath(
            path = cityPath,
            color = outlineColor,
            style = Stroke(
                width = max(sizePx * 0.0135f, 1f),
                join = StrokeJoin.Round,
            ),
        )
    }

    for (cloister in cloisters) {
        val cx = cloister.x * sizePx
        val cy = cloister.y * sizePx
        drawLine(
            color = outlineColor,
            start = Offset(cx, cy - sizePx * 0.16f),
            end = Offset(cx, cy + sizePx * 0.14f),
            strokeWidth = max(sizePx * 0.09f, 2.8f),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = outlineColor,
            start = Offset(cx - sizePx * 0.12f, cy - sizePx * 0.03f),
            end = Offset(cx + sizePx * 0.12f, cy - sizePx * 0.03f),
            strokeWidth = max(sizePx * 0.07f, 2.4f),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = cloisterColor,
            start = Offset(cx, cy - sizePx * 0.16f),
            end = Offset(cx, cy + sizePx * 0.14f),
            strokeWidth = max(sizePx * 0.05f, 2f),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = cloisterColor,
            start = Offset(cx - sizePx * 0.12f, cy - sizePx * 0.03f),
            end = Offset(cx + sizePx * 0.12f, cy - sizePx * 0.03f),
            strokeWidth = max(sizePx * 0.036f, 1.7f),
            cap = StrokeCap.Round,
        )
    }

    for (city in cities) {
        val pennants = city.pennants.coerceAtLeast(0)
        if (pennants <= 0) continue
        val cx = city.x * sizePx
        val cy = city.y * sizePx
        if (pennants == 1) {
            drawShield(center = Offset(cx, cy), sizePx = sizePx * 0.058f, alpha = a)
        } else {
            repeat(pennants.coerceAtMost(6)) { i ->
                val angle = (PI * 2.0 * i.toDouble()) / pennants.coerceAtMost(6).toDouble()
                drawShield(
                    center = Offset(
                        x = cx + cos(angle).toFloat() * sizePx * 0.072f,
                        y = cy + sin(angle).toFloat() * sizePx * 0.072f,
                    ),
                    sizePx = sizePx * 0.052f,
                    alpha = a,
                )
            }
        }
    }

    drawRect(
        color = outlineColor,
        size = Size(sizePx, sizePx),
        style = Stroke(width = max(sizePx * 0.014f, 1.2f)),
    )
}

private fun DrawScope.drawShield(center: Offset, sizePx: Float, alpha: Float) {
    val s = sizePx
    val path = Path().apply {
        moveTo(center.x, center.y - s * 1.2f)
        lineTo(center.x + s * 0.9f, center.y - s * 0.7f)
        lineTo(center.x + s * 0.8f, center.y + s * 0.5f)
        lineTo(center.x, center.y + s * 1.5f)
        lineTo(center.x - s * 0.8f, center.y + s * 0.5f)
        lineTo(center.x - s * 0.9f, center.y - s * 0.7f)
        close()
    }
    drawPath(path = path, color = Color(0xFF3F78D9).copy(alpha = alpha))
    drawPath(
        path = path,
        color = Color(0xFF111111).copy(alpha = alpha),
        style = Stroke(width = max(sizePx * 0.16f, 0.9f), join = StrokeJoin.Round),
    )
}

private fun buildRoadRenderContext(roads: List<TileFeatureVisualState>): RoadRenderContext {
    val onePortRoads = roads.filter { edgePortsOfFeature(it).size == 1 }
    val multiPortRoads = roads.filter { edgePortsOfFeature(it).size > 1 }
    if (multiPortRoads.isEmpty() && onePortRoads.size >= 3) {
        val junction = SimplePoint(50f, 50f)
        return RoadRenderContext(
            singleToJunction = onePortRoads.associate { it.id to junction },
            junction = junction,
        )
    }
    return RoadRenderContext(singleToJunction = emptyMap(), junction = null)
}

private fun roadFeatureDrawData(
    feature: TileFeatureVisualState,
    features: List<TileFeatureVisualState>,
    context: RoadRenderContext,
    sizePx: Float,
): RoadDrawSpec {
    val ports = edgePortsOfFeature(feature)
    if (ports.isEmpty()) return RoadDrawSpec(paths = emptyList(), dots = emptyList())

    if (ports.size == 1) {
        val edge = ports.first()
        val anchor = SIMPLE_EDGE_ANCHOR[edge] ?: SimplePoint(50f, 50f)
        val sharedJunction = context.singleToJunction[feature.id]
        if (sharedJunction != null) {
            return RoadDrawSpec(
                paths = listOf(linePath(anchor, sharedJunction, sizePx)),
                dots = emptyList(),
            )
        }
        val target = roadDeadEndTarget(feature, features)
        return RoadDrawSpec(
            paths = listOf(linePath(anchor, target.point, sizePx)),
            dots = if (target.drawDot) listOf(RoadDotSpec(target.point, 3.2f)) else emptyList(),
        )
    }

    if (ports.size == 2) {
        val a = SIMPLE_EDGE_ANCHOR[ports[0]] ?: SimplePoint(50f, 0f)
        val b = SIMPLE_EDGE_ANCHOR[ports[1]] ?: SimplePoint(50f, 100f)
        val c1 = edgeInwardControl(ports[0], 24f)
        val c2 = edgeInwardControl(ports[1], 24f)
        return RoadDrawSpec(
            paths = listOf(cubicPath(a, c1, c2, b, sizePx)),
            dots = emptyList(),
        )
    }

    val anchors = ports.mapNotNull { SIMPLE_EDGE_ANCHOR[it] }
    if (anchors.isEmpty()) return RoadDrawSpec(paths = emptyList(), dots = emptyList())
    val mp = SimplePoint(feature.x * 100f, feature.y * 100f)
    val sx = anchors.fold(0f) { acc, p -> acc + p.x } + mp.x
    val sy = anchors.fold(0f) { acc, p -> acc + p.y } + mp.y
    val junction = SimplePoint(sx / (anchors.size + 1), sy / (anchors.size + 1))
    val paths = anchors.map { anchor ->
        val control = SimplePoint(
            x = (anchor.x * 2f + junction.x) / 3f,
            y = (anchor.y * 2f + junction.y) / 3f,
        )
        quadraticPath(anchor, control, junction, sizePx)
    }
    return RoadDrawSpec(
        paths = paths,
        dots = listOf(RoadDotSpec(junction, 3.3f)),
    )
}

private fun roadDeadEndTarget(
    road: TileFeatureVisualState,
    features: List<TileFeatureVisualState>,
): RoadDeadEndTarget {
    val port = edgePortsOfFeature(road).firstOrNull() ?: return RoadDeadEndTarget(SimplePoint(50f, 50f), true)
    val split = roadSplitsFieldsAtEdge(road, features, port)

    if (features.any { it.type.equals("cloister", ignoreCase = true) }) {
        return RoadDeadEndTarget(SimplePoint(50f, 50f), false)
    }
    if (features.any { it.type.equals("city", ignoreCase = true) }) {
        return when (port) {
            "N" -> RoadDeadEndTarget(SimplePoint(50f, 52f), false)
            "S" -> RoadDeadEndTarget(SimplePoint(50f, 48f), false)
            "E" -> RoadDeadEndTarget(SimplePoint(48f, 50f), false)
            "W" -> RoadDeadEndTarget(SimplePoint(52f, 50f), false)
            else -> RoadDeadEndTarget(SimplePoint(50f, 50f), false)
        }
    }
    return when (port) {
        "N" -> RoadDeadEndTarget(SimplePoint(50f, if (split) 36f else 28f), true)
        "S" -> RoadDeadEndTarget(SimplePoint(50f, if (split) 64f else 72f), true)
        "E" -> RoadDeadEndTarget(SimplePoint(if (split) 64f else 72f, 50f), true)
        "W" -> RoadDeadEndTarget(SimplePoint(if (split) 36f else 28f, 50f), true)
        else -> RoadDeadEndTarget(SimplePoint(50f, 50f), true)
    }
}

private fun roadSplitsFieldsAtEdge(
    road: TileFeatureVisualState,
    features: List<TileFeatureVisualState>,
    edge: String,
): Boolean {
    if (!road.type.equals("road", ignoreCase = true)) return false
    val fieldOwner = mutableMapOf<String, String>()
    for (feature in features) {
        if (!feature.type.equals("field", ignoreCase = true)) continue
        for (port in feature.ports) {
            if (port in HALF_FIELD_PORTS) {
                fieldOwner[port] = feature.id
            }
        }
    }
    val halves = EDGE_TO_FIELD_HALVES[edge] ?: return false
    val a = fieldOwner[halves[0]]
    val b = fieldOwner[halves[1]]
    return a != null && b != null && a != b
}

private fun edgeInwardControl(edge: String, distance: Float): SimplePoint {
    return when (edge) {
        "N" -> SimplePoint(50f, distance)
        "S" -> SimplePoint(50f, 100f - distance)
        "E" -> SimplePoint(100f - distance, 50f)
        else -> SimplePoint(distance, 50f)
    }
}

private fun edgePortsOfFeature(feature: TileFeatureVisualState): List<String> {
    return feature.ports
        .filter { it in SIMPLE_EDGE_ORDER }
        .distinct()
        .sortedBy { SIMPLE_EDGE_ORDER.indexOf(it) }
}

private fun cityPathFromPorts(ports: List<String>, sizePx: Float): Path? {
    val points = cityPointsFromPorts(ports)
    if (points.size < 3) return null
    return Path().apply {
        val first = points.first().toOffset(sizePx)
        moveTo(first.x, first.y)
        for (i in 1 until points.size) {
            val p = points[i].toOffset(sizePx)
            lineTo(p.x, p.y)
        }
        close()
    }
}

private fun cityPointsFromPorts(ports: List<String>): List<SimplePoint> {
    val p = ports
        .filter { it in SIMPLE_EDGE_ORDER }
        .distinct()
        .sortedBy { SIMPLE_EDGE_ORDER.indexOf(it) }
    val s = p.toSet()

    if (p.isEmpty()) {
        return listOf(
            SimplePoint(34f, 34f),
            SimplePoint(66f, 34f),
            SimplePoint(66f, 66f),
            SimplePoint(34f, 66f),
        )
    }
    if (p.size == 4) {
        return listOf(
            SimplePoint(0f, 0f),
            SimplePoint(100f, 0f),
            SimplePoint(100f, 100f),
            SimplePoint(0f, 100f),
        )
    }
    if (p.size == 1) return cityOneEdgeFanPoints(p[0])
    if (p.size == 2) {
        val a = p[0]
        val b = p[1]
        val opposite = (a == "N" && b == "S") ||
            (a == "S" && b == "N") ||
            (a == "E" && b == "W") ||
            (a == "W" && b == "E")
        if (opposite) {
            val base = listOf(
                SimplePoint(0f, 0f),
                SimplePoint(100f, 0f),
                SimplePoint(82f, 46f),
                SimplePoint(82f, 54f),
                SimplePoint(100f, 100f),
                SimplePoint(0f, 100f),
                SimplePoint(18f, 54f),
                SimplePoint(18f, 46f),
            )
            val steps = if (a == "N" || a == "S") 0 else 1
            return rotatePointsCW(base, steps)
        }
        return cityAdjacentEdgesPoints(p)
    }
    val missing = SIMPLE_EDGE_ORDER.firstOrNull { it !in s } ?: "S"
    return cityThreeEdgesPoints(missing)
}

private fun cityOneEdgeFanPoints(edge: String, depth: Float = 30f, samples: Int = 16): List<SimplePoint> {
    val base = mutableListOf(
        SimplePoint(0f, 0f),
        SimplePoint(100f, 0f),
    )
    for (i in 1 until samples) {
        val x = 100f - (i.toFloat() * 100f / samples.toFloat())
        val t = sin(PI * (x / 100f).toDouble()).toFloat()
        val y = depth * max(0f, t)
        base += SimplePoint(x, y)
    }
    val steps = when (edge) {
        "N" -> 0
        "E" -> 1
        "S" -> 2
        "W" -> 3
        else -> 0
    }
    return rotatePointsCW(base, steps)
}

private fun cityAdjacentEdgesPoints(ports: List<String>): List<SimplePoint> {
    val s = ports.toSet()
    val base = mutableListOf(
        SimplePoint(0f, 0f),
        SimplePoint(100f, 0f),
    )
    base += sampleQuadratic(
        p0 = SimplePoint(100f, 0f),
        c = SimplePoint(28f, 28f),
        p1 = SimplePoint(0f, 100f),
        n = 16,
    )
    base += SimplePoint(0f, 100f)

    val steps = when {
        s.contains("N") && s.contains("E") -> 1
        s.contains("E") && s.contains("S") -> 2
        s.contains("S") && s.contains("W") -> 3
        else -> 0
    }
    return rotatePointsCW(base, steps)
}

private fun cityThreeEdgesPoints(missing: String): List<SimplePoint> {
    val base = mutableListOf(
        SimplePoint(0f, 0f),
        SimplePoint(100f, 0f),
        SimplePoint(100f, 100f),
    )
    base += sampleQuadratic(
        p0 = SimplePoint(100f, 100f),
        c = SimplePoint(50f, 72f),
        p1 = SimplePoint(0f, 100f),
        n = 18,
    )
    base += SimplePoint(0f, 100f)
    val steps = when (missing) {
        "S" -> 0
        "W" -> 1
        "N" -> 2
        "E" -> 3
        else -> 0
    }
    return rotatePointsCW(base, steps)
}

private fun sampleQuadratic(p0: SimplePoint, c: SimplePoint, p1: SimplePoint, n: Int = 14): List<SimplePoint> {
    if (n <= 1) return emptyList()
    val out = mutableListOf<SimplePoint>()
    for (i in 1 until n) {
        val t = i.toFloat() / n.toFloat()
        val mt = 1f - t
        out += SimplePoint(
            x = mt * mt * p0.x + 2f * mt * t * c.x + t * t * p1.x,
            y = mt * mt * p0.y + 2f * mt * t * c.y + t * t * p1.y,
        )
    }
    return out
}

private fun rotatePointsCW(points: List<SimplePoint>, steps: Int): List<SimplePoint> {
    return points.map { rotatePointCW(it, steps) }
}

private fun rotatePointCW(point: SimplePoint, steps: Int): SimplePoint {
    var x = point.x
    var y = point.y
    repeat(((steps % 4) + 4) % 4) {
        val nx = 100f - y
        val ny = x
        x = nx
        y = ny
    }
    return SimplePoint(x, y)
}

private fun linePath(a: SimplePoint, b: SimplePoint, sizePx: Float): Path {
    val ap = a.toOffset(sizePx)
    val bp = b.toOffset(sizePx)
    return Path().apply {
        moveTo(ap.x, ap.y)
        lineTo(bp.x, bp.y)
    }
}

private fun quadraticPath(a: SimplePoint, c: SimplePoint, b: SimplePoint, sizePx: Float): Path {
    val ap = a.toOffset(sizePx)
    val cp = c.toOffset(sizePx)
    val bp = b.toOffset(sizePx)
    return Path().apply {
        moveTo(ap.x, ap.y)
        quadraticBezierTo(cp.x, cp.y, bp.x, bp.y)
    }
}

private fun cubicPath(a: SimplePoint, c1: SimplePoint, c2: SimplePoint, b: SimplePoint, sizePx: Float): Path {
    val ap = a.toOffset(sizePx)
    val c1p = c1.toOffset(sizePx)
    val c2p = c2.toOffset(sizePx)
    val bp = b.toOffset(sizePx)
    return Path().apply {
        moveTo(ap.x, ap.y)
        cubicTo(c1p.x, c1p.y, c2p.x, c2p.y, bp.x, bp.y)
    }
}

private fun SimplePoint.toOffset(sizePx: Float): Offset {
    return Offset(x * sizePx / 100f, y * sizePx / 100f)
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

    return Offset(
        x = tileLeft + option.x * cellPx,
        y = tileTop + option.y * cellPx,
    )
}

private fun rotateNormPoint(x: Float, y: Float, rotDeg: Int): Offset {
    val rot = ((rotDeg % 360) + 360) % 360
    return when (rot) {
        90 -> Offset(1f - y, x)
        180 -> Offset(1f - x, 1f - y)
        270 -> Offset(y, 1f - x)
        else -> Offset(x, y)
    }
}

private fun playerColor(player: Int?): Color {
    return if (player == 2) Color(0xFFD53E3E) else Color(0xFF2B6BE1)
}

private fun highlightToneColor(tone: String): Color {
    return when (tone.lowercase()) {
        "p1" -> Color(0xFF2B6BE1)
        "p2" -> Color(0xFFD53E3E)
        "tie" -> Color(0xFF9C6BDA)
        else -> Color(0xFF4A8B50)
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
