package com.carcassonne.lan.core

import com.carcassonne.lan.model.MeeplePlacement
import com.carcassonne.lan.model.PlacedTile
import com.carcassonne.lan.model.TileDef
import com.carcassonne.lan.model.TileEdge
import com.carcassonne.lan.model.TileFeature
import com.carcassonne.lan.model.TilesetPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class CarcassonneEngine(
    val tileset: TilesetPayload,
    private val boardHalfSpan: Int = 12,
) {
    companion object {
        private val EDGE_OPP = mapOf("N" to "S", "E" to "W", "S" to "N", "W" to "E")
        private val PORT_ROT_CW = mapOf(
            "Nw" to "En",
            "Ne" to "Es",
            "En" to "Se",
            "Es" to "Sw",
            "Se" to "Ws",
            "Sw" to "Wn",
            "Ws" to "Nw",
            "Wn" to "Ne",
        )
        private val CITY_EDGE_TO_ADJ_FIELD_PORTS = mapOf(
            "N" to listOf("Nw", "Ne", "Wn", "En"),
            "E" to listOf("En", "Es", "Ne", "Se"),
            "S" to listOf("Sw", "Se", "Ws", "Es"),
            "W" to listOf("Wn", "Ws", "Nw", "Sw"),
        )
    }

    data class CanPlaceResult(val ok: Boolean, val reason: String)

    data class RotatedTile(
        val id: String,
        val edges: Map<String, TileEdge>,
        val features: List<TileFeature>,
    )

    data class NodeMeta(
        val type: String,
        val ports: List<String>,
        val tags: JsonObject,
        val instId: Int,
        val cellKey: String,
        val localId: String,
    )

    data class FeatureGroup(
        val id: String,
        val type: String,
        val nodes: MutableSet<String> = linkedSetOf(),
        val tiles: MutableSet<Int> = linkedSetOf(),
        val meeplesByPlayer: MutableMap<Int, Int> = mutableMapOf(1 to 0, 2 to 0),
        var pennants: Int = 0,
        var complete: Boolean = false,
        val openPorts: MutableSet<String> = linkedSetOf(),
        var adjacentCount: Int = 0,
        val adjCompletedCities: MutableSet<String> = linkedSetOf(),
        var key: String = "",
    )

    data class Analysis(
        val uf: UnionFind<String>,
        val nodeMeta: Map<String, NodeMeta>,
        val groups: Map<String, FeatureGroup>,
    )

    data class InstLookup(
        val roadEdge: MutableMap<String, String> = mutableMapOf(),
        val cityEdge: MutableMap<String, String> = mutableMapOf(),
        val fieldHalf: MutableMap<String, String> = mutableMapOf(),
    )

    private val tileById: Map<String, TileDef> = tileset.tiles.associateBy { it.id }
    val counts: Map<String, Int> = tileset.tileCounts.mapValues { (_, v) -> v }
    val startTileId: String? = pickStartTileId()
    private val fieldCityAdjacency: Map<String, Map<String, Set<String>>> = buildFieldCityAdjacency()

    private fun pickStartTileId(): String? {
        val explicit = tileset.tiles.firstOrNull { tile ->
            tile.isStartTileType && (counts[tile.id] ?: 0) > 0
        }
        if (explicit != null) return explicit.id
        return counts.entries
            .filter { it.value > 0 }
            .map { it.key }
            .sorted()
            .firstOrNull()
    }

    private fun buildFieldCityAdjacency(): Map<String, Map<String, Set<String>>> {
        val out = mutableMapOf<String, Map<String, Set<String>>>()
        for ((tileId, tile) in tileById) {
            val fields = tile.features.filter { it.type == "field" }
            val cities = tile.features.filter { it.type == "city" }
            val tileMap = mutableMapOf<String, Set<String>>()
            for (field in fields) {
                val fPorts = field.ports.toSet()
                if (fPorts.isEmpty()) continue
                val hits = mutableSetOf<String>()
                for (city in cities) {
                    var adjacent = false
                    for (edge in city.ports) {
                        for (candidate in CITY_EDGE_TO_ADJ_FIELD_PORTS[edge].orEmpty()) {
                            if (candidate in fPorts) {
                                adjacent = true
                                break
                            }
                        }
                        if (adjacent) break
                    }
                    if (adjacent) hits += city.id
                }
                if (hits.isNotEmpty()) tileMap[field.id] = hits
            }
            out[tileId] = tileMap
        }
        return out
    }

    fun keyXY(x: Int, y: Int): String = "$x,$y"

    fun parseXY(key: String): Pair<Int, Int> {
        val parts = key.split(",", limit = 2)
        return Pair(parts[0].toInt(), parts[1].toInt())
    }

    fun rotPort(port: String, rotDeg: Int): String {
        val steps = ((rotDeg % 360) + 360) % 360 / 90
        var p = port
        repeat(steps) {
            p = when (p) {
                in PORT_ROT_CW -> PORT_ROT_CW.getValue(p)
                "N" -> "E"
                "E" -> "S"
                "S" -> "W"
                "W" -> "N"
                else -> error("Unknown port: $p")
            }
        }
        return p
    }

    fun rotateTile(tileId: String, rotDeg: Int): RotatedTile {
        val base = tileById[tileId] ?: error("Unknown tile: $tileId")
        val inv = (360 - rotDeg) % 360
        val edges = linkedMapOf<String, TileEdge>()
        for (edge in listOf("N", "E", "S", "W")) {
            val srcEdge = rotPort(edge, inv)
            val be = base.edges[srcEdge] ?: TileEdge()
            edges[edge] = TileEdge(
                primary = be.primary,
                feature = be.feature,
                halves = be.halves,
            )
        }

        val features = base.features.map { f ->
            f.copy(ports = f.ports.map { p -> rotPort(p, rotDeg) })
        }
        return RotatedTile(id = tileId, edges = edges, features = features)
    }

    fun baseFeatures(tileId: String): List<TileFeature> {
        return tileById[tileId]?.features.orEmpty()
    }

    fun featureType(tileId: String, featureLocalId: String): String? {
        return tileById[tileId]
            ?.features
            ?.firstOrNull { it.id == featureLocalId }
            ?.type
    }

    fun featurePlacementNormalized(
        tileId: String,
        featureLocalId: String,
        rotDeg: Int,
    ): Pair<Float, Float>? {
        val feature = tileById[tileId]
            ?.features
            ?.firstOrNull { it.id == featureLocalId }
            ?: return null
        var px = feature.meeplePlacement.getOrNull(0)?.toFloat() ?: 0.5f
        var py = feature.meeplePlacement.getOrNull(1)?.toFloat() ?: 0.5f

        // Base set cloister tiles A/B: move field marker upward for clearer placement.
        if (feature.type == "field" && tileId in setOf("A", "B")) {
            py = (py - 0.25f).coerceIn(0.05f, 0.95f)
            px = px.coerceIn(0.05f, 0.95f)
        }
        return rotateNormalized(px, py, rotDeg)
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

    private fun withinBounds(x: Int, y: Int): Boolean =
        kotlin.math.abs(x) <= boardHalfSpan && kotlin.math.abs(y) <= boardHalfSpan

    fun canPlaceAt(
        board: Map<String, PlacedTile>,
        tileId: String,
        rotDeg: Int,
        x: Int,
        y: Int,
    ): CanPlaceResult {
        if (!withinBounds(x, y)) return CanPlaceResult(false, "Out of board bounds.")
        val key = keyXY(x, y)
        if (board.containsKey(key)) return CanPlaceResult(false, "Cell occupied.")

        val hasAny = board.isNotEmpty()
        var touches = false
        val tile = rotateTile(tileId, rotDeg)
        val neighbors = listOf(
            Triple(0, -1, "N"),
            Triple(1, 0, "E"),
            Triple(0, 1, "S"),
            Triple(-1, 0, "W"),
        )

        for ((dx, dy, edge) in neighbors) {
            val nk = keyXY(x + dx, y + dy)
            val nInst = board[nk] ?: continue
            touches = true
            val nTile = rotateTile(nInst.tileId, nInst.rotDeg)
            val opp = EDGE_OPP.getValue(edge)
            val a = tile.edges[edge]?.primary
            val b = nTile.edges[opp]?.primary
            if (a != b) {
                return CanPlaceResult(false, "Edge mismatch $edge: $a vs neighbor $opp: $b")
            }
        }

        if (hasAny && !touches) {
            return CanPlaceResult(false, "Tile must touch at least one placed tile.")
        }
        return CanPlaceResult(true, "OK")
    }

    fun buildFrontier(board: Map<String, PlacedTile>): Set<Pair<Int, Int>> {
        val frontier = linkedSetOf<Pair<Int, Int>>()
        if (board.isEmpty()) {
            frontier += Pair(0, 0)
            return frontier
        }
        for (cellKey in board.keys) {
            val (x, y) = parseXY(cellKey)
            val candidates = listOf(
                Pair(x, y - 1),
                Pair(x + 1, y),
                Pair(x, y + 1),
                Pair(x - 1, y),
            )
            for ((nx, ny) in candidates) {
                if (!withinBounds(nx, ny)) continue
                if (!board.containsKey(keyXY(nx, ny))) {
                    frontier += Pair(nx, ny)
                }
            }
        }
        return frontier
    }

    fun hasAnyPlacement(board: Map<String, PlacedTile>, tileId: String): Boolean {
        for ((x, y) in buildFrontier(board)) {
            for (rot in listOf(0, 90, 180, 270)) {
                if (canPlaceAt(board, tileId, rot, x, y).ok) return true
            }
        }
        return false
    }

    private fun edgeDelta(edge: String): Pair<Int, Int> = when (edge) {
        "N" -> Pair(0, -1)
        "E" -> Pair(1, 0)
        "S" -> Pair(0, 1)
        "W" -> Pair(-1, 0)
        else -> error("Bad edge: $edge")
    }

    fun analyzeBoard(board: Map<String, PlacedTile>): Analysis {
        val uf = UnionFind<String>()
        val nodeMeta = mutableMapOf<String, NodeMeta>()
        val perTileLookup = mutableMapOf<Int, InstLookup>()
        val instById = mutableMapOf<Int, Pair<String, PlacedTile>>()

        for ((cellKey, inst) in board) {
            val instId = inst.instId
            instById[instId] = Pair(cellKey, inst)
            val tile = rotateTile(inst.tileId, inst.rotDeg)

            val lookup = InstLookup()
            for (feat in tile.features) {
                val nodeKey = "$instId:${feat.id}"
                uf.add(nodeKey)
                nodeMeta[nodeKey] = NodeMeta(
                    type = feat.type,
                    ports = feat.ports,
                    tags = feat.tags,
                    instId = instId,
                    cellKey = cellKey,
                    localId = feat.id,
                )

                when (feat.type) {
                    "road" -> feat.ports.forEach { p -> lookup.roadEdge[p] = feat.id }
                    "city" -> feat.ports.forEach { p -> lookup.cityEdge[p] = feat.id }
                    "field" -> feat.ports.forEach { p -> lookup.fieldHalf[p] = feat.id }
                }
            }
            perTileLookup[instId] = lookup
        }

        for ((cellKey, inst) in board) {
            val (x, y) = parseXY(cellKey)
            val lookA = perTileLookup[inst.instId] ?: continue

            val neighborDefs = listOf(
                NeighborDef(x + 1, y, "E", "W", listOf(Pair("En", "Wn"), Pair("Es", "Ws"))),
                NeighborDef(x, y + 1, "S", "N", listOf(Pair("Sw", "Nw"), Pair("Se", "Ne"))),
            )

            for (nd in neighborDefs) {
                val nk = keyXY(nd.nx, nd.ny)
                val instB = board[nk] ?: continue
                val lookB = perTileLookup[instB.instId] ?: continue

                val roadA = lookA.roadEdge[nd.edgeA]
                val roadB = lookB.roadEdge[nd.edgeB]
                if (roadA != null && roadB != null) {
                    uf.union("${inst.instId}:$roadA", "${instB.instId}:$roadB")
                }

                val cityA = lookA.cityEdge[nd.edgeA]
                val cityB = lookB.cityEdge[nd.edgeB]
                if (cityA != null && cityB != null) {
                    uf.union("${inst.instId}:$cityA", "${instB.instId}:$cityB")
                }

                for ((halfA, halfB) in nd.halfPairs) {
                    val fA = lookA.fieldHalf[halfA]
                    val fB = lookB.fieldHalf[halfB]
                    if (fA != null && fB != null) {
                        uf.union("${inst.instId}:$fA", "${instB.instId}:$fB")
                    }
                }
            }
        }

        val groups = linkedMapOf<String, FeatureGroup>()
        for ((nodeKey, meta) in nodeMeta) {
            val root = uf.find(nodeKey)
            val g = groups.getOrPut(root) {
                FeatureGroup(id = root, type = meta.type)
            }
            g.nodes += nodeKey
            g.tiles += meta.instId
            if (meta.type == "city") {
                g.pennants += meta.tags["pennants"]?.jsonPrimitive?.intOrNull ?: 0
            }
        }

        for ((_, inst) in board) {
            for (meeple in inst.meeples) {
                val nodeKey = "${inst.instId}:${meeple.featureLocalId}"
                if (!nodeMeta.containsKey(nodeKey)) continue
                val root = uf.find(nodeKey)
                val g = groups[root] ?: continue
                val p = meeple.player
                if (p == 1 || p == 2) {
                    g.meeplesByPlayer[p] = (g.meeplesByPlayer[p] ?: 0) + 1
                }
            }
        }

        for (g in groups.values) {
            g.key = stableGroupKey(g)
        }

        for (g in groups.values) {
            when (g.type) {
                "road", "city" -> {
                    val openPorts = linkedSetOf<String>()
                    for (nodeKey in g.nodes) {
                        val meta = nodeMeta[nodeKey] ?: continue
                        val (x, y) = parseXY(meta.cellKey)
                        for (edge in meta.ports) {
                            val (dx, dy) = edgeDelta(edge)
                            val nk = keyXY(x + dx, y + dy)
                            val nInst = board[nk]
                            if (nInst == null) {
                                openPorts += "${meta.cellKey}:$edge"
                                continue
                            }
                            val nLookup = perTileLookup[nInst.instId] ?: continue
                            val opp = EDGE_OPP.getValue(edge)
                            val hasOpp = if (g.type == "road") {
                                nLookup.roadEdge.containsKey(opp)
                            } else {
                                nLookup.cityEdge.containsKey(opp)
                            }
                            if (!hasOpp) {
                                openPorts += "${meta.cellKey}:$edge"
                            }
                        }
                    }
                    g.openPorts += openPorts
                    g.complete = openPorts.isEmpty()
                }

                "cloister" -> {
                    val onlyInstId = g.tiles.firstOrNull()
                    val cellKey = onlyInstId?.let { instById[it]?.first }
                    if (cellKey == null) {
                        g.adjacentCount = 0
                        g.complete = false
                    } else {
                        val (x, y) = parseXY(cellKey)
                        var cnt = 0
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue
                                if (board.containsKey(keyXY(x + dx, y + dy))) cnt += 1
                            }
                        }
                        g.adjacentCount = cnt
                        g.complete = cnt == 8
                    }
                }
            }
        }

        for (g in groups.values) {
            if (g.type != "field") continue
            for (nodeKey in g.nodes) {
                val meta = nodeMeta[nodeKey] ?: continue
                val inst = board[meta.cellKey] ?: continue
                val tileAdj = fieldCityAdjacency[inst.tileId].orEmpty()
                val cityLocals = tileAdj[meta.localId].orEmpty()
                for (cityLocal in cityLocals) {
                    val cityNode = "${meta.instId}:$cityLocal"
                    if (!nodeMeta.containsKey(cityNode)) continue
                    val cityRoot = uf.find(cityNode)
                    val cityGroup = groups[cityRoot]
                    if (cityGroup != null && cityGroup.type == "city" && cityGroup.complete) {
                        g.adjCompletedCities += cityGroup.key
                    }
                }
            }
        }

        return Analysis(uf = uf, nodeMeta = nodeMeta, groups = groups)
    }

    fun scoreFeature(group: FeatureGroup, completed: Boolean): Int {
        return when (group.type) {
            "road" -> group.tiles.size
            "city" -> {
                val tiles = group.tiles.size
                val pennants = group.pennants
                if (completed) (2 * tiles + 2 * pennants) else (tiles + pennants)
            }

            "cloister" -> if (completed) 9 else (1 + group.adjacentCount)
            "field" -> 3 * group.adjCompletedCities.size
            else -> 0
        }
    }

    fun scoreEndNowValue(group: FeatureGroup): Int {
        return when (group.type) {
            "city" -> scoreFeature(group, group.complete)
            "road" -> scoreFeature(group, completed = true)
            "cloister" -> scoreFeature(group, completed = false)
            "field" -> scoreFeature(group, completed = false)
            else -> 0
        }
    }

    fun stableGroupKey(group: FeatureGroup): String {
        return "${group.type}|${group.nodes.sorted().joinToString("/")}"
    }

    fun winnersOfGroup(group: FeatureGroup): List<Int> {
        val m1 = group.meeplesByPlayer[1] ?: 0
        val m2 = group.meeplesByPlayer[2] ?: 0
        val mx = kotlin.math.max(m1, m2)
        if (mx <= 0) return emptyList()
        val winners = mutableListOf<Int>()
        if (m1 == mx) winners += 1
        if (m2 == mx) winners += 2
        return winners
    }

    private data class NeighborDef(
        val nx: Int,
        val ny: Int,
        val edgeA: String,
        val edgeB: String,
        val halfPairs: List<Pair<String, String>>,
    )
}
