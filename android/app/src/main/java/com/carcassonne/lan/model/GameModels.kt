package com.carcassonne.lan.model

import kotlinx.serialization.Serializable

@Serializable
data class MeeplePlacement(
    val player: Int,
    val featureLocalId: String,
)

@Serializable
data class PlacedTile(
    val instId: Int,
    val tileId: String,
    val rotDeg: Int,
    val meeples: List<MeeplePlacement> = emptyList(),
)

@Serializable
enum class MatchStatus {
    WAITING,
    ACTIVE,
    FINISHED,
    ABORTED,
}

@Serializable
data class PlayerSlot(
    val player: Int,
    val name: String,
    val token: String,
    val connected: Boolean = true,
    val lastSeenEpochMs: Long,
)

@Serializable
data class TurnState(
    val player: Int,
    val tileId: String? = null,
    val turnIndex: Int = 1,
    val burnedTiles: List<String> = emptyList(),
    val intent: TurnIntentState? = null,
)

@Serializable
data class TurnIntentState(
    val player: Int,
    val tileId: String,
    val x: Int,
    val y: Int,
    val rotDeg: Int,
    val meepleFeatureId: String? = null,
    val locked: Boolean = false,
    val updatedAtEpochMs: Long,
)

@Serializable
data class MatchState(
    val id: String,
    val status: MatchStatus = MatchStatus.WAITING,
    val players: Map<Int, PlayerSlot> = emptyMap(),
    val board: Map<String, PlacedTile> = emptyMap(),
    val instSeq: Int = 1,
    val remaining: Map<String, Int> = emptyMap(),
    val score: Map<Int, Int> = mapOf(1 to 0, 2 to 0),
    val scoredKeys: Set<String> = emptySet(),
    val meeplesAvailable: Map<Int, Int> = mapOf(1 to 7, 2 to 7),
    val turnState: TurnState = TurnState(player = 1),
    val nextTiles: Map<Int, String?> = mapOf(1 to null, 2 to null),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastEvent: String = "",
)

@Serializable
data class ClientSession(
    val hostAddress: String,
    val port: Int,
    val token: String,
    val player: Int,
    val playerName: String,
)
