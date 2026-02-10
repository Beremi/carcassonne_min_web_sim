package com.carcassonne.lan.network

import com.carcassonne.lan.core.CarcassonneEngine
import com.carcassonne.lan.data.NameGenerator
import com.carcassonne.lan.model.GenericOkResponse
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
import com.carcassonne.lan.model.PlayerSlot
import com.carcassonne.lan.model.PlayerSummary
import com.carcassonne.lan.model.PollResponse
import com.carcassonne.lan.model.SubmitTurnResponse
import com.carcassonne.lan.model.TurnIntentState
import com.carcassonne.lan.model.TurnState
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
    )

    private val lock = Any()
    private var nextMatchId = 1
    private var nextInviteId = 1
    private var hostName: String = NameGenerator.generate(random)
    private var hostToken: String = newToken()
    private var match: MatchState = createWaitingMatch(hostName, hostToken)
    private val invitesById = linkedMapOf<String, InviteRecord>()
    private val acceptedInviteNames = linkedSetOf<String>()

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
            match = restored
            nextMatchId = extractNextMatchId(saved.id)
            val p1 = saved.players[1]
            if (p1 != null) {
                hostName = p1.name
                hostToken = p1.token
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
            match = match.copy(players = players, updatedAtEpochMs = nowMs())
            return JoinResponse(ok = true, token = freshToken, player = existingByName.player, match = match)
        }

        var p2 = match.players[2]
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
        consumeInviteForNameLocked(requested)
        ensureNextTiles()
        drawPlaceableTileForTurn()
        match = match.copy(
            lastEvent = "Match started: ${players[1]?.name ?: "Host"} vs $requested",
            updatedAtEpochMs = nowMs(),
        )
        JoinResponse(ok = true, token = joinToken, player = 2, match = match)
    }

    fun sendInvite(fromNameRaw: String): InviteSendResponse = synchronized(lock) {
        cleanupInvitesLocked()
        val fromName = NameGenerator.ensureNumericSuffix(fromNameRaw, random)

        if (!isJoinSlotAvailableLocked()) {
            return InviteSendResponse(ok = false, error = "Unavailable")
        }

        val existing = invitesById.values.firstOrNull {
            it.status == InviteStatus.PENDING && it.fromName.equals(fromName, ignoreCase = true)
        }
        if (existing != null) {
            return InviteSendResponse(ok = true, inviteId = existing.id)
        }

        val id = "inv${nextInviteId++}"
        invitesById[id] = InviteRecord(
            id = id,
            fromName = fromName,
            createdAtEpochMs = nowMs(),
            status = InviteStatus.PENDING,
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

        val canAct = match.status == MatchStatus.ACTIVE && slot.player == match.turnState.player
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

        val nextPlayer = if (slot.player == 1) 2 else 1
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

        val remaining = engine.counts.toMutableMap()
        remaining[startTile] = ((remaining[startTile] ?: 1) - 1).coerceAtLeast(0)

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
            meeplesAvailable = mapOf(1 to 7, 2 to 7),
            turnState = TurnState(player = listOf(1, 2).random(random), tileId = null, turnIndex = 1),
            nextTiles = mapOf(1 to null, 2 to null),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            lastEvent = "Waiting for opponent.",
        )
    }

    private fun isJoinSlotAvailableLocked(): Boolean {
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
        invitesById.clear()
        acceptedInviteNames.clear()
    }

    private fun recomputeAndScore(reawardAll: Boolean = false) {
        val analysis = engine.analyzeBoard(match.board)
        var scoredKeys = if (reawardAll) mutableSetOf() else match.scoredKeys.toMutableSet()
        val score = if (reawardAll) mutableMapOf(1 to 0, 2 to 0) else match.score.toMutableMap()
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

            val pts = engine.scoreFeature(g, completed = true)
            for (winner in winners) {
                score[winner] = (score[winner] ?: 0) + pts
            }

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
                        meeplesAvail[player] = ((meeplesAvail[player] ?: 0) + 1).coerceAtMost(7)
                    }
                }
                board[cellKey] = inst.copy(meeples = kept)
            }
            match = match.copy(board = board, meeplesAvailable = meeplesAvail)
        }

        match = match.copy(score = score, scoredKeys = scoredKeys, updatedAtEpochMs = nowMs())
    }

    private fun finalizeMatch() {
        if (match.status != MatchStatus.ACTIVE) return

        val analysis = engine.analyzeBoard(match.board)
        val score = match.score.toMutableMap()

        for (g in analysis.groups.values) {
            val winners = engine.winnersOfGroup(g)
            if (winners.isEmpty()) continue
            if (g.type != "field" && g.complete && g.key in match.scoredKeys) continue
            val pts = engine.scoreEndNowValue(g)
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
            lastEvent = event,
            updatedAtEpochMs = nowMs(),
        )
    }

    private fun ensureNextTiles() {
        if (match.status != MatchStatus.ACTIVE) return
        val nextTiles = match.nextTiles.toMutableMap()
        val remaining = match.remaining.toMutableMap()
        val turnPlayer = match.turnState.player
        for (player in listOf(1, 2)) {
            if (player == turnPlayer) continue
            if (nextTiles[player] != null) continue
            val tile = drawReservedTile(remaining)
            if (tile != null) {
                nextTiles[player] = tile
            }
        }
        match = match.copy(nextTiles = nextTiles, remaining = remaining, updatedAtEpochMs = nowMs())
    }

    private fun drawPlaceableTileForTurn() {
        val burned = mutableListOf<String>()
        val turnPlayer = match.turnState.player
        val nextTiles = match.nextTiles.toMutableMap()
        val remaining = match.remaining.toMutableMap()

        while (true) {
            val tileId = nextTiles[turnPlayer].also {
                if (it != null) nextTiles[turnPlayer] = null
            } ?: drawReservedTile(remaining)

            if (tileId == null) {
                match = match.copy(
                    remaining = remaining,
                    nextTiles = nextTiles,
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
                    turnState = match.turnState.copy(tileId = tileId, burnedTiles = burned, intent = null),
                    updatedAtEpochMs = nowMs(),
                )
                ensureNextTiles()
                return
            }

            burned += tileId
        }
    }

    private fun drawReservedTile(remaining: MutableMap<String, Int>): String? {
        val total = remaining.values.sumOf { it.coerceAtLeast(0) }
        if (total <= 0) return null

        val r = random.nextInt(1, total + 1)
        var acc = 0
        for ((tileId, rawCount) in remaining.toSortedMap()) {
            val cnt = rawCount.coerceAtLeast(0)
            if (cnt <= 0) continue
            acc += cnt
            if (r <= acc) {
                remaining[tileId] = (cnt - 1).coerceAtLeast(0)
                return tileId
            }
        }
        return null
    }

    private fun playerByToken(token: String): PlayerSlot? {
        return match.players.values.firstOrNull { it.token == token }
    }

    private fun clearIntentForPlayerLocked(player: Int) {
        val intent = match.turnState.intent ?: return
        if (intent.player != player) return
        match = match.copy(
            turnState = match.turnState.copy(intent = null),
            updatedAtEpochMs = nowMs(),
        )
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
    }
}
