package com.carcassonne.lan

import com.carcassonne.lan.core.CarcassonneEngine
import com.carcassonne.lan.model.PlacedTile
import com.carcassonne.lan.model.TileDef
import com.carcassonne.lan.model.TileEdge
import com.carcassonne.lan.model.TileFeature
import com.carcassonne.lan.model.TilesetPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarcassonneEngineTest {
    private val tileset = TilesetPayload(
        tileCounts = mapOf("A" to 5, "B" to 3),
        tiles = listOf(
            TileDef(
                id = "A",
                isStartTileType = true,
                edges = mapOf(
                    "N" to TileEdge(primary = "field"),
                    "E" to TileEdge(primary = "field"),
                    "S" to TileEdge(primary = "field"),
                    "W" to TileEdge(primary = "field"),
                ),
                features = listOf(
                    TileFeature(
                        id = "field1",
                        type = "field",
                        ports = listOf("Nw", "Ne", "En", "Es", "Se", "Sw", "Ws", "Wn"),
                    )
                ),
            ),
            TileDef(
                id = "B",
                edges = mapOf(
                    "N" to TileEdge(primary = "road"),
                    "E" to TileEdge(primary = "field"),
                    "S" to TileEdge(primary = "road"),
                    "W" to TileEdge(primary = "field"),
                ),
                features = listOf(
                    TileFeature(id = "road1", type = "road", ports = listOf("N", "S")),
                    TileFeature(id = "fieldL", type = "field", ports = listOf("Wn", "Ws", "Nw", "Sw")),
                    TileFeature(id = "fieldR", type = "field", ports = listOf("En", "Es", "Ne", "Se")),
                    TileFeature(
                        id = "city1",
                        type = "city",
                        ports = listOf("N"),
                        tags = JsonObject(mapOf("pennants" to JsonPrimitive(1))),
                    ),
                ),
            )
        ),
    )

    private val engine = CarcassonneEngine(tileset)

    @Test
    fun canPlaceFieldNextToField() {
        val board = mapOf(
            "0,0" to PlacedTile(instId = 1, tileId = "A", rotDeg = 0),
        )
        val legal = engine.canPlaceAt(board, tileId = "A", rotDeg = 0, x = 1, y = 0)
        assertTrue(legal.ok)
    }

    @Test
    fun rejectsEdgeMismatch() {
        val board = mapOf(
            "0,0" to PlacedTile(instId = 1, tileId = "A", rotDeg = 0),
        )
        val illegal = engine.canPlaceAt(board, tileId = "B", rotDeg = 0, x = 0, y = -1)
        assertFalse(illegal.ok)
        assertTrue(illegal.reason.contains("Edge mismatch"))
    }

    @Test
    fun cityScoreFormulaMatchesRules() {
        val group = CarcassonneEngine.FeatureGroup(
            id = "g1",
            type = "city",
            pennants = 1,
            complete = true,
        ).apply {
            tiles += 1
            tiles += 2
        }

        assertEquals(6, engine.scoreFeature(group, completed = true))
        assertEquals(3, engine.scoreFeature(group, completed = false))
    }
}
