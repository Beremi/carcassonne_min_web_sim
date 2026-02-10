package com.carcassonne.lan

import com.carcassonne.lan.core.CarcassonneEngine
import com.carcassonne.lan.model.TileDef
import com.carcassonne.lan.model.TileEdge
import com.carcassonne.lan.model.TileFeature
import com.carcassonne.lan.model.TilesetPayload
import com.carcassonne.lan.network.HostGameManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HostGameManagerTest {
    private fun simpleTileset(): TilesetPayload {
        return TilesetPayload(
            tileCounts = mapOf("A" to 8),
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
                )
            ),
        )
    }

    @Test
    fun joinThenReconnectBySameNameKeepsPlayerSlot() {
        val engine = CarcassonneEngine(simpleTileset())
        val manager = HostGameManager(engine = engine, random = Random(7), nowMs = { 1000L })

        manager.configureHostPlayer("Host1000")

        val blockedJoin = manager.joinOrReconnect("Remote2000")
        assertFalse(blockedJoin.ok)
        assertTrue(blockedJoin.error.orEmpty().contains("Invite required"))

        val invite = manager.sendInvite("Remote2000")
        assertTrue(invite.ok)
        val inviteId = invite.inviteId.orEmpty()
        assertTrue(inviteId.isNotBlank())

        val accepted = manager.respondInvite(inviteId, "accept")
        assertTrue(accepted.ok)
        assertEquals("accepted", accepted.status)

        val firstJoin = manager.joinOrReconnect("Remote2000")
        assertTrue(firstJoin.ok)
        assertEquals(2, firstJoin.player)
        val firstToken = firstJoin.token
        assertNotNull(firstToken)

        val reconnect = manager.joinOrReconnect("Remote2000")
        assertTrue(reconnect.ok)
        assertEquals(2, reconnect.player)
        assertNotEquals(firstToken, reconnect.token)

        val poll = manager.poll(reconnect.token.orEmpty())
        assertTrue(poll.ok)
        assertEquals("Remote2000", poll.match?.players?.get(2)?.name)
    }

    @Test
    fun turnIntentBroadcastsAndClearsOnSubmit() {
        val engine = CarcassonneEngine(simpleTileset())
        val manager = HostGameManager(engine = engine, random = Random(11), nowMs = { 2000L })

        val hostToken = manager.configureHostPlayer("Host1000")
        val inviteId = manager.sendInvite("Remote2000").inviteId.orEmpty()
        manager.respondInvite(inviteId, "accept")
        val join = manager.joinOrReconnect("Remote2000")
        assertTrue(join.ok)
        val remoteToken = join.token.orEmpty()
        assertTrue(remoteToken.isNotBlank())

        val match = join.match ?: error("missing match")
        val turnPlayer = match.turnState.player
        val actingToken = if (turnPlayer == 1) hostToken else remoteToken
        val watchingToken = if (turnPlayer == 1) remoteToken else hostToken

        val intent = manager.publishTurnIntent(
            token = actingToken,
            x = 1,
            y = 0,
            rotDeg = 90,
            meepleFeatureId = null,
            locked = false,
        )
        assertTrue(intent.ok)

        val watched = manager.poll(watchingToken)
        assertTrue(watched.ok)
        assertEquals(1, watched.match?.turnState?.intent?.x)
        assertEquals(0, watched.match?.turnState?.intent?.y)
        assertEquals(90, watched.match?.turnState?.intent?.rotDeg)

        val submit = manager.submitTurn(
            token = actingToken,
            x = 1,
            y = 0,
            rotDeg = 90,
            meepleFeatureId = null,
        )
        assertTrue(submit.ok)
        assertNull(submit.match?.turnState?.intent)
    }

    @Test
    fun clearTurnIntentRemovesPreviewForOpponent() {
        val engine = CarcassonneEngine(simpleTileset())
        val manager = HostGameManager(engine = engine, random = Random(13), nowMs = { 3000L })

        val hostToken = manager.configureHostPlayer("Host1000")
        val inviteId = manager.sendInvite("Remote2000").inviteId.orEmpty()
        manager.respondInvite(inviteId, "accept")
        val join = manager.joinOrReconnect("Remote2000")
        assertTrue(join.ok)
        val remoteToken = join.token.orEmpty()
        val match = join.match ?: error("missing match")
        val turnPlayer = match.turnState.player
        val actingToken = if (turnPlayer == 1) hostToken else remoteToken
        val watchingToken = if (turnPlayer == 1) remoteToken else hostToken

        val publish = manager.publishTurnIntent(
            token = actingToken,
            x = 0,
            y = 1,
            rotDeg = 180,
            meepleFeatureId = "field1",
            locked = true,
        )
        assertTrue(publish.ok)

        val clear = manager.clearTurnIntent(actingToken)
        assertTrue(clear.ok)

        val watched = manager.poll(watchingToken)
        assertTrue(watched.ok)
        assertNull(watched.match?.turnState?.intent)
    }
}
