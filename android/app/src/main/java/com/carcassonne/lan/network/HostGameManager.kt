package com.carcassonne.lan.network

import com.carcassonne.lan.core.CarcassonneEngine
import com.carcassonne.lan.data.NameGenerator
import com.carcassonne.lan.model.GenericOkResponse
import com.carcassonne.lan.model.AreaScoreHistoryEntry
import com.carcassonne.lan.model.GameMode
import com.carcassonne.lan.model.GameRules
import com.carcassonne.lan.model.InviteListItem
import com.carcassonne.lan.model.InviteListResponse
import com.carcassonne.lan.model.InviteSendResponse
import com.carcassonne.lan.model.InviteStatusResponse
import com.carcassonne.lan.model.JoinResponse
import com.carcassonne.lan.model.MatchState
import com.carcassonne.lan.model.MatchStatus
import com.carcassonne.lan.model.MeeplePlacement
import com.carcassonne.lan.model.PingResponse
import com.carcassonne.lan.model.PlacedTile
import com.carcassonne.lan.model.ParallelConflictState
import com.carcassonne.lan.model.ParallelConflictType
import com.carcassonne.lan.model.ParallelPhase
import com.carcassonne.lan.model.ParallelPlayerRoundState
import com.carcassonne.lan.model.ParallelRoundState
import com.carcassonne.lan.model.PlayerSlot
import com.carcassonne.lan.model.PlayerSummary
import com.carcassonne.lan.model.PollResponse
import com.carcassonne.lan.model.SubmitTurnResponse
import com.carcassonne.lan.model.TurnIntentState
import com.carcassonne.lan.model.TurnState
import java.util.ArrayDeque
import java.util.UUID
import kotlin.random.Random

