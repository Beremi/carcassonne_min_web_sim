package com.carcassonne.lan.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TilesetPayload(
    val meta: JsonObject = JsonObject(emptyMap()),
    @SerialName("tile_counts")
    val tileCounts: Map<String, Int> = emptyMap(),
    val tiles: List<TileDef> = emptyList(),
)

@Serializable
data class TileDef(
    val id: String,
    val count: Int = 0,
    @SerialName("is_start_tile_type")
    val isStartTileType: Boolean = false,
    val image: String? = null,
    val edges: Map<String, TileEdge> = emptyMap(),
    val features: List<TileFeature> = emptyList(),
    val tags: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TileEdge(
    val primary: String = "",
    val feature: String? = null,
    val halves: Map<String, TileEdgeHalf> = emptyMap(),
)

@Serializable
data class TileEdgeHalf(
    val type: String = "",
    val feature: String? = null,
)

@Serializable
data class TileFeature(
    val id: String,
    val type: String,
    val ports: List<String> = emptyList(),
    val tags: JsonObject = JsonObject(emptyMap()),
    @SerialName("meeple_placement")
    val meeplePlacement: List<Double> = listOf(0.5, 0.5),
)
