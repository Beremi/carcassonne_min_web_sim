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
enum class GameMode {
    STANDARD,
    RANDOM,
    PARALLEL,
}

@Serializable
enum class ParallelPhase {
    PICK,
    PLACE,
    RESOLVE,
    MEEPLE,
}

@Serializable
enum class ParallelConflictType {
    SAME_CELL,
    EDGE_MISMATCH,
}

@Serializable
data class ParallelConflictState(
    val type: ParallelConflictType,
    val tokenHolder: Int,
    val fixedPlayer: Int,
    val replacerPlayer: Int,
    val message: String,
)

@Serializable
data class ParallelPlayerRoundState(
    val pickIndex: Int? = null,
    val pickedTileId: String? = null,
    val intent: TurnIntentState? = null,
    val tileLocked: Boolean = false,
    val committedCellKey: String? = null,
    val committedRotDeg: Int? = null,
    val committedInstId: Int? = null,
    val meepleFeatureId: String? = null,
    val meepleConfirmed: Boolean = false,
)

@Serializable
data class ParallelRoundState(
    val roundIndex: Int = 1,
    val moveLimit: Int = 36,
    val selection: List<String> = emptyList(),
    val phase: ParallelPhase = ParallelPhase.PICK,
    val players: Map<Int, ParallelPlayerRoundState> = emptyMap(),
    val conflict: ParallelConflictState? = null,
    val placementDoneAtEpochMs: Long = 0L,
)

@Serializable
data class GameRules(
    val gameMode: GameMode = GameMode.STANDARD,
    val meeplesPerPlayer: Int = 7,
    val smallCityTwoTilesFourPoints: Boolean = true,
    val randomizedMode: Boolean = false,
    val randomizedMoveLimit: Int = 72,
    val previewEnabled: Boolean = false,
    val previewCount: Int = 4,
    val parallelSelectionSize: Int = 3,
    val parallelMoveLimit: Int = 36,
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
    val scoredAreaHistory: Map<String, AreaScoreHistoryEntry> = emptyMap(),
    val meeplesAvailable: Map<Int, Int> = mapOf(1 to 7, 2 to 7),
    val rules: GameRules = GameRules(),
    val turnState: TurnState = TurnState(player = 1),
    val nextTiles: Map<Int, String?> = mapOf(1 to null, 2 to null),
    val drawQueue: List<String> = emptyList(),
    val priorityTokenPlayer: Int? = null,
    val parallelRound: ParallelRoundState? = null,
    val parallelIntents: Map<Int, TurnIntentState> = emptyMap(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastEvent: String = "",
)

@Serializable
data class AreaScoreHistoryEntry(
    val key: String,
    val type: String,
    val p1: Int = 0,
    val p2: Int = 0,
    val closed: Boolean = true,
)

@Serializable
data class ClientSession(
    val hostAddress: String,
    val port: Int,
    val token: String,
    val player: Int,
    val playerName: String,
)