class HostGameManager(
    private val engine: CarcassonneEngine,
    private val random: Random = Random.Default,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private enum class InviteStatus {
        PENDING,
        ACCEPTED,
        DECLINED,
        USED,
        EXPIRED,
    }

    private data class InviteRecord(
        val id: String,
        val fromName: String,
        val createdAtEpochMs: Long,
        var status: InviteStatus,
        var rules: GameRules? = null,
    )

    private val lock = Any()
    private var nextMatchId = 1
    private var nextInviteId = 1
    private var hostName: String = NameGenerator.generate(random)
    private var hostToken: String = newToken()
    private var lobbyRules: GameRules = normalizeRules(GameRules())
    private var match: MatchState = createWaitingMatch(hostName, hostToken)
    private var soloMode: Boolean = false
    private val invitesById = linkedMapOf<String, InviteRecord>()
    private val acceptedInviteNames = linkedSetOf<String>()

    private fun modeOf(rules: GameRules = match.rules): GameMode = rules.gameMode

    private fun isParallelMode(rules: GameRules = match.rules): Boolean = modeOf(rules) == GameMode.PARALLEL

    private fun isRandomizedMode(rules: GameRules = match.rules): Boolean = modeOf(rules) == GameMode.RANDOM

    fun configureHostPlayer(nameInput: String): String = synchronized(lock) {
        hostName = NameGenerator.ensureNumericSuffix(nameInput, random)
        val players = match.players.toMutableMap()
        val existing = players[1]
        hostToken = existing?.token ?: hostToken
        val updated = PlayerSlot(
            player = 1,
            name = hostName,
            token = hostToken,
            connected = true,
            lastSeenEpochMs = nowMs(),
        )
        players[1] = updated
        match = match.copy(players = players, updatedAtEpochMs = nowMs())
        hostToken
    }

    fun restoreMatchIfCompatible(saved: MatchState?) {
        if (saved == null) return
        synchronized(lock) {
            if (saved.board.isEmpty()) return@synchronized
            var restored = saved.copy(
                updatedAtEpochMs = nowMs(),
                turnState = saved.turnState.copy(intent = null),
            )
            val restoredP2 = restored.players[2]
            if (restoredP2 != null) {
                // After process/app restart, treat remote slot as offline until it reconnects.
                val players = restored.players.toMutableMap()
                players[2] = restoredP2.copy(connected = false)
                restored = restored.copy(players = players)
            }
            lobbyRules = normalizeRules(restored.rules)
            val normalizedMeeples = normalizedMeeplesByRules(restored.meeplesAvailable, lobbyRules.meeplesPerPlayer)
            val normalizedDrawQueue = normalizeDrawQueue(
                existing = restored.drawQueue,
                rules = lobbyRules,
                remaining = restored.remaining,
            )
            match = restored
                .copy(
                    rules = lobbyRules,
                    meeplesAvailable = normalizedMeeples,
                    drawQueue = normalizedDrawQueue,
                    priorityTokenPlayer = if (lobbyRules.gameMode == GameMode.PARALLEL) {
                        restored.priorityTokenPlayer ?: listOf(1, 2).random(random)
                    } else {
                        null
                    },
                    parallelRound = if (lobbyRules.gameMode == GameMode.PARALLEL) {
                        normalizeParallelRound(restored.parallelRound, restored, lobbyRules)
                    } else {
                        null
                    },
                    parallelIntents = if (lobbyRules.gameMode == GameMode.PARALLEL) {
                        restored.parallelIntents
                    } else {
                        emptyMap()
                    },
                )
            nextMatchId = extractNextMatchId(saved.id)
            val p1 = saved.players[1]
            if (p1 != null) {
                hostName = p1.name
                hostToken = p1.token
            }
            soloMode = restored.status == MatchStatus.ACTIVE && restored.players[2] == null
            if (match.status == MatchStatus.ACTIVE && isParallelMode() && match.parallelRound == null) {
                startParallelRoundLocked(roundIndex = 1)
            }
            cleanupDisconnectedOpponentLocked()
        }
    }

    fun snapshot(): MatchState = synchronized(lock) { match.copy() }

    fun ping(): PingResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val players = match.players.values.sortedBy { it.player }.map {
            PlayerSummary(player = it.player, name = it.name, connected = it.connected)
        }
        val open = if (isJoinSlotAvailableLocked()) 1 else 0
        PingResponse(
            hostName = hostName,
            matchStatus = match.status,
            openSlots = open,
            rules = match.rules,
            players = players,
        )
    }

    fun joinOrReconnect(nameRaw: String): JoinResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val requested = NameGenerator.ensureNumericSuffix(nameRaw, random)

        val existingByName = match.players.values.firstOrNull {
            it.name.equals(requested, ignoreCase = true)
        }
        if (existingByName != null) {
            val freshToken = newToken()
            val players = match.players.toMutableMap()
            players[existingByName.player] = existingByName.copy(
                token = freshToken,
                connected = true,
                lastSeenEpochMs = nowMs(),
                name = requested,
            )
            if (existingByName.player == 1) hostToken = freshToken
            var updatedMatch = match.copy(players = players, updatedAtEpochMs = nowMs())
            if (updatedMatch.rules.gameMode == GameMode.PARALLEL) {
                val round = updatedMatch.parallelRound
                if (round != null && existingByName.player !in round.players.keys) {
                    val roundPlayers = round.players.toMutableMap()
                    roundPlayers[existingByName.player] = ParallelPlayerRoundState()
                    updatedMatch = updatedMatch.copy(parallelRound = round.copy(players = roundPlayers))
                }
            }
            match = updatedMatch
            return JoinResponse(ok = true, token = freshToken, player = existingByName.player, match = match)
        }

        var p2 = match.players[2]
        if (soloMode && p2 == null) {
            return JoinResponse(ok = false, error = "Solo game is active.")
        }
        if (p2 != null && match.status != MatchStatus.FINISHED && match.status != MatchStatus.ABORTED) {
            return JoinResponse(ok = false, error = "Match is full.")
        }

        if (p2 != null && (match.status == MatchStatus.FINISHED || match.status == MatchStatus.ABORTED)) {
            match = createWaitingMatch(hostName, hostToken)
            p2 = null
        }

        if (p2 == null && !isInviteAcceptedForNameLocked(requested)) {
            return JoinResponse(ok = false, error = "Invite required. Ask the host to accept your invite.")
        }

        val joinToken = newToken()
        val players = match.players.toMutableMap()
        players[2] = PlayerSlot(
            player = 2,
            name = requested,
            token = joinToken,
            connected = true,
            lastSeenEpochMs = nowMs(),
        )

        match = match.copy(players = players, status = MatchStatus.ACTIVE, updatedAtEpochMs = nowMs())
        soloMode = false
        consumeInviteForNameLocked(requested)
        if (isParallelMode()) {
            if (match.priorityTokenPlayer == null) {
                match = match.copy(priorityTokenPlayer = listOf(1, 2).random(random))
            }
            startParallelRoundLocked(roundIndex = 1)
            match = match.copy(
                lastEvent = "Parallel match started: ${players[1]?.name ?: "Host"} vs $requested",
                updatedAtEpochMs = nowMs(),
            )
        } else {
            ensureNextTiles()
            drawPlaceableTileForTurn()
            match = match.copy(
                lastEvent = "Match started: ${players[1]?.name ?: "Host"} vs $requested",
                updatedAtEpochMs = nowMs(),
            )
        }
        JoinResponse(ok = true, token = joinToken, player = 2, match = match)
    }

    fun startSoloGame(): GenericOkResponse = synchronized(lock) {
        cleanupInvitesLocked()

        val p2 = match.players[2]
        if (p2 != null && p2.connected && match.status == MatchStatus.ACTIVE) {
            return GenericOkResponse(ok = false, error = "Opponent is connected. Disconnect first.")
        }

        val fresh = createWaitingMatch(hostName, hostToken)
        val p1 = fresh.players[1]
            ?: PlayerSlot(
                player = 1,
                name = hostName,
                token = hostToken,
                connected = true,
                lastSeenEpochMs = nowMs(),
            )
        match = fresh.copy(
            status = MatchStatus.ACTIVE,
            players = mapOf(1 to p1),
            turnState = fresh.turnState.copy(
                player = 1,
                tileId = null,
                burnedTiles = emptyList(),
                turnIndex = 1,
                intent = null,
            ),
            nextTiles = mapOf(1 to null, 2 to null),
            lastEvent = "Solo game started.",
            updatedAtEpochMs = nowMs(),
        )
        soloMode = true
        invitesById.clear()
        acceptedInviteNames.clear()
        if (isParallelMode()) {
            if (match.priorityTokenPlayer == null) {
                match = match.copy(priorityTokenPlayer = 1)
            }
            startParallelRoundLocked(roundIndex = 1)
        } else {
            ensureNextTiles()
            drawPlaceableTileForTurn()
        }
        return GenericOkResponse(ok = true)
    }

    fun currentRules(): GameRules = synchronized(lock) {
        match.rules
    }

    fun configureGameRules(input: GameRules): GenericOkResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val normalized = normalizeRules(input)
        val hasActiveOpponent = match.status == MatchStatus.ACTIVE && match.players[2]?.connected == true
        if (hasActiveOpponent) {
            return GenericOkResponse(ok = false, error = "Cannot change game settings during active multiplayer match.")
        }

        lobbyRules = normalized
        resetToWaitingLocked("Game settings updated. Waiting for opponent.")
        return GenericOkResponse(ok = true)
    }

    fun refreshLobbyState(): Boolean = synchronized(lock) {
        cleanupInvitesLocked()
        val now = nowMs()
        val p2 = match.players[2]
        val hasActiveTwoPlayerMatch = match.status == MatchStatus.ACTIVE &&
            !soloMode &&
            p2 != null &&
            p2.connected

        if (hasActiveTwoPlayerMatch) {
            val players = match.players.toMutableMap()
            val p1 = players[1]
            if (p1 != null && !p1.connected) {
                players[1] = p1.copy(
                    connected = true,
                    lastSeenEpochMs = now,
                )
                match = match.copy(
                    players = players,
                    updatedAtEpochMs = now,
                )
            }
            return false
        }

        resetToWaitingLocked("Lobby refreshed. Waiting for opponent.")
        return true
    }

    fun sendInvite(fromNameRaw: String, rules: GameRules? = null): InviteSendResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val fromName = NameGenerator.ensureNumericSuffix(fromNameRaw, random)
        val normalizedRules = rules?.let { normalizeRules(it) }

        if (!isJoinSlotAvailableLocked()) {
            return InviteSendResponse(ok = false, error = "Unavailable")
        }

        val existing = invitesById.values.firstOrNull {
            it.status == InviteStatus.PENDING && it.fromName.equals(fromName, ignoreCase = true)
        }
        if (existing != null) {
            existing.rules = normalizedRules
            return InviteSendResponse(ok = true, inviteId = existing.id)
        }

        val id = "inv${nextInviteId++}"
        invitesById[id] = InviteRecord(
            id = id,
            fromName = fromName,
            createdAtEpochMs = nowMs(),
            status = InviteStatus.PENDING,
            rules = normalizedRules,
        )
        return InviteSendResponse(ok = true, inviteId = id)
    }

    fun inviteStatus(inviteId: String): InviteStatusResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val invite = invitesById[inviteId]
            ?: return@synchronized InviteStatusResponse(ok = false, error = "Invite not found.")
        InviteStatusResponse(
            ok = true,
            inviteId = invite.id,
            status = invite.status.name.lowercase(),
        )
    }

    fun listIncomingInvites(): InviteListResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val out = invitesById.values
            .filter { it.status == InviteStatus.PENDING }
            .sortedByDescending { it.createdAtEpochMs }
            .map {
                InviteListItem(
                    id = it.id,
                    fromName = it.fromName,
                    createdAtEpochMs = it.createdAtEpochMs,
                    status = it.status.name.lowercase(),
                    rules = it.rules,
                )
            }
        InviteListResponse(ok = true, invites = out)
    }

    fun respondInvite(inviteId: String, actionRaw: String): InviteStatusResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val invite = invitesById[inviteId]
            ?: return@synchronized InviteStatusResponse(ok = false, error = "Invite not found.")
        if (invite.status != InviteStatus.PENDING) {
            return@synchronized InviteStatusResponse(
                ok = true,
                inviteId = invite.id,
                status = invite.status.name.lowercase(),
            )
        }

        val action = actionRaw.trim().lowercase()
        when (action) {
            "accept" -> {
                if (!isJoinSlotAvailableLocked()) {
                    return@synchronized InviteStatusResponse(ok = false, error = "Unavailable")
                }
                val inviteRules = invite.rules?.let { normalizeRules(it) }
                if (inviteRules != null) {
                    lobbyRules = inviteRules
                    if (match.status != MatchStatus.ACTIVE || match.players[2]?.connected != true) {
                        match = createWaitingMatch(hostName, hostToken).copy(
                            lastEvent = "Invite accepted with custom rules.",
                            updatedAtEpochMs = nowMs(),
                        )
                    }
                }
                invite.status = InviteStatus.ACCEPTED
                acceptedInviteNames += invite.fromName.trim().lowercase()
            }
            "decline" -> invite.status = InviteStatus.DECLINED
            else -> return@synchronized InviteStatusResponse(ok = false, error = "Invalid action.")
        }

        return@synchronized InviteStatusResponse(
            ok = true,
            inviteId = invite.id,
            status = invite.status.name.lowercase(),
        )
    }

    fun heartbeat(token: String): GenericOkResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val slot = playerByToken(token)
        if (slot == null) {
            GenericOkResponse(ok = false, error = "Invalid session token.")
        } else {
            val players = match.players.toMutableMap()
            players[slot.player] = slot.copy(connected = true, lastSeenEpochMs = nowMs())
            match = match.copy(players = players, updatedAtEpochMs = nowMs())
            GenericOkResponse(ok = true)
        }
    }

    fun poll(token: String): PollResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val slot = playerByToken(token)
            ?: return@synchronized PollResponse(ok = false, error = "Invalid session token.")

        val canAct = when {
            match.status != MatchStatus.ACTIVE -> false
            isParallelMode() -> canPlayerActParallelLocked(slot.player)
            else -> slot.player == match.turnState.player
        }
        PollResponse(ok = true, canAct = canAct, match = match)
    }

    fun publishTurnIntent(
        token: String,
        x: Int,
        y: Int,
        rotDeg: Int,
        meepleFeatureId: String?,
        locked: Boolean,
    ): GenericOkResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val slot = playerByToken(token)
            ?: return@synchronized GenericOkResponse(ok = false, error = "Invalid session token.")
        if (match.status != MatchStatus.ACTIVE) {
            return@synchronized GenericOkResponse(ok = false, error = "Match is not active.")
        }
        if (isParallelMode()) {
            return@synchronized publishParallelIntentLocked(
                player = slot.player,
                x = x,
                y = y,
                rotDeg = rotDeg,
                meepleFeatureId = meepleFeatureId,
                locked = locked,
            )
        }

        if (slot.player != match.turnState.player) {
            return@synchronized GenericOkResponse(ok = false, error = "It is not your turn.")
        }
        val tileId = match.turnState.tileId
            ?: return@synchronized GenericOkResponse(ok = false, error = "No tile assigned.")
        val nextIntent = TurnIntentState(
            player = slot.player,
            tileId = tileId,
            x = x,
            y = y,
            rotDeg = normalizeRot(rotDeg),
            meepleFeatureId = meepleFeatureId?.trim().orEmpty().ifBlank { null },
            locked = locked,
            updatedAtEpochMs = nowMs(),
        )
        match = match.copy(
            turnState = match.turnState.copy(intent = nextIntent),
            updatedAtEpochMs = nowMs(),
        )
        GenericOkResponse(ok = true)
    }

    fun clearTurnIntent(token: String): GenericOkResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val slot = playerByToken(token)
            ?: return@synchronized GenericOkResponse(ok = false, error = "Invalid session token.")
        if (isParallelMode()) {
            val round = match.parallelRound ?: return@synchronized GenericOkResponse(ok = false, error = "Parallel round not initialized.")
            val playerState = round.players[slot.player] ?: return@synchronized GenericOkResponse(ok = false, error = "Player is not part of this round.")
            if (playerState.tileLocked) {
                return@synchronized GenericOkResponse(ok = false, error = "Placement already locked.")
            }
            val players = round.players.toMutableMap()
            players[slot.player] = playerState.copy(intent = null)
            val intents = match.parallelIntents.toMutableMap()
            intents.remove(slot.player)
            match = match.copy(
                parallelRound = round.copy(players = players),
                parallelIntents = intents,
                updatedAtEpochMs = nowMs(),
            )
            return@synchronized GenericOkResponse(ok = true)
        }
        clearIntentForPlayerLocked(slot.player)
        GenericOkResponse(ok = true)
    }

    fun submitTurn(
        token: String,
        x: Int,
        y: Int,
        rotDeg: Int,
        meepleFeatureId: String?,
    ): SubmitTurnResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val slot = playerByToken(token)
            ?: return@synchronized SubmitTurnResponse(ok = false, error = "Invalid session token.")
        if (match.status != MatchStatus.ACTIVE) {
            return@synchronized SubmitTurnResponse(ok = false, error = "Match is not active.")
        }

        if (isParallelMode()) {
            return@synchronized submitParallelMeepleLocked(
                player = slot.player,
                meepleFeatureId = meepleFeatureId,
            )
        }

        if (slot.player != match.turnState.player) {
            return@synchronized SubmitTurnResponse(ok = false, error = "It is not your turn.")
        }

        val tileId = match.turnState.tileId
            ?: return@synchronized SubmitTurnResponse(ok = false, error = "No tile assigned.")

        val rot = normalizeRot(rotDeg)
        if (rot !in listOf(0, 90, 180, 270)) {
            return@synchronized SubmitTurnResponse(ok = false, error = "Rotation must be 0/90/180/270.")
        }

        val legal = engine.canPlaceAt(match.board, tileId, rot, x, y)
        if (!legal.ok) {
            return@synchronized SubmitTurnResponse(ok = false, error = legal.reason)
        }

        val board = match.board.toMutableMap()
        val instId = match.instSeq
        var placedMeeples: List<MeeplePlacement> = emptyList()

        val selectedFeature = meepleFeatureId?.trim().orEmpty().ifBlank { null }
        if (selectedFeature != null) {
            val left = match.meeplesAvailable[slot.player] ?: 0
            if (left <= 0) {
                return@synchronized SubmitTurnResponse(ok = false, error = "No meeples remaining.")
            }

            val rotated = engine.rotateTile(tileId, rot)
            val feature = rotated.features.firstOrNull { it.id == selectedFeature }
                ?: return@synchronized SubmitTurnResponse(ok = false, error = "Invalid meeple feature id.")
            if (feature.type !in listOf("road", "city", "field", "cloister")) {
                return@synchronized SubmitTurnResponse(ok = false, error = "Meeple cannot be placed on that feature.")
            }

            val trialTile = PlacedTile(instId = instId, tileId = tileId, rotDeg = rot, meeples = emptyList())
            board[engine.keyXY(x, y)] = trialTile
            val analysis = engine.analyzeBoard(board)
            val nodeKey = "$instId:$selectedFeature"
            val group = analysis.groups[analysis.uf.find(nodeKey)]
            val occupied = group?.let {
                (it.meeplesByPlayer[1] ?: 0) + (it.meeplesByPlayer[2] ?: 0)
            } ?: 0
            if (occupied > 0) {
                board.remove(engine.keyXY(x, y))
                return@synchronized SubmitTurnResponse(
                    ok = false,
                    error = "Meeple rule: that connected feature is occupied.",
                )
            }
            placedMeeples = listOf(MeeplePlacement(player = slot.player, featureLocalId = selectedFeature))
        }

        board[engine.keyXY(x, y)] = PlacedTile(
            instId = instId,
            tileId = tileId,
            rotDeg = rot,
            meeples = placedMeeples,
        )

        val meeplesAvail = match.meeplesAvailable.toMutableMap()
        if (placedMeeples.isNotEmpty()) {
            meeplesAvail[slot.player] = (meeplesAvail[slot.player] ?: 0).coerceAtLeast(1) - 1
        }

        match = match.copy(
            board = board,
            instSeq = instId + 1,
            meeplesAvailable = meeplesAvail,
            turnState = match.turnState.copy(intent = null),
            updatedAtEpochMs = nowMs(),
        )

        recomputeAndScore()
        val completedMoves = match.turnState.turnIndex
        if (isRandomizedMode(match.rules) && completedMoves >= match.rules.randomizedMoveLimit) {
            finalizeMatch()
            return SubmitTurnResponse(ok = true, match = match)
        }

        val activePlayers = activePlayersLocked()
        val nextPlayer = if (activePlayers.size <= 1) {
            1
        } else {
            if (slot.player == 1) 2 else 1
        }
        match = match.copy(
            turnState = match.turnState.copy(
                player = nextPlayer,
                tileId = null,
                burnedTiles = emptyList(),
                turnIndex = match.turnState.turnIndex + 1,
                intent = null,
            ),
            lastEvent = "${slot.name} placed $tileId at ($x,$y) r$rot",
            updatedAtEpochMs = nowMs(),
        )

        drawPlaceableTileForTurn()

        SubmitTurnResponse(ok = true, match = match)
    }

    fun parallelPickTile(token: String, pickIndex: Int): SubmitTurnResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val slot = playerByToken(token)
            ?: return@synchronized SubmitTurnResponse(ok = false, error = "Invalid session token.")
        if (!isParallelMode() || match.status != MatchStatus.ACTIVE) {
            return@synchronized SubmitTurnResponse(ok = false, error = "Parallel mode is not active.")
        }
        val out = pickParallelTileLocked(player = slot.player, pickIndex = pickIndex)
        if (!out.ok) return@synchronized out
        return@synchronized SubmitTurnResponse(ok = true, match = match)
    }

    fun parallelResolveConflict(token: String, actionRaw: String): SubmitTurnResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val slot = playerByToken(token)
            ?: return@synchronized SubmitTurnResponse(ok = false, error = "Invalid session token.")
        if (!isParallelMode() || match.status != MatchStatus.ACTIVE) {
            return@synchronized SubmitTurnResponse(ok = false, error = "Parallel mode is not active.")
        }
        val out = resolveParallelConflictLocked(player = slot.player, actionRaw = actionRaw)
        if (!out.ok) return@synchronized out
        return@synchronized SubmitTurnResponse(ok = true, match = match)
    }

    fun removeToken(token: String): GenericOkResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val slot = playerByToken(token)
            ?: return@synchronized GenericOkResponse(ok = false, error = "Invalid session token.")
        if (slot.player == 2) {
            resetToWaitingLocked("${slot.name} left the match.")
            return@synchronized GenericOkResponse(ok = true)
        }
        val players = match.players.toMutableMap()
        players[slot.player] = slot.copy(connected = false, lastSeenEpochMs = nowMs())
        clearIntentForPlayerLocked(slot.player)
        match = match.copy(players = players, updatedAtEpochMs = nowMs())
        GenericOkResponse(ok = true)
    }

    private fun createWaitingMatch(hostDisplayName: String, token: String): MatchState {
        val now = nowMs()
        val startTile = engine.startTileId ?: error("Tileset has no start tile.")
        val rules = lobbyRules

        val remaining = engine.counts.toMutableMap()
        remaining[startTile] = ((remaining[startTile] ?: 1) - 1).coerceAtLeast(0)
        val drawQueue = normalizeDrawQueue(
            existing = emptyList(),
            rules = rules,
            remaining = remaining,
        )

        val board = mapOf(
            "0,0" to PlacedTile(
                instId = 1,
                tileId = startTile,
                rotDeg = 0,
                meeples = emptyList(),
            )
        )

        return MatchState(
            id = "m${nextMatchId++}",
            status = MatchStatus.WAITING,
            players = mapOf(
                1 to PlayerSlot(
                    player = 1,
                    name = hostDisplayName,
                    token = token,
                    connected = true,
                    lastSeenEpochMs = now,
                )
            ),
            board = board,
            instSeq = 2,
            remaining = remaining,
            score = mapOf(1 to 0, 2 to 0),
            scoredKeys = emptySet(),
            meeplesAvailable = mapOf(1 to rules.meeplesPerPlayer, 2 to rules.meeplesPerPlayer),
            rules = rules,
            turnState = TurnState(player = listOf(1, 2).random(random), tileId = null, turnIndex = 1),
            nextTiles = mapOf(1 to null, 2 to null),
            drawQueue = drawQueue,
            priorityTokenPlayer = if (rules.gameMode == GameMode.PARALLEL) listOf(1, 2).random(random) else null,
            parallelRound = null,
            parallelIntents = emptyMap(),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            lastEvent = "Waiting for opponent.",
        )
    }

    private fun isJoinSlotAvailableLocked(): Boolean {
        if (soloMode) return false
        val p2 = match.players[2]
        if (p2 == null) return true
        return match.status == MatchStatus.FINISHED || match.status == MatchStatus.ABORTED
    }

    private fun isInviteAcceptedForNameLocked(name: String): Boolean {
        val key = name.trim().lowercase()
        return acceptedInviteNames.contains(key)
    }

    private fun consumeInviteForNameLocked(name: String) {
        val key = name.trim().lowercase()
        acceptedInviteNames.remove(key)
        val acceptedInvite = invitesById.values.firstOrNull {
            it.fromName.trim().lowercase() == key && it.status == InviteStatus.ACCEPTED
        }
        if (acceptedInvite != null) {
            acceptedInvite.status = InviteStatus.USED
        }
    }

    private fun cleanupInvitesLocked() {
        cleanupDisconnectedOpponentLocked()

        val now = nowMs()
        for (invite in invitesById.values) {
            if (invite.status == InviteStatus.PENDING && now - invite.createdAtEpochMs > INVITE_TTL_MS) {
                invite.status = InviteStatus.EXPIRED
            }
        }

        val staleIds = invitesById.values
            .filter { it.status != InviteStatus.PENDING && now - it.createdAtEpochMs > INVITE_RETAIN_MS }
            .map { it.id }
        staleIds.forEach { invitesById.remove(it) }

        acceptedInviteNames.removeAll { accepted ->
            invitesById.values.none {
                it.fromName.trim().lowercase() == accepted && it.status == InviteStatus.ACCEPTED
            }
        }
    }

    private fun cleanupDisconnectedOpponentLocked() {
        val p2 = match.players[2] ?: return
        val now = nowMs()
        if (p2.connected) return
        if (now - p2.lastSeenEpochMs < DISCONNECTED_PLAYER_RELEASE_MS) return
        resetToWaitingLocked("${p2.name} disconnected. Waiting for opponent.")
    }

    private fun resetToWaitingLocked(event: String) {
        match = createWaitingMatch(hostName, hostToken).copy(
            lastEvent = event,
            updatedAtEpochMs = nowMs(),
        )
        soloMode = false
        invitesById.clear()
        acceptedInviteNames.clear()
    }

    private fun recomputeAndScore(reawardAll: Boolean = false) {
        val analysis = engine.analyzeBoard(match.board)
        var scoredKeys = if (reawardAll) mutableSetOf() else match.scoredKeys.toMutableSet()
        val score = if (reawardAll) mutableMapOf(1 to 0, 2 to 0) else match.score.toMutableMap()
        val scoredAreaHistory = if (reawardAll) {
            mutableMapOf<String, AreaScoreHistoryEntry>()
        } else {
            match.scoredAreaHistory.toMutableMap()
        }
        val scoredNow = mutableSetOf<String>()

        for (g in analysis.groups.values) {
            if (g.type == "field" || !g.complete) continue
            if (g.key in scoredKeys) continue

            val m1 = g.meeplesByPlayer[1] ?: 0
            val m2 = g.meeplesByPlayer[2] ?: 0
            val mx = maxOf(m1, m2)
            if (mx <= 0) {
                scoredKeys += g.key
                continue
            }

            val winners = mutableListOf<Int>()
            if (m1 == mx) winners += 1
            if (m2 == mx) winners += 2

            val pts = engine.scoreFeature(g, completed = true, rules = match.rules)
            var p1Award = 0
            var p2Award = 0
            for (winner in winners) {
                score[winner] = (score[winner] ?: 0) + pts
                if (winner == 1) p1Award += pts
                if (winner == 2) p2Award += pts
            }
            scoredAreaHistory[g.key] = AreaScoreHistoryEntry(
                key = g.key,
                type = g.type,
                p1 = p1Award,
                p2 = p2Award,
                closed = true,
            )

            scoredKeys += g.key
            scoredNow += g.key
        }

        if (scoredNow.isNotEmpty()) {
            val board = match.board.toMutableMap()
            val meeplesAvail = match.meeplesAvailable.toMutableMap()
            for ((cellKey, inst) in board) {
                val kept = mutableListOf<MeeplePlacement>()
                for (meeple in inst.meeples) {
                    val nodeKey = "${inst.instId}:${meeple.featureLocalId}"
                    val meta = analysis.nodeMeta[nodeKey]
                    if (meta == null) {
                        kept += meeple
                        continue
                    }
                    val gid = analysis.uf.find(nodeKey)
                    val group = analysis.groups[gid]
                    if (group == null || group.type == "field" || group.key !in scoredNow) {
                        kept += meeple
                        continue
                    }
                    val player = meeple.player
                    if (player == 1 || player == 2) {
                        meeplesAvail[player] = ((meeplesAvail[player] ?: 0) + 1)
                            .coerceAtMost(match.rules.meeplesPerPlayer)
                    }
                }
                board[cellKey] = inst.copy(meeples = kept)
            }
            match = match.copy(board = board, meeplesAvailable = meeplesAvail)
        }

        match = match.copy(
            score = score,
            scoredKeys = scoredKeys,
            scoredAreaHistory = scoredAreaHistory,
            updatedAtEpochMs = nowMs(),
        )
    }

    private fun finalizeMatch() {
        if (match.status != MatchStatus.ACTIVE) return

        val analysis = engine.analyzeBoard(match.board)
        val score = match.score.toMutableMap()

        for (g in analysis.groups.values) {
            val winners = engine.winnersOfGroup(g)
            if (winners.isEmpty()) continue
            if (g.type != "field" && g.complete && g.key in match.scoredKeys) continue
            val pts = engine.scoreEndNowValue(g, match.rules)
            if (pts <= 0) continue
            for (p in winners) {
                score[p] = (score[p] ?: 0) + pts
            }
        }

        val p1 = score[1] ?: 0
        val p2 = score[2] ?: 0
        val event = when {
            p1 > p2 -> "Match finished: ${match.players[1]?.name ?: "P1"} won $p1-$p2"
            p2 > p1 -> "Match finished: ${match.players[2]?.name ?: "P2"} won $p2-$p1"
            else -> "Match finished: draw $p1-$p2"
        }

        match = match.copy(
            status = MatchStatus.FINISHED,
            score = score,
            turnState = match.turnState.copy(tileId = null, burnedTiles = emptyList(), intent = null),
            nextTiles = mapOf(1 to null, 2 to null),
            parallelIntents = emptyMap(),
            lastEvent = event,
            updatedAtEpochMs = nowMs(),
        )
    }

    private fun ensureNextTiles() {
        if (match.status != MatchStatus.ACTIVE || isParallelMode()) return
        val nextTiles = match.nextTiles.toMutableMap()
        val remaining = match.remaining.toMutableMap()
        val drawQueue = ArrayDeque(
            normalizeDrawQueue(
                existing = match.drawQueue,
                rules = match.rules,
                remaining = remaining,
            )
        )
        val turnPlayer = match.turnState.player
        val randomizedMode = isRandomizedMode(match.rules)
        for (player in activePlayersLocked()) {
            if (player == turnPlayer) continue
            if (nextTiles[player] != null) continue
            val tile = drawTileFromQueue(
                randomizedMode = randomizedMode,
                remaining = remaining,
                drawQueue = drawQueue,
            )
            if (tile != null) {
                nextTiles[player] = tile
            }
        }
        match = match.copy(
            nextTiles = nextTiles,
            remaining = remaining,
            drawQueue = drawQueue.toList(),
            updatedAtEpochMs = nowMs(),
        )
    }

    private fun drawPlaceableTileForTurn() {
        if (isParallelMode()) return
        val burned = mutableListOf<String>()
        val turnPlayer = match.turnState.player
        val nextTiles = match.nextTiles.toMutableMap()
        val remaining = match.remaining.toMutableMap()
        val drawQueue = ArrayDeque(
            normalizeDrawQueue(
                existing = match.drawQueue,
                rules = match.rules,
                remaining = remaining,
            )
        )
        val randomized = isRandomizedMode(match.rules)

        if (randomized && !hasAnyPlaceableTileInBasePool(match.board)) {
            match = match.copy(
                remaining = remaining,
                nextTiles = nextTiles,
                drawQueue = drawQueue.toList(),
                turnState = match.turnState.copy(tileId = null, burnedTiles = burned, intent = null),
                updatedAtEpochMs = nowMs(),
            )
            finalizeMatch()
            return
        }

        var attempts = 0

        while (true) {
            val tileId = nextTiles[turnPlayer].also {
                if (it != null) nextTiles[turnPlayer] = null
            } ?: drawTileFromQueue(
                randomizedMode = randomized,
                remaining = remaining,
                drawQueue = drawQueue,
            )

            attempts += 1
            if (randomized && attempts > MAX_RANDOM_DRAW_ATTEMPTS) {
                match = match.copy(
                    remaining = remaining,
                    nextTiles = nextTiles,
                    drawQueue = drawQueue.toList(),
                    turnState = match.turnState.copy(tileId = null, burnedTiles = burned, intent = null),
                    updatedAtEpochMs = nowMs(),
                )
                finalizeMatch()
                return
            }

            if (tileId == null) {
                match = match.copy(
                    remaining = remaining,
                    nextTiles = nextTiles,
                    drawQueue = drawQueue.toList(),
                    turnState = match.turnState.copy(tileId = null, burnedTiles = burned, intent = null),
                    updatedAtEpochMs = nowMs(),
                )
                finalizeMatch()
                return
            }

            if (engine.hasAnyPlacement(match.board, tileId)) {
                match = match.copy(
                    remaining = remaining,
                    nextTiles = nextTiles,
                    drawQueue = drawQueue.toList(),
                    turnState = match.turnState.copy(tileId = tileId, burnedTiles = burned, intent = null),
                    updatedAtEpochMs = nowMs(),
                )
                ensureNextTiles()
                return
            }

            burned += tileId
        }
    }

    private fun drawTileFromQueue(
        randomizedMode: Boolean,
        remaining: MutableMap<String, Int>,
        drawQueue: ArrayDeque<String>,
    ): String? {
        if (randomizedMode) {
            refillRandomizedDrawQueue(drawQueue, RANDOM_QUEUE_REFILL_MIN)
            val tile = drawQueue.pollFirst() ?: return null
            refillRandomizedDrawQueue(drawQueue, RANDOM_QUEUE_REFILL_MIN)
            return tile
        }

        if (drawQueue.isEmpty()) {
            drawQueue.addAll(buildShuffledQueueFromRemaining(remaining))
        }

        while (drawQueue.isNotEmpty()) {
            val tileId = drawQueue.removeFirst()
            val count = (remaining[tileId] ?: 0).coerceAtLeast(0)
            if (count <= 0) continue
            remaining[tileId] = (count - 1).coerceAtLeast(0)
            return tileId
        }
        return null
    }

    private fun normalizeDrawQueue(
        existing: List<String>,
        rules: GameRules,
        remaining: Map<String, Int>,
    ): List<String> {
        if (isRandomizedMode(rules) || rules.gameMode == GameMode.PARALLEL) {
            val queue = ArrayDeque(existing.filter { it.isNotBlank() })
            refillRandomizedDrawQueue(queue, RANDOM_QUEUE_TARGET)
            return queue.toList()
        }

        val totalRemaining = remaining.values.sumOf { it.coerceAtLeast(0) }
        if (totalRemaining <= 0) return emptyList()
        if (existing.isNotEmpty()) return existing
        return buildShuffledQueueFromRemaining(remaining)
    }

    private fun buildShuffledQueueFromRemaining(remaining: Map<String, Int>): List<String> {
        val deck = mutableListOf<String>()
        for ((tileId, rawCount) in remaining.toSortedMap()) {
            val count = rawCount.coerceAtLeast(0)
            repeat(count) { deck += tileId }
        }
        deck.shuffle(random)
        return deck
    }

    private fun refillRandomizedDrawQueue(queue: ArrayDeque<String>, targetSize: Int) {
        while (queue.size < targetSize) {
            val tileId = drawWeightedTileFromBasePool() ?: break
            queue.addLast(tileId)
        }
    }

    private fun drawWeightedTileFromBasePool(): String? {
        val total = engine.counts.values.sumOf { it.coerceAtLeast(0) }
        if (total <= 0) return null

        val r = random.nextInt(1, total + 1)
        var acc = 0
        for ((tileId, rawCount) in engine.counts.toSortedMap()) {
            val cnt = rawCount.coerceAtLeast(0)
            if (cnt <= 0) continue
            acc += cnt
            if (r <= acc) {
                return tileId
            }
        }
        return null
    }

    private fun hasAnyPlaceableTileInBasePool(board: Map<String, PlacedTile>): Boolean {
        return engine.counts.entries
            .filter { it.value > 0 }
            .any { (tileId, _) -> engine.hasAnyPlacement(board, tileId) }
    }

    private fun normalizeRules(input: GameRules): GameRules {
        val mode = when {
            input.gameMode == GameMode.PARALLEL -> GameMode.PARALLEL
            input.gameMode == GameMode.RANDOM || input.randomizedMode -> GameMode.RANDOM
            else -> GameMode.STANDARD
        }
        val meeples = input.meeplesPerPlayer.coerceIn(1, 20)
        val randomLimit = input.randomizedMoveLimit.coerceIn(1, 500)
        val previewCount = input.previewCount.coerceIn(1, 20)
        val parallelSelectionSize = input.parallelSelectionSize.coerceIn(1, 6)
        val parallelMoveLimit = input.parallelMoveLimit.coerceIn(1, 500)
        return input.copy(
            gameMode = mode,
            meeplesPerPlayer = meeples,
            randomizedMode = mode == GameMode.RANDOM,
            randomizedMoveLimit = randomLimit,
            previewCount = previewCount,
            parallelSelectionSize = parallelSelectionSize,
            parallelMoveLimit = parallelMoveLimit,
        )
    }

    private fun normalizedMeeplesByRules(
        original: Map<Int, Int>,
        maxMeeples: Int,
    ): Map<Int, Int> {
        val cap = maxMeeples.coerceIn(1, 20)
        return mapOf(
            1 to (original[1] ?: cap).coerceIn(0, cap),
            2 to (original[2] ?: cap).coerceIn(0, cap),
        )
    }

    private fun activePlayersLocked(): List<Int> {
        if (soloMode || match.players[2] == null) return listOf(1)
        return listOf(1, 2)
    }

    private fun playerByToken(token: String): PlayerSlot? {
        return match.players.values.firstOrNull { it.token == token }
    }

    private fun clearIntentForPlayerLocked(player: Int) {
        if (isParallelMode()) {
            val round = match.parallelRound ?: return
            val playerState = round.players[player] ?: return
            if (playerState.tileLocked) return
            val players = round.players.toMutableMap()
            players[player] = playerState.copy(intent = null)
            val intents = match.parallelIntents.toMutableMap()
            intents.remove(player)
            match = match.copy(
                parallelRound = round.copy(players = players),
                parallelIntents = intents,
                updatedAtEpochMs = nowMs(),
            )
            return
        }
        val intent = match.turnState.intent ?: return
        if (intent.player != player) return
        match = match.copy(
            turnState = match.turnState.copy(intent = null),
            updatedAtEpochMs = nowMs(),
        )
    }

    private fun normalizeParallelRound(
        saved: ParallelRoundState?,
        restored: MatchState,
        rules: GameRules,
    ): ParallelRoundState? {
        if (restored.status != MatchStatus.ACTIVE) return null
        val activePlayers = if (restored.players[2] == null) listOf(1) else listOf(1, 2)
        val seed = saved ?: ParallelRoundState(
            roundIndex = 1,
            moveLimit = rules.parallelMoveLimit,
            selection = emptyList(),
            phase = ParallelPhase.PICK,
            players = emptyMap(),
            conflict = null,
            placementDoneAtEpochMs = 0L,
        )
        val players = activePlayers.associateWith { player ->
            seed.players[player] ?: ParallelPlayerRoundState()
        }
        val selection = seed.selection
            .filter { it.isNotBlank() }
            .let { current ->
                if (current.size >= rules.parallelSelectionSize) {
                    current.take(rules.parallelSelectionSize)
                } else {
                    (
                        current +
                            buildParallelSelectionForBoard(
                                board = restored.board,
                                size = rules.parallelSelectionSize - current.size,
                            )
                    ).take(rules.parallelSelectionSize)
                }
            }
        return seed.copy(
            moveLimit = rules.parallelMoveLimit,
            selection = selection,
            players = players,
        )
    }

    private fun canPlayerActParallelLocked(player: Int): Boolean {
        val round = match.parallelRound ?: return false
        val playerState = round.players[player] ?: return false
        if (player !in activePlayersLocked()) return false
        return when (round.phase) {
            ParallelPhase.PICK -> playerState.pickedTileId == null || !playerState.tileLocked
            ParallelPhase.PLACE -> {
                if (round.conflict != null) {
                    false
                } else {
                    playerState.pickedTileId == null || !playerState.tileLocked
                }
            }
            ParallelPhase.RESOLVE -> round.conflict?.tokenHolder == player
            ParallelPhase.MEEPLE -> !playerState.meepleConfirmed
        }
    }

    private fun startParallelRoundLocked(roundIndex: Int) {
        val rules = match.rules
        val moveLimit = rules.parallelMoveLimit
        if (roundIndex > moveLimit) {
            finalizeMatch()
            return
        }
        if (!hasAnyPlaceableTileInBasePool(match.board)) {
            finalizeMatch()
            return
        }
        val activePlayers = activePlayersLocked()
        val selection = buildParallelSelectionLocked(rules.parallelSelectionSize)
        val players = activePlayers.associateWith { ParallelPlayerRoundState() }
        val tokenHolder = match.priorityTokenPlayer ?: activePlayers.firstOrNull() ?: 1
        match = match.copy(
            priorityTokenPlayer = tokenHolder,
            parallelRound = ParallelRoundState(
                roundIndex = roundIndex,
                moveLimit = moveLimit,
                selection = selection,
                phase = ParallelPhase.PICK,
                players = players,
                conflict = null,
                placementDoneAtEpochMs = 0L,
            ),
            parallelIntents = emptyMap(),
            turnState = match.turnState.copy(
                player = tokenHolder,
                tileId = null,
                burnedTiles = emptyList(),
                turnIndex = roundIndex,
                intent = null,
            ),
            nextTiles = mapOf(1 to null, 2 to null),
            lastEvent = "Round $roundIndex: pick a tile.",
            updatedAtEpochMs = nowMs(),
        )
    }

    private fun buildParallelSelectionLocked(size: Int): List<String> {
        return buildParallelSelectionForBoard(board = match.board, size = size)
    }

    private fun buildParallelSelectionForBoard(
        board: Map<String, PlacedTile>,
        size: Int,
    ): List<String> {
        val target = size.coerceIn(1, 6)
        val out = mutableListOf<String>()
        var attempts = 0
        val maxAttempts = MAX_RANDOM_DRAW_ATTEMPTS * target
        while (out.size < target && attempts < maxAttempts) {
            attempts++
            val tile = drawWeightedTileFromBasePool() ?: break
            if (!engine.hasAnyPlacement(board, tile)) continue
            out += tile
        }
        if (out.isEmpty()) {
            val fallback = engine.counts.keys.sorted().firstOrNull() ?: return emptyList()
            return List(target) { fallback }
        }
        while (out.size < target) {
            out += out.random(random)
        }
        return out
    }

    private fun pickParallelTileLocked(player: Int, pickIndex: Int): SubmitTurnResponse {
        val round = match.parallelRound ?: return SubmitTurnResponse(ok = false, error = "Parallel round not initialized.")
        if (round.phase !in setOf(ParallelPhase.PICK, ParallelPhase.PLACE)) {
            return SubmitTurnResponse(ok = false, error = "Tile pick is not available in this phase.")
        }
        val playerState = round.players[player]
            ?: return SubmitTurnResponse(ok = false, error = "Player is not part of this round.")
        if (playerState.tileLocked) {
            return SubmitTurnResponse(ok = false, error = "Tile already locked.")
        }
        val selection = round.selection
        if (pickIndex !in selection.indices) {
            return SubmitTurnResponse(ok = false, error = "Invalid tile index.")
        }
        val tileId = selection[pickIndex]
        val firstIntent = firstLegalParallelIntentLocked(player = player, tileId = tileId)
            ?: return SubmitTurnResponse(ok = false, error = "Selected tile has no legal placement.")

        val players = round.players.toMutableMap()
        players[player] = playerState.copy(
            pickIndex = pickIndex,
            pickedTileId = tileId,
            intent = firstIntent,
            tileLocked = false,
            committedCellKey = null,
            committedRotDeg = null,
            committedInstId = null,
            meepleFeatureId = null,
            meepleConfirmed = false,
        )
        val intents = match.parallelIntents.toMutableMap()
        intents[player] = firstIntent

        var nextRound = round.copy(
            players = players,
            conflict = null,
        )
        if (nextRound.phase == ParallelPhase.PICK) {
            val allPicked = activePlayersLocked().all { p -> !players[p]?.pickedTileId.isNullOrBlank() }
            if (allPicked) {
                nextRound = nextRound.copy(phase = ParallelPhase.PLACE)
            }
        }
        match = match.copy(
            parallelRound = nextRound,
            parallelIntents = intents,
            lastEvent = if (nextRound.phase == ParallelPhase.PLACE) {
                "All players picked. Place and long-press to lock."
            } else {
                "Tile picked: $tileId."
            },
            updatedAtEpochMs = nowMs(),
        )
        if (nextRound.phase == ParallelPhase.PLACE) {
            evaluateParallelPlacementLocked()
        }
        return SubmitTurnResponse(ok = true, match = match)
    }

    private fun firstLegalParallelIntentLocked(player: Int, tileId: String): TurnIntentState? {
        val frontier = engine.buildFrontier(match.board)
            .sortedWith(
                compareBy<Pair<Int, Int>> { kotlin.math.abs(it.first) + kotlin.math.abs(it.second) }
                    .thenBy { kotlin.math.abs(it.second) }
                    .thenBy { kotlin.math.abs(it.first) }
                    .thenBy { it.second }
                    .thenBy { it.first },
            )
        for ((x, y) in frontier) {
            for (rot in listOf(0, 90, 180, 270)) {
                val legal = engine.canPlaceAt(match.board, tileId, rot, x, y)
                if (legal.ok) {
                    return TurnIntentState(
                        player = player,
                        tileId = tileId,
                        x = x,
                        y = y,
                        rotDeg = rot,
                        meepleFeatureId = null,
                        locked = false,
                        updatedAtEpochMs = nowMs(),
                    )
                }
            }
        }
        return null
    }

    private fun publishParallelIntentLocked(
        player: Int,
        x: Int,
        y: Int,
        rotDeg: Int,
        meepleFeatureId: String?,
        locked: Boolean,
    ): GenericOkResponse {
        val round = match.parallelRound ?: return GenericOkResponse(ok = false, error = "Parallel round not initialized.")
        if (round.phase !in setOf(ParallelPhase.PICK, ParallelPhase.PLACE)) {
            return GenericOkResponse(ok = false, error = "Placement phase is not active.")
        }
        val playerState = round.players[player]
            ?: return GenericOkResponse(ok = false, error = "Player is not part of this round.")
        if (playerState.tileLocked) {
            return GenericOkResponse(ok = false, error = "Placement already locked.")
        }
        val tileId = playerState.pickedTileId
            ?: return GenericOkResponse(ok = false, error = "Pick a tile first.")
        val normalizedRot = normalizeRot(rotDeg)
        val legal = engine.canPlaceAt(match.board, tileId, normalizedRot, x, y)
        if (locked && !legal.ok) {
            return GenericOkResponse(ok = false, error = legal.reason)
        }
        val intent = TurnIntentState(
            player = player,
            tileId = tileId,
            x = x,
            y = y,
            rotDeg = normalizedRot,
            meepleFeatureId = meepleFeatureId?.trim().orEmpty().ifBlank { null },
            locked = locked,
            updatedAtEpochMs = nowMs(),
        )
        val players = round.players.toMutableMap()
        players[player] = playerState.copy(
            intent = intent,
            tileLocked = locked,
        )
        val intents = match.parallelIntents.toMutableMap()
        intents[player] = intent
        match = match.copy(
            parallelRound = round.copy(players = players),
            parallelIntents = intents,
            updatedAtEpochMs = nowMs(),
        )
        if (locked) {
            evaluateParallelPlacementLocked()
        }
        return GenericOkResponse(ok = true)
    }

    private fun evaluateParallelPlacementLocked() {
        val round = match.parallelRound ?: return
        if (round.phase != ParallelPhase.PLACE) return
        val activePlayers = activePlayersLocked()
        if (activePlayers.any { player ->
                val state = round.players[player]
                state?.tileLocked != true || state.intent == null
            }
        ) {
            return
        }
        val intents = activePlayers.associateWith { player ->
            round.players[player]?.intent ?: return
        }
        val conflict = detectParallelConflictLocked(intents)
        if (conflict != null) {
            val holder = match.priorityTokenPlayer
                ?.takeIf { it in activePlayers }
                ?: activePlayers.first()
            val fixed = activePlayers.firstOrNull { it != holder } ?: holder
            val conflictState = ParallelConflictState(
                type = conflict,
                tokenHolder = holder,
                fixedPlayer = fixed,
                replacerPlayer = holder,
                message = if (conflict == ParallelConflictType.SAME_CELL) {
                    "Conflict: both tiles target the same cell."
                } else {
                    "Conflict: tile edges mismatch between both placements."
                },
            )
            match = match.copy(
                parallelRound = round.copy(
                    phase = ParallelPhase.RESOLVE,
                    conflict = conflictState,
                ),
                lastEvent = "${match.players[holder]?.name ?: "Token holder"}: choose Burn Token or Retreat.",
                updatedAtEpochMs = nowMs(),
            )
            return
        }
        commitParallelPlacementsLocked()
    }

    private fun detectParallelConflictLocked(
        intents: Map<Int, TurnIntentState>,
    ): ParallelConflictType? {
        if (intents.size <= 1) return null
        val orderedPlayers = intents.keys.sorted()
        for (i in orderedPlayers.indices) {
            for (j in i + 1 until orderedPlayers.size) {
                val a = intents[orderedPlayers[i]] ?: continue
                val b = intents[orderedPlayers[j]] ?: continue
                if (a.x == b.x && a.y == b.y) {
                    return ParallelConflictType.SAME_CELL
                }
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (kotlin.math.abs(dx) + kotlin.math.abs(dy) != 1) continue
                val edgeA = when {
                    dx == 1 -> "E"
                    dx == -1 -> "W"
                    dy == 1 -> "S"
                    else -> "N"
                }
                val edgeB = when (edgeA) {
                    "N" -> "S"
                    "S" -> "N"
                    "E" -> "W"
                    else -> "E"
                }
                val tileA = engine.rotateTile(a.tileId, a.rotDeg)
                val tileB = engine.rotateTile(b.tileId, b.rotDeg)
                val primaryA = tileA.edges[edgeA]?.primary
                val primaryB = tileB.edges[edgeB]?.primary
                if (primaryA != primaryB) {
                    return ParallelConflictType.EDGE_MISMATCH
                }
            }
        }
        return null
    }

    private fun resolveParallelConflictLocked(player: Int, actionRaw: String): SubmitTurnResponse {
        val round = match.parallelRound ?: return SubmitTurnResponse(ok = false, error = "Parallel round not initialized.")
        if (round.phase != ParallelPhase.RESOLVE) {
            return SubmitTurnResponse(ok = false, error = "No conflict to resolve.")
        }
        val conflict = round.conflict ?: return SubmitTurnResponse(ok = false, error = "No conflict to resolve.")
        if (conflict.tokenHolder != player) {
            return SubmitTurnResponse(ok = false, error = "Only priority token holder can resolve conflict.")
        }
        val activePlayers = activePlayersLocked()
        val other = activePlayers.firstOrNull { it != player } ?: player
        val action = actionRaw.trim().lowercase()
        val nextHolder: Int
        val replacer: Int
        val fixed: Int
        val event: String
        when (action) {
            "retreat", "replace" -> {
                nextHolder = player
                replacer = player
                fixed = other
                event = "${match.players[player]?.name ?: "Player"} kept priority and must place in another place."
            }
            "burn", "burn_token", "burn-token" -> {
                nextHolder = other
                replacer = other
                fixed = player
                event = "${match.players[player]?.name ?: "Player"} burned priority. ${match.players[other]?.name ?: "Opponent"} must place in another place."
            }
            else -> return SubmitTurnResponse(ok = false, error = "Invalid action. Use retreat or burn.")
        }
        val players = round.players.toMutableMap()
        val replacerState = players[replacer] ?: ParallelPlayerRoundState()
        val unlockedIntent = replacerState.intent?.copy(
            locked = false,
            updatedAtEpochMs = nowMs(),
        )
        players[replacer] = replacerState.copy(
            intent = unlockedIntent,
            tileLocked = false,
            committedCellKey = null,
            committedRotDeg = null,
            committedInstId = null,
            meepleFeatureId = null,
            meepleConfirmed = false,
        )
        val intents = match.parallelIntents.toMutableMap()
        if (unlockedIntent != null) {
            intents[replacer] = unlockedIntent
        } else {
            intents.remove(replacer)
        }
        val fixedState = players[fixed]
        if (fixedState != null && fixedState.intent != null) {
            intents[fixed] = fixedState.intent
        }
        match = match.copy(
            priorityTokenPlayer = nextHolder,
            parallelRound = round.copy(
                phase = ParallelPhase.PLACE,
                conflict = null,
                players = players,
            ),
            parallelIntents = intents,
            lastEvent = event,
            updatedAtEpochMs = nowMs(),
        )
        return SubmitTurnResponse(ok = true, match = match)
    }

    private fun commitParallelPlacementsLocked() {
        val round = match.parallelRound ?: return
        val activePlayers = activePlayersLocked()
        val players = round.players.toMutableMap()
        val board = match.board.toMutableMap()
        var instSeq = match.instSeq
        for (player in activePlayers.sorted()) {
            val state = players[player] ?: continue
            val intent = state.intent ?: continue
            val cellKey = engine.keyXY(intent.x, intent.y)
            if (board.containsKey(cellKey)) {
                return
            }
            board[cellKey] = PlacedTile(
                instId = instSeq,
                tileId = intent.tileId,
                rotDeg = intent.rotDeg,
                meeples = emptyList(),
            )
            players[player] = state.copy(
                committedCellKey = cellKey,
                committedRotDeg = intent.rotDeg,
                committedInstId = instSeq,
                meepleFeatureId = null,
                meepleConfirmed = false,
            )
            instSeq += 1
        }
        match = match.copy(
            board = board,
            instSeq = instSeq,
            parallelRound = round.copy(
                phase = ParallelPhase.MEEPLE,
                players = players,
                conflict = null,
                placementDoneAtEpochMs = nowMs(),
            ),
            parallelIntents = emptyMap(),
            turnState = match.turnState.copy(intent = null),
            lastEvent = "Placement done.",
            updatedAtEpochMs = nowMs(),
        )
    }

    private fun submitParallelMeepleLocked(
        player: Int,
        meepleFeatureId: String?,
    ): SubmitTurnResponse {
        val round = match.parallelRound ?: return SubmitTurnResponse(ok = false, error = "Parallel round not initialized.")
        if (round.phase != ParallelPhase.MEEPLE) {
            return SubmitTurnResponse(ok = false, error = "Meeple phase is not active.")
        }
        val playerState = round.players[player]
            ?: return SubmitTurnResponse(ok = false, error = "Player is not part of this round.")
        if (playerState.meepleConfirmed) {
            return SubmitTurnResponse(ok = false, error = "Meeple already confirmed.")
        }
        val committedCellKey = playerState.committedCellKey
            ?: return SubmitTurnResponse(ok = false, error = "Tile placement is not committed.")
        val committedRot = playerState.committedRotDeg
            ?: return SubmitTurnResponse(ok = false, error = "Tile placement is not committed.")
        val committedInst = playerState.committedInstId
            ?: return SubmitTurnResponse(ok = false, error = "Tile placement is not committed.")
        val inst = match.board[committedCellKey]
            ?: return SubmitTurnResponse(ok = false, error = "Committed tile not found.")
        val selectedFeature = meepleFeatureId?.trim().orEmpty().ifBlank { null }
        if (selectedFeature != null) {
            val left = match.meeplesAvailable[player] ?: 0
            if (left <= 0) {
                return SubmitTurnResponse(ok = false, error = "No meeples remaining.")
            }
            val rotated = engine.rotateTile(inst.tileId, committedRot)
            val feature = rotated.features.firstOrNull { it.id == selectedFeature }
                ?: return SubmitTurnResponse(ok = false, error = "Invalid meeple feature id.")
            if (feature.type !in listOf("road", "city", "field", "cloister")) {
                return SubmitTurnResponse(ok = false, error = "Meeple cannot be placed on that feature.")
            }
            val analysis = engine.analyzeBoard(match.board)
            val nodeKey = "$committedInst:$selectedFeature"
            if (!analysis.nodeMeta.containsKey(nodeKey)) {
                return SubmitTurnResponse(ok = false, error = "Invalid feature selection.")
            }
            val group = analysis.groups[analysis.uf.find(nodeKey)]
            val occupied = group?.let {
                (it.meeplesByPlayer[1] ?: 0) + (it.meeplesByPlayer[2] ?: 0)
            } ?: 0
            if (occupied > 0) {
                return SubmitTurnResponse(ok = false, error = "Meeple rule: that connected feature is occupied.")
            }
        }
        val players = round.players.toMutableMap()
        players[player] = playerState.copy(
            meepleFeatureId = selectedFeature,
            meepleConfirmed = true,
        )
        match = match.copy(
            parallelRound = round.copy(players = players),
            updatedAtEpochMs = nowMs(),
        )

        val allConfirmed = activePlayersLocked().all { p -> players[p]?.meepleConfirmed == true }
        if (allConfirmed) {
            applyParallelMeeplesAndAdvanceLocked()
        } else {
            match = match.copy(
                lastEvent = "${match.players[player]?.name ?: "Player"} confirmed meeple.",
                updatedAtEpochMs = nowMs(),
            )
        }
        return SubmitTurnResponse(ok = true, match = match)
    }

    private fun applyParallelMeeplesAndAdvanceLocked() {
        val round = match.parallelRound ?: return
        val activePlayers = activePlayersLocked()
        val players = round.players
        val board = match.board.toMutableMap()
        val meeplesAvailable = match.meeplesAvailable.toMutableMap()
        for (player in activePlayers.sorted()) {
            val state = players[player] ?: continue
            val cellKey = state.committedCellKey ?: continue
            val selected = state.meepleFeatureId ?: continue
            val inst = board[cellKey] ?: continue
            board[cellKey] = inst.copy(
                meeples = inst.meeples + MeeplePlacement(
                    player = player,
                    featureLocalId = selected,
                ),
            )
            meeplesAvailable[player] = ((meeplesAvailable[player] ?: 0) - 1).coerceAtLeast(0)
        }
        match = match.copy(
            board = board,
            meeplesAvailable = meeplesAvailable,
            updatedAtEpochMs = nowMs(),
        )
        recomputeAndScore()

        if (round.roundIndex >= round.moveLimit) {
            finalizeMatch()
            return
        }
        startParallelRoundLocked(round.roundIndex + 1)
    }

    private fun normalizeRot(raw: Int): Int {
        val m = ((raw % 360) + 360) % 360
        return when (m) {
            0, 90, 180, 270 -> m
            else -> ((m / 90) * 90) % 360
        }
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    private fun extractNextMatchId(matchId: String): Int {
        val n = matchId.removePrefix("m").toIntOrNull() ?: 1
        return n + 1
    }

    companion object {
        private const val INVITE_TTL_MS = 120_000L
        private const val INVITE_RETAIN_MS = 300_000L
        private const val DISCONNECTED_PLAYER_RELEASE_MS = 90_000L
        private const val MAX_RANDOM_DRAW_ATTEMPTS = 220
        private const val RANDOM_QUEUE_TARGET = 96
        private const val RANDOM_QUEUE_REFILL_MIN = 48
    }
}
